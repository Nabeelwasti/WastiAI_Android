package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.device.AccessibilityRecoveryCoordinator

/**
 * Recovery body for capability-unavailable states. It never replaces the main executor:
 * it preserves the objective, requests the Android-controlled capability, and keeps
 * alternative discovery/research alive while the user decides.
 */
class WastiAccessibilityRecoveryReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RETRY = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_RETRY"
        const val ACTION_CHECK = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_CHECK"
        private const val ACTION_EXECUTE_GESTURE = "com.wasti.os.ACTION_EXECUTE_GESTURE"
        private const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_EXECUTE_GESTURE -> {
                if (!AccessibilityRecoveryCoordinator.isAccessibilityEnabled(app)) {
                    val actionType = intent.getStringExtra("actionType") ?: "device-control"
                    val target = intent.getStringExtra("targetText")
                        ?: intent.getStringExtra("targetElement")
                        ?: ""
                    AccessibilityRecoveryCoordinator.requestRecovery(
                        app,
                        objective = "Execute $actionType device-control operation",
                        target = target
                    )
                }
            }
            ACTION_RETRY -> {
                val objective = intent.getStringExtra("objective") ?: return
                val target = intent.getStringExtra("target") ?: ""
                if (AccessibilityRecoveryCoordinator.isAccessibilityEnabled(app)) {
                    AccessibilityRecoveryCoordinator.clearCheckpoint(app)
                } else {
                    AccessibilityRecoveryCoordinator.requestRecovery(app, objective, target)
                }
            }
            ACTION_CHECK -> AccessibilityRecoveryCoordinator.resumeFromCheckpoint(app)
            ACTION_BOOT_COMPLETED -> AccessibilityRecoveryCoordinator.resumeFromCheckpoint(app)
        }
    }
}
