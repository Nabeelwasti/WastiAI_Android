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

enum class ImplementationStatus { READY, IN_PROGRESS, CONTRACT_ONLY, NOT_IMPLEMENTED }
enum class LiveConnectionStatus { VERIFIED, NOT_VERIFIED, AUTHENTICATION_REQUIRED, FAILED, DISCONNECTED }
enum class CapabilityExecutionStatus { OPERATIONAL, DEGRADED, BLOCKED_BY_POLICY, UNAVAILABLE }
enum class CapabilityAuthStatus { AUTHENTICATED, REQUIRED_NOT_PROVIDED, EXPIRED, NOT_REQUIRED }

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

    private fun normalizedKey(capabilityId: String): String = capabilityId.trim().uppercase(Locale.ROOT)

    init { registerDefaults() }

    private fun registerDefaults() {
        updateCapabilityReality(CapabilityReality("FILES", "STORAGE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WorkspaceManager", listOf("read_file", "write_file", "list_files", "delete_file"), listOf("Restricted to workspace boundary")))
        // Terminal is an implemented execution route. OPERATIONAL means the fabric may dispatch
        // to the real executor; it does not claim a live connection, verification, or trust state.
        updateCapabilityReality(CapabilityReality("TERMINAL", "EXECUTION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.OPERATIONAL, CapabilityAuthStatus.NOT_REQUIRED, "LocalAndroidProvider", listOf("execute_code", "run_script"), listOf("Sandboxed execution environment")))
        // Device control is an implemented execution route. OPERATIONAL means the fabric
        // may dispatch to the real executor; it does NOT claim accessibility permission,
        // live connectivity, verification, or trust. The executor itself reports runtime
        // permission/environment failures (for example an inactive accessibility service).
        updateCapabilityReality(CapabilityReality("device_control", "AUTOMATION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.OPERATIONAL, CapabilityAuthStatus.NOT_REQUIRED, "WastiDeviceController", listOf("open_app", "send_whatsapp", "send_email", "send_sms", "read_screen", "simulate_tap"), listOf("Requires accessibility service for node clicking")))
        // Memory search is a local execution capability. OPERATIONAL means the query path can run;
        // it does not promote live connection, verification, or trust state.
        updateCapabilityReality(CapabilityReality("memory_search", "MEMORY", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.OPERATIONAL, CapabilityAuthStatus.NOT_REQUIRED, "MemoryManager", listOf("hybridSearch"), emptyList()))
        updateCapabilityReality(CapabilityReality("SYSTEM_INFO", "INSPECTION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiEnvironmentInspector", listOf("get_system_info", "inspect_environment", "get_status"), emptyList()))
        updateCapabilityReality(CapabilityReality("search_web", "INTELLIGENCE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WebSearchEngine", listOf("search", "read_web_page", "b2b_xray_search"), listOf("Network dependent")))
        updateCapabilityReality(CapabilityReality("GEMINI_AI", "AI_MODEL", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "Google Gemini REST API", listOf("generatePlan", "analyzeError", "proposeCorrection"), listOf("Requires valid GEMINI_API_KEY credential")))
        updateCapabilityReality(CapabilityReality("GMAIL", "COMMUNICATION", ImplementationStatus.READY, LiveConnectionStatus.AUTHENTICATION_REQUIRED, CapabilityExecutionStatus.BLOCKED_BY_POLICY, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "Google Workspace OAuth", listOf("read_messages", "create_draft", "send_email"), listOf("Requires user OAuth authentication and risk-based approval"), realityState = CapabilityRealityState.AUTHENTICATION_REQUIRED))
        updateCapabilityReality(CapabilityReality("GITHUB", "DEVELOPMENT", ImplementationStatus.CONTRACT_ONLY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.DEGRADED, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "GitHub REST API", listOf("read_repo", "create_issue", "create_pull_request"), listOf("Contract defined; live repo mutation disabled in security policy"), realityState = CapabilityRealityState.CONTRACT_ONLY))
        updateCapabilityReality(CapabilityReality("PYTHON_RUNTIME", "EXECUTION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiNativeExecutionProvider", listOf("run_python_script", "python3"), listOf("Python runtime binary dynamically detected via WastiNativeExecutionProvider")))
        updateCapabilityReality(CapabilityReality("NODE_RUNTIME", "EXECUTION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiNativeExecutionProvider", listOf("run_node_script", "node", "npm"), listOf("Node.js runtime binary dynamically detected via WastiNativeExecutionProvider")))
        updateCapabilityReality(CapabilityReality("PROJECT_DEV_MANAGER", "DEVELOPMENT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiProjectManager", listOf("create_project", "create_managed_project", "inspect_project", "list_projects", "delete_project", "scan_languages", "get_language_profile"), listOf("Projects created inside sandboxed wasti_workspace")))
        updateCapabilityReality(CapabilityReality("BUILD_MANAGER", "DEVELOPMENT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiBuildAndTestManager", listOf("build_project", "compile_project"), listOf("Builds validated within workspace; compiled languages check toolchain availability")))
        updateCapabilityReality(CapabilityReality("TEST_RUNNER", "DEVELOPMENT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiBuildAndTestManager", listOf("test_project", "run_tests"), listOf("Discovers and executes workspace tests")))
        updateCapabilityReality(CapabilityReality("DEBUG_DIAGNOSTICS", "DEVELOPMENT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiBuildAndTestManager", listOf("debug_project", "analyze_diagnostics"), listOf("Analyzes compiler errors and stack traces without fabricating debug protocols")))
        updateCapabilityReality(CapabilityReality("PACKAGE_MANAGER", "DEVELOPMENT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiRuntimeManager", listOf("resolve_package", "install_package", "list_packages"), listOf("Resolves packages for discovered language runtimes")))
        updateCapabilityReality(CapabilityReality("WASTI_SANDBOX", "SECURITY", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiSandbox", listOf("execute_in_sandbox", "enforce_resource_limits", "enforce_network_policy"), listOf("Confines execution to workspace with emergency stop, timeout and resource limit controls")))
        // The action bus is an implemented local execution path. OPERATIONAL here means
        // the route can execute; it does NOT mean the route is live-verified or trusted.
        updateCapabilityReality(CapabilityReality("NAVIGATE_TO", "ACTION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.OPERATIONAL, CapabilityAuthStatus.NOT_REQUIRED, "WastiAppActionBus", listOf("navigate_to", "open_screen", "navigate"), listOf("Dispatches navigation commands through canonical WastiAppActionBus")))
        updateCapabilityReality(CapabilityReality("LOCAL_SERVER", "TRANSPORT", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiLocalServerManager", listOf("start_server", "stop_server", "server_status"), listOf("Binds local HTTP/WS bridge to localhost or network interface")))
        updateCapabilityReality(CapabilityReality("PYTHON_BRIDGE", "BRIDGE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiNativeBridgeManager", listOf("run_python_script", "execute_python"), listOf("Dispatches execution to native Python runtime via WRE workspace")))
        updateCapabilityReality(CapabilityReality("TERMUX_BRIDGE", "BRIDGE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiNativeBridgeManager", listOf("execute_termux_command"), listOf("Integrates with Termux CLI via intents and socket bridge")))
        updateCapabilityReality(CapabilityReality("B2B_XRAY", "RESEARCH", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiCore", listOf("b2b_xray_search", "analyze_prospects", "enrich_company"), listOf("Dispatches real market and enterprise intelligence queries")))
        updateCapabilityReality(CapabilityReality("LEAD_RADAR", "RESEARCH", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "LeadRadarRepository", listOf("lead_radar_search", "find_leads", "scan_opportunities"), listOf("Dispatches structured real-time lead radar scans")))
        updateCapabilityReality(CapabilityReality("SCREEN_ACCESSIBILITY", "AUTOMATION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiDeviceController", listOf("device_screen_read", "device_screen_tap", "inspect_screen_elements"), listOf("Requires Android accessibility service permission when accessing external apps")))
        updateCapabilityReality(CapabilityReality("DRAFT_PERSISTENCE", "COMMUNICATION", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "DraftPersistenceManager", listOf("save_draft", "get_drafts", "email_draft_creation", "linkedin_draft_creation"), listOf("Persists verified drafts locally and bridges with verified OAuth providers")))
        updateCapabilityReality(CapabilityReality("WEB_RESEARCH_SCRAPER", "RESEARCH", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WebSearchEngine", listOf("web_search", "web_scraping", "fetch_url_content"), listOf("Executes real network requests without fabricating results")))
        updateCapabilityReality(CapabilityReality("LOCAL_NEURAL_INFERENCE", "AI_PROVIDERS", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WastiLocalModelRuntime:wasti_ai_native", listOf("run_local_model_inference", "local_neural_inference", "local_ai", "execute_prompt"), listOf("Requires arm64-v8a native library (libwasti_ai_native.so) and local GGUF model weights on disk")))
        updateCapabilityReality(CapabilityReality("BACKEND_SERVICE", "BRIDGE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "BackendIntegrationAdapter", listOf("check_health", "probe_reachability", "get_queue_status", "compute_offload"), listOf("Requires live reachable WASTI_BACKEND_URL with HTTP 200 /health probe")))
        updateCapabilityReality(CapabilityReality("OPEN_SOURCE_MODELS", "AI_MODEL", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "OpenSourceAIModelSuite:UnifiedBrain", listOf("execute_local_model", "download_weights", "verify_weights", "cooperative_consensus"), listOf("Free, sovereign models: Llama, Qwen, DeepSeek, Gemma, Mistral, Phi, SmolLM via on-device GGUF or local server")))
        updateCapabilityReality(CapabilityReality("LOCAL_LLM", "AI_MODEL", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "LocalLLMClient:Ollama", listOf("generate_text", "stream_tokens", "probe_server"), listOf("Direct HTTP connection to local Ollama / llama.cpp server on 127.0.0.1:11434 / 127.0.0.1:8080")))
        updateCapabilityReality(CapabilityReality("HUGGINGFACE_AI", "AI_MODEL", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "HuggingFaceClient:InferenceRouter", listOf("generate_text", "stream_tokens", "open_source_inference"), listOf("Official open-source model inference via Hugging Face Router with HUGGINGFACE_ACCESS_TOKEN")))
    }

    fun getCapabilityReality(capabilityId: String): CapabilityReality {
        val norm = capabilityId.trim()
        capabilityMap[normalizedKey(norm)]?.let { return it }
        if (norm.startsWith("wre_tool_", ignoreCase = true) || com.example.data.tool.ToolRegistry.getTool(norm) != null || com.example.data.tool.ToolRegistry.getTool(capabilityId) != null) {
            return CapabilityReality(capabilityId, "DYNAMIC_WRE", ImplementationStatus.READY, LiveConnectionStatus.NOT_VERIFIED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.NOT_REQUIRED, "WreDynamicToolProvider", listOf("execute"), emptyList())
        }
        if (norm.equals("navigate_to", true) || norm.equals("open_screen", true) || norm.equals("navigate", true)) capabilityMap["NAVIGATE_TO"]?.let { return it }
        if (norm.equals("local_server", true) || norm.equals("start_server", true) || norm.equals("stop_server", true) || norm.equals("server_status", true) || norm.equals("server", true)) capabilityMap["LOCAL_SERVER"]?.let { return it }
        if (norm.equals("local_llm", true) || norm.equals("ollama", true) || norm.equals("llama_server", true)) capabilityMap["LOCAL_LLM"]?.let { return it }
        if (norm.equals("open_source_models", true) || norm.equals("open_source_model", true) || norm.equals("wasti_models", true)) capabilityMap["OPEN_SOURCE_MODELS"]?.let { return it }
        if (norm.equals("huggingface", true) || norm.equals("huggingface_ai", true) || norm.equals("hf", true)) capabilityMap["HUGGINGFACE_AI"]?.let { return it }
        if (norm.equals("python_bridge", true)) capabilityMap["PYTHON_BRIDGE"]?.let { return it }
        if (norm.equals("termux_bridge", true)) capabilityMap["TERMUX_BRIDGE"]?.let { return it }
        if (norm.equals("python", true) || norm.equals("python3", true)) capabilityMap["PYTHON_RUNTIME"]?.let { return it }
        if (norm.equals("node", true) || norm.equals("nodejs", true) || norm.equals("javascript", true) || norm.equals("npm", true)) capabilityMap["NODE_RUNTIME"]?.let { return it }
        if (norm.equals("project", true) || norm.equals("create_project", true) || norm.equals("project_manager", true) || norm.equals("project_dev_manager", true) || norm.equals("dev_environment", true)) capabilityMap["PROJECT_DEV_MANAGER"]?.let { return it }
        if (norm.equals("build_project", true) || norm.equals("compile_project", true) || norm.equals("build", true) || norm.equals("compile", true) || norm.equals("build_manager", true)) capabilityMap["BUILD_MANAGER"]?.let { return it }
        if (norm.equals("test_project", true) || norm.equals("run_tests", true) || norm.equals("test", true) || norm.equals("test_runner", true)) capabilityMap["TEST_RUNNER"]?.let { return it }
        if (norm.equals("debug_project", true) || norm.equals("analyze_diagnostics", true) || norm.equals("debug", true) || norm.equals("debug_diagnostics", true)) capabilityMap["DEBUG_DIAGNOSTICS"]?.let { return it }
        if (norm.equals("package_manager", true) || norm.equals("resolve_package", true) || norm.equals("install_package", true)) capabilityMap["PACKAGE_MANAGER"]?.let { return it }
        if (norm.equals("wasti_sandbox", true) || norm.equals("sandbox", true)) capabilityMap["WASTI_SANDBOX"]?.let { return it }
        if (norm.equals("terminal", true) || norm.equals("cmd", true) || norm.equals("sh", true) || norm.equals("bash", true) || norm.equals("execute_code", true) || norm.equals("execute_command", true)) capabilityMap["TERMINAL"]?.let { return it }
        if (norm.equals("files", true) || norm.equals("read_file", true) || norm.equals("write_file", true) || norm.equals("list_files", true)) capabilityMap["FILES"]?.let { return it }
        if (norm.equals("system_info", true) || norm.equals("system", true) || norm.equals("inspect_environment", true) || norm.equals("environment", true) || norm.equals("status", true)) capabilityMap["SYSTEM_INFO"]?.let { return it }
        if (norm.equals("local_neural_inference", true) || norm.equals("local_neural", true) || norm.equals("local_ai", true) || norm.equals("neural_inference", true) || norm.equals("local_model", true) || norm.equals("wasti_smollm", true)) capabilityMap["LOCAL_NEURAL_INFERENCE"]?.let { return it }
        if (norm.equals("backend_service", true) || norm.equals("backend", true) || norm.equals("cloud_backend", true)) capabilityMap["BACKEND_SERVICE"]?.let { return it }
        return CapabilityReality(capabilityId, "UNKNOWN", ImplementationStatus.NOT_IMPLEMENTED, LiveConnectionStatus.DISCONNECTED, CapabilityExecutionStatus.UNAVAILABLE, CapabilityAuthStatus.REQUIRED_NOT_PROVIDED, "None", emptyList(), listOf("Capability unknown or not registered"), realityState = CapabilityRealityState.UNAVAILABLE)
    }

    fun get(capabilityId: String): CapabilityReality? = getCapabilityReality(capabilityId)
    fun getCapability(capabilityId: String): CapabilityReality? = get(capabilityId)

    fun updateCapabilityReality(capability: CapabilityReality) {
        require(capability.capabilityId.isNotBlank()) { "Capability ID must not be blank." }
        val canonical = capability.verificationMethod.startsWith("CANONICAL_REALITY_VERIFIED:")
        val attemptedPromotion = capability.liveConnectionStatus == LiveConnectionStatus.VERIFIED || capability.realityState == CapabilityRealityState.LIVE_CONNECTED
        if (attemptedPromotion && !canonical) {
            capabilityMap[normalizedKey(capability.capabilityId)] = capability.copy(
                liveConnectionStatus = LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = if (capability.executionStatus == CapabilityExecutionStatus.OPERATIONAL) CapabilityExecutionStatus.DEGRADED else capability.executionStatus,
                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                verificationMethod = "UNVERIFIED_REGISTRATION"
            )
            return
        }
        capabilityMap[normalizedKey(capability.capabilityId)] = capability
    }

    fun recordExecutionFact(fact: ExecutionFact) {
        val key = normalizedKey(fact.capabilityId)
        val existing = getCapabilityReality(fact.capabilityId)
        val isSimulatedOrTest = fact.environmentTier.isSimulated || fact.environmentTier == ExecutionEnvironmentTier.ROBOLECTRIC_HOST || fact.environmentTier == ExecutionEnvironmentTier.EMULATOR
        if (fact.isVerifiedSuccess && !isSimulatedOrTest && fact.environmentTier == ExecutionEnvironmentTier.PHYSICAL_DEVICE && fact.verificationStatus == UnifiedVerificationStatus.VERIFIED && fact.evidenceBundle?.independentProbe?.isNotBlank() == true && fact.evidenceBundle.verificationMethod == "INDEPENDENT_PROBE") {
            capabilityMap[key] = existing.copy(
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                realityState = CapabilityRealityState.LIVE_CONNECTED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                lastVerifiedAt = fact.completedAt,
                verificationMethod = "EXECUTION_FACT_VERIFIED"
            )
        } else if (fact.terminalTruthState == TerminalTruthState.EXECUTION_FAILED || fact.terminalTruthState == TerminalTruthState.VERIFICATION_FAILED) {
            capabilityMap[key] = existing.copy(liveConnectionStatus = LiveConnectionStatus.FAILED, realityState = CapabilityRealityState.FAILED, executionStatus = CapabilityExecutionStatus.DEGRADED, lastVerifiedAt = fact.completedAt, verificationMethod = if (isSimulatedOrTest) "SIMULATION_ONLY_TEST_FAILED" else "EXECUTION_FACT_FAILED")
        }
    }

    fun projectCanonicalReality(realityVerified: RealityVerifiedCapability) {
        val key = normalizedKey(realityVerified.capabilityId)
        val existing = getCapabilityReality(realityVerified.capabilityId)
        capabilityMap[key] = existing.copy(
            liveConnectionStatus = LiveConnectionStatus.VERIFIED,
            realityState = CapabilityRealityState.LIVE_CONNECTED,
            executionStatus = CapabilityExecutionStatus.OPERATIONAL,
            lastVerifiedAt = realityVerified.verifiedAtEpochMs,
            verificationMethod = "CANONICAL_REALITY_VERIFIED:${realityVerified.canonicalVerifier}"
        )
    }

    fun getSystemRealityReport(): List<CapabilityReality> = capabilityMap.values.sortedBy { normalizedKey(it.capabilityId) }
}
