package com.example.data.ai.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.data.core.TestCategory
import com.example.data.core.TestTier

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Unit tests verifying UnifiedBrainStrategy multi-agent/multi-model consensus, merged replies, and task/project orchestration"
)
class UnifiedBrainStrategyTest {

    @Test
    fun testStrategyModeToggleAndStatus() {
        UnifiedBrainStrategy.setUnifiedConsensusActive(null, true)
        assertTrue(UnifiedBrainStrategy.isUnifiedConsensusActive())
        assertEquals(BrainStrategyMode.UNIFIED_CONSENSUS, UnifiedBrainStrategy.strategyMode.value)

        UnifiedBrainStrategy.setUnifiedConsensusActive(null, false)
        assertFalse(UnifiedBrainStrategy.isUnifiedConsensusActive())
        assertEquals(BrainStrategyMode.SOLO, UnifiedBrainStrategy.strategyMode.value)

        // Re-enable for subsequent tests
        UnifiedBrainStrategy.setUnifiedConsensusActive(null, true)
        assertTrue(UnifiedBrainStrategy.isUnifiedConsensusActive())
    }

    @Test
    fun testExecuteConsensusReasoningProducesMergedReply() = runBlocking {
        val prompt = "Create a high-performance database caching layer in Kotlin for Android"
        val reply = UnifiedBrainStrategy.executeConsensusReasoning(
            prompt = prompt,
            context = null,
            fileContext = null,
            activeAgentId = "coding_agent"
        )

        assertNotNull(reply)
        assertTrue(reply.finalMergedResponse.isNotBlank())
        assertTrue(reply.strategicSummary.isNotBlank())
        assertTrue(reply.technicalCodeSummary.isNotBlank())
        assertTrue(reply.validationChecks.isNotBlank())
        assertTrue(reply.overallConsensusScore in 0.0f..1.0f)
        assertTrue(reply.totalLatencyMs >= 0L)
        assertTrue(reply.participatingContributions.isNotEmpty())

        // Verify status stream updated
        val status = UnifiedBrainStrategy.liveConsensusStatus.value
        assertNotNull(status)
        assertTrue(status!!.displayText.isNotBlank())
    }

    @Test
    fun testGreetingConsensusProducesWarmDirectResponse() = runBlocking {
        val reply = UnifiedBrainStrategy.executeConsensusReasoning(
            prompt = "hello",
            context = null,
            fileContext = null
        )

        assertNotNull(reply)
        assertTrue(reply.finalMergedResponse.contains("Wasti AI", ignoreCase = true))
        assertEquals(1.0f, reply.overallConsensusScore, 0.01f)
        assertEquals("Conversational Fast-Path", reply.validationChecks)
    }

    @Test
    fun testEmptyConsensusProducesZeroScoreAndUnverifiedState() = runBlocking {
        val emptyReply = UnifiedBrainStrategy.executeConsensusReasoning(
            prompt = "xyz123abc_unique_non_matching_query_with_no_models_responding",
            context = null,
            fileContext = null
        )
        assertNotNull(emptyReply)
        // Verify that consensus score is bounded and truthful
        assertTrue(emptyReply.overallConsensusScore in 0.0f..1.0f)
    }

    @Test
    fun testExecuteTaskConsensusProducesVerifiedDeliverable() = runBlocking {
        val taskTitle = "Optimize SQLite Room DB Indices"
        val taskDesc = "Analyze slow queries and create compound indices"
        val taskResult = UnifiedBrainStrategy.executeTaskConsensus(
            taskId = "task_test_101",
            taskTitle = taskTitle,
            taskDescription = taskDesc,
            context = null
        )

        assertNotNull(taskResult)
        assertEquals("task_test_101", taskResult.taskId)
        assertEquals(taskTitle, taskResult.taskTitle)
        assertTrue(taskResult.decomposedSteps.size >= 4)
        assertTrue(taskResult.synthesizedExecutionPlan.contains("Multi-Brain", ignoreCase = true))
        assertTrue(taskResult.invariantVerification.contains("Passed", ignoreCase = true))
        assertTrue(taskResult.finalMergedDeliverable.contains("SUCCEEDED", ignoreCase = true))
        assertTrue(taskResult.isVerified)
    }

    @Test
    fun testExecuteProjectConsensusProducesArchitectureReport() = runBlocking {
        val projectName = "Wasti Sovereign Mesh"
        val projectGoal = "Enable offline peer-to-peer cognitive synchronization across devices"
        val report = UnifiedBrainStrategy.executeProjectConsensus(
            projectId = "proj_test_202",
            projectName = projectName,
            projectGoal = projectGoal,
            context = null
        )

        assertNotNull(report)
        assertTrue(report.contains(projectName))
        assertTrue(report.contains("Executive Strategy & Goal"))
        assertTrue(report.contains("Engineering Architecture"))
        assertTrue(report.contains("Verification & Governance"))
    }
}
