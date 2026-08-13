package com.example.data.agent.runtime

enum class CapabilityGapType {
    MISSING_TOOL,
    MISSING_LIBRARY,
    MISSING_EXECUTABLE,
    MISSING_PROVIDER,
    MISSING_AUTHENTICATION,
    MISSING_DEVICE_PERMISSION,
    MISSING_NETWORK,
    MISSING_KNOWLEDGE,
    MISSING_RUNTIME,
    MISSING_INTEGRATION
}

data class CapabilityGapReport(
    val requestedCapability: String,
    val gapType: CapabilityGapType,
    val explanation: String,
    val possibleNativeSolution: String?,
    val possibleOpenSourceSolution: String?,
    val possibleExternalProvider: String?,
    val estimatedComplexity: String,
    val dependencyRequirements: List<String>
)

class CapabilityGapAnalyzer(
    private val realityRegistry: CapabilityRealityRegistry
) {

    fun analyzeGap(requestedCapability: String): CapabilityGapReport {
        val reality = realityRegistry.getCapabilityReality(requestedCapability)

        if (reality.realityState == CapabilityRealityState.UNAVAILABLE) {
            return CapabilityGapReport(
                requestedCapability = requestedCapability,
                gapType = CapabilityGapType.MISSING_INTEGRATION,
                explanation = "Capability $requestedCapability is not registered or implemented in current runtime",
                possibleNativeSolution = "Build custom Wasti workspace tool or module",
                possibleOpenSourceSolution = "Import open-source Kotlin/Android library",
                possibleExternalProvider = "Integrate external REST API via ExternalIntegrationAdapter",
                estimatedComplexity = "MEDIUM",
                dependencyRequirements = listOf("Network access", "Credential config")
            )
        }

        if (reality.realityState == CapabilityRealityState.AUTHENTICATION_REQUIRED) {
            return CapabilityGapReport(
                requestedCapability = requestedCapability,
                gapType = CapabilityGapType.MISSING_AUTHENTICATION,
                explanation = "Capability $requestedCapability requires user OAuth credential or API key",
                possibleNativeSolution = null,
                possibleOpenSourceSolution = null,
                possibleExternalProvider = reality.provider,
                estimatedComplexity = "LOW",
                dependencyRequirements = listOf("User Auth / OAuth Token")
            )
        }

        return CapabilityGapReport(
            requestedCapability = requestedCapability,
            gapType = CapabilityGapType.MISSING_TOOL,
            explanation = "Capability exists but state is ${reality.realityState}",
            possibleNativeSolution = "Update reality state upon verification",
            possibleOpenSourceSolution = null,
            possibleExternalProvider = null,
            estimatedComplexity = "LOW",
            dependencyRequirements = emptyList()
        )
    }
}
