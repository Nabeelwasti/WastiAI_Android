package com.example.data.agent.runtime

data class CapabilityPlan(
    val requiredCapabilities: List<String>,
    val availableCapabilities: List<String>,
    val unavailableCapabilities: List<String>,
    val preferredExecutionPath: String,
    val fallbackPaths: List<String>,
    val missingDependencies: List<String>,
    val requiredAction: String = "process_request",
    val capabilityCategory: String = "GENERAL",
    val availableExecutors: List<String> = emptyList(),
    val realityState: CapabilityRealityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
    val authenticationRequirement: CapabilityAuthStatus = CapabilityAuthStatus.NOT_REQUIRED,
    val authorizationRequirement: String = "STANDARD_USER_PERMITTED",
    val executionStrategies: List<ExecutionStrategy> = listOf(ExecutionStrategy.NATIVE, ExecutionStrategy.LOCAL),
    val limitations: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val reasonIfUnavailable: String? = null
)

class CapabilityDiscoveryEngine(
    private val realityRegistry: CapabilityRealityRegistry,
    private val modelRegistry: ModelProviderRegistry
) {

    fun inspectCapabilitiesForRequest(userRequest: String): CapabilityPlan {
        val reqLower = userRequest.lowercase()
        val required = mutableListOf<String>()
        val available = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        val missingDeps = mutableListOf<String>()
        val executors = mutableListOf<String>()
        val limitationsList = mutableListOf<String>()
        val strategies = mutableListOf<ExecutionStrategy>()

        var action = "general_processing"
        var category = "INTELLIGENCE"
        var primaryAuthStatus = CapabilityAuthStatus.NOT_REQUIRED
        var primaryRealityState = CapabilityRealityState.NATIVE
        var confidence = 1.0
        var unavailableReason: String? = null

        // 1. Check for Device Control / App / WhatsApp / Messaging Intent
        if (reqLower.contains("whatsapp") || reqLower.contains("open ") || reqLower.contains("app") ||
            reqLower.contains("screen") || reqLower.contains("tap") || reqLower.contains("sms") || reqLower.contains("device")
        ) {
            action = if (reqLower.contains("whatsapp")) "send_whatsapp" else "device_automation"
            category = "AUTOMATION"
            required.add("device_control")

            if (reqLower.contains("whatsapp")) {
                required.add("whatsapp")
                required.add("open_app")
            }

            val reality = realityRegistry.getCapabilityReality("device_control")
            executors.add(reality.provider)
            limitationsList.addAll(reality.limitations)

            if (reality.realityState == CapabilityRealityState.NATIVE || reality.realityState == CapabilityRealityState.LIVE_CONNECTED) {
                available.add("device_control")
                strategies.add(ExecutionStrategy.NATIVE)
            } else {
                unavailable.add("device_control")
                primaryRealityState = reality.realityState
                unavailableReason = "Device control accessibility or service inactive"
            }
        }

        // 2. Check for File / Code / Workspace Storage Intent
        if (reqLower.contains("file") || reqLower.contains("code") || reqLower.contains("read file") ||
            reqLower.contains("write file") || reqLower.contains("workspace") || reqLower.contains("project")
        ) {
            action = "workspace_file_operation"
            category = "STORAGE"
            required.add("FILES")

            val reality = realityRegistry.getCapabilityReality("FILES")
            executors.add(reality.provider)
            limitationsList.addAll(reality.limitations)

            if (reality.realityState == CapabilityRealityState.NATIVE) {
                available.add("FILES")
                strategies.add(ExecutionStrategy.NATIVE)
            } else {
                unavailable.add("FILES")
                primaryRealityState = reality.realityState
            }
        }

        // 3. Check for Terminal / Command / Runtime Execution Intent
        if (reqLower.contains("run") || reqLower.contains("terminal") || reqLower.contains("execute") ||
            reqLower.contains("script") || reqLower.contains("python") || reqLower.contains("bash") || reqLower.contains("cmd")
        ) {
            action = "command_execution"
            category = "EXECUTION"
            required.add("TERMINAL")

            val reality = realityRegistry.getCapabilityReality("TERMINAL")
            executors.add(reality.provider)
            limitationsList.addAll(reality.limitations)

            if (reality.realityState == CapabilityRealityState.NATIVE) {
                available.add("TERMINAL")
                strategies.add(ExecutionStrategy.NATIVE)
                strategies.add(ExecutionStrategy.LOCAL)
            } else {
                unavailable.add("TERMINAL")
                primaryRealityState = reality.realityState
            }

            if (reqLower.contains("python")) {
                required.add("PYTHON_RUNTIME")
                val pyReality = realityRegistry.getCapabilityReality("PYTHON_RUNTIME")
                if (pyReality.realityState == CapabilityRealityState.UNAVAILABLE || pyReality.realityState == CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED) {
                    unavailable.add("PYTHON_RUNTIME")
                    missingDeps.add("Python3 binary on Android system path")
                    unavailableReason = "Python runtime is not currently available in the native Wasti environment."
                    confidence = 0.5
                }
            }
        }

        // 4. Check for Email / Gmail Intent
        if (reqLower.contains("gmail") || reqLower.contains("email") || reqLower.contains("send email")) {
            action = "send_email"
            category = "COMMUNICATION"
            required.add("GMAIL")

            val reality = realityRegistry.getCapabilityReality("GMAIL")
            executors.add(reality.provider)
            primaryAuthStatus = reality.authenticationStatus
            limitationsList.addAll(reality.limitations)

            if (reality.liveConnectionStatus == LiveConnectionStatus.VERIFIED) {
                available.add("GMAIL")
                strategies.add(ExecutionStrategy.EXTERNAL_API)
            } else {
                unavailable.add("GMAIL")
                missingDeps.add("OAuth Authentication for Gmail")
                primaryRealityState = reality.realityState
                unavailableReason = "OAuth credentials required for Gmail execution"
            }
        }

        // 5. Default AI Model fallback if no explicit capability match
        if (required.isEmpty()) {
            required.add("AI_MODEL")
            action = "generate_response"
            category = "INTELLIGENCE"

            val reality = realityRegistry.getCapabilityReality("GEMINI_AI")
            executors.add(reality.provider)
            primaryAuthStatus = reality.authenticationStatus
            primaryRealityState = reality.realityState

            if (reality.realityState == CapabilityRealityState.LIVE_CONNECTED || reality.realityState == CapabilityRealityState.NATIVE) {
                available.add("GEMINI_AI")
                strategies.add(ExecutionStrategy.EXTERNAL_API)
            } else {
                unavailable.add("GEMINI_AI")
                missingDeps.add("GEMINI_API_KEY")
                strategies.add(ExecutionStrategy.EXTERNAL_API)
            }
        }

        if (strategies.isEmpty()) {
            strategies.add(ExecutionStrategy.OPEN_SOURCE_DYNAMIC)
            strategies.add(ExecutionStrategy.HUMAN_ASSISTANCE)
        }

        val preferredPath = if (unavailable.isEmpty()) "NATIVE_WORKSPACE" else "EXTERNAL_PROVIDER_FALLBACK"
        val fallbacks = listOf("GEMINI_REST_API", "LOCAL_SANDBOX")

        return CapabilityPlan(
            requiredCapabilities = required,
            availableCapabilities = available,
            unavailableCapabilities = unavailable,
            preferredExecutionPath = preferredPath,
            fallbackPaths = fallbacks,
            missingDependencies = missingDeps,
            requiredAction = action,
            capabilityCategory = category,
            availableExecutors = executors.distinct(),
            realityState = if (unavailable.isEmpty()) primaryRealityState else CapabilityRealityState.UNAVAILABLE,
            authenticationRequirement = primaryAuthStatus,
            authorizationRequirement = "STANDARD_USER_PERMITTED",
            executionStrategies = strategies.distinct(),
            limitations = limitationsList.distinct(),
            confidence = confidence,
            reasonIfUnavailable = unavailableReason
        )
    }
}
