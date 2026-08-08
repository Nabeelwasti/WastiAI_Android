package com.example.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object WastiNotificationManager {

    private const val CHANNEL_ID = "wasti_lead_alerts"
    private const val CHANNEL_NAME = "Wasti Lead Radar High-Match Alerts"
    private const val CHANNEL_DESC = "Notifications when high-matching freelance client opportunities (>85%) are discovered."

    private const val ALERT_CHANNEL_ID = "wasti_business_alerts"
    private const val ALERT_CHANNEL_NAME = "Wasti Business Alerts"
    private const val ALERT_CHANNEL_DESC = "Notifications for payments, invoices, and other business events."

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val leadChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(leadChannel)

            val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = ALERT_CHANNEL_DESC
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * Sends a general-purpose high-priority alert notification (e.g. payment received,
     * invoice status changes). Named "voice alert" because these are the events Wasti
     * should also be able to announce via TTS when the app is in an active voice session.
     */
    fun sendVoiceAlertNotification(
        context: Context,
        title: String,
        message: String
    ) {
        initNotificationChannel(context)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationId = (System.currentTimeMillis() % 10000).toInt()

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("WastiNotification", "Error sending voice alert notification", e)
        }
    }

    fun sendHighMatchLeadNotification(
        context: Context,
        leadTitle: String,
        matchScore: Int,
        category: String,
        draftedPitch: String
    ) {
        initNotificationChannel(context)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationId = (System.currentTimeMillis() % 10000).toInt()

        val bigText = "🎯 Match Score: $matchScore/100 ($category)\n\n" +
                "Client Post: $leadTitle\n\n" +
                "Auto-Drafted Pitch:\n$draftedPitch"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🎯 High-Match Lead Discovered ($matchScore% Score)")
            .setContentText("$leadTitle • $category")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            android.util.Log.e("WastiNotification", "Error sending notification", e)
        }
    }
}
