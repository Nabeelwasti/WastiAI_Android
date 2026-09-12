package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.device.AccessibilityRecoveryCoordinator

/**
 * Receives recovery/retry signals without replacing the canonical execution fabric.
 * The receiver only re-evaluates capability availability and keeps the task checkpoint alive.
 */
class WastiAccessibilityRecoveryReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RETRY = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_RETRY"
        const val ACTION_CHECK = "com.wasti.os.ACTION_ACCESSIBILITY_RECOVERY_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
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
