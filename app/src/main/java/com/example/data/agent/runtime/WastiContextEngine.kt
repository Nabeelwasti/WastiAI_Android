package com.example.data.agent.runtime

data class ContextSnapshot(
    val goal: String,
    val background: String,
    val constraints: List<String>,
    val relevantHistory: List<String>,
    val currentState: String,
    val availableCapabilities: List<String>,
    val previousAttempts: List<String>,
    val knownFailures: List<String>,
    val relevantFiles: List<String>,
    val userPreferences: Map<String, String>,
    val successCriteria: List<String>
)

class WastiContextEngine(
    private val realityRegistry: CapabilityRealityRegistry
) {

    fun buildContextSnapshot(
        userRequest: String,
        conversationHistory: List<String> = emptyList(),
        activeFiles: List<String> = emptyList()
    ): ContextSnapshot {
        val availableCaps = realityRegistry.getSystemRealityReport()
            .filter { it.realityState == CapabilityRealityState.NATIVE || it.realityState == CapabilityRealityState.LIVE_CONNECTED }
            .map { it.capabilityId }

        val constraints = listOf(
            "Respect security boundaries; do not reveal secrets",
            "Prefer Wasti-native execution over unverified external APIs",
            "Never fabricate execution results"
        )

        return ContextSnapshot(
            goal = userRequest,
            background = "Wasti AI OS active runtime context",
            constraints = constraints,
            relevantHistory = conversationHistory.takeLast(5),
            currentState = "READY_FOR_EXECUTION",
            availableCapabilities = availableCaps,
            previousAttempts = emptyList(),
            knownFailures = emptyList(),
            relevantFiles = activeFiles,
            userPreferences = mapOf("preferred_provider" to "GEMINI"),
            successCriteria = listOf("Request processed without security violations", "Result verified")
        )
    }
}
