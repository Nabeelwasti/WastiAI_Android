package com.example.data.security

import android.app.KeyguardManager
import android.content.Context
import java.util.UUID

import com.example.security.BiometricSecurityManager
import com.example.security.findFragmentActivity

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

    /**
     * Consolidates protected core file checks with WastiRiskModel's canonical protected paths.
     */
    fun isProtectedCoreFile(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val normalized = filePath.replace("\\", "/").lowercase()
        return protectedCoreFiles.any { normalized.contains(it.lowercase()) } ||
                com.example.data.agent.runtime.WastiRiskModel.isProtectedPath(filePath)
    }

    fun isDeviceSecured(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    /**
     * Consolidates PIN verification to use BiometricSecurityManager's AES-256 encrypted storage.
     * Eliminates legacy hardcoded backdoor PIN and duplicate plaintext preferences.
     */
    fun verifyPasscode(context: Context, enteredPin: String): Boolean {
        return BiometricSecurityManager.verifyPin(context, enteredPin)
    }

    /**
     * Consolidates PIN setting to use BiometricSecurityManager's AES-256 encrypted storage.
     */
    fun setMasterPasscode(context: Context, newPin: String) {
        BiometricSecurityManager.setPin(context, newPin)
    }

    /**
     * Real authentication flow: uses BiometricPrompt when FragmentActivity is present.
     * Fails closed when authentication cannot be performed, eliminating fake onSuccess() bypass.
     */
    fun authenticateUserForSensitiveAction(
        context: Context,
        title: String = "Authentication Required",
        description: String = "Confirm your identity to view sensitive credentials",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity != null) {
            BiometricSecurityManager.authenticate(
                activity = activity,
                title = title,
                subtitle = description,
                onSuccess = onSuccess,
                onError = onError
            )
        } else {
            if (isDeviceSecured(context)) {
                onError("Authentication requires an active foreground activity for biometric/credential prompt.")
            } else {
                onError("Device security credentials not enrolled.")
            }
        }
    }

    fun requiresConfirmationForAction(actionType: String): Boolean {
        val lower = actionType.lowercase()
        val sensitiveActions = listOf(
            "payment", "send_message", "delete_file", "system_settings",
            "stripe_charge", "zapier_trigger", "outreach_send", "execute_code",
            "root_command", "install_app", "wipe_data"
        )
        return sensitiveActions.any { lower.contains(it) } ||
                com.example.data.agent.runtime.WastiRiskModel.isProtectedPath(actionType)
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
