package com.example.data.agent.runtime

import android.content.Context

/**
 * Platform-independent PermissionModel implementation.
 * Mediates user and biometric approval requests without direct coupling to Compose UI or Android BiometricPrompt.
 * Privileged authorization is never permanently cached.
 *
 * When an Android Context is supplied, consent is persisted through PermissionManager's
 * canonical context-aware store. JVM tests may omit the context and use the local test
 * consent map without touching deprecated Android compatibility APIs.
 */
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

    /* ---------------------------------------------------------------------- */
    /* [P0-36] & [P0-48] PERMISSION-TRUTH: Unified Capability Consent Store   */
    /* ---------------------------------------------------------------------- */

    fun setCapabilityConsent(capabilityName: String, consented: Boolean) {
        capabilityConsentMap[capabilityName] = consented
        context?.let { com.example.assistant.PermissionManager.setUserConsent(it, capabilityName, consented) }
    }

    fun hasCapabilityConsent(capabilityName: String): Boolean {
        return capabilityConsentMap[capabilityName]
            ?: context?.let { com.example.assistant.PermissionManager.hasUserConsent(it, capabilityName) }
            ?: false
    }

    fun revokeAllConsents() {
        capabilityConsentMap.clear()
        context?.let { com.example.assistant.PermissionManager.clearUserConsents(it) }
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
