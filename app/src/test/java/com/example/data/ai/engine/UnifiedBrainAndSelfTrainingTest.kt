package com.example.data.ai.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.WastiCapabilityRegistry
import com.example.data.ai.model.ModelSpecialization
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.provider.OfflineProvider
import com.example.data.ai.provider.WastiLocalBrainProvider
import com.example.data.cloud.ComputeTaskRequest
import com.example.data.cloud.ComputeTaskType
import com.example.data.cloud.FirebaseComputeOffloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UnifiedBrainAndSelfTrainingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSelfTrainingDistillationLoopAndSkillRecall() = runBlocking {
        val initialSize = SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize()

        val artifact = SelfTrainingKnowledgeDistillationEngine.recordVerifiedInteractionAndDistill(
            taskPrompt = "build an android background worker for data sync",
            successfulExecutionEvidence = "WorkManager worker verified with periodic constraint",
            winningModelId = "wasti-qwen"
        )

        assertNotNull(artifact)
        assertEquals("Wasti Qwen Local (Alibaba Family)", artifact.sourceModel)
        assertEquals(ModelSpecialization.DEEP_CODING, artifact.targetSpecialization)
        assertTrue(SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize() >= initialSize + 1)

        // Test skill matching for future prompts
        val matches = SelfTrainingKnowledgeDistillationEngine.findMatchingSkills("background worker for data sync")
        assertTrue(matches.isNotEmpty())
        assertEquals(artifact.artifactId, matches.first().artifactId)
    }

    @Test
    fun testWastiLocalBrainProviderDomainSpecializedInference() = runBlocking {
        val qwenDescriptor = OpenSourceModelCatalog.getModelById("wasti-qwen")!!
        val provider = WastiLocalBrainProvider(qwenDescriptor)

        assertTrue(provider.isAvailable())
        val response = provider.generate(ProviderRequest(prompt = "write a function to sort integers"))
        assertNotNull(response)
        assertFalse(response.isError)
        assertTrue(response.content.contains("Wasti Qwen Local"))
        assertTrue(response.content.contains("CODING"))
    }

    @Test
    fun testUnifiedBrainCooperativeConsensusAndUltimateSynthesizer() = runBlocking {
        val consensus = UnifiedBrain.executeCooperativeReasoning(
            prompt = "Verify database integrity and sync cloud backups",
            participatingModelIds = listOf("wasti-llama", "wasti-qwen", "wasti-deepseek")
        )

        assertNotNull(consensus)
        assertTrue(consensus.isFullyLocal)
        assertEquals(3, consensus.participatingModels.size)
        assertTrue(consensus.averageInferenceConfidence > 0.5f)
        assertTrue(consensus.finalSynthesis.contains("Wasti AI OS Unified Multi-Brain Consensus Masterpiece"))
        assertTrue(consensus.finalSynthesis.contains("Consensus Participating Nodes"))
    }

    @Test
    fun testOfflineProviderCooperativeReasoningFallback() = runBlocking {
        val offline = OfflineProvider()
        assertTrue(offline.isAvailable())

        val res = offline.generate(ProviderRequest(prompt = "Plan autonomous workflow for local files"))
        assertNotNull(res)
        assertFalse(res.isError)
        assertTrue(res.content.isNotBlank())
        assertTrue(res.content.contains("Consensus") || res.content.contains("Masterpiece"))
    }

    @Test
    fun testFirebaseComputeOffloaderShouldOffloadCriteria() {
        val heavyTask = ComputeTaskRequest(
            type = ComputeTaskType.MULTI_MODEL_CONSENSUS,
            payload = mapOf("prompt" to "Deep multi-agent consensus across 50 nodes"),
            requiredRamMb = 2048
        )
        val shouldOffloadHeavy = FirebaseComputeOffloader.shouldOffload(context, heavyTask)
        assertTrue(shouldOffloadHeavy)

        val batchTask = ComputeTaskRequest(
            type = ComputeTaskType.BATCH_EMBEDDINGS,
            payload = mapOf("texts" to listOf("doc1", "doc2", "doc3")),
            requiredRamMb = 1024
        )
        assertTrue(FirebaseComputeOffloader.shouldOffload(context, batchTask))
    }

    @Test
    fun testWastiCapabilityRegistryOperationalDefaults() {
        val registry = WastiCapabilityRegistry()
        val supported = registry.getSupportedCapabilities()

        assertTrue(supported.contains("FILES"))
        assertTrue(supported.contains("CODING"))
        assertTrue(supported.contains("TERMINAL"))
        assertTrue(supported.contains("AUTOMATION"))
        assertTrue(supported.contains("ANDROID_CONTROL"))
        assertTrue(supported.contains("RESEARCH"))
        assertTrue(supported.contains("WEB"))
        assertTrue(supported.contains("CLOUD"))

        // All core operational capabilities must be enabled by default
        assertTrue(registry.isCapabilityEnabled("FILES"))
        assertTrue(registry.isCapabilityEnabled("CODING"))
        assertTrue(registry.isCapabilityEnabled("TERMINAL"))
        assertTrue(registry.isCapabilityEnabled("AUTOMATION"))
        assertTrue(registry.isCapabilityEnabled("ANDROID_CONTROL"))
    }
}
