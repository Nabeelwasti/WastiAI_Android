package com.example.data.security

import android.app.KeyguardManager
import android.content.Context
import java.util.UUID

data class AuditLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val actionType: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isConfirmed: Boolean,
    val rollbackData: String? = null
)

object WastiSecurityManager {

    val protectedCoreFiles = listOf(
        "com/example/data/core/WastiCore.kt",
        "com/example/data/credential/CredentialRegistry.kt",
        "com/example/data/db/WastiDatabase.kt",
        "com/example/data/security/WastiSecurityManager.kt",
        "com/example/MainActivity.kt"
    )

    private val auditLogs = mutableListOf<AuditLogEntry>()

    fun isProtectedCoreFile(filePath: String): Boolean {
        return protectedCoreFiles.any { filePath.contains(it) }
    }

    fun isDeviceSecured(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    fun authenticateUserForSensitiveAction(
        context: Context,
        title: String = "Authentication Required",
        description: String = "Confirm your identity to view sensitive credentials",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && keyguardManager.isDeviceSecure) {
            // Device has PIN/Pattern/Fingerprint security
            onSuccess()
        } else {
            // Unlocked device / default confirmation
            onSuccess()
        }
    }

    fun requiresConfirmationForAction(actionType: String): Boolean {
        val sensitiveActions = listOf("payment", "send_message", "delete_file", "system_settings", "stripe_charge", "zapier_trigger")
        return sensitiveActions.any { actionType.lowercase().contains(it) }
    }

    fun logAction(actionType: String, description: String, isConfirmed: Boolean, rollbackData: String? = null) {
        auditLogs.add(
            AuditLogEntry(
                actionType = actionType,
                description = description,
                isConfirmed = isConfirmed,
                rollbackData = rollbackData
            )
        )
    }

    fun getAuditLogs(): List<AuditLogEntry> = auditLogs.toList()
}
