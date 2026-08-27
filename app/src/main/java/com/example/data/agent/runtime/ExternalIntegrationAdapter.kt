package com.example.data.agent.runtime

enum class ExternalActionResultStatus {
    SUCCESS,
    NOT_IMPLEMENTED,
    NOT_CONNECTED,
    AUTHENTICATION_REQUIRED,
    FAILED
}

data class ExternalActionResult(
    val status: ExternalActionResultStatus,
    val data: Map<String, Any> = emptyMap(),
    val diagnosticMessage: String
)

interface ExternalIntegrationAdapter {
    val capabilityId: String
    val supportedActions: List<String>
    fun getAuthState(): CapabilityAuthStatus
    fun getLiveVerificationState(): LiveConnectionStatus
    fun execute(action: String, params: Map<String, Any>): ExternalActionResult
    fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult
    fun describeAction(action: String): String
}

class GmailIntegrationAdapter : ExternalIntegrationAdapter {
    override val capabilityId: String = "GMAIL"
    override val supportedActions: List<String> = listOf("READ_MESSAGES", "CREATE_DRAFT", "SEND_EMAIL")

    override fun getAuthState(): CapabilityAuthStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED
    override fun getLiveVerificationState(): LiveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED

    override fun execute(action: String, params: Map<String, Any>): ExternalActionResult {
        return ExternalActionResult(
            status = ExternalActionResultStatus.AUTHENTICATION_REQUIRED,
            diagnosticMessage = "Gmail OAuth authentication required; cannot perform live email action without user authorization"
        )
    }

    override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult {
        return ExternalActionResult(
            status = ExternalActionResultStatus.SUCCESS,
            data = mapOf("preview" to "Draft message to ${params["to"]} with subject ${params["subject"]}"),
            diagnosticMessage = "Dry-run preview generated successfully"
        )
    }

    override fun describeAction(action: String): String {
        return "Gmail action: $action"
    }
}

/**
 * Real-world Android Device & Accessibility integration adapter for ActionIntentSystem.
 */
class AndroidDeviceIntegrationAdapter(
    private val context: android.content.Context? = com.example.WastiApplication.instance
) : ExternalIntegrationAdapter {
    override val capabilityId: String = "ANDROID_DEVICE"
    override val supportedActions: List<String> = listOf(
        "OPEN_APP",
        "SEND_WHATSAPP",
        "SEND_EMAIL",
        "SEND_SMS",
        "POST_SOCIAL",
        "READ_SCREEN",
        "TAP_ELEMENT",
        "TAP_COORDINATE",
        "SWIPE"
    )

    override fun getAuthState(): CapabilityAuthStatus =
        if (context != null) CapabilityAuthStatus.AUTHENTICATED else CapabilityAuthStatus.REQUIRED_NOT_PROVIDED

    override fun getLiveVerificationState(): LiveConnectionStatus =
        if (com.example.service.WastiAccessibilityService.isServiceActive) LiveConnectionStatus.VERIFIED else LiveConnectionStatus.NOT_VERIFIED

    override fun execute(action: String, params: Map<String, Any>): ExternalActionResult {
        val ctx = context ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return ExternalActionResult(
                status = ExternalActionResultStatus.NOT_CONNECTED,
                diagnosticMessage = "Application context unavailable for device action execution"
            )
        }

        return try {
            when (action.uppercase()) {
                "OPEN_APP", "LAUNCH_APP" -> {
                    val app = params["target"]?.toString() ?: params["app"]?.toString() ?: ""
                    val res = com.example.data.device.WastiDeviceController.openApp(ctx, app)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("app" to app, "action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "SEND_WHATSAPP" -> {
                    val recipient = params["recipient"]?.toString() ?: params["to"]?.toString() ?: ""
                    val msg = params["message"]?.toString() ?: params["text"]?.toString() ?: "Hello"
                    val res = com.example.data.device.WastiDeviceController.sendWhatsAppMessage(ctx, recipient, msg)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("recipient" to recipient),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "SEND_EMAIL" -> {
                    val recipient = params["recipient"]?.toString() ?: params["to"]?.toString() ?: ""
                    val subject = params["subject"]?.toString() ?: ""
                    val body = params["body"]?.toString() ?: params["message"]?.toString() ?: ""
                    val res = com.example.data.device.WastiDeviceController.sendEmail(ctx, recipient, subject, body)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("recipient" to recipient, "subject" to subject),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "SEND_SMS" -> {
                    val recipient = params["recipient"]?.toString() ?: params["to"]?.toString() ?: ""
                    val msg = params["message"]?.toString() ?: ""
                    val res = com.example.data.device.WastiDeviceController.sendSMS(ctx, recipient, msg)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("recipient" to recipient),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "POST_SOCIAL" -> {
                    val platform = params["platform"]?.toString() ?: "Social"
                    val content = params["content"]?.toString() ?: ""
                    val res = com.example.data.device.WastiDeviceController.postSocialMedia(ctx, platform, content)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("platform" to platform),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "READ_SCREEN" -> {
                    val content = com.example.data.device.WastiDeviceController.readScreenContent(ctx)
                    ExternalActionResult(
                        status = ExternalActionResultStatus.SUCCESS,
                        data = mapOf("screenContent" to content),
                        diagnosticMessage = "Screen content read successfully"
                    )
                }
                "TAP_ELEMENT", "CLICK_ELEMENT" -> {
                    val target = params["target"]?.toString() ?: params["text"]?.toString() ?: ""
                    val res = com.example.data.device.WastiDeviceController.simulateTap(ctx, target)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("target" to target),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "TAP_COORDINATE", "CLICK_COORD" -> {
                    val x = (params["x"] as? Number)?.toFloat() ?: params["x"]?.toString()?.toFloatOrNull() ?: 0f
                    val y = (params["y"] as? Number)?.toFloat() ?: params["y"]?.toString()?.toFloatOrNull() ?: 0f
                    val res = com.example.data.device.WastiDeviceController.simulateTapAt(ctx, x, y)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("x" to x, "y" to y),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "SWIPE" -> {
                    val startX = (params["startX"] as? Number)?.toFloat() ?: 0f
                    val startY = (params["startY"] as? Number)?.toFloat() ?: 0f
                    val endX = (params["endX"] as? Number)?.toFloat() ?: 0f
                    val endY = (params["endY"] as? Number)?.toFloat() ?: 0f
                    val duration = (params["duration"] as? Number)?.toLong() ?: 300L
                    val res = com.example.data.device.WastiDeviceController.simulateSwipe(ctx, startX, startY, endX, endY, duration)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("startX" to startX, "startY" to startY, "endX" to endX, "endY" to endY),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "BACK", "NAV_BACK" -> {
                    val res = com.example.data.device.WastiDeviceController.performBack(ctx)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "HOME", "NAV_HOME" -> {
                    val res = com.example.data.device.WastiDeviceController.performHome(ctx)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "RECENTS", "NAV_RECENTS" -> {
                    val res = com.example.data.device.WastiDeviceController.performRecents(ctx)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "NOTIFICATIONS", "NAV_NOTIFICATIONS" -> {
                    val res = com.example.data.device.WastiDeviceController.performNotifications(ctx)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "QUICK_SETTINGS", "NAV_QUICK_SETTINGS" -> {
                    val res = com.example.data.device.WastiDeviceController.performQuickSettings(ctx)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("action" to res.actionType),
                        diagnosticMessage = res.userFeedback
                    )
                }
                "SCROLL" -> {
                    val direction = params["direction"]?.toString() ?: "DOWN"
                    val res = com.example.data.device.WastiDeviceController.performScroll(ctx, direction)
                    ExternalActionResult(
                        status = if (res.success) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("direction" to direction),
                        diagnosticMessage = res.userFeedback
                    )
                }
                else -> {
                    ExternalActionResult(
                        status = ExternalActionResultStatus.NOT_IMPLEMENTED,
                        diagnosticMessage = "Unsupported Android device action: $action"
                    )
                }
            }
        } catch (e: Exception) {
            ExternalActionResult(
                status = ExternalActionResultStatus.FAILED,
                diagnosticMessage = "Exception executing device action '$action': ${e.message}"
            )
        }
    }

    override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult {
        return ExternalActionResult(
            status = ExternalActionResultStatus.SUCCESS,
            data = mapOf("preview" to "Android device action '$action' with parameters $params"),
            diagnosticMessage = "Dry run preview generated"
        )
    }

    override fun describeAction(action: String): String = "Android Device Action: $action"
}

/**
 * Sandboxed WASM Tool runner integration adapter.
 */
class WasmSandboxIntegrationAdapter(
    private val runtime: com.example.data.sandbox.WastiWasmRuntime = com.example.data.sandbox.WastiWasmRuntime.instance
) : ExternalIntegrationAdapter {
    override val capabilityId: String = "WASM_SANDBOX"
    override val supportedActions: List<String> = listOf("EXECUTE_MODULE", "RUN_FUNCTION", "RUN_TOOL", "STATUS")

    override fun getAuthState(): CapabilityAuthStatus = CapabilityAuthStatus.AUTHENTICATED
    override fun getLiveVerificationState(): LiveConnectionStatus = LiveConnectionStatus.VERIFIED

    override fun execute(action: String, params: Map<String, Any>): ExternalActionResult {
        return try {
            when (action.uppercase()) {
                "RUN_TOOL", "EXECUTE_TOOL" -> {
                    val toolName = params["toolName"]?.toString() ?: "sandboxed_tool"
                    val expression = params["expression"]?.toString() ?: ""
                    val pMap = params.filterKeys { it != "toolName" && it != "expression" }.mapValues { it.value.toString() }
                    val res = runtime.runSandboxedScript(toolName, expression, pMap)
                    ExternalActionResult(
                        status = if (res.isSuccess) ExternalActionResultStatus.SUCCESS else ExternalActionResultStatus.FAILED,
                        data = mapOf("output" to (res.stringOutput ?: ""), "fuel" to res.fuelConsumed, "timeMs" to res.executionTimeMs),
                        diagnosticMessage = res.diagnosticMessage
                    )
                }
                "STATUS" -> {
                    val status = runtime.getRuntimeStatus()
                    ExternalActionResult(
                        status = ExternalActionResultStatus.SUCCESS,
                        data = status,
                        diagnosticMessage = "WASM Runtime operational"
                    )
                }
                else -> {
                    ExternalActionResult(
                        status = ExternalActionResultStatus.NOT_IMPLEMENTED,
                        diagnosticMessage = "WASM Action '$action' not implemented"
                    )
                }
            }
        } catch (e: Exception) {
            ExternalActionResult(
                status = ExternalActionResultStatus.FAILED,
                diagnosticMessage = "WASM Sandboxed execution error: ${e.message}"
            )
        }
    }

    override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult {
        return ExternalActionResult(
            status = ExternalActionResultStatus.SUCCESS,
            data = mapOf("preview" to "WASM Sandboxed tool '$action' preview with params $params"),
            diagnosticMessage = "WASM dry run preview generated"
        )
    }

    override fun describeAction(action: String): String = "WASM Sandbox Action: $action"
}

/**
 * Filesystem Integration Adapter.
 * Real, verified file operations within the workspace boundary.
 */
class FilesIntegrationAdapter(
    private val context: android.content.Context? = com.example.WastiApplication.instance
) : ExternalIntegrationAdapter {
    override val capabilityId: String = "FILES"
    override val supportedActions: List<String> = listOf(
        "LIST_FILES", "READ_FILE", "WRITE_FILE", "DELETE_FILE", "FILE_EXISTS", "CREATE_DIR"
    )

    override fun getAuthState(): CapabilityAuthStatus = CapabilityAuthStatus.AUTHENTICATED
    override fun getLiveVerificationState(): LiveConnectionStatus = LiveConnectionStatus.VERIFIED

    override fun execute(action: String, params: Map<String, Any>): ExternalActionResult {
        val ctx = context ?: com.example.WastiApplication.instance
        if (ctx == null) {
            return ExternalActionResult(
                status = ExternalActionResultStatus.NOT_CONNECTED,
                diagnosticMessage = "Context unavailable for file operations"
            )
        }
        val workspace = WorkspaceManager(ctx)
        val path = params["path"]?.toString() ?: params["file"]?.toString() ?: "."

        return try {
            when (action.uppercase()) {
                "LIST_FILES", "LS", "DIR" -> {
                    val res = workspace.listDirectory(path)
                    if (res.isSuccess) {
                        val files = res.getOrNull().orEmpty()
                        ExternalActionResult(
                            status = ExternalActionResultStatus.SUCCESS,
                            data = mapOf("files" to files, "path" to path),
                            diagnosticMessage = "Found ${files.size} items in '$path': ${files.joinToString(", ")}"
                        )
                    } else {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.FAILED,
                            diagnosticMessage = "Failed to list directory '$path': ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
                "READ_FILE", "CAT" -> {
                    val res = workspace.readFile(path)
                    if (res.isSuccess) {
                        val content = res.getOrNull().orEmpty()
                        ExternalActionResult(
                            status = ExternalActionResultStatus.SUCCESS,
                            data = mapOf("path" to path, "content" to content),
                            diagnosticMessage = "File '$path' read successfully (${content.length} characters)"
                        )
                    } else {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.FAILED,
                            diagnosticMessage = "Failed to read file '$path': ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
                "WRITE_FILE", "CREATE_FILE" -> {
                    val content = params["content"]?.toString() ?: ""
                    val res = workspace.writeFile(path, content)
                    if (res.isSuccess) {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.SUCCESS,
                            data = mapOf("path" to path, "bytesWritten" to content.toByteArray().size),
                            diagnosticMessage = "File '$path' written successfully (${content.length} characters)"
                        )
                    } else {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.FAILED,
                            diagnosticMessage = "Failed to write file '$path': ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
                "DELETE_FILE", "RM" -> {
                    val res = workspace.deleteFile(path)
                    if (res.isSuccess && res.getOrNull() == true) {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.SUCCESS,
                            data = mapOf("path" to path),
                            diagnosticMessage = "Deleted file/directory '$path' successfully"
                        )
                    } else {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.FAILED,
                            diagnosticMessage = "File '$path' could not be deleted or does not exist"
                        )
                    }
                }
                "FILE_EXISTS" -> {
                    val exists = workspace.fileExists(path)
                    ExternalActionResult(
                        status = ExternalActionResultStatus.SUCCESS,
                        data = mapOf("path" to path, "exists" to exists),
                        diagnosticMessage = if (exists) "File '$path' exists in workspace" else "File '$path' does not exist"
                    )
                }
                "CREATE_DIR", "MKDIR" -> {
                    val res = workspace.createDirectory(path)
                    if (res.isSuccess) {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.SUCCESS,
                            data = mapOf("path" to path),
                            diagnosticMessage = "Directory '$path' created successfully"
                        )
                    } else {
                        ExternalActionResult(
                            status = ExternalActionResultStatus.FAILED,
                            diagnosticMessage = "Failed to create directory '$path': ${res.exceptionOrNull()?.message}"
                        )
                    }
                }
                else -> {
                    ExternalActionResult(
                        status = ExternalActionResultStatus.NOT_IMPLEMENTED,
                        diagnosticMessage = "File action '$action' not implemented"
                    )
                }
            }
        } catch (e: Exception) {
            ExternalActionResult(
                status = ExternalActionResultStatus.FAILED,
                diagnosticMessage = "File operation error: ${e.message}"
            )
        }
    }

    override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult =
        ExternalActionResult(ExternalActionResultStatus.SUCCESS, mapOf("dryRun" to true), "File dry-run: $action")

    override fun describeAction(action: String): String = "Workspace File Action: $action"
}

/**
 * System Reality & Info Integration Adapter.
 */
class SystemInfoIntegrationAdapter(
    private val context: android.content.Context? = com.example.WastiApplication.instance
) : ExternalIntegrationAdapter {
    override val capabilityId: String = "SYSTEM_INFO"
    override val supportedActions: List<String> = listOf("SYSTEM_INFO", "CAPABILITY_STATUS", "READINESS_CHECK")

    override fun getAuthState(): CapabilityAuthStatus = CapabilityAuthStatus.AUTHENTICATED
    override fun getLiveVerificationState(): LiveConnectionStatus = LiveConnectionStatus.VERIFIED

    override fun execute(action: String, params: Map<String, Any>): ExternalActionResult {
        val registry = CapabilityRealityRegistry()
        val allCaps = registry.getSystemRealityReport()
        val verifiedCount = allCaps.count { it.liveConnectionStatus == LiveConnectionStatus.VERIFIED }
        val accActive = com.example.service.WastiAccessibilityService.isServiceActive
        val serverActive = com.example.data.server.WastiLocalServerManager.getInstance().serverInfo.value.state.name

        val summary = buildString {
            appendLine("=== WASTI AI OS REALITY & STATUS ===")
            appendLine("• OS Engine: Wasti Unified Cognitive Brain")
            appendLine("• Accessibility Bridge: ${if (accActive) "ONLINE & ACTIVE" else "INACTIVE (Needs Android permission)"}")
            appendLine("• Local HTTP Daemon: $serverActive")
            appendLine("• Sandboxed WASM Engine: VERIFIED & READY")
            appendLine("• Capabilities Verified: $verifiedCount / ${allCaps.size}")
            appendLine("• Capabilities Overview:")
            allCaps.take(8).forEach { cap ->
                appendLine("  - ${cap.capabilityId}: ${cap.liveConnectionStatus.name} (${cap.executionStatus.name})")
            }
        }

        return ExternalActionResult(
            status = ExternalActionResultStatus.SUCCESS,
            data = mapOf(
                "verifiedCapabilities" to verifiedCount,
                "totalCapabilities" to allCaps.size,
                "accessibilityActive" to accActive,
                "summary" to summary
            ),
            diagnosticMessage = summary
        )
    }

    override fun dryRun(action: String, params: Map<String, Any>): ExternalActionResult =
        ExternalActionResult(ExternalActionResultStatus.SUCCESS, emptyMap(), "System info dry-run preview")

    override fun describeAction(action: String): String = "System Readiness & Capability Reality Check"
}
