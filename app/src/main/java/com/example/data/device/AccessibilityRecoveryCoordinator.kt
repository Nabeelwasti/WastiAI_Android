package com.example.data.device

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.assistant.PermissionManager
import com.example.data.ops.WebSearchEngine
import com.example.service.WastiAccessibilityRecoveryReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Multi-path recovery for device-control tasks when Accessibility is unavailable.
 *
 * Accessibility is a capability provider, not the whole device-control system.
 * This coordinator keeps the objective alive, asks the user to enable the relevant
 * Android-controlled capability, and simultaneously prepares alternative routes.
 * It never claims that a route succeeded until the normal observation/verification
 * pipeline proves the resulting real-world state.
 */
object AccessibilityRecoveryCoordinator {
    private const val CHANNEL_ID = "wasti_capability_recovery"
    private const val CHANNEL_NAME = "Wasti Capability Recovery"
    private const val NOTIFICATION_ID = 73142
    private const val PREFS = "wasti_recovery_checkpoint"
    private const val KEY_OBJECTIVE = "objective"
    private const val KEY_TARGET = "target"
    private const val KEY_UPDATED = "updatedAt"

    data class RecoveryPlan(
        val objective: String,
        val target: String,
        val accessibilityAvailable: Boolean,
        val routes: List<String>
    )

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, com.example.service.WastiAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun requestRecovery(context: Context, objective: String, target: String): RecoveryPlan {
        val app = context.applicationContext
        val routes = buildAlternativeRoutes(target)
        saveCheckpoint(app, objective, target)
        if (!isAccessibilityEnabled(app)) {
            showRecoveryNotification(app, objective, target)
            researchAlternativesInBackground(app, objective, target)
        }
        return RecoveryPlan(objective, target, isAccessibilityEnabled(app), routes)
    }

    fun resumeFromCheckpoint(context: Context): RecoveryPlan? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val objective = prefs.getString(KEY_OBJECTIVE, null) ?: return null
        val target = prefs.getString(KEY_TARGET, "") ?: ""
        return requestRecovery(context, objective, target)
    }

    fun clearCheckpoint(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun saveCheckpoint(context: Context, objective: String, target: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OBJECTIVE, objective)
            .putString(KEY_TARGET, target)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    private fun buildAlternativeRoutes(target: String): List<String> = listOf(
        "AccessibilityService UI-node and gesture route",
        "Native Android Intent / deep-link route",
        "PackageManager application-discovery route",
        "System API / foreground-service route where Android permits it",
        "IPC / broadcast / provider route where the target exposes one",
        "Web or app-native workflow route when the objective can be completed without UI automation",
        "Another registered Wasti execution body or node when available",
        "Native Wasti execution/runtime capability discovery and synthesis"
    )

    private fun researchAlternativesInBackground(context: Context, objective: String, target: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val queries = listOf(
                "Android $target official automation API current documentation",
                "Android $target intent deep link official documentation",
                "Android accessibility alternative API $target official",
                "Android $objective native API current documentation"
            )
            queries.forEach { query ->
                runCatching { WebSearchEngine.search(query, context) }
            }
        }
    }

    private fun showRecoveryNotification(context: Context, objective: String, target: String) {
        if (!PermissionManager.hasPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsPending = PendingIntent.getActivity(
            context,
            73143,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retryIntent = Intent(context, WastiAccessibilityRecoveryReceiver::class.java).apply {
            action = WastiAccessibilityRecoveryReceiver.ACTION_RETRY
            putExtra("objective", objective)
            putExtra("target", target)
        }
        val retryPending = PendingIntent.getBroadcast(
            context,
            73144,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Wasti needs device-control capability")
            .setContentText("Accessibility is unavailable; Wasti is continuing recovery and alternative-route research.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Objective: $objective\nTarget: $target\n\nEnable the relevant Wasti Accessibility service for the strongest UI-control route. Wasti will keep the recovery checkpoint and continue evaluating other legitimate routes while you decide."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(NotificationCompat.Action.Builder(0, "Enable Accessibility", settingsPending).build())
            .addAction(NotificationCompat.Action.Builder(0, "Retry", retryPending).build())
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
