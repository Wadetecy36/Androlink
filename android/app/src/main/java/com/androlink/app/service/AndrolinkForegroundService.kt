package com.androlink.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.androlink.app.MainActivity
import com.androlink.app.model.AndrolinkPacket
import com.androlink.app.network.TcpClient
import com.androlink.app.network.UdpDiscovery
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

class AndrolinkForegroundService : Service() {
    private val TAG = "AndrolinkService"
    private val CHANNEL_ID = "androlink_service_channel"
    private val NOTIFICATION_ID = 1001
    private val ACTION_STOP_RINGING = "com.androlink.app.STOP_RINGING"
    private val ACTION_COPY_CLIPBOARD = "com.androlink.app.COPY_CLIPBOARD"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var udpDiscovery: UdpDiscovery? = null
    private var tcpClient: TcpClient? = null
    private var clipboardManager: ClipboardManager? = null
    private var lastSentClipboard = ""
    private var deviceId = ""
    private var ringtone: Ringtone? = null

    companion object {
        var isRunning = false
        var isConnected = false
        var currentPeerName = "None"
        var serviceInstance: AndrolinkForegroundService? = null
        var onStateChanged: (() -> Unit)? = null

        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (ni.isLoopback || !ni.isUp) continue
                    for (ia in ni.interfaceAddresses) {
                        val addr = ia.address
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            } catch (_: Exception) {}
            return "Unknown"
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android-device"
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Discovering desktop..."))
        isRunning = true
        onStateChanged?.invoke()

        setupClipboardListener()
        setupBatteryReceiver()
        setupNotificationListenerCallback()
        setupCommandReceiver()
        startDiscovery()
    }

    fun connectDirectly(ip: String, port: Int = 8700) {
        val prefs = getSharedPreferences("androlink_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_ip", ip).apply()

        scope.launch {
            try {
                updateNotification("Connecting directly to $ip...")
                val address = InetAddress.getByName(ip)
                connectToDesktop(address, port)
            } catch (e: Exception) {
                Log.e(TAG, "Direct connect failed to $ip", e)
                updateNotification("Connection to $ip failed. Searching...")
            }
        }
    }

    fun pingLaptop() {
        scope.launch {
            tcpClient?.sendPacket(
                AndrolinkPacket(
                    channel = "system",
                    event = "ping",
                    data = JsonObject()
                )
            )
        }
    }

    fun pushClipboardToLaptop(text: String) {
        lastSentClipboard = text
        scope.launch {
            val data = JsonObject().apply {
                addProperty("mimeType", "text/plain")
                addProperty("content", text)
            }
            tcpClient?.sendPacket(
                AndrolinkPacket(
                    channel = "clipboard",
                    event = "sync",
                    data = data
                )
            )
        }
    }

    private fun startDiscovery() {
        tcpClient = TcpClient(
            onPacketReceived = { packet -> handleIncomingPacket(packet) },
            onConnectionStateChanged = { connected ->
                isConnected = connected
                if (!connected) {
                    currentPeerName = "None"
                    updateNotification("Disconnected. Searching for desktop...")
                } else {
                    updateNotification("Connected to $currentPeerName")
                }
                onStateChanged?.invoke()
            }
        )

        udpDiscovery = UdpDiscovery(this, deviceId) { beacon, address ->
            if (beacon.deviceType == "desktop" && !isConnected) {
                currentPeerName = beacon.deviceName
                updateNotification("Connecting to ${beacon.deviceName}...")
                scope.launch {
                    connectToDesktop(address, beacon.tcpPort)
                }
            }
        }

        scope.launch {
            udpDiscovery?.startListening()
        }

        // Periodically broadcast presence & retry saved IP
        scope.launch {
            val prefs = getSharedPreferences("androlink_prefs", Context.MODE_PRIVATE)
            var attempts = 0
            while (isRunning) {
                if (!isConnected) {
                    val savedIp = prefs.getString("last_ip", null)
                    udpDiscovery?.broadcastPresence(8700, savedIp)

                    attempts++
                    if (attempts % 4 == 0 && !savedIp.isNullOrBlank()) {
                        try {
                            val addr = InetAddress.getByName(savedIp)
                            connectToDesktop(addr, 8700)
                        } catch (_: Exception) {}
                    }
                }
                delay(3000)
            }
        }
    }

    private suspend fun connectToDesktop(address: InetAddress, port: Int) {
        tcpClient?.connect(address, port)
    }

    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotBlank() && text != lastSentClipboard) {
                    pushClipboardToLaptop(text)
                }
            }
        }
    }

    private fun setupBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !isConnected) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

            scope.launch {
                val data = JsonObject().apply {
                    addProperty("level", batteryPct)
                    addProperty("isCharging", isCharging)
                }
                tcpClient?.sendPacket(
                    AndrolinkPacket(
                        channel = "system",
                        event = "battery",
                        data = data
                    )
                )
            }
        }
    }

    private fun setupNotificationListenerCallback() {
        NotificationListener.onNotificationPostedCallback = { notif ->
            if (isConnected) {
                scope.launch {
                    val data = JsonObject().apply {
                        addProperty("key", notif.key)
                        addProperty("appName", notif.appName)
                        addProperty("packageName", notif.packageName)
                        addProperty("title", notif.title)
                        addProperty("text", notif.text)
                    }
                    tcpClient?.sendPacket(
                        AndrolinkPacket(
                            channel = "notification",
                            event = "post",
                            data = data
                        )
                    )
                }
            }
        }
    }

    private fun setupCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_RINGING)
            addAction(ACTION_COPY_CLIPBOARD)
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_STOP_RINGING -> stopRinging()
                    ACTION_COPY_CLIPBOARD -> {
                        val text = intent.getStringExtra("clip_text") ?: return
                        val clip = ClipData.newPlainText("Androlink", text)
                        clipboardManager?.setPrimaryClip(clip)
                        val manager = getSystemService(NotificationManager::class.java)
                        manager?.cancel(1004)
                    }
                }
            }
        }, filter)
    }

    private fun handleIncomingPacket(packet: AndrolinkPacket) {
        when (packet.channel) {
            "clipboard" -> {
                if (packet.event == "sync") {
                    val content = packet.data.get("content")?.asString ?: ""
                    if (content.isNotBlank()) {
                        lastSentClipboard = content
                        scope.launch(Dispatchers.Main) {
                            try {
                                val clip = ClipData.newPlainText("Androlink", content)
                                clipboardManager?.setPrimaryClip(clip)
                            } catch (_: Exception) {}
                            showClipboardReceivedNotification(content)
                        }
                    }
                }
            }
            "file" -> {
                if (packet.event == "offer") {
                    val fileName = packet.data.get("fileName")?.asString ?: "downloaded_file"
                    val downloadUrl = packet.data.get("downloadUrl")?.asString ?: ""
                    if (downloadUrl.isNotBlank()) {
                        downloadFileFromPc(downloadUrl, fileName)
                    }
                }
            }
            "system" -> {
                if (packet.event == "ring") {
                    scope.launch(Dispatchers.Main) {
                        startRinging()
                    }
                }
            }
        }
    }

    private fun startRinging() {
        try {
            stopRinging()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()

            val stopIntent = Intent(ACTION_STOP_RINGING)
            val pendingStop = PendingIntent.getBroadcast(
                this,
                1002,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Androlink: Ringing Phone 🔔")
                .setContentText("Your laptop is searching for this device.")
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_delete, "Stop Ringing", pendingStop)
                .setOngoing(true)
                .build()

            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(1003, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone", e)
        }
    }

    fun stopRinging() {
        try {
            ringtone?.stop()
            ringtone = null
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(1003)
        } catch (_: Exception) {}
    }

    private fun showClipboardReceivedNotification(text: String) {
        val copyIntent = Intent(ACTION_COPY_CLIPBOARD).apply {
            putExtra("clip_text", text)
        }
        val pendingCopy = PendingIntent.getBroadcast(
            this,
            1004,
            copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val snippet = if (text.length > 50) text.substring(0, 47) + "..." else text
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Text Received from PC 📋")
            .setContentText(snippet)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .addAction(android.R.drawable.ic_input_get, "Copy to Clipboard", pendingCopy)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1004, notif)
    }

    private fun downloadFileFromPc(urlStr: String, fileName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, fileName)

                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 30000
                conn.connect()

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    showFileDownloadedNotification(destFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading file from PC", e)
            }
        }
    }

    private fun showFileDownloadedNotification(file: File) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Received from PC 📁")
            .setContentText("${file.name} saved to Downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(1005, notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Androlink Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps Androlink connected with your PC"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Androlink")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        serviceInstance = null
        isRunning = false
        isConnected = false
        onStateChanged?.invoke()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        udpDiscovery?.stop()
        tcpClient?.disconnect()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
