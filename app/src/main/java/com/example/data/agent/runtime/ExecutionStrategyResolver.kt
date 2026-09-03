package com.example.data.agent.runtime

enum class ExecutionStrategy {
    NATIVE,
    LOCAL,
    INSTALLED_TOOL,
    OPEN_SOURCE_DYNAMIC,
    REMOTE_SANDBOX,
    EXTERNAL_API,
    HUMAN_ASSISTANCE
}

data class ExecutionStrategyDecision(
    val strategy: ExecutionStrategy,
    val reasoning: String,
    val selectedCapability: String,
    val estimatedTimeMs: Long,
    val executorProvider: String = "WastiRuntime"
)

class ExecutionStrategyResolver(
    private val realityRegistry: CapabilityRealityRegistry
) {

    /**
     * Resolves the optimal execution strategy based on strict priority hierarchy:
     * 1. NATIVE
     * 2. LOCAL
     * 3. INSTALLED_TOOL
     * 4. OPEN_SOURCE_DYNAMIC
     * 5. REMOTE_SANDBOX
     * 6. EXTERNAL_API
     * 7. HUMAN_ASSISTANCE
     */
    fun resolveStrategy(requiredCapability: String, isUrgent: Boolean = false): ExecutionStrategyDecision {
        val reality = realityRegistry.getCapabilityReality(requiredCapability)

        // 1. Priority 1: NATIVE
        if (reality.realityState == CapabilityRealityState.NATIVE ||
            (reality.realityState == CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED &&
             (reality.provider == "WorkspaceManager" || reality.provider == "LocalAndroidProvider" || reality.provider == "WastiNativeExecutionProvider" || reality.provider == "WastiProjectManager"))
        ) {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.NATIVE,
                reasoning = "Capability [$requiredCapability] is natively available in Wasti OS workspace runtime.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 50,
                executorProvider = reality.provider
            )
        }

        // 2. Priority 2: LOCAL (e.g. Local Android Provider / Process / Workspace)
        if (reality.realityState == CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED &&
            (reality.category == "EXECUTION" || reality.category == "STORAGE" || reality.category == "AUTOMATION")
        ) {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.LOCAL,
                reasoning = "Capability [$requiredCapability] resolved to verified local runtime executor.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 200,
                executorProvider = reality.provider
            )
        }

        // 3. Priority 3: INSTALLED_TOOL (Registered tools in ToolRegistry)
        if (reality.supportedOperations.isNotEmpty() && reality.category == "TOOL") {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.INSTALLED_TOOL,
                reasoning = "Capability [$requiredCapability] dispatched to registered system tool.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 300,
                executorProvider = reality.provider
            )
        }

        // 4. Priority 4: OPEN_SOURCE_DYNAMIC
        if (reality.realityState == CapabilityRealityState.CONTRACT_ONLY && !isUrgent) {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.OPEN_SOURCE_DYNAMIC,
                reasoning = "Capability [$requiredCapability] requires dynamic code synthesis or open-source adaptation.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 3000,
                executorProvider = "DynamicPluginAdapter"
            )
        }

        // 5. Priority 5: REMOTE_SANDBOX
        if (requiredCapability == "REMOTE_SANDBOX" || reality.provider.contains("RemoteSandbox")) {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.REMOTE_SANDBOX,
                reasoning = "Capability [$requiredCapability] executing in isolated remote sandbox environment.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 1500,
                executorProvider = "RemoteSandboxCodeExecutionProvider"
            )
        }

        // 6. Priority 6: EXTERNAL_API
        if (reality.realityState == CapabilityRealityState.LIVE_CONNECTED ||
            (isUrgent && reality.category == "AI_MODEL" && reality.realityState != CapabilityRealityState.AUTHENTICATION_REQUIRED)
        ) {
            return ExecutionStrategyDecision(
                strategy = ExecutionStrategy.EXTERNAL_API,
                reasoning = "Capability [$requiredCapability] dispatched to live connected external API.",
                selectedCapability = requiredCapability,
                estimatedTimeMs = 800,
                executorProvider = reality.provider
            )
        }

        // 7. Priority 7: HUMAN_ASSISTANCE (Authentication, policy block, or unavailable)
        val reason = when (reality.realityState) {
            CapabilityRealityState.AUTHENTICATION_REQUIRED -> "Authentication required for capability [$requiredCapability]"
            CapabilityRealityState.UNAVAILABLE -> "Capability [$requiredCapability] is unavailable in current runtime"
            else -> "Capability [$requiredCapability] requires user intervention or authorization"
        }

        return ExecutionStrategyDecision(
            strategy = ExecutionStrategy.HUMAN_ASSISTANCE,
            reasoning = reason,
            selectedCapability = requiredCapability,
            estimatedTimeMs = 0,
            executorProvider = "HumanInTheLoop"
        )
    }
}
