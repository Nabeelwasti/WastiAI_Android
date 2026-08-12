package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

enum class CapabilityRealityState {
    NATIVE,
    LIVE_CONNECTED,
    IMPLEMENTED_NOT_LIVE_VERIFIED,
    EXTERNAL_PROVIDER_AVAILABLE,
    CONTRACT_ONLY,
    PLACEHOLDER,
    UNAVAILABLE,
    FAILED,
    AUTHENTICATION_REQUIRED,
    QUOTA_EXHAUSTED
}

enum class ImplementationStatus {
    READY,
    IN_PROGRESS,
    CONTRACT_ONLY,
    NOT_IMPLEMENTED
}

enum class LiveConnectionStatus {
    VERIFIED,
    NOT_VERIFIED,
    AUTHENTICATION_REQUIRED,
    FAILED,
    DISCONNECTED
}

enum class CapabilityExecutionStatus {
    OPERATIONAL,
    DEGRADED,
    BLOCKED_BY_POLICY,
    UNAVAILABLE
}

enum class CapabilityAuthStatus {
    AUTHENTICATED,
    REQUIRED_NOT_PROVIDED,
    EXPIRED,
    NOT_REQUIRED
}

data class CapabilityReality(
    val capabilityId: String,
    val category: String,
    val implementationStatus: ImplementationStatus,
    val liveConnectionStatus: LiveConnectionStatus,
    val executionStatus: CapabilityExecutionStatus,
    val authenticationStatus: CapabilityAuthStatus,
    val provider: String,
    val supportedOperations: List<String>,
    val limitations: List<String>,
    val lastVerifiedAt: Long = System.currentTimeMillis(),
    val verificationMethod: String = "SYSTEM_AUDIT",
    val fallbackCapabilities: List<String> = emptyList(),
    val realityState: CapabilityRealityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
)

class CapabilityRealityRegistry {

    private val capabilityMap = ConcurrentHashMap<String, CapabilityReality>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        // Workspace Files
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "FILES",
                category = "STORAGE",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WorkspaceManager",
                supportedOperations = listOf("read_file", "write_file", "list_files", "delete_file"),
                limitations = listOf("Restricted to workspace boundary"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Terminal & Code Execution
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "TERMINAL",
                category = "EXECUTION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "LocalAndroidProvider",
                supportedOperations = listOf("execute_code", "run_script"),
                limitations = listOf("Sandboxed execution environment"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Gemini AI Provider
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "GEMINI_AI",
                category = "AI_MODEL",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED,
                provider = "Google Gemini REST API",
                supportedOperations = listOf("generatePlan", "analyzeError", "proposeCorrection"),
                limitations = listOf("Requires valid GEMINI_API_KEY credential"),
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            )
        )

        // Gmail Connector
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "GMAIL",
                category = "COMMUNICATION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.AUTHENTICATION_REQUIRED,
                executionStatus = CapabilityExecutionStatus.BLOCKED_BY_POLICY,
                authenticationStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED,
                provider = "Google Workspace OAuth",
                supportedOperations = listOf("read_messages", "create_draft", "send_email"),
                limitations = listOf("Requires user OAuth authentication and risk-based approval"),
                realityState = CapabilityRealityState.AUTHENTICATION_REQUIRED
            )
        )

        // GitHub Connector
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "GITHUB",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.CONTRACT_ONLY,
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.DEGRADED,
                authenticationStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED,
                provider = "GitHub REST API",
                supportedOperations = listOf("read_repo", "create_issue", "create_pull_request"),
                limitations = listOf("Contract defined; live repo mutation disabled in security policy"),
                realityState = CapabilityRealityState.CONTRACT_ONLY
            )
        )
    }

    fun getCapabilityReality(capabilityId: String): CapabilityReality {
        return capabilityMap[capabilityId] ?: CapabilityReality(
            capabilityId = capabilityId,
            category = "UNKNOWN",
            implementationStatus = ImplementationStatus.NOT_IMPLEMENTED,
            liveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
            executionStatus = CapabilityExecutionStatus.UNAVAILABLE,
            authenticationStatus = CapabilityAuthStatus.REQUIRED_NOT_PROVIDED,
            provider = "None",
            supportedOperations = emptyList(),
            limitations = listOf("Capability unknown or not registered"),
            realityState = CapabilityRealityState.UNAVAILABLE
        )
    }

    fun updateCapabilityReality(capability: CapabilityReality) {
        capabilityMap[capability.capabilityId] = capability
    }

    fun getSystemRealityReport(): List<CapabilityReality> {
        return capabilityMap.values.toList()
    }
}
