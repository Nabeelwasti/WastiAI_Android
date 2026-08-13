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
