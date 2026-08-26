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
