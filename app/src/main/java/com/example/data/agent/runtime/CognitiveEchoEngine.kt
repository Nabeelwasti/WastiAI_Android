package com.example.data.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class DecisionVector(
    val riskTolerance: Float = 0.35f,        // 0.0 = Conservative, 1.0 = Aggressive
    val autonomyDelegation: Float = 0.85f,   // How much Wasti can execute before human sign-off
    val codeArchitectureStyle: String = "Clean Architecture / Verification-First",
    val communicationTone: String = "Precise, Jargon-Free, Truthful",
    val businessPricingConfidence: Float = 0.90f
)

data class ShadowPilotProposal(
    val proposalId: String = UUID.randomUUID().toString(),
    val incomingScenario: String,
    val recommendedOption: String,
    val alternativeOptionA: String,
    val alternativeOptionB: String,
    val alignmentConfidencePercent: Int = 94,
    val philosophicalRationale: String,
    val requiresHumanSignOff: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

object CognitiveEchoEngine {

    private val _decisionVector = MutableStateFlow(DecisionVector())
    val decisionVector: StateFlow<DecisionVector> = _decisionVector.asStateFlow()

    private val _shadowProposals = MutableStateFlow<List<ShadowPilotProposal>>(emptyList())
    val shadowProposals: StateFlow<List<ShadowPilotProposal>> = _shadowProposals.asStateFlow()

    fun updateDecisionVector(
        risk: Float? = null,
        delegation: Float? = null,
        style: String? = null,
        tone: String? = null
    ) {
        val current = _decisionVector.value
        _decisionVector.value = current.copy(
            riskTolerance = risk ?: current.riskTolerance,
            autonomyDelegation = delegation ?: current.autonomyDelegation,
            codeArchitectureStyle = style ?: current.codeArchitectureStyle,
            communicationTone = tone ?: current.communicationTone
        )
    }

    fun evaluateShadowPilotDecision(scenario: String): ShadowPilotProposal {
        val vector = _decisionVector.value
        val proposal = when {
            scenario.contains("pricing", ignoreCase = true) || scenario.contains("invoice", ignoreCase = true) -> {
                ShadowPilotProposal(
                    incomingScenario = scenario,
                    recommendedOption = "Value-based fixed milestone pricing with 50% upfront deposit and clear deliverables.",
                    alternativeOptionA = "Hourly rate billing at $95/hr with time tracking.",
                    alternativeOptionB = "Low introductory flat fee to win the client.",
                    alignmentConfidencePercent = 96,
                    philosophicalRationale = "Aligned with high business pricing confidence ($90%) and milestone-driven accountability.",
                    requiresHumanSignOff = true
                )
            }
            scenario.contains("refactor", ignoreCase = true) || scenario.contains("architecture", ignoreCase = true) -> {
                ShadowPilotProposal(
                    incomingScenario = scenario,
                    recommendedOption = "Preserve existing interfaces; add non-breaking verification decorator and modular sub-packages.",
                    alternativeOptionA = "Complete ground-up rewrite of the module.",
                    alternativeOptionB = "Quick inline monkey-patch without modular isolation.",
                    alignmentConfidencePercent = 98,
                    philosophicalRationale = "Adheres strictly to the 10,000-Year Principle: Preserve interfaces before implementations.",
                    requiresHumanSignOff = false
                )
            }
            else -> {
                ShadowPilotProposal(
                    incomingScenario = scenario,
                    recommendedOption = "Execute through canonical UnifiedExecutionFabric, verify independently, and log to memory.",
                    alternativeOptionA = "Fast heuristic execution without formal proof.",
                    alternativeOptionB = "Defer to human pilot for manual intervention.",
                    alignmentConfidencePercent = 94,
                    philosophicalRationale = "Adheres to the Absolute Truth Doctrine: Truth > model confidence.",
                    requiresHumanSignOff = vector.riskTolerance < 0.2f
                )
            }
        }

        val list = _shadowProposals.value.toMutableList()
        list.add(0, proposal)
        _shadowProposals.value = list.take(15)
        return proposal
    }
}
