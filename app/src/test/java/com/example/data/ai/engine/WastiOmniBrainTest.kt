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
    description = "Unit tests verifying WastiOmniBrain multi-perspective reasoning, consensus synthesis, and sovereign learning"
)
class WastiOmniBrainTest {

    @Test
    fun testReasonAndSynthesizeProducesComprehensiveSynthesis() = runBlocking {
        val testPrompt = "Build an offline task scheduler for Android with zero latency"
        val synthesis = WastiOmniBrain.reasonAndSynthesize(
            prompt = testPrompt,
            context = null,
            appId = "test_brain",
            preferredLocalModels = listOf("wasti-llama", "wasti-qwen", "wasti-deepseek")
        )

        assertNotNull(synthesis)
        assertTrue(synthesis.masterResponse.isNotBlank())
        assertTrue(synthesis.executiveStrategy.isNotBlank())
        assertTrue(synthesis.technicalExecution.isNotBlank())
        assertTrue(synthesis.logicalValidation.isNotBlank())
        assertTrue(synthesis.operationalPlan.isNotBlank())
        assertTrue(synthesis.overallConfidence in 0.0f..1.0f)
        assertTrue(synthesis.totalLatencyMs >= 0L)

        // Check OmniBrain StateFlows
        val state = WastiOmniBrain.omniBrainState.value
        assertNotNull(state)
        assertEquals(synthesis.masterResponse, state?.masterResponse)

        val stream = WastiOmniBrain.activeThoughtStream.value
        assertTrue(stream.isNotBlank())
    }

    @Test
    fun testEmptyModelPerspectivesFallbackGracefully() = runBlocking {
        val synthesis = WastiOmniBrain.reasonAndSynthesize(
            prompt = "Emergency offline check",
            context = null,
            appId = "test_empty",
            preferredLocalModels = emptyList()
        )

        assertNotNull(synthesis)
        assertTrue(synthesis.masterResponse.isNotBlank())
        assertTrue(synthesis.isFullyLocalSovereign)
    }

    @Test
    fun testPerspectivesContainDistinctRolesAndIdentities() = runBlocking {
        val synthesis = WastiOmniBrain.reasonAndSynthesize(
            prompt = "Design a safe database migration protocol",
            preferredLocalModels = listOf("wasti-llama", "wasti-qwen", "wasti-gemma")
        )

        val perspectives = synthesis.participatingPerspectives
        assertTrue(perspectives.isNotEmpty())
        for (p in perspectives) {
            assertTrue(p.modelId.isNotBlank())
            assertTrue(p.displayName.isNotBlank())
            assertTrue(p.role.isNotBlank())
            assertTrue(p.confidence in 0.0f..1.0f)
        }
    }
}
