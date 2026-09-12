package com.example.data.agent.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveExecutionIntelligenceTest {
    @Test
    fun duration_is_diagnostic_not_termination() {
        val checkpoint = AdaptiveExecutionIntelligence.Checkpoint(
            taskId = "long-task",
            objective = "complete long running work",
            stage = "EXECUTING",
            health = AdaptiveExecutionIntelligence.Health.ACTIVE_PROGRESS,
            strategy = "NATIVE",
            provider = null,
            runtime = "WRE",
            actions = listOf("execute"),
            observations = listOf("process remains active"),
            errors = emptyList(),
            evidence = emptyList(),
            nextAction = "observe",
            resumePoint = "execute",
            startedAt = 0L,
            updatedAt = 30L * 60L * 1000L
        )

        val diagnosis = AdaptiveExecutionIntelligence.diagnoseDuration(
            checkpoint,
            now = checkpoint.startedAt + (30L * 60L * 1000L)
        )

        assertTrue(diagnosis.contains("duration is telemetry only"))
        assertTrue(checkpoint.health == AdaptiveExecutionIntelligence.Health.ACTIVE_PROGRESS)
    }

    @Test
    fun checkpoints_are_recoverable() {
        val checkpoint = AdaptiveExecutionIntelligence.Checkpoint(
            taskId = "recoverable",
            objective = "resume work",
            stage = "OBSERVING",
            health = AdaptiveExecutionIntelligence.Health.RECOVERING,
            strategy = "ALTERNATIVE_ROUTE",
            provider = "provider-b",
            runtime = "WRE",
            actions = listOf("attempt-a", "switch-provider"),
            observations = listOf("provider-a unavailable"),
            errors = listOf("connection failed"),
            evidence = listOf("provider-a failure observed"),
            nextAction = "continue with provider-b",
            resumePoint = "after-provider-a",
            startedAt = 1L
        )

        AdaptiveExecutionIntelligence.save(checkpoint)
        val recovered = AdaptiveExecutionIntelligence.current("recoverable")

        assertTrue(recovered != null)
        assertTrue(recovered!!.resumePoint == "after-provider-a")
        assertTrue(recovered.nextAction == "continue with provider-b")
    }
}
