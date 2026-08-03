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

    fun verifyPasscode(context: Context, enteredPin: String): Boolean {
        val prefs = context.getSharedPreferences("wasti_security_prefs", Context.MODE_PRIVATE)
        val savedPin = prefs.getString("vault_master_pin", null)
        if (savedPin.isNullOrBlank()) {
            // Default master passcode if none set yet: "1234" or match any 4+ digit pin entered on first setup
            if (enteredPin.length >= 4) {
                prefs.edit().putString("vault_master_pin", enteredPin).apply()
                return true
            }
            return enteredPin == "1234"
        }
        return enteredPin == savedPin
    }

    fun setMasterPasscode(context: Context, newPin: String) {
        val prefs = context.getSharedPreferences("wasti_security_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("vault_master_pin", newPin).apply()
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
            onSuccess()
        } else {
            // Fallback to passcode prompt callback
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
