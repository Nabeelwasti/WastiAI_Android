package com.example.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that hosts ongoing assistant tasks (continuous listening, automation, etc.)
 * It starts as sticky so it can be restarted by the system when needed.
 */
class AssistantForegroundService : Service() {
    private val CHANNEL_ID = "wasti_assistant_channel"
    private val NOTIF_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Wasti Assistant running")
        startForeground(NOTIF_ID, notification)
        Log.i("AssistantService", "Foreground service started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Wasti Assistant", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Foreground service channel for Wasti Assistant"
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder.setContentTitle("Wasti Assistant")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: start real assistant work (STT/TTS, monitoring) here or delegate to a manager
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("AssistantService", "Foreground service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
