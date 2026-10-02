package com.androlink.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.androlink.app.model.NotificationActionInfo
import com.androlink.app.model.NotificationInfo

class NotificationListener : NotificationListenerService() {
    private val TAG = "NotificationListener"

    companion object {
        var listenerInstance: NotificationListener? = null
        var onNotificationPostedCallback: ((NotificationInfo) -> Unit)? = null
        var onNotificationRemovedCallback: ((String) -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerInstance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        listenerInstance = null
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val actions = mutableListOf<NotificationActionInfo>()
        sbn.notification.actions?.forEachIndexed { index, action ->
            val hasRemoteInput = action.remoteInputs?.isNotEmpty() == true
            actions.add(
                NotificationActionInfo(
                    id = index.toString(),
                    title = action.title?.toString() ?: "",
                    hasInput = hasRemoteInput
                )
            )
        }

        val appName = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val info = NotificationInfo(
            key = sbn.key,
            appName = appName,
            packageName = sbn.packageName,
            title = title,
            text = text,
            actions = actions
        )

        onNotificationPostedCallback?.invoke(info)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        onNotificationRemovedCallback?.invoke(sbn.key)
    }
}
