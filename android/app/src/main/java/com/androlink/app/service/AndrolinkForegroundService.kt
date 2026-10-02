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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.androlink.app.MainActivity
import com.androlink.app.model.AndrolinkPacket
import com.androlink.app.network.TcpClient
import com.androlink.app.network.UdpDiscovery
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress

class AndrolinkForegroundService : Service() {
    private val CHANNEL_ID = "androlink_service_channel"
    private val NOTIFICATION_ID = 1001
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val gson = Gson()

    private var udpDiscovery: UdpDiscovery? = null
    private var tcpClient: TcpClient? = null
    private var clipboardManager: ClipboardManager? = null
    private var lastSentClipboard = ""
    private var deviceId = ""

    companion object {
        var isRunning = false
        var isConnected = false
        var currentPeerName = "None"
        var onStateChanged: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android-device"
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Discovering desktop..."))
        isRunning = true
        onStateChanged?.invoke()

        setupClipboardListener()
        setupBatteryReceiver()
        setupNotificationListenerCallback()
        startDiscovery()
    }

    private fun startDiscovery() {
        tcpClient = TcpClient(
            onPacketReceived = { packet -> handleIncomingPacket(packet) },
            onConnectionStateChanged = { connected ->
                isConnected = connected
                if (!connected) {
                    currentPeerName = "None"
                    updateNotification("Disconnected. Searching for desktop...")
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

        // Periodically broadcast presence
        scope.launch {
            while (isRunning) {
                if (!isConnected) {
                    udpDiscovery?.broadcastPresence()
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

    private fun handleIncomingPacket(packet: AndrolinkPacket) {
        when (packet.channel) {
            "clipboard" -> {
                if (packet.event == "sync") {
                    val content = packet.data.get("content")?.asString ?: ""
                    if (content.isNotBlank() && content != lastSentClipboard) {
                        lastSentClipboard = content
                        scope.launch(Dispatchers.Main) {
                            val clip = ClipData.newPlainText("Androlink", content)
                            clipboardManager?.setPrimaryClip(clip)
                        }
                    }
                }
            }
            "system" -> {
                if (packet.event == "ping") {
                    scope.launch {
                        tcpClient?.sendPacket(
                            AndrolinkPacket(
                                channel = "system",
                                event = "pong",
                                data = JsonObject()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Androlink Service",
                NotificationManager.IMPORTANCE_LOW
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
