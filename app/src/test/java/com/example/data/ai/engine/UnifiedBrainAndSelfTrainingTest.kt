package com.example.data.ai.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.ActionVerificationStatus
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.EvidenceSource
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.VerificationResult
import com.example.data.agent.runtime.VerifiedExecutionEvidence
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
import com.example.data.core.TestCategory
import com.example.data.core.TestTier

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Robolectric host simulation of neural consensus fallback and distillation logic"
)
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
        val validArtifact = checkNotNull(artifact)
        assertEquals("Wasti Qwen Local (Alibaba Family)", validArtifact.sourceModel)
        assertEquals(ModelSpecialization.DEEP_CODING, validArtifact.targetSpecialization)
        assertTrue(SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize() >= initialSize + 1)

        // Test skill matching for future prompts
        val matches = SelfTrainingKnowledgeDistillationEngine.findMatchingSkills("background worker for data sync")
        assertTrue(matches.isNotEmpty())
        assertEquals(validArtifact.artifactId, matches.first().artifactId)
    }

    @Test
    fun testSelfTrainingDistillationRejectsMockEvidence() = runBlocking {
        val sizeBefore = SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize()

        val mockArtifact = SelfTrainingKnowledgeDistillationEngine.recordVerifiedInteractionAndDistill(
            taskPrompt = "deploy backend container",
            successfulExecutionEvidence = "mock_evidence of container deploy",
            winningModelId = "wasti-qwen"
        )
        assertNull(mockArtifact)

        val syntheticArtifact = SelfTrainingKnowledgeDistillationEngine.recordVerifiedInteractionAndDistill(
            taskPrompt = "deploy backend container",
            successfulExecutionEvidence = "synthetic test execution",
            winningModelId = "wasti-qwen"
        )
        assertNull(syntheticArtifact)

        val failedVerification = VerificationResult(
            taskId = "task_01",
            actionId = "act_01",
            capabilityId = "docker_deploy",
            status = ActionVerificationStatus.FAILED,
            evidence = "Container exited with code 1"
        )
        val failedArtifact = SelfTrainingKnowledgeDistillationEngine.recordVerifiedInteractionAndDistill(
            taskPrompt = "deploy backend container",
            verificationResult = failedVerification,
            winningModelId = "wasti-qwen"
        )
        assertNull(failedArtifact)

        assertEquals(sizeBefore, SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize())
    }

    @Test
    fun testSelfTrainingDistillationWithCanonicalVerificationEvidence() = runBlocking {
        val sizeBefore = SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize()

        val evidence = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.FILESYSTEM,
            subject = "service_worker_config",
            verifiedState = "CONFIG_APPLIED",
            confidence = 0.95
        )

        val artifact = SelfTrainingKnowledgeDistillationEngine.recordVerifiedInteractionAndDistill(
            taskPrompt = "configure service worker background sync",
            verifiedEvidence = evidence,
            winningModelId = "wasti-qwen"
        )

        assertNotNull(artifact)
        assertEquals(sizeBefore + 1, SelfTrainingKnowledgeDistillationEngine.getKnowledgeBaseSize())
    }

    @Test
    fun testWastiLocalBrainProviderDomainSpecializedInference() = runBlocking {
        val qwenDescriptor = OpenSourceModelCatalog.getModelById("wasti-qwen")!!
        val provider = WastiLocalBrainProvider(qwenDescriptor)

        // Truthful availability: false unless neural weights & native runtime bridge are active
        assertFalse(provider.isAvailable())
        assertTrue(provider.isHeuristicFallbackAvailable())
        assertEquals(
            com.example.data.ai.provider.LocalBrainRuntimeState.HEURISTIC_NON_NEURAL_FALLBACK,
            provider.getRuntimeState()
        )

        val response = provider.generate(ProviderRequest(prompt = "write a function to sort integers"))
        assertNotNull(response)
        assertFalse(response.isError)
        assertTrue(response.modelUsed.contains("HEURISTIC_NON_NEURAL"))
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
        assertEquals(BrainConsensusType.HEURISTIC_DOMAIN_SYNTHESIS, consensus.consensusType)
        assertEquals(0, consensus.neuralNodeCount)
        assertEquals(3, consensus.heuristicNodeCount)
        assertTrue(consensus.finalSynthesis.contains("Wasti AI OS Unified Heuristic Domain Synthesis"))
        assertTrue(consensus.finalSynthesis.contains("Synthesis Participating Nodes"))
    }

    @Test
    fun testUnifiedBrainRequiresNeuralConsensusFailsClosedWhenWeightsMissing() = runBlocking {
        val consensus = UnifiedBrain.executeCooperativeReasoning(
            prompt = "Verify database integrity and sync cloud backups",
            participatingModelIds = listOf("wasti-llama", "wasti-qwen"),
            requireNeuralConsensus = true
        )

        assertNotNull(consensus)
        assertEquals(BrainConsensusType.UNAVAILABLE, consensus.consensusType)
        assertEquals(0, consensus.neuralNodeCount)
        assertTrue(consensus.finalSynthesis.contains("Neural consensus unavailable"))
    }

    @Test
    fun testOfflineProviderCooperativeReasoningFallback() = runBlocking {
        val offline = OfflineProvider()
        assertTrue(offline.isAvailable())
        assertFalse(offline.isNeuralExecutionActive)
        assertTrue(offline.isHeuristicFallbackActive)

        val res = offline.generate(ProviderRequest(prompt = "Plan autonomous workflow for local files"))
        assertNotNull(res)
        assertFalse(res.isError)
        assertTrue(res.content.isNotBlank())
        assertTrue(res.modelUsed.contains("NON_NEURAL_FALLBACK"))
        assertTrue(res.content.contains("Synthesis") || res.content.contains("Consensus"))
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
