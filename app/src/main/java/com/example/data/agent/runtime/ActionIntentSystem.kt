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
    }

    fun registerAdapter(adapter: ExternalIntegrationAdapter) {
        adapters[adapter.capabilityId.uppercase()] = adapter
    }

    fun getAdapter(capabilityId: String): ExternalIntegrationAdapter? =
        adapters[capabilityId.uppercase()]

    fun parseIntent(prompt: String): ActionIntent? {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // 1. System Reality & Diagnostics
        if (lower in setOf("system status", "system reality", "capability status", "device readiness", "check system", "readiness check", "check readiness") ||
            (lower.contains("system") && lower.contains("status")) ||
            (lower.contains("capability") && lower.contains("reality")) ||
            (lower.contains("device") && lower.contains("readiness"))
        ) {
            return prepareActionIntent(
                target = "SYSTEM_INFO",
                intent = "SYSTEM_INFO",
                payload = emptyMap(),
                previewText = "Verify and check Wasti AI OS capability reality and system status",
                riskLevel = RiskLevel.LOW
            )
        }

        // 2. Screen Reading & Accessibility
        if (lower in setOf("read screen", "read my screen", "what is on my screen", "what is on screen", "what's on my screen", "what's on screen", "see screen", "inspect screen", "scan screen", "dump screen")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "READ_SCREEN",
                payload = emptyMap(),
                previewText = "Read active screen content via Android Accessibility Service",
                riskLevel = RiskLevel.LOW
            )
        }

        // 3. Android System Navigation (Back, Home, Notifications, Quick Settings, Recents, Scroll)
        if (lower in setOf("go back", "press back", "back button", "back")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "BACK",
                payload = emptyMap(),
                previewText = "Press Android Back button",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower in setOf("go home", "press home", "home button", "home screen")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "HOME",
                payload = emptyMap(),
                previewText = "Navigate to Android Home screen",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower in setOf("open notifications", "show notifications", "notifications shade", "view notifications")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "NOTIFICATIONS",
                payload = emptyMap(),
                previewText = "Open Android Notification shade",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower in setOf("open quick settings", "show quick settings", "quick settings")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "QUICK_SETTINGS",
                payload = emptyMap(),
                previewText = "Open Android Quick Settings panel",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower in setOf("open recents", "show recents", "app switcher", "recent apps")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "RECENTS",
                payload = emptyMap(),
                previewText = "Open Android Recents / App Switcher",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower.startsWith("scroll down")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "SCROLL",
                payload = mapOf("direction" to "DOWN"),
                previewText = "Scroll active screen downward",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower.startsWith("scroll up")) {
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "SCROLL",
                payload = mapOf("direction" to "UP"),
                previewText = "Scroll active screen upward",
                riskLevel = RiskLevel.LOW
            )
        }

        // 4. Tap / Click UI element
        if (lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ")) {
            val element = trimmed.substringAfter(' ').trim()
            if (element.isNotBlank() && !element.startsWith("file") && !element.startsWith("project")) {
                return prepareActionIntent(
                    target = "ANDROID_DEVICE",
                    intent = "TAP_ELEMENT",
                    payload = mapOf("target" to element),
                    previewText = "Simulate tap on UI element '$element'",
                    riskLevel = RiskLevel.LOW
                )
            }
        }

        // 5. WhatsApp Messaging
        if (lower.startsWith("send whatsapp to ") || lower.startsWith("whatsapp to ") || (lower.startsWith("send whatsapp ") && lower.contains("to "))) {
            val afterTo = trimmed.substring(trimmed.indexOf("to ", ignoreCase = true) + 3).trim()
            val recipient = afterTo.substringBefore(' ').trim()
            val msg = afterTo.substringAfter(' ', "").trim().ifBlank { "Hello" }
            return prepareActionIntent(
                target = "ANDROID_DEVICE",
                intent = "SEND_WHATSAPP",
                payload = mapOf("recipient" to recipient, "message" to msg),
                previewText = "Send WhatsApp message to $recipient: '$msg'",
                riskLevel = RiskLevel.MEDIUM
            )
        }

        // 6. Direct App Launching
        if (lower.startsWith("open ") || lower.startsWith("launch ")) {
            val appTarget = trimmed.substringAfter(' ').trim()
            // Ensure this isn't a known internal workspace navigation like "open dashboard"
            val internalScreens = setOf("dashboard", "chat", "operations", "telemetry", "agents", "memory", "projects", "terminal", "code", "integrations", "account_hub", "settings")
            if (appTarget.lowercase() !in internalScreens && !appTarget.lowercase().startsWith("file ") && !appTarget.lowercase().startsWith("workspace")) {
                return prepareActionIntent(
                    target = "ANDROID_DEVICE",
                    intent = "OPEN_APP",
                    payload = mapOf("target" to appTarget),
                    previewText = "Launch application '$appTarget' on device",
                    riskLevel = RiskLevel.LOW
                )
            }
        }

        // 7. Workspace & File Operations
        if (lower in setOf("list files", "show files", "dir", "ls", "files in workspace", "workspace files") || lower.startsWith("list files ")) {
            val path = if (lower.startsWith("list files ")) trimmed.removePrefix("list files ").trim() else "."
            return prepareActionIntent(
                target = "FILES",
                intent = "LIST_FILES",
                payload = mapOf("path" to path),
                previewText = "List workspace files in path '$path'",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower.startsWith("read file ") || lower.startsWith("view file ") || lower.startsWith("cat ")) {
            val path = when {
                lower.startsWith("read file ") -> trimmed.removePrefix("read file ").trim()
                lower.startsWith("view file ") -> trimmed.removePrefix("view file ").trim()
                else -> trimmed.removePrefix("cat ").trim()
            }
            return prepareActionIntent(
                target = "FILES",
                intent = "READ_FILE",
                payload = mapOf("path" to path),
                previewText = "Read content of file '$path' in workspace",
                riskLevel = RiskLevel.LOW
            )
        }
        if (lower.startsWith("write file ") || lower.startsWith("create file ")) {
            val after = if (lower.startsWith("write file ")) trimmed.removePrefix("write file ").trim() else trimmed.removePrefix("create file ").trim()
            val path = after.substringBefore(' ').trim()
            val content = after.substringAfter(' ', "").trim()
            return prepareActionIntent(
                target = "FILES",
                intent = "WRITE_FILE",
                payload = mapOf("path" to path, "content" to content),
                previewText = "Write file '$path' in workspace (${content.length} characters)",
                riskLevel = RiskLevel.MEDIUM
            )
        }
        if (lower.startsWith("delete file ") || lower.startsWith("rm ")) {
            val path = if (lower.startsWith("delete file ")) trimmed.removePrefix("delete file ").trim() else trimmed.removePrefix("rm ").trim()
            return prepareActionIntent(
                target = "FILES",
                intent = "DELETE_FILE",
                payload = mapOf("path" to path),
                previewText = "Delete file '$path' from workspace",
                riskLevel = RiskLevel.HIGH
            )
        }

        // 8. WASM Sandbox operations
        if (lower in setOf("wasm status", "wasm runtime status", "check wasm", "wasm check")) {
            return prepareActionIntent(
                target = "WASM_SANDBOX",
                intent = "STATUS",
                payload = emptyMap(),
                previewText = "Check WASM Sandboxed runtime engine status",
                riskLevel = RiskLevel.LOW
            )
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
        adapter: ExternalIntegrationAdapter? = null
    ): ActionIntent {
        val resolvedAdapter = adapter ?: getAdapter(action.target) ?: adapters["ANDROID_DEVICE"]
        if (resolvedAdapter == null) {
            action.authorizationState = ActionAuthorizationState.FAILED
            action.verificationState = LiveConnectionStatus.FAILED
            action.resultMessage = "No adapter registered for capability target '${action.target}'"
            timeline.appendPhase(
                taskId = action.taskId,
                phase = TaskTimelinePhase.FAILED,
                description = action.resultMessage ?: "Missing adapter",
                metadata = mapOf("actionId" to action.actionId)
            )
            return action
        }

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
            description = "Executing action '${action.intent}' via ${resolvedAdapter::class.simpleName ?: "Adapter"}.",
            metadata = mapOf("actionId" to action.actionId)
        )

        val result = resolvedAdapter.execute(action.intent, action.payload)

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
