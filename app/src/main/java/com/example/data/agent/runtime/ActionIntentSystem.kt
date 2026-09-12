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

enum class ActionExecutionTruthState {
    NOT_STARTED,
    DISPATCHED,
    EXECUTOR_COMPLETED,
    COMPLETED_UNVERIFIED,
    COMPLETED_VERIFIED,
    EXECUTION_FAILED,
    VERIFICATION_UNAVAILABLE,
    VERIFICATION_FAILED
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
    var executionTruthState: ActionExecutionTruthState = ActionExecutionTruthState.NOT_STARTED,
    var resultMessage: String? = null
)

/**
 * Stage 20: Canonical Action Intent Engine.
 * Serves as the authoritative bridge between Intent Planning, Policy Authorization,
 * Execution Fabric, Observation, and Universal Task Timeline tracking.
 *
 * Truth rule: executor success is never independent post-state verification.
 * Android dispatch actions therefore stop at EXECUTOR_COMPLETED / COMPLETED_UNVERIFIED
 * until an independent verifier explicitly upgrades the action to VERIFIED.
 */
class ActionIntentEngine(
    private val securityPolicyEngine: WastiSecurityPolicyEngine? = null,
    private val timeline: UniversalTaskTimeline = UniversalTaskTimeline.getInstance()
) {
    private val adapters = java.util.concurrent.ConcurrentHashMap<String, ExternalIntegrationAdapter>()

    companion object {
        @Volatile
        private var defaultInstance: ActionIntentEngine? = null

        val instance: ActionIntentEngine
            get() = defaultInstance ?: synchronized(this) {
                defaultInstance ?: ActionIntentEngine().also { defaultInstance = it }
            }
    }

    init {
        registerAdapter(AndroidDeviceIntegrationAdapter())
        registerAdapter(WasmSandboxIntegrationAdapter())
        registerAdapter(GmailIntegrationAdapter())
        registerAdapter(FilesIntegrationAdapter())
        registerAdapter(SystemInfoIntegrationAdapter())
        registerAdapter(BackendIntegrationAdapter())
        registerAdapter(WreExecutionIntegrationAdapter())
        registerAdapter(UniversalFabricIntegrationAdapter())
    }

    fun registerAdapter(adapter: ExternalIntegrationAdapter) {
        adapters[adapter.capabilityId.uppercase()] = adapter
    }

    fun getAdapter(capabilityId: String): ExternalIntegrationAdapter? =
        adapters[capabilityId.uppercase()]

    fun parseIntent(prompt: String): ActionIntent? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        if (lower in setOf("system status", "system reality", "capability status", "device readiness", "check system", "readiness check", "check readiness") ||
            (lower.contains("system") && lower.contains("status")) ||
            (lower.contains("capability") && lower.contains("reality")) ||
            (lower.contains("device") && lower.contains("readiness"))
        ) {
            return prepareActionIntent("SYSTEM_INFO", "SYSTEM_INFO", emptyMap(), "Verify and check Wasti AI OS capability reality and system status", RiskLevel.LOW)
        }

        if (lower in setOf("read screen", "read my screen", "what is on my screen", "what is on screen", "what's on my screen", "what's on screen", "see screen", "inspect screen", "scan screen", "dump screen")) {
            return prepareActionIntent("ANDROID_DEVICE", "READ_SCREEN", emptyMap(), "Read active screen content via Android Accessibility Service", RiskLevel.LOW)
        }
        if (lower in setOf("go back", "press back", "back button", "back")) {
            return prepareActionIntent("ANDROID_DEVICE", "BACK", emptyMap(), "Press Android Back button", RiskLevel.LOW)
        }
        if (lower in setOf("go home", "press home", "home button", "home screen")) {
            return prepareActionIntent("ANDROID_DEVICE", "HOME", emptyMap(), "Navigate to Android Home screen", RiskLevel.LOW)
        }
        if (lower in setOf("open notifications", "show notifications", "notifications shade", "view notifications")) {
            return prepareActionIntent("ANDROID_DEVICE", "NOTIFICATIONS", emptyMap(), "Open Android Notification shade", RiskLevel.LOW)
        }
        if (lower in setOf("open quick settings", "show quick settings", "quick settings")) {
            return prepareActionIntent("ANDROID_DEVICE", "QUICK_SETTINGS", emptyMap(), "Open Android Quick Settings panel", RiskLevel.LOW)
        }
        if (lower in setOf("open recents", "show recents", "app switcher", "recent apps")) {
            return prepareActionIntent("ANDROID_DEVICE", "RECENTS", emptyMap(), "Open Android Recents / App Switcher", RiskLevel.LOW)
        }
        if (lower.startsWith("scroll down")) {
            return prepareActionIntent("ANDROID_DEVICE", "SCROLL", mapOf("direction" to "DOWN"), "Scroll active screen downward", RiskLevel.LOW)
        }
        if (lower.startsWith("scroll up")) {
            return prepareActionIntent("ANDROID_DEVICE", "SCROLL", mapOf("direction" to "UP"), "Scroll active screen upward", RiskLevel.LOW)
        }

        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            val element = trimmed.substringAfter(' ').trim()
            if (element.isNotBlank() && !element.startsWith("file") && !element.startsWith("project")) {
                return prepareActionIntent("ANDROID_DEVICE", "TAP_ELEMENT", mapOf("target" to element), "Simulate tap on UI element '$element'", RiskLevel.LOW)
            }
        }

        if (lower.startsWith("send whatsapp to ") || lower.startsWith("whatsapp to ") || (lower.startsWith("send whatsapp ") && lower.contains("to "))) {
            val afterTo = trimmed.substring(trimmed.indexOf("to ", ignoreCase = true) + 3).trim()
            val recipient = afterTo.substringBefore(' ').trim()
            val msg = afterTo.substringAfter(' ', "").trim().ifBlank { "Hello" }
            return prepareActionIntent("ANDROID_DEVICE", "SEND_WHATSAPP", mapOf("recipient" to recipient, "message" to msg), "Send WhatsApp message to $recipient: '$msg'", RiskLevel.MEDIUM)
        }

        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appTarget = trimmed.substringAfter(' ').trim()
            val internalScreens = setOf("dashboard", "chat", "operations", "telemetry", "agents", "memory", "projects", "terminal", "code", "integrations", "account_hub", "settings")
            if (appTarget.lowercase() !in internalScreens && !appTarget.lowercase().startsWith("file ") && !appTarget.lowercase().startsWith("workspace")) {
                return prepareActionIntent("ANDROID_DEVICE", "OPEN_APP", mapOf("target" to appTarget), "Launch application '$appTarget' on device", RiskLevel.LOW)
            }
        }

        if (lower in setOf("list files", "show files", "dir", "ls", "files in workspace", "workspace files") || lower.startsWith("list files ")) {
            val path = if (lower.startsWith("list files ")) trimmed.removePrefix("list files ").trim() else "."
            return prepareActionIntent("FILES", "LIST_FILES", mapOf("path" to path), "List workspace files in path '$path'", RiskLevel.LOW)
        }
        if (lower.startsWith("read file ") || lower.startsWith("view file ") || lower.startsWith("cat ")) {
            val path = when {
                lower.startsWith("read file ") -> trimmed.removePrefix("read file ").trim()
                lower.startsWith("view file ") -> trimmed.removePrefix("view file ").trim()
                else -> trimmed.removePrefix("cat ").trim()
            }
            return prepareActionIntent("FILES", "READ_FILE", mapOf("path" to path), "Read content of file '$path' in workspace", RiskLevel.LOW)
        }
        if (lower.startsWith("write file ") || lower.startsWith("create file ")) {
            val after = if (lower.startsWith("write file ")) trimmed.removePrefix("write file ").trim() else trimmed.removePrefix("create file ").trim()
            val path = after.substringBefore(' ').trim()
            val content = after.substringAfter(' ', "").trim()
            return prepareActionIntent("FILES", "WRITE_FILE", mapOf("path" to path, "content" to content), "Write file '$path' in workspace (${content.length} characters)", RiskLevel.MEDIUM)
        }
        if (lower.startsWith("delete file ") || lower.startsWith("rm ")) {
            val path = if (lower.startsWith("delete file ")) trimmed.removePrefix("delete file ").trim() else trimmed.removePrefix("rm ").trim()
            return prepareActionIntent("FILES", "DELETE_FILE", mapOf("path" to path), "Delete file '$path' from workspace", RiskLevel.HIGH)
        }

        if (lower in setOf("wasm status", "wasm runtime status", "check wasm", "wasm check")) {
            return prepareActionIntent("WASM_SANDBOX", "STATUS", emptyMap(), "Check WASM Sandboxed runtime engine status", RiskLevel.LOW)
        }
        if (lower.startsWith("python ") || lower.startsWith("py ") || lower.startsWith("node ") || lower.startsWith("sh ") || lower.startsWith("bash ") || lower.startsWith("git ")) {
            val cmd = trimmed
            return prepareActionIntent("WRE_EXECUTION", "EXECUTE_COMMAND", mapOf("command" to cmd), "Execute polyglot command '$cmd'", RiskLevel.MEDIUM)
        }
        if (trimmed.isNotBlank()) {
            return prepareActionIntent("UNIVERSAL_FABRIC", "PROCESS_INTENT", mapOf("prompt" to trimmed), "Process human intent: '$trimmed'", RiskLevel.MEDIUM)
        }
        return null
    }

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
        timeline.appendPhase(taskId, TaskTimelinePhase.PLANNED, "Action planned: target='$target', intent='$intent', risk=${riskLevel.name}", mapOf("actionId" to action.actionId, "preview" to previewText))
        return action
    }

    fun authorizeAction(action: ActionIntent, userApproved: Boolean): ActionIntent {
        if (userApproved) {
            action.authorizationState = ActionAuthorizationState.AUTHORIZED
            timeline.appendPhase(action.taskId, TaskTimelinePhase.AUTHORIZED, "Action authorized by user for execution.", mapOf("actionId" to action.actionId))
        } else {
            action.authorizationState = ActionAuthorizationState.CANCELLED
            action.executionTruthState = ActionExecutionTruthState.EXECUTION_FAILED
            action.resultMessage = "Action cancelled by user policy."
            timeline.appendPhase(action.taskId, TaskTimelinePhase.CANCELLED, "Action cancelled by user.", mapOf("actionId" to action.actionId))
        }
        return action
    }

    fun executeAction(action: ActionIntent, adapter: ExternalIntegrationAdapter? = null): ActionIntent {
        val resolvedAdapter = adapter ?: getAdapter(action.target) ?: adapters["ANDROID_DEVICE"]
        if (resolvedAdapter == null) {
            action.authorizationState = ActionAuthorizationState.FAILED
            action.executionTruthState = ActionExecutionTruthState.EXECUTION_FAILED
            action.verificationState = LiveConnectionStatus.FAILED
            action.resultMessage = "No adapter registered for capability target '${action.target}'"
            timeline.appendPhase(action.taskId, TaskTimelinePhase.FAILED, action.resultMessage ?: "Missing adapter", mapOf("actionId" to action.actionId))
            return action
        }

        if (action.authorizationState != ActionAuthorizationState.AUTHORIZED && action.executionMode != ActionExecutionMode.AUTONOMOUS_WITHIN_POLICY) {
            action.authorizationState = ActionAuthorizationState.REQUIRES_CONFIRMATION
            action.executionTruthState = ActionExecutionTruthState.EXECUTION_FAILED
            action.resultMessage = "Execution blocked: Action requires explicit user authorization"
            timeline.appendPhase(action.taskId, TaskTimelinePhase.CAPABILITY_CHECKED, "Execution blocked: Confirmation required.", mapOf("actionId" to action.actionId))
            return action
        }

        action.authorizationState = ActionAuthorizationState.EXECUTING
        action.executionTruthState = ActionExecutionTruthState.DISPATCHED
        timeline.appendPhase(action.taskId, TaskTimelinePhase.EXECUTING, "Executing action '${action.intent}' via ${resolvedAdapter::class.simpleName ?: "Adapter"}.", mapOf("actionId" to action.actionId))

        val result = resolvedAdapter.execute(action.intent, action.payload)

        timeline.appendPhase(action.taskId, TaskTimelinePhase.OBSERVING, "Executor result received: status=${result.status.name}, diagnostic=${result.diagnosticMessage}", mapOf("actionId" to action.actionId, "status" to result.status.name))

        if (result.status == ExternalActionResultStatus.SUCCESS) {
            action.authorizationState = ActionAuthorizationState.SUCCEEDED
            action.resultMessage = result.diagnosticMessage

            if (resolvedAdapter.capabilityId.equals("ANDROID_DEVICE", ignoreCase = true)) {
                action.executionTruthState = ActionExecutionTruthState.EXECUTOR_COMPLETED
                action.verificationState = LiveConnectionStatus.NOT_VERIFIED
                timeline.appendPhase(action.taskId, TaskTimelinePhase.DISPATCHED, "Android action dispatched to the device executor; dispatch is not proof of post-state completion.", mapOf("actionId" to action.actionId, "status" to "DISPATCHED"))
                timeline.appendPhase(action.taskId, TaskTimelinePhase.EXECUTOR_COMPLETED, "Android executor reported completion, but no independent post-state verification evidence is available.", mapOf("actionId" to action.actionId, "status" to "COMPLETED_UNVERIFIED"))
            } else {
                action.executionTruthState = ActionExecutionTruthState.COMPLETED_VERIFIED
                action.verificationState = LiveConnectionStatus.VERIFIED
                timeline.appendPhase(action.taskId, TaskTimelinePhase.VERIFYING, "Adapter supplied a successful execution result accepted by the existing non-device verification contract.", mapOf("actionId" to action.actionId))
                timeline.appendPhase(action.taskId, TaskTimelinePhase.COMPLETED, "Action completed and verified: ${result.diagnosticMessage}", mapOf("actionId" to action.actionId, "status" to "VERIFIED"))
            }
        } else {
            action.authorizationState = ActionAuthorizationState.FAILED
            action.executionTruthState = ActionExecutionTruthState.EXECUTION_FAILED
            action.verificationState = LiveConnectionStatus.FAILED
            action.resultMessage = result.diagnosticMessage
            timeline.appendPhase(action.taskId, TaskTimelinePhase.FAILED, "Action failed: ${result.diagnosticMessage}", mapOf("actionId" to action.actionId, "error" to result.diagnosticMessage))
        }

        return action
    }

    /**
     * Independently upgrades an executor-completed action only when concrete evidence
     * is supplied by a separate verification mechanism. The executor cannot self-verify.
     */
    fun markIndependentlyVerified(action: ActionIntent, evidence: String): ActionIntent {
        require(evidence.isNotBlank()) { "Independent verification evidence is required" }
        require(action.executionTruthState == ActionExecutionTruthState.EXECUTOR_COMPLETED || action.executionTruthState == ActionExecutionTruthState.COMPLETED_UNVERIFIED) {
            "Action must have executor completion before independent verification"
        }
        action.executionTruthState = ActionExecutionTruthState.COMPLETED_VERIFIED
        action.verificationState = LiveConnectionStatus.VERIFIED
        action.resultMessage = "Verified: $evidence"
        timeline.appendPhase(action.taskId, TaskTimelinePhase.VERIFYING, "Independent post-state verification started.", mapOf("actionId" to action.actionId))
        timeline.appendPhase(action.taskId, TaskTimelinePhase.COMPLETED, "Action independently verified: $evidence", mapOf("actionId" to action.actionId, "status" to "VERIFIED", "evidence" to evidence))
        return action
    }

    /**
     * Direct Human Command Intent Execution.
     * Treats an explicit human command as direct authorization and intention,
     * proceeding through the canonical PLAN -> AUTH -> EXEC -> OBSERVE -> VERIFY loop.
     */
    fun parseAndExecuteUserCommand(prompt: String): ActionIntent? {
        val intent = parseIntent(prompt) ?: return null
        intent.authorizationState = ActionAuthorizationState.AUTHORIZED
        intent.resultMessage = null
        return executeAction(intent)
    }
}
