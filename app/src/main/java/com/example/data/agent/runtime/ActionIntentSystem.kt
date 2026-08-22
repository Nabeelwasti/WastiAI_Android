package com.example.data.agent.runtime

import java.util.UUID

// TODO: unused — evaluate for removal or wiring in
enum class ActionExecutionMode {
    PREVIEW,
    USER_CONFIRMED,
    AUTONOMOUS_WITHIN_POLICY
}

enum class ActionAuthorizationState {
    PREPARED,
    PREVIEW_READY,
    REQUIRES_CONFIRMATION,
    AUTHORIZED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNVERIFIED
}

// TODO: unused — evaluate for removal or wiring in
data class ActionIntent(
    val actionId: String = UUID.randomUUID().toString(),
    val target: String,
    val intent: String,
    val payload: Map<String, Any>,
    val previewText: String,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val executionMode: ActionExecutionMode = ActionExecutionMode.PREVIEW,
    var authorizationState: ActionAuthorizationState = ActionAuthorizationState.PREPARED,
    var verificationState: LiveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
    var resultMessage: String? = null
)

// TODO: unused — evaluate for removal or wiring in
class ActionIntentEngine(
    private val securityPolicyEngine: WastiSecurityPolicyEngine
) {

    fun prepareActionIntent(
        target: String,
        intent: String,
        payload: Map<String, Any>,
        previewText: String,
        riskLevel: RiskLevel = RiskLevel.MEDIUM
    ): ActionIntent {
        val action = ActionIntent(
            target = target,
            intent = intent,
            payload = payload,
            previewText = previewText,
            riskLevel = riskLevel,
            executionMode = ActionExecutionMode.PREVIEW,
            authorizationState = ActionAuthorizationState.PREVIEW_READY
        )
        return action
    }

    fun authorizeAction(action: ActionIntent, userApproved: Boolean): ActionIntent {
        if (userApproved) {
            action.authorizationState = ActionAuthorizationState.AUTHORIZED
        } else {
            action.authorizationState = ActionAuthorizationState.CANCELLED
            action.resultMessage = "Action cancelled by user"
        }
        return action
    }

    fun executeAction(
        action: ActionIntent,
        adapter: ExternalIntegrationAdapter
    ): ActionIntent {
        if (action.authorizationState != ActionAuthorizationState.AUTHORIZED &&
            action.executionMode != ActionExecutionMode.AUTONOMOUS_WITHIN_POLICY) {
            action.authorizationState = ActionAuthorizationState.REQUIRES_CONFIRMATION
            action.resultMessage = "Execution blocked: Action requires user authorization"
            return action
        }

        action.authorizationState = ActionAuthorizationState.EXECUTING
        val result = adapter.execute(action.intent, action.payload)

        if (result.status == ExternalActionResultStatus.SUCCESS) {
            action.authorizationState = ActionAuthorizationState.SUCCEEDED
            action.verificationState = LiveConnectionStatus.VERIFIED
            action.resultMessage = result.diagnosticMessage
        } else {
            action.authorizationState = ActionAuthorizationState.FAILED
            action.verificationState = LiveConnectionStatus.FAILED
            action.resultMessage = result.diagnosticMessage
        }

        return action
    }
}
