package com.androlink.app.model

import com.google.gson.JsonObject
import java.util.UUID

data class AndrolinkPacket(
    val id: String = UUID.randomUUID().toString(),
    val channel: String,
    val event: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: JsonObject = JsonObject()
)

data class DiscoveryBeacon(
    val protocol: String = "androlink-v1",
    val type: String = "discovery",
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "phone",
    val tcpPort: Int = 8700,
    val version: String = "1.0.0"
)

data class BatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val powerSaveMode: Boolean = false
)

data class ClipboardInfo(
    val mimeType: String = "text/plain",
    val content: String
)

data class NotificationInfo(
    val key: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val actions: List<NotificationActionInfo> = emptyList()
)

data class NotificationActionInfo(
    val id: String,
    val title: String,
    val hasInput: Boolean = false
)
