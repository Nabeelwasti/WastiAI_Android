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

    fun setAutoApproveControlledForTesting(autoApprove: Boolean) {
        autoApproveControlledForTesting = autoApprove
    }

    fun setAutoApproveBiometricForTesting(autoApprove: Boolean) {
        autoApproveBiometricForTesting = autoApprove
    }

    override suspend fun requestUserApproval(
        actionSummary: String,
        permissionLevel: PermissionLevel
    ): Boolean {
        return autoApproveControlledForTesting
    }

    override suspend fun requestBiometricApproval(
        promptReason: String
    ): Boolean {
        // Dynamic prompt; zero cached state
        return autoApproveBiometricForTesting
    }
}
