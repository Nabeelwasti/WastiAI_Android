package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.device.AccessibilityRecoveryCoordinator

/**
 * Receives recovery/retry signals without replacing the canonical execution fabric.
 * When a gesture is dispatched while Accessibility is unavailable, this receiver
 * creates a durable recovery checkpoint and prompts the user while alternatives run.
 */
class WastiAccessibilityRecoveryReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RETRY = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_RETRY"
        const val ACTION_CHECK = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_CHECK"
        private const val ACTION_EXECUTE_GESTURE = "com.wasti.os.ACTION_EXECUTE_GESTURE"
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
        }
    }
}
