package com.example.data.ai

import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.ExternalActionResultStatus
import com.example.data.agent.runtime.UniversalFabricIntegrationAdapter
import com.example.data.ai.provider.HuggingFaceProvider
import com.example.data.ai.provider.LocalLLMProvider
import com.example.data.api.HuggingFaceClient
import com.example.data.api.LocalLLMClient
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.HOST_SIMULATION,
    description = "Tests genuine open-source model client, Ollama/llama.cpp candidate probing, and universal fabric execution"
)
class OpenSourceModelRealIntegrationTest {

    @Test
    fun testLocalLLMClientCandidateEndpoints() {
        val candidates = LocalLLMClient.getCandidateEndpoints()
        assertNotNull(candidates)
        assertTrue(candidates.contains("http://127.0.0.1:11434"))
        assertTrue(candidates.contains("http://127.0.0.1:8080"))
    }

    @Test
    fun testLocalLLMProviderRegistrationAndDefaults() {
        val provider = LocalLLMProvider()
        assertEquals("local_llm", provider.id)
        assertTrue(provider.name.contains("Local Sovereign LLM"))
        assertTrue(provider.defaultModel.contains("llama3.2"))

        // Registered in AIManager
        val registered = AIManager.capabilityRegistry.getProvider("local_llm")
        assertNotNull(registered)
    }

    @Test
    fun testHuggingFaceClientRepositoryMapping() {
        assertEquals("meta-llama/Llama-3.2-3B-Instruct", HuggingFaceClient.mapToHuggingFaceRepo("wasti-llama"))
        assertEquals("Qwen/Qwen2.5-Coder-7B-Instruct", HuggingFaceClient.mapToHuggingFaceRepo("wasti-qwen"))
        assertEquals("deepseek-ai/DeepSeek-R1-Distill-Qwen-8B", HuggingFaceClient.mapToHuggingFaceRepo("wasti-deepseek"))
        assertEquals("mistralai/Mistral-7B-Instruct-v0.3", HuggingFaceClient.mapToHuggingFaceRepo("wasti-mistral"))
        assertEquals("google/gemma-2-2b-it", HuggingFaceClient.mapToHuggingFaceRepo("wasti-gemma"))
        assertEquals("HuggingFaceTB/SmolLM2-1.7B-Instruct", HuggingFaceClient.mapToHuggingFaceRepo("wasti-smollm"))
        assertEquals("ibm-granite/granite-3.0-8b-instruct", HuggingFaceClient.mapToHuggingFaceRepo("wasti-granite"))
    }

    @Test
    fun testHuggingFaceClientEmbeddingSupport() = runBlocking {
        val parsed = HuggingFaceClient.parseEmbeddingResponse("[0.1, 0.2, 0.3]")
        assertEquals(3, parsed.size)
        assertEquals(0.1f, parsed[0], 0.001f)

        val empty = HuggingFaceClient.parseEmbeddingResponse("")
        assertTrue(empty.isEmpty())

        val unconfigured = HuggingFaceClient.generateEmbedding("test query")
        assertTrue(unconfigured.isEmpty())
    }

    @Test
    fun testHuggingFaceProviderRegistrationAndDefaults() {
        val provider = HuggingFaceProvider()
        assertEquals("huggingface", provider.id)
        assertTrue(provider.name.contains("Hugging Face"))

        val registered = AIManager.capabilityRegistry.getProvider("huggingface")
        assertNotNull(registered)
    }

    @Test
    fun testUniversalFabricIntegrationAdapterRealExecution() {
        val adapter = UniversalFabricIntegrationAdapter()
        assertEquals("UNIVERSAL_FABRIC", adapter.capabilityId)

        // Blank prompt fails gracefully
        val failRes = adapter.execute("PROCESS_INTENT", emptyMap())
        assertEquals(ExternalActionResultStatus.FAILED, failRes.status)

        // Real autonomous intent compiles genuine 5-stage plan
        val successRes = adapter.execute("PROCESS_INTENT", mapOf("prompt" to "Compile, scan directory, and backup database"))
        assertEquals(ExternalActionResultStatus.SUCCESS, successRes.status)
        assertEquals(true, successRes.data["processed"])
        val stepCount = successRes.data["stepCount"] as? Int ?: 0
        assertTrue(stepCount >= 5)
        assertTrue(successRes.diagnosticMessage.contains("verified stages"))
    }

    @Test
    fun testCapabilityRealityRegistryNewCapabilities() {
        val registry = CapabilityRealityRegistry()

        val localLlm = registry.getCapabilityReality("LOCAL_LLM")
        assertNotNull(localLlm)
        assertEquals("LOCAL_LLM", localLlm.capabilityId)
        assertTrue(localLlm.provider.contains("Ollama"))

        val openSource = registry.getCapabilityReality("OPEN_SOURCE_MODELS")
        assertNotNull(openSource)
        assertEquals("OPEN_SOURCE_MODELS", openSource.capabilityId)

        val hf = registry.getCapabilityReality("HUGGINGFACE_AI")
        assertNotNull(hf)
        assertEquals("HUGGINGFACE_AI", hf.capabilityId)
    }
}
