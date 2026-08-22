package com.example.data.agent.runtime

enum class IntegrationStatus {
    VERIFIED_CONNECTED,
    IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
    CONTRACT_ONLY,
    PLACEHOLDER,
    UNAVAILABLE
}

data class IntegrationCapabilityAudit(
    val capabilityName: String,
    val status: IntegrationStatus,
    val detail: String
)

/**
 * Task 12: Real-World Integration Audit Model.
 * Reality-based audit model reflecting true connectivity status across Wasti OS systems.
 * Prevents claiming live connectivity merely because an interface or button exists.
 */
// TODO: unused — evaluate for removal or wiring in
object IntegrationAuditRegistry {

    fun getAuditReport(): List<IntegrationCapabilityAudit> {
        return listOf(
            IntegrationCapabilityAudit(
                capabilityName = "Gemini API",
                status = IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
                detail = "Provider client implemented via REST/SDK; live connection requires runtime API key in secrets"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Vosk Offline Speech",
                status = IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
                detail = "Vosk library integrated in Gradle; live speech recognition requires local model download"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Voice Input / Audio Capture",
                status = IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
                detail = "AudioRecorder service declared; requires RECORD_AUDIO runtime permission grant"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Floating Assistant Overlay",
                status = IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
                detail = "Android Foreground Service implementation exists; requires SYSTEM_ALERT_WINDOW permission grant"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Android Accessibility Service",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "Contract declared; disabled in runtime security policy to enforce zero accessibility action bypass"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Android System Control",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "System interfaces defined; restricted by Android security model and permission model"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Workspace File Operations",
                status = IntegrationStatus.VERIFIED_CONNECTED,
                detail = "Fully verified and connected via WorkspaceManager inside app internal storage boundary"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Terminal / Code Execution",
                status = IntegrationStatus.VERIFIED_CONNECTED,
                detail = "Verified via LocalAndroidProvider & ExecuteCodeTool inside workspace sandbox"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "GitHub API Integration",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "Data structures declared; live repo mutations disabled in runtime policy"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Web Scraping / Search",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "Search contract declared; requires external network endpoint configuration"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Deep Research Engine",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "Research workflow contract defined; live web crawling not connected in unit runtime"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Cloud Sync / Deployment",
                status = IntegrationStatus.CONTRACT_ONLY,
                detail = "Cloud contract defined; automatic production deployment disabled in Stage 4"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Automation Engine",
                status = IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED,
                detail = "Workflow execution engine exists; automated triggers require active background worker"
            )
        )
    }
}
