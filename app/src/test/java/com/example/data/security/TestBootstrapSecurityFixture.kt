package com.example.data.security

import com.example.data.agent.runtime.SelfModificationSafetyEngine
import com.example.data.agent.runtime.WastiEmergencyStopController
import java.util.UUID

/**
 * Test-only authenticated bootstrap fixture for tests that exercise or initialize the
 * [SYSTEM_INITIALIZER] governance and emergency-stop reset path.
 *
 * Uses existing production APIs [SelfModificationSafetyEngine.registerAdminToken] and
 * [SelfModificationSafetyEngine.revokeAdminToken] without introducing any production
 * test-token APIs or bypasses.
 */
object TestBootstrapSecurityFixture {

    const val BOOTSTRAP_REQUESTER = "SYSTEM_INITIALIZER"

    /**
     * Generates a unique, non-forgeable test bootstrap token and registers it in
     * the active admin token registry for the duration of the test.
     */
    fun createAuthorizedBootstrapToken(): String {
        val token = "test_bootstrap_token_${UUID.randomUUID()}"
        SelfModificationSafetyEngine.registerAdminToken(token)
        return token
    }

    /**
     * Revokes the test bootstrap token immediately upon test completion.
     */
    fun revokeAuthorizedBootstrapToken(token: String?) {
        if (!token.isNullOrBlank()) {
            SelfModificationSafetyEngine.revokeAdminToken(token)
        }
    }

    /**
     * Performs an authenticated emergency-stop reset on the singleton controller
     * using the [SYSTEM_INITIALIZER] identity and the provided bootstrap admin token.
     */
    fun resetEmergencyStopForBootstrap(token: String): Boolean {
        return WastiEmergencyStopController.resetEmergencyStop(
            requester = BOOTSTRAP_REQUESTER,
            adminToken = token
        )
    }

    /**
     * Performs an authenticated emergency-stop reset on a specific controller instance
     * using the [SYSTEM_INITIALIZER] identity and the provided bootstrap admin token.
     */
    fun resetEmergencyStopForBootstrap(controller: WastiEmergencyStopController, token: String): Boolean {
        return controller.resetEmergencyStop(
            requester = BOOTSTRAP_REQUESTER,
            adminToken = token
        )
    }
}
