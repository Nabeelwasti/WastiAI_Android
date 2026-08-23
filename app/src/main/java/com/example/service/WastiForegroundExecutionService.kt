package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.core.GlobalExecutionContext
import com.example.data.core.WastiOSRuntime
import com.example.data.server.WastiLocalServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Stage 10: Canonical Wasti OS Autonomous Background Execution Daemon.
 *
 * Keeps WastiBrain alive and autonomous during background execution, long-running
 * workflows, self-improvement tasks, and local server hosting.
 *
 * Employs bounded wake-locks with strict timeouts, updates notifications with real-time
 * progress, and offers instant cancellation/emergency stop controls.
 */
class WastiForegroundExecutionService : Service() {

    companion object {
        private const val TAG = "WastiForegroundService"
        const val CHANNEL_ID = "wasti_execution_daemon_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START_DAEMON = "com.example.service.ACTION_START_DAEMON"
        const val ACTION_STOP_DAEMON = "com.example.service.ACTION_STOP_DAEMON"
        const val ACTION_CANCEL_ACTIVE_TASK = "com.example.service.ACTION_CANCEL_ACTIVE_TASK"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startDaemon(context: Context) {
            val intent = Intent(context, WastiForegroundExecutionService::class.java).apply {
                action = ACTION_START_DAEMON
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopDaemon(context: Context) {
            val intent = Intent(context, WastiForegroundExecutionService::class.java).apply {
                action = ACTION_STOP_DAEMON
            }
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    private val runtime: WastiOSRuntime by lazy {
        WastiOSRuntime.getInstance(applicationContext)
    }

    private val localServerManager: WastiLocalServerManager by lazy {
        WastiLocalServerManager.getInstance(applicationContext)
    }

    private val proactiveEngine: com.example.data.proactive.WastiProactiveAutonomousEngine by lazy {
        com.example.data.proactive.WastiProactiveAutonomousEngine.getInstance(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val notification = buildNotification(
            title = "Wasti AI OS — Autonomous Daemon Active",
            content = "One Brain operational across all interfaces"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Auto-start local embedded server daemon
        serviceScope.launch(Dispatchers.IO) {
            localServerManager.startServer(8080)
        }

        // Auto-start Proactive Autonomous Engine for background task execution
        serviceScope.launch(Dispatchers.Default) {
            proactiveEngine.startAutonomousEngine()
        }

        observeRuntimeState()
        Log.i(TAG, "WastiForegroundExecutionService initialized and running.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_DAEMON -> {
                proactiveEngine.stopAutonomousEngine()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ACTIVE_TASK -> {
                runtime.cancelActiveExecution("User cancelled from system notification")
                proactiveEngine.getActiveTasks().forEach { task ->
                    proactiveEngine.cancelTask(task.taskId, "User cancelled from system notification")
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wasti OS Autonomous Execution Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors background multi-agent execution, local server hosting, and cross-interface transport"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        isBusy: Boolean = false
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, WastiForegroundExecutionService::class.java).apply {
            action = ACTION_CANCEL_ACTIVE_TASK
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isBusy) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel Task",
                cancelPendingIntent
            )
        }

        return builder.build()
    }

    private fun observeRuntimeState() {
        serviceScope.launch {
            runtime.activeContext.collectLatest { ctx ->
                updateNotificationForContext(ctx)
                manageWakeLock(ctx.isBusy)
            }
        }
    }

    private fun updateNotificationForContext(ctx: GlobalExecutionContext) {
        val title = if (ctx.isBusy) {
            "⚡ Wasti OS: Active Task Running"
        } else {
            "Wasti AI OS — System Idle"
        }

        val content = if (ctx.isBusy) {
            "${ctx.progressMessage} (${ctx.activeTaskId?.take(8) ?: "executing"})"
        } else {
            "Ready for requests across Chat, Voice, Floating Bubble, and Local Server"
        }

        val notif = buildNotification(title, content, ctx.isBusy)
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    private fun manageWakeLock(isBusy: Boolean) {
        try {
            if (isBusy) {
                if (wakeLock == null || !wakeLock!!.isHeld) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "WastiOS:AutonomousTaskExecutionWakeLock"
                    ).apply {
                        // 5-minute maximum timeout safety for battery preservation
                        acquire(5 * 60 * 1000L)
                    }
                    Log.d(TAG, "Acquired bounded execution WakeLock")
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.d(TAG, "Released execution WakeLock (System Idle)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error managing execution wake lock", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) {}
        }
        serviceScope.cancel()
        Log.i(TAG, "WastiForegroundExecutionService stopped.")
    }
}
