package com.example.data.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Stage 22: execution truth boundary tests.
 * Executor success is not independent post-state verification for Android actions.
 */
class Stage22ExecutionTruthTest {

    private class FakeAndroidAdapter : ExternalIntegrationAdapter {
        override val capabilityId: String = "ANDROID_DEVICE"
        override val supportedActions: List<String> = listOf("OPEN_APP", "SEND_WHATSAPP")
        override fun getAuthState(): CapabilityAuthStatus = CapabilityAuthStatus.AUTHENTICATED
        override fun getLiveVerificationState(): LiveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED

        override fun execute(action: String, params: Map<String, Any>): ExternalActionResult =
            ExternalActionResult(
                status = ExternalActionResultStatus.SUCCESS,
                data = mapOf("dispatchAccepted" to true),
                diagnosticMessage = "Android executor accepted dispatch for $action"
            )

        override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult =
            ExternalActionResult(
                status = ExternalActionResultStatus.SUCCESS,
                diagnosticMessage = "Dry run"
            )

        override fun describeAction(action: String): String = action
    }

    @Test
    fun androidExecutorSuccess_isNotReportedAsVerified() {
        val engine = ActionIntentEngine()
        val action = engine.prepareActionIntent(
            target = "ANDROID_DEVICE",
            intent = "OPEN_APP",
            payload = mapOf("target" to "YouTube"),
            previewText = "Open YouTube",
            riskLevel = RiskLevel.LOW
        )

        val result = engine.executeAction(action, FakeAndroidAdapter())

        assertEquals(ActionAuthorizationState.SUCCEEDED, result.authorizationState)
        assertEquals(ActionExecutionTruthState.EXECUTOR_COMPLETED, result.executionTruthState)
        assertEquals(LiveConnectionStatus.NOT_VERIFIED, result.verificationState)
        assertNotEquals(ActionExecutionTruthState.COMPLETED_VERIFIED, result.executionTruthState)
    }

    @Test
    fun independentEvidence_isRequiredBeforeVerifiedState() {
        val engine = ActionIntentEngine()
        val action = engine.prepareActionIntent(
            target = "ANDROID_DEVICE",
            intent = "SEND_WHATSAPP",
            payload = mapOf("recipient" to "+923001234567", "message" to "test"),
            previewText = "Send WhatsApp test",
            riskLevel = RiskLevel.MEDIUM
        )
        action.authorizationState = ActionAuthorizationState.AUTHORIZED

        val result = engine.executeAction(action, FakeAndroidAdapter())
        assertEquals(ActionExecutionTruthState.EXECUTOR_COMPLETED, result.executionTruthState)

        val verified = engine.markIndependentlyVerified(result, "independent post-state probe observed expected WhatsApp state")
        assertEquals(ActionExecutionTruthState.COMPLETED_VERIFIED, verified.executionTruthState)
        assertEquals(LiveConnectionStatus.VERIFIED, verified.verificationState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun verificationWithoutEvidence_isRejected() {
        val engine = ActionIntentEngine()
        val action = engine.prepareActionIntent(
            target = "ANDROID_DEVICE",
            intent = "OPEN_APP",
            payload = mapOf("target" to "YouTube"),
            previewText = "Open YouTube",
            riskLevel = RiskLevel.LOW
        )
        engine.markIndependentlyVerified(action, "")
    }
}
