package com.example.data.agent.runtime

import java.util.Locale
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

    /**
     * Capability IDs are case-insensitive at the registry boundary. Locale.ROOT
     * prevents a device's language settings from changing identifier resolution.
     */
    private fun normalizedKey(capabilityId: String): String =
        capabilityId.trim().uppercase(Locale.ROOT)

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

        // Device Control Automation
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "device_control",
                category = "AUTOMATION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiDeviceController",
                supportedOperations = listOf("open_app", "send_whatsapp", "send_email", "send_sms", "read_screen", "simulate_tap"),
                limitations = listOf("Requires accessibility service for node clicking"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Memory Search
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "memory_search",
                category = "MEMORY",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "MemoryManager",
                supportedOperations = listOf("hybridSearch"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // System Info & Environment Inspection
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "SYSTEM_INFO",
                category = "INSPECTION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiEnvironmentInspector",
                supportedOperations = listOf("get_system_info", "inspect_environment", "get_status"),
                limitations = emptyList(),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Web Search
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "search_web",
                category = "INTELLIGENCE",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WebSearchEngine",
                supportedOperations = listOf("search", "read_web_page", "b2b_xray_search"),
                limitations = listOf("Network dependent"),
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

        // Python Runtime Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "PYTHON_RUNTIME",
                category = "EXECUTION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiNativeExecutionProvider",
                supportedOperations = listOf("run_python_script", "python3"),
                limitations = listOf("Python runtime binary dynamically detected via WastiNativeExecutionProvider"),
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            )
        )

        // Node.js Runtime Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "NODE_RUNTIME",
                category = "EXECUTION",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiNativeExecutionProvider",
                supportedOperations = listOf("run_node_script", "node", "npm"),
                limitations = listOf("Node.js runtime binary dynamically detected via WastiNativeExecutionProvider"),
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            )
        )

        // Multi-Language Project Manager Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "PROJECT_DEV_MANAGER",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiProjectManager",
                supportedOperations = listOf("create_project", "create_managed_project", "inspect_project", "list_projects", "delete_project", "scan_languages", "get_language_profile"),
                limitations = listOf("Projects created inside sandboxed wasti_workspace"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Build Manager Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "BUILD_MANAGER",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiBuildAndTestManager",
                supportedOperations = listOf("build_project", "compile_project"),
                limitations = listOf("Builds validated within workspace; compiled languages check toolchain availability"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Test Runner Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "TEST_RUNNER",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiBuildAndTestManager",
                supportedOperations = listOf("test_project", "run_tests"),
                limitations = listOf("Discovers and executes workspace tests"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Debugging & Diagnostics Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "DEBUG_DIAGNOSTICS",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiBuildAndTestManager",
                supportedOperations = listOf("debug_project", "analyze_diagnostics"),
                limitations = listOf("Analyzes compiler errors and stack traces without fabricating debug protocols"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Package Manager Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "PACKAGE_MANAGER",
                category = "DEVELOPMENT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiRuntimeManager",
                supportedOperations = listOf("resolve_package", "install_package", "list_packages"),
                limitations = listOf("Resolves packages for discovered language runtimes"),
                realityState = CapabilityRealityState.NATIVE
            )
        )

        // Wasti Sandbox Capability
        updateCapabilityReality(
            CapabilityReality(
                capabilityId = "WASTI_SANDBOX",
                category = "SECURITY",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiSandbox",
                supportedOperations = listOf("execute_in_sandbox", "enforce_resource_limits", "enforce_network_policy"),
                limitations = listOf("Confines execution to workspace with emergency stop, timeout and resource limit controls"),
                realityState = CapabilityRealityState.NATIVE
            )
        )
    }

    fun getCapabilityReality(capabilityId: String): CapabilityReality {
        val norm = capabilityId.trim()
        val direct = capabilityMap[normalizedKey(norm)]
        if (direct != null) return direct

        // Check alias mapping
        if (norm.equals("python", ignoreCase = true) || norm.equals("python3", ignoreCase = true)) {
            capabilityMap["PYTHON_RUNTIME"]?.let { return it }
        }
        if (norm.equals("node", ignoreCase = true) || norm.equals("nodejs", ignoreCase = true) || norm.equals("javascript", ignoreCase = true) || norm.equals("npm", ignoreCase = true)) {
            capabilityMap["NODE_RUNTIME"]?.let { return it }
        }
        if (norm.equals("project", ignoreCase = true) || norm.equals("create_project", ignoreCase = true) || norm.equals("project_manager", ignoreCase = true) || norm.equals("project_dev_manager", ignoreCase = true) || norm.equals("dev_environment", ignoreCase = true)) {
            capabilityMap["PROJECT_DEV_MANAGER"]?.let { return it }
        }
        if (norm.equals("build_project", ignoreCase = true) || norm.equals("compile_project", ignoreCase = true) || norm.equals("build", ignoreCase = true) || norm.equals("compile", ignoreCase = true) || norm.equals("build_manager", ignoreCase = true)) {
            capabilityMap["BUILD_MANAGER"]?.let { return it }
        }
        if (norm.equals("test_project", ignoreCase = true) || norm.equals("run_tests", ignoreCase = true) || norm.equals("test", ignoreCase = true) || norm.equals("test_runner", ignoreCase = true)) {
            capabilityMap["TEST_RUNNER"]?.let { return it }
        }
        if (norm.equals("debug_project", ignoreCase = true) || norm.equals("analyze_diagnostics", ignoreCase = true) || norm.equals("debug", ignoreCase = true) || norm.equals("debug_diagnostics", ignoreCase = true)) {
            capabilityMap["DEBUG_DIAGNOSTICS"]?.let { return it }
        }
        if (norm.equals("package_manager", ignoreCase = true) || norm.equals("resolve_package", ignoreCase = true) || norm.equals("install_package", ignoreCase = true)) {
            capabilityMap["PACKAGE_MANAGER"]?.let { return it }
        }
        if (norm.equals("wasti_sandbox", ignoreCase = true) || norm.equals("sandbox", ignoreCase = true)) {
            capabilityMap["WASTI_SANDBOX"]?.let { return it }
        }
        if (norm.equals("terminal", ignoreCase = true) || norm.equals("cmd", ignoreCase = true) || norm.equals("sh", ignoreCase = true) || norm.equals("bash", ignoreCase = true) || norm.equals("execute_code", ignoreCase = true) || norm.equals("execute_command", ignoreCase = true)) {
            capabilityMap["TERMINAL"]?.let { return it }
        }
        if (norm.equals("files", ignoreCase = true) || norm.equals("read_file", ignoreCase = true) || norm.equals("write_file", ignoreCase = true) || norm.equals("list_files", ignoreCase = true)) {
            capabilityMap["FILES"]?.let { return it }
        }
        if (norm.equals("system_info", ignoreCase = true) || norm.equals("system", ignoreCase = true) || norm.equals("inspect_environment", ignoreCase = true) || norm.equals("environment", ignoreCase = true) || norm.equals("status", ignoreCase = true)) {
            capabilityMap["SYSTEM_INFO"]?.let { return it }
        }

        return CapabilityReality(
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

    fun get(capabilityId: String): CapabilityReality? = getCapabilityReality(capabilityId)

    /**
     * Registers or atomically replaces the current reality record for one capability.
     * Concurrent readers always see either the previous complete record or the next one.
     */
    fun updateCapabilityReality(capability: CapabilityReality) {
        require(capability.capabilityId.isNotBlank()) {
            "Capability ID must not be blank."
        }
        capabilityMap[normalizedKey(capability.capabilityId)] = capability
    }

    /**
     * Returns a stable snapshot suitable for a dashboard, audit, or planner.
     */
    fun getSystemRealityReport(): List<CapabilityReality> =
        capabilityMap.values.sortedBy { normalizedKey(it.capabilityId) }
}


