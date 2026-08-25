package com.example.data.agent.runtime

import com.example.data.conversation.TaskTimelinePhase
import com.example.data.conversation.UniversalTaskTimeline
import java.util.UUID

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

data class ActionIntent(
    val actionId: String = UUID.randomUUID().toString(),
    val taskId: String = UUID.randomUUID().toString(),
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

/**
 * Stage 20: Canonical Action Intent Engine.
 * Serves as the authoritative bridge between Intent Planning, Policy Authorization,
 * Execution Fabric, Observation, and Universal Task Timeline tracking.
 */
class ActionIntentEngine(
    private val securityPolicyEngine: WastiSecurityPolicyEngine? = null,
    private val timeline: UniversalTaskTimeline = UniversalTaskTimeline.getInstance()
) {

    fun prepareActionIntent(
        target: String,
        intent: String,
        payload: Map<String, Any>,
        previewText: String,
        riskLevel: RiskLevel = RiskLevel.MEDIUM,
        taskId: String = UUID.randomUUID().toString()
    ): ActionIntent {
        val action = ActionIntent(
            actionId = UUID.randomUUID().toString(),
            taskId = taskId,
            target = target,
            intent = intent,
            payload = payload,
            previewText = previewText,
            riskLevel = riskLevel,
            executionMode = if (riskLevel == RiskLevel.LOW) ActionExecutionMode.AUTONOMOUS_WITHIN_POLICY else ActionExecutionMode.PREVIEW,
            authorizationState = if (riskLevel == RiskLevel.LOW) ActionAuthorizationState.AUTHORIZED else ActionAuthorizationState.PREVIEW_READY
        )

        timeline.appendPhase(
            taskId = taskId,
            phase = TaskTimelinePhase.PLANNED,
            description = "Action planned: target='$target', intent='$intent', risk=${riskLevel.name}",
            metadata = mapOf("actionId" to action.actionId, "preview" to previewText)
        )

        return action
    }

    fun authorizeAction(action: ActionIntent, userApproved: Boolean): ActionIntent {
        if (userApproved) {
            action.authorizationState = ActionAuthorizationState.AUTHORIZED
            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.AUTHORIZED,
                description = "Action authorized by user for execution.",
                metadata = mapOf("actionId" to action.actionId)
            )
        } else {
            action.authorizationState = ActionAuthorizationState.CANCELLED
            action.resultMessage = "Action cancelled by user policy."
            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.CANCELLED,
                description = "Action cancelled by user.",
                metadata = mapOf("actionId" to action.actionId)
            )
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
            action.resultMessage = "Execution blocked: Action requires explicit user authorization"
            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.CAPABILITY_CHECKED,
                description = "Execution blocked: Confirmation required.",
                metadata = mapOf("actionId" to action.actionId)
            )
            return action
        }

        action.authorizationState = ActionAuthorizationState.EXECUTING
        timeline.appendPhase(
            taskId = action.taskId,
            phase = TaskTimelinePhase.EXECUTING,
            description = "Executing action '${action.intent}' via ${adapter::class.simpleName ?: "Adapter"}.",
            metadata = mapOf("actionId" to action.actionId)
        )

        val result = adapter.execute(action.intent, action.payload)

        timeline.appendPhase(
            taskId = action.taskId,
            phase = TaskTimelinePhase.OBSERVING,
            description = "Observation received: status=${result.status.name}, diagnostic=${result.diagnosticMessage}",
            metadata = mapOf("actionId" to action.actionId, "status" to result.status.name)
        )

        if (result.status == ExternalActionResultStatus.SUCCESS) {
            action.authorizationState = ActionAuthorizationState.SUCCEEDED
            action.verificationState = LiveConnectionStatus.VERIFIED
            action.resultMessage = result.diagnosticMessage

            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.COMPLETED,
                description = "Action completed and verified: ${result.diagnosticMessage}",
                metadata = mapOf("actionId" to action.actionId, "status" to "VERIFIED")
            )
        } else {
            action.authorizationState = ActionAuthorizationState.FAILED
            action.verificationState = LiveConnectionStatus.FAILED
            action.resultMessage = result.diagnosticMessage

            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.FAILED,
                description = "Action failed: ${result.diagnosticMessage}",
                metadata = mapOf("actionId" to action.actionId, "error" to result.diagnosticMessage)
            )
        }

        return action
    }
}
