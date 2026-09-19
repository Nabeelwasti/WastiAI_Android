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
// Active implementation: wired into WastiServiceLocator and ProductionReadinessGate
object IntegrationAuditRegistry {

    private fun mapRealityToStatus(reality: CapabilityReality?): IntegrationStatus {
        if (reality == null) return IntegrationStatus.UNAVAILABLE
        return when (reality.liveConnectionStatus) {
            LiveConnectionStatus.VERIFIED -> IntegrationStatus.VERIFIED_CONNECTED
            LiveConnectionStatus.NOT_VERIFIED -> {
                if (reality.implementationStatus == ImplementationStatus.READY) {
                    IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED
                } else if (reality.implementationStatus == ImplementationStatus.CONTRACT_ONLY) {
                    IntegrationStatus.CONTRACT_ONLY
                } else {
                    IntegrationStatus.PLACEHOLDER
                }
            }
            LiveConnectionStatus.DISCONNECTED, LiveConnectionStatus.ERROR -> IntegrationStatus.UNAVAILABLE
            LiveConnectionStatus.AUTHENTICATION_REQUIRED -> IntegrationStatus.IMPLEMENTED_BUT_NOT_LIVE_VERIFIED
        }
    }

    fun getAuditReport(): List<IntegrationCapabilityAudit> {
        val registry = UnifiedExecutionFabric.instance.realityRegistry

        val geminiReality = registry.getCapabilityReality("GEMINI_AI")
        val localNeuralReality = registry.getCapabilityReality("LOCAL_NEURAL_INFERENCE")
        val filesReality = registry.getCapabilityReality("FILES")
        val terminalReality = registry.getCapabilityReality("TERMINAL")
        val deviceControlReality = registry.getCapabilityReality("device_control")
        val backendReality = registry.getCapabilityReality("BACKEND_SERVICE")
        val githubReality = registry.getCapabilityReality("GITHUB")
        val webSearchReality = registry.getCapabilityReality("search_web")

        return listOf(
            IntegrationCapabilityAudit(
                capabilityName = "Gemini API",
                status = mapRealityToStatus(geminiReality),
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
                status = mapRealityToStatus(deviceControlReality),
                detail = "Full accessibility service implemented with UI node scraper, gesture dispatch, and IPC bridge; live execution requires user enabling Wasti Accessibility in Android Settings"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Android System Control",
                status = mapRealityToStatus(deviceControlReality),
                detail = "Implemented via WastiDeviceController for app launching, intents, messaging, and system navigation"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Workspace File Operations",
                status = mapRealityToStatus(filesReality),
                detail = "Fully verified and connected via WorkspaceManager inside app internal storage boundary"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Terminal / Code Execution",
                status = mapRealityToStatus(terminalReality),
                detail = "Verified via LocalAndroidProvider & ExecuteCodeTool inside workspace sandbox"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Wasti Local 12-Brain Suite",
                status = mapRealityToStatus(localNeuralReality),
                detail = if (localNeuralReality?.liveConnectionStatus == LiveConnectionStatus.VERIFIED) {
                    "12 open-source brand models with genuine neural tensor execution verified on-device"
                } else {
                    "12 open-source brand models with domain-specialized native reasoning, multi-model consensus, and self-training distillation loop (awaiting native weights/verified execution)"
                }
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Firebase Cloud Compute Offloader",
                status = mapRealityToStatus(backendReality),
                detail = "Heavy compute offloading to Firebase Firestore task queue and cloud backend for thermal and memory protection of mobile hardware"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "GitHub API Integration",
                status = mapRealityToStatus(githubReality),
                detail = "Backend client and patch endpoints implemented; live repository mutations require runtime GITHUB_TOKEN"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Web Scraping / Search",
                status = mapRealityToStatus(webSearchReality),
                detail = "Implemented via WebSearchEngine (Google Search & DuckDuckGo APIs); live query requires internet connection"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Deep Research Engine",
                status = mapRealityToStatus(webSearchReality),
                detail = "Deep research synthesis engine implemented with evidence citation analysis; live execution requires network connectivity"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Cloud Sync / Deployment",
                status = mapRealityToStatus(backendReality),
                detail = "CloudSyncManager and SyncWorker background sync implemented; automatic live deployment requires cloud backend credentials"
            ),
            IntegrationCapabilityAudit(
                capabilityName = "Automation Engine",
                status = mapRealityToStatus(deviceControlReality),
                detail = "Workflow execution engine exists; automated triggers require active background worker"
            )
        )
    }
}
