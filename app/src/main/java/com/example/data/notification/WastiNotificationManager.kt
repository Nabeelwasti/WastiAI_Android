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

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
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
