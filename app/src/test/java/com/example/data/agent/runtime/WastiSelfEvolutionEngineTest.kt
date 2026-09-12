package com.example.data.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WastiSelfEvolutionEngineTest {
    @Test
    fun plan_researches_combines_adapts_invents_and_verifies_without_claiming_success() {
        val plan = WastiSelfEvolutionEngine.plan(
            WastiSelfEvolutionEngine.Problem(
                taskId = "device-control-1",
                objective = "complete the requested device action",
                failureOrGap = "accessibility is unavailable",
                currentStrategies = listOf(
                    WastiSelfEvolutionEngine.Strategy("intent", "native intent", "KNOWN"),
                    WastiSelfEvolutionEngine.Strategy("web", "web workflow", "KNOWN")
                ),
                availableCapabilities = setOf("intent", "web", "native_runtime"),
                observations = listOf("Accessibility service is disabled")
            )
        )

        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.RESEARCH))
        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.COMBINE))
        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.ADAPT))
        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.INVENT))
        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.PROBE))
        assertTrue(plan.moves.contains(WastiSelfEvolutionEngine.Move.VERIFY))
        assertTrue(plan.candidates.any { it.source == "COMBINED_EXISTING_STRATEGIES" })
        assertTrue(plan.candidates.any { it.source == "COMPOSED_NEW_ROUTE" })
    }

    @Test
    fun success_rate_uses_only_recorded_verified_outcomes() {
        WastiSelfEvolutionEngine.clear("history-test")
        WastiSelfEvolutionEngine.recordOutcome("history-test", true)
        WastiSelfEvolutionEngine.recordOutcome("history-test", false)
        WastiSelfEvolutionEngine.recordOutcome("history-test", true)

        assertEquals(2.0 / 3.0, WastiSelfEvolutionEngine.successRate("history-test"), 0.0001)
        WastiSelfEvolutionEngine.clear("history-test")
    }
}
