package com.androlink.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.androlink.app.model.DiscoveryBeacon
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

class UdpDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val onDeviceDiscovered: (beacon: DiscoveryBeacon, address: InetAddress) -> Unit
) {
    private val TAG = "UdpDiscovery"
    private val DISCOVERY_PORT = 8699
    private val gson = Gson()
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    suspend fun startListening() = withContext(Dispatchers.IO) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("AndrolinkMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                reuseAddress = true
            }

            val buffer = ByteArray(4096)
            Log.d(TAG, "Started listening for UDP discovery on port $DISCOVERY_PORT")

            while (isActive && socket != null && !socket!!.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val beacon = gson.fromJson(message, DiscoveryBeacon::class.java)

                    if (beacon != null && beacon.protocol == "androlink-v1" && beacon.deviceId != deviceId) {
                        Log.d(TAG, "Discovered device: ${beacon.deviceName} at ${packet.address.hostAddress}")
                        onDeviceDiscovered(beacon, packet.address)
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing discovery packet", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP discovery listener", e)
        } finally {
            stop()
        }
    }

    suspend fun broadcastPresence(tcpPort: Int = 8700, directIp: String? = null) = withContext(Dispatchers.IO) {
        try {
            val beacon = DiscoveryBeacon(
                deviceId = deviceId,
                deviceName = Build.MODEL ?: "Android Device",
                deviceType = "phone",
                tcpPort = tcpPort
            )
            val json = gson.toJson(beacon)
            val data = json.toByteArray(Charsets.UTF_8)

            val targets = getBroadcastAddresses().toMutableList()
            if (!directIp.isNullOrBlank()) {
                try {
                    targets.add(InetAddress.getByName(directIp))
                } catch (_: Exception) {}
            }

            val broadcastSocket = DatagramSocket().apply { broadcast = true }
            for (target in targets) {
                try {
                    val packet = DatagramPacket(data, data.size, target, DISCOVERY_PORT)
                    broadcastSocket.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending to $target: ${e.message}")
                }
            }
            broadcastSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast presence", e)
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                for (ia in ni.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && !list.contains(bcast)) {
                        list.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading interface broadcasts", e)
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        return list
    }

    fun stop() {
        try {
            socket?.close()
            socket = null
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping UDP discovery", e)
        }
    }
}
