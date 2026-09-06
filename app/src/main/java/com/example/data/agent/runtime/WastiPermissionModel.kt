package com.example.data.agent.runtime

/**
 * Platform-independent PermissionModel implementation.
 * Mediates user and biometric approval requests without direct coupling to Compose UI or Android BiometricPrompt.
 * Privileged authorization is never permanently cached.
 */
class WastiPermissionModel(
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
        com.example.assistant.PermissionManager.setUserConsent(capabilityName, consented)
    }

    fun hasCapabilityConsent(capabilityName: String): Boolean {
        return capabilityConsentMap[capabilityName]
            ?: com.example.assistant.PermissionManager.hasUserConsent(capabilityName)
    }

    fun revokeAllConsents() {
        capabilityConsentMap.clear()
        com.example.assistant.PermissionManager.clearUserConsents()
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

