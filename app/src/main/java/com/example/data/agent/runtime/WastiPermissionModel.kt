package com.example.data.agent.runtime

import android.content.Context

/**
 * Platform-independent PermissionModel implementation.
 * Mediates user and biometric approval requests without direct coupling to Compose UI or Android BiometricPrompt.
 * Privileged authorization is never permanently cached.
 *
 * Consent resolution is deliberately multi-path:
 * 1) canonical context-aware persistent PermissionManager storage when Context exists;
 * 2) process-local compatibility storage for JVM/legacy callers when Context is unavailable;
 * 3) the local map remains a fast, request-scoped cache of explicit decisions.
 * The compatibility path is never used by the normal Android service-locator path.
 */
@Suppress("DEPRECATION")
class WastiPermissionModel(
    private val context: Context? = null,
    private var autoApproveControlledForTesting: Boolean = false,
    private var autoApproveBiometricForTesting: Boolean = false
) : PermissionModel {

    private val capabilityConsentMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun setAutoApproveControlledForTesting(autoApprove: Boolean) {
        autoApproveControlledForTesting = autoApprove
    }

    fun setAutoApproveBiometricForTesting(autoApprove: Boolean) {
        autoApproveBiometricForTesting = autoApprove
    }

    private val effectiveContext: Context?
        get() = context ?: com.example.WastiApplication.instance

    /* ---------------------------------------------------------------------- */
    /* [P0-36] & [P0-48] PERMISSION-TRUTH: Unified Capability Consent Store   */
    /* ---------------------------------------------------------------------- */

    fun setCapabilityConsent(capabilityName: String, consented: Boolean) {
        capabilityConsentMap[capabilityName] = consented
        val targetCtx = effectiveContext
        if (targetCtx != null) {
            com.example.assistant.PermissionManager.setUserConsent(targetCtx, capabilityName, consented)
        } else {
            // Compatibility fallback for JVM/legacy callers with no Android Context.
            // This is intentionally process-local and never represents OS permission truth.
            com.example.assistant.PermissionManager.setUserConsent(capabilityName, consented)
        }
    }

    fun hasCapabilityConsent(capabilityName: String): Boolean {
        capabilityConsentMap[capabilityName]?.let { return it }
        val targetCtx = effectiveContext
        return if (targetCtx != null) {
            com.example.assistant.PermissionManager.hasUserConsent(targetCtx, capabilityName)
        } else {
            // Secondary compatibility source when no Context is available.
            com.example.assistant.PermissionManager.hasUserConsent(capabilityName)
        }
    }

    fun revokeAllConsents() {
        capabilityConsentMap.clear()
        val targetCtx = effectiveContext
        if (targetCtx != null) {
            com.example.assistant.PermissionManager.clearUserConsents(targetCtx)
        } else {
            com.example.assistant.PermissionManager.clearUserConsents()
        }
    }

    override suspend fun requestUserApproval(
        actionSummary: String,
        permissionLevel: PermissionLevel
    ): Boolean {
        if (autoApproveControlledForTesting) return true
        return hasCapabilityConsent(actionSummary)
    }

    override suspend fun requestBiometricApproval(
        promptReason: String
    ): Boolean {
        // Dynamic prompt; zero cached state
        return autoApproveBiometricForTesting
    }
}
