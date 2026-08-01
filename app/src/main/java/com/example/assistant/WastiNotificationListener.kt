package com.example.assistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener skeleton. Opt-in; user must grant notification access.
 * Use this to read notifications and trigger automations.
 */
class WastiNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.d("WastiNotif", "Posted: ${'$'}{sbn.packageName} - ${'$'}{sbn.tag}")
        // TODO: parse notifications and trigger actions safely
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.d("WastiNotif", "Removed: ${'$'}{sbn.packageName}")
    }
}
