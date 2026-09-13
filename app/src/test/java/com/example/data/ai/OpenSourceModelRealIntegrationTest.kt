package com.example.data.ai

import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.ExternalActionResultStatus
import com.example.data.agent.runtime.UniversalFabricIntegrationAdapter
import com.example.data.ai.provider.HuggingFaceProvider
import com.example.data.ai.provider.LocalLLMProvider
import com.example.data.api.HuggingFaceClient
import com.example.data.api.LocalLLMClient
import com.example.data.ai.runtime.WastiModelDownloader
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

    @Test
    fun testAllTwelveOpenSourceModelsManifestsExistAndValid() {
        val allModels = com.example.data.ai.model.OpenSourceModelCatalog.ALL_MODELS
        assertEquals(12, allModels.size)

        for (model in allModels) {
            val manifest = com.example.data.ai.engine.ModelArtifactManager.getManifest(model.id)
            assertNotNull("Manifest for ${model.id} must exist in ModelArtifactManager", manifest)
            manifest?.let {
                assertEquals(model.id, it.modelId)
                assertTrue("Expected SHA-256 must be 64 characters for ${it.modelId}", it.expectedSha256.length == 64)
                assertTrue("Must pass isTrustedSha256 for ${it.modelId}", WastiModelDownloader.isTrustedSha256(it.expectedSha256))
                assertTrue("Must have positive byte size for ${it.modelId}", it.byteSize > 0)
                assertTrue("Download URL must be secure HTTPS for ${it.modelId}", WastiModelDownloader.isSecureDownloadUrl(it.downloadUrl))
                assertNotNull("Mirror download URL must be present for ${it.modelId}", it.mirrorDownloadUrl)
                assertTrue("Mirror download URL must be secure HTTPS for ${it.modelId}", WastiModelDownloader.isSecureDownloadUrl(it.mirrorDownloadUrl!!))
                assertTrue("Checksum must be published for ${it.modelId}", it.isChecksumVerifiedPublished)
            }
        }
    }

    @Test
    fun testCodingEnvironmentInstallerBridgeScriptGeneration() {
        val manifest = com.example.data.ai.engine.ModelArtifactManager.getManifest("wasti-llama")
        assertNotNull(manifest)

        val script = com.example.data.ai.runtime.CodingEnvironmentInstallerBridge.generateTermuxInstallScript(null, manifest!!)
        assertTrue(script.startsWith("#!/usr/bin/env bash"))
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains(manifest.expectedSha256))
        assertTrue(script.contains(manifest.downloadUrl))
        assertTrue(script.contains(manifest.mirrorDownloadUrl ?: ""))
        assertTrue(script.contains("curl -L -C -"))

        val plan = com.example.data.ai.runtime.CodingEnvironmentInstallerBridge.planResolution(null, "wasti-llama", "Device storage constrained")
        assertEquals("wasti-llama", plan.modelId)
        assertTrue(plan.suggestedStrategy.contains("Coding environment"))
        assertTrue(plan.commandLineSnippet.contains("install_wasti_agent.sh"))
        assertTrue(plan.fallbackProvidersAvailable.isNotEmpty())
    }

    @Test
    fun testWastiAgentLearningPreserverCrossAppRetention() {
        val preserver = com.example.data.agent.runtime.WastiAgentLearningPreserver
        preserver.resetForTesting()

        val basePrompt = "You are Wasti AI OS."
        val initialPrompt = preserver.getAdaptedSystemPrompt(basePrompt, "chat")
        assertEquals(basePrompt, initialPrompt)

        // Record cross-app learning
        preserver.recordLearnedSkill(
            skillName = "SafeFileRead",
            targetAppId = "general",
            promptDirective = "Always verify path boundary before reading files",
            executionEvidence = "Verified 15 execution runs"
        )

        val adaptedPrompt = preserver.getAdaptedSystemPrompt(basePrompt, "coding")
        assertTrue(adaptedPrompt.contains("PERSISTENT WASTI TRAINING & ACCUMULATED LEARNING"))
        assertTrue(adaptedPrompt.contains("SafeFileRead"))
        assertTrue(adaptedPrompt.contains("Always verify path boundary before reading files"))

        val skills = preserver.getAllLearnedSkills()
        assertEquals(1, skills.size)
        assertEquals("SafeFileRead", skills[0].skillName)
    }

    @Test
    fun testNativePolyglotTerminalModelCommands() = runBlocking {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val workspace = com.example.data.wre.WreWorkspaceManager(context)
        val terminal = com.example.data.wre.WastiPolyglotTerminalEngine(context, workspace)

        assertTrue(terminal.canExecute(com.example.data.wre.ExecutionRequest(command = "model list")))
        assertTrue(terminal.canExecute(com.example.data.wre.ExecutionRequest(command = "model status wasti-llama")))

        val listResult = terminal.execute(com.example.data.wre.ExecutionRequest(command = "model list"))
        assertEquals(com.example.data.wre.ExecutionStatus.SUCCESS, listResult.status)
        assertTrue(listResult.stdout.contains("12 OPEN-SOURCE SOVEREIGN AGENTS"))
        assertTrue(listResult.stdout.contains("wasti-llama"))
        assertTrue(listResult.stdout.contains("wasti-qwen"))
        assertTrue(listResult.stdout.contains("wasti-deepseek"))

        val statusResult = terminal.execute(com.example.data.wre.ExecutionRequest(command = "model status wasti-llama"))
        assertEquals(com.example.data.wre.ExecutionStatus.SUCCESS, statusResult.status)
        assertTrue(statusResult.stdout.contains("Model: wasti-llama"))
    }
}
