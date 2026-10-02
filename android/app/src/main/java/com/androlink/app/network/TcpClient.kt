package com.androlink.app.network

import android.util.Log
import com.androlink.app.model.AndrolinkPacket
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket

class TcpClient(
    private val onPacketReceived: (AndrolinkPacket) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private val TAG = "TcpClient"
    private val gson = Gson()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    suspend fun connect(address: InetAddress, port: Int) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to ${address.hostAddress}:$port...")
            socket = Socket(address, port).apply {
                tcpNoDelay = true
                keepAlive = true
            }
            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))

            onConnectionStateChanged(true)
            Log.d(TAG, "Connected to ${address.hostAddress}:$port successfully")

            while (isActive && socket != null && !socket!!.isClosed) {
                val line = reader?.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    val packet = gson.fromJson(line, AndrolinkPacket::class.java)
                    if (packet != null) {
                        onPacketReceived(packet)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed packet received: $line", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP connection error", e)
        } finally {
            disconnect()
        }
    }

    suspend fun sendPacket(packet: AndrolinkPacket) = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(packet)
            writer?.println(json)
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet", e)
        }
    }

    fun disconnect() {
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP socket", e)
        } finally {
            writer = null
            reader = null
            socket = null
            onConnectionStateChanged(false)
        }
    }
}
