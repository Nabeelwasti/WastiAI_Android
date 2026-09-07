package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.ai.engine.HardwareCapabilityDetector
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.*
import com.example.data.ai.provider.WastiLocalBrainProvider
import com.example.data.ai.runtime.*
import com.example.data.cloud.ComputeExecutionTier
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
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@TestCategory(
    tier = TestTier.HOST_SIMULATION,
    description = "Robolectric host simulation of model catalog, weights presence, and downloader safety"
)
class WastiLocalModelRuntimeAndProvenanceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testWastiLocalTokenizerEncodeDecode() {
        val vocab = mapOf("Hello" to 10, "world" to 11, "Wasti" to 12, "AI" to 13)
        val invVocab = vocab.entries.associate { (k, v) -> v to k }
        val tokenizer = WastiLocalTokenizer(vocab = vocab, invVocab = invVocab)

        val text = "Hello world"
        val encoded = tokenizer.encode(text)
        assertFalse(encoded.isEmpty())
        assertTrue(encoded.contains(10) && encoded.contains(11))

        val decoded = tokenizer.decode(encoded)
        assertEquals("Hello world", decoded)
    }

    @Test
    fun testWastiLocalTokenizerByteFallbackForUnknownTokens() {
        val tokenizer = WastiLocalTokenizer(vocab = emptyMap(), invVocab = emptyMap())
        val text = "ABC"
        val encoded = tokenizer.encode(text)
        assertEquals(3, encoded.size) // 3 UTF-8 bytes

        val decoded = tokenizer.decode(encoded)
        assertEquals("ABC", decoded)
    }

    /**
     * Truthful Scope: Verifies the mathematical properties of WastiEmbeddingRuntime's
     * deterministic subword trigonometric harmonic projection (dimension = 384, cosine ranking heuristic).
     * This test passes on deterministic math; it does NOT prove a neural transformer embedding model
     * (e.g. MiniLM) is loaded or executing on device.
     */
    @Test
    fun testWastiEmbeddingRuntimeDimensionAndCosineSimilarity() {
        val vec1 = WastiEmbeddingRuntime.encode("Setup wifi network and router IP")
        val vec2 = WastiEmbeddingRuntime.encode("Configure wireless internet connection")
        val vecUnrelated = WastiEmbeddingRuntime.encode("Bake a chocolate strawberry cake")

        assertEquals(384, vec1.size)
        assertEquals(384, vec2.size)
        assertEquals(384, vecUnrelated.size)

        val simRelated = WastiEmbeddingRuntime.cosineSimilarity(vec1, vec2)
        val simUnrelated = WastiEmbeddingRuntime.cosineSimilarity(vec1, vecUnrelated)

        assertTrue(simRelated > simUnrelated)
    }

    @Test
    fun testWastiEmbeddingRuntimeTopKSearch() {
        val query = WastiEmbeddingRuntime.encode("database storage table")
        val candidates = listOf(
            "SQLite Room entity database" to WastiEmbeddingRuntime.encode("SQLite Room entity database"),
            "System UI notification volume" to WastiEmbeddingRuntime.encode("System UI notification volume"),
            "Memory storage key value" to WastiEmbeddingRuntime.encode("Memory storage key value")
        )

        val results = WastiEmbeddingRuntime.findTopK(query, candidates, topK = 2)
        assertEquals(2, results.size)
        assertTrue(results[0].first.contains("database") || results[0].first.contains("storage"))
    }

    @Test
    fun testWastiEmbeddingRuntimeZeroFabricationTypeAssertion() {
        assertFalse(WastiEmbeddingRuntime.isNeuralEmbedding)
        assertEquals(
            EmbeddingEngineType.DETERMINISTIC_MATHEMATICAL_FALLBACK,
            WastiEmbeddingRuntime.engineType
        )
    }

    @Test
    fun testExecutionProvenanceLedgerHashChainingAndIntegrity() {
        val evidence1 = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.FILESYSTEM,
            subject = "workspace_create",
            verifiedState = "DIR_EXISTS",
            confidence = 1.0
        )

        val e1 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_001",
            actionId = "act_001",
            capabilityId = "create_workspace",
            providerId = "WastiSandbox",
            modelId = "wasti-smollm",
            inputContent = "dir=/tmp/wasti",
            outputContent = "Directory created successfully",
            evidence = evidence1
        )

        val evidence2 = VerifiedExecutionEvidence(
            evidenceSource = EvidenceSource.DATABASE_QUERY,
            subject = "memory_write",
            verifiedState = "RECORD_INSERTED",
            confidence = 0.95
        )

        val e2 = ExecutionProvenanceLedger.recordExecution(
            taskId = "task_001",
            actionId = "act_002",
            capabilityId = "save_memory",
            providerId = "MemoryManager",
            modelId = null,
            inputContent = "key=session_token",
            outputContent = "Saved",
            evidence = evidence2
        )

        assertEquals(e1.entryHash, e2.previousEntryHash)
        assertTrue(ExecutionProvenanceLedger.verifyLedgerIntegrity())

        val taskEntries = ExecutionProvenanceLedger.getProvenanceForTask("task_001")
        assertEquals(2, taskEntries.size)
        assertTrue(taskEntries.all { it.isVerified })
    }

    /**
     * Truthful Scope: Verifies fail-closed behavior when model weights are missing on-device.
     * Confirms the runtime truthfully returns an unavailable message and refuses to simulate
     * or fabricate pseudo-logits. This test does NOT prove neural inference with real weights.
     */
    @Test
    fun testWastiLocalModelRuntimeInferenceWithoutWeights() {
        runBlocking {
            val runtime = WastiLocalModelRuntime(context)
            val detailedResult = runtime.executeInferenceDetailed(
                modelId = "wasti-smollm",
                prompt = "Hello Wasti"
            )
            assertEquals(LocalInferenceStatus.WEIGHTS_MISSING, detailedResult.status)
            assertFalse(detailedResult.isNeuralOutput)
            assertTrue(detailedResult.output.contains("not present locally") || detailedResult.output.contains("Download required"))

            val output = runtime.executeInference(
                modelId = "wasti-smollm",
                prompt = "Hello Wasti"
            )
            assertTrue(output.contains("not present locally") || output.contains("Download required"))
        }
    }

    /**
     * Truthful Scope: Verifies low-level binary parsing of the GGUF container format header
     * (magic bytes, version, tensor count, metadata KV count).
     * This test validates byte-stream parsing only; it does NOT prove neural tensor computation,
     * weight execution, or token generation.
     */
    @Test
    fun testWastiLocalModelRuntimeGgufHeaderParser() {
        runBlocking {
            val dummyFile = File(context.cacheDir, "test_model.gguf")
            FileOutputStream(dummyFile).use { fos ->
                // Write GGUF Magic "GGUF" (0x46554747 in little-endian), Version 3, TensorCount 10, MetadataCount 5
                val buf = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0x46554747)
                buf.putInt(3)
                buf.putLong(10L)
                buf.putLong(5L)
                fos.write(buf.array())
            }

            val runtime = WastiLocalModelRuntime(context)
            val header = runtime.parseGgufHeader(dummyFile)

            assertTrue(header.isValidGguf)
            assertEquals("GGUF", header.magic)
            assertEquals(3u, header.version)
            assertEquals(10uL, header.tensorCount)
            assertEquals(5uL, header.metadataKvCount)

            dummyFile.delete()
        }
    }

    @Test
    fun testModelCatalogAndManifestsIntegrity() {
        val smollmManifest = ModelArtifactManager.getManifest("wasti-smollm")
        assertNotNull(smollmManifest)
        assertEquals("decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33", smollmManifest?.expectedSha256)
        assertTrue(smollmManifest?.isChecksumVerifiedPublished == true)

        val llamaManifest = ModelArtifactManager.getManifest("wasti-llama")
        assertNotNull(llamaManifest)
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", llamaManifest?.expectedSha256)
        assertTrue(llamaManifest?.isChecksumVerifiedPublished == true)

        val qwenManifest = ModelArtifactManager.getManifest("wasti-qwen")
        assertNotNull(qwenManifest)
        assertEquals("cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046", qwenManifest?.expectedSha256)
        assertTrue(qwenManifest?.isChecksumVerifiedPublished == true)

        // Heavy / remote-only models must not claim local execution without mesh or server
        val commandR = OpenSourceModelCatalog.getModelById("wasti-commandr")
        assertNotNull(commandR)
        assertFalse(commandR!!.isLocalExecutionSupported)
        assertEquals(LocalExecutionBackend.WASTI_MESH_FEDERATION, commandR.defaultBackend)

        val deepseek = OpenSourceModelCatalog.getModelById("wasti-deepseek")
        assertNotNull(deepseek)
        assertFalse(deepseek!!.isLocalExecutionSupported)
    }

    @Test
    fun testModelRunnableStatusFailsClosedWithoutWeights() {
        // Model weights are not downloaded in test environment
        val (isRunnable, reason) = ModelArtifactManager.isModelRunnableLocally(context, "wasti-smollm")
        assertFalse(isRunnable)
        assertTrue(reason.contains("not present locally") || reason.contains("Download required"))

        // Unmanifested model fails closed
        val (isRunnableDeepseek, reasonDeepseek) = ModelArtifactManager.isModelRunnableLocally(context, "wasti-deepseek")
        assertFalse(isRunnableDeepseek)
        assertTrue(reasonDeepseek.contains("manifest"))

        // Status for downloadable model with missing weights must be AVAILABLE_PENDING_DOWNLOAD, not LOCAL_WEIGHTS_PRESENT
        val status = ModelArtifactManager.getModelStatus(context, "wasti-smollm")
        assertEquals(ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD, status)
    }

    @Test
    fun testWastiModelDownloaderSafetyAndValidation() {
        // SHA-256 validation
        assertTrue(WastiModelDownloader.isTrustedSha256("decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33"))
        assertFalse(WastiModelDownloader.isTrustedSha256("PENDING_VERIFICATION"))
        assertFalse(WastiModelDownloader.isTrustedSha256("0000000000000000000000000000000000000000000000000000000000000000"))
        assertFalse(WastiModelDownloader.isTrustedSha256("short_sha"))

        // Secure URL validation
        assertTrue(WastiModelDownloader.isSecureDownloadUrl("https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf"))
        assertFalse(WastiModelDownloader.isSecureDownloadUrl("http://huggingface.co/model.gguf"))
        assertFalse(WastiModelDownloader.isSecureDownloadUrl("https://localhost/model.gguf"))
        assertFalse(WastiModelDownloader.isSecureDownloadUrl("https://127.0.0.1/model.gguf"))
        assertFalse(WastiModelDownloader.isSecureDownloadUrl("https://192.168.1.1/model.gguf"))
        assertFalse(WastiModelDownloader.isSecureDownloadUrl("https://untrusted-domain.com/model.gguf"))

        // canDownload safety check
        val unverifiedManifest = ModelArtifactManifest(
            modelId = "test-unverified",
            canonicalFileName = "test.gguf",
            expectedSha256 = "INVALID_HASH",
            byteSize = 1000L,
            quantization = QuantizationType.Q4_K_M,
            downloadUrl = "https://huggingface.co/test/model.gguf",
            license = "MIT",
            minRamRequiredMb = 256,
            requiredHardwareBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
            isChecksumVerifiedPublished = false
        )
        val (canDl, dlReason) = WastiModelDownloader.canDownload(context, unverifiedManifest)
        assertFalse(canDl)
        assertTrue(dlReason.contains("SHA-256") || dlReason.contains("checksum"))
    }

    @Test
    fun testHardwareCapabilityDetectorTruthfulAccelerationEvidence() {
        // Clear any previous evidence
        HardwareCapabilityDetector.clearAcceleratorExecutionEvidence()

        val initialSpecs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        // Without verified execution evidence, acceleratorStatus cannot be ACTIVE_VERIFIED_ACCELERATION
        assertNotEquals(AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION, initialSpecs.acceleratorStatus)
        assertFalse(initialSpecs.hasNpuAcceleration)
        assertNull(initialSpecs.verifiedExecutionEvidence)

        // Record real execution evidence (e.g. from native benchmark / llama tensor eval)
        HardwareCapabilityDetector.recordAcceleratorExecutionEvidence("NNAPI_CONV2D_BENCHMARK_EVIDENCE_OK_25ms")

        val acceleratedSpecs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        assertEquals(AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION, acceleratedSpecs.acceleratorStatus)
        assertTrue(acceleratedSpecs.hasNpuAcceleration)
        assertEquals("NNAPI_CONV2D_BENCHMARK_EVIDENCE_OK_25ms", acceleratedSpecs.verifiedExecutionEvidence)

        // Clearing evidence resets truthful status
        HardwareCapabilityDetector.clearAcceleratorExecutionEvidence()
        val resetSpecs = HardwareCapabilityDetector.detectHardwareEnvironment(context)
        assertFalse(resetSpecs.hasNpuAcceleration)
        assertNotEquals(AcceleratorExecutionStatus.ACTIVE_VERIFIED_ACCELERATION, resetSpecs.acceleratorStatus)
    }

    @Test
    fun testComputeOffloadTruthfulExecutionAndFailClosed() = runBlocking {
        // 1. Batch embeddings real bounded computation
        val embRequest = ComputeTaskRequest(
            type = ComputeTaskType.BATCH_EMBEDDINGS,
            payload = mapOf("texts" to listOf("Kotlin flow", "Room SQLite database"))
        )
        val embOutcome = FirebaseComputeOffloader.executeTask(context, embRequest)
        assertTrue(embOutcome.success)
        assertEquals(ComputeExecutionTier.LOCAL_THROTTLED_SAFE, embOutcome.executionTier)
        assertTrue(embOutcome.output.contains("Computed batch of 2 deterministic fallback embeddings"))

        // 2. Empty payload fails closed
        val emptyRequest = ComputeTaskRequest(
            type = ComputeTaskType.BATCH_EMBEDDINGS,
            payload = emptyMap()
        )
        val emptyOutcome = FirebaseComputeOffloader.executeTask(context, emptyRequest)
        assertFalse(emptyOutcome.success)
        assertNotNull(emptyOutcome.error)
        assertTrue(emptyOutcome.error!!.contains("Missing or empty texts list"))

        // 3. File transform real SHA-256
        val content = "Zero-Fabrication Wasti Engine"
        val transformRequest = ComputeTaskRequest(
            type = ComputeTaskType.HEAVY_FILE_TRANSFORM,
            payload = mapOf("content" to content)
        )
        val transformOutcome = FirebaseComputeOffloader.executeTask(context, transformRequest)
        assertTrue(transformOutcome.success)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(transformOutcome.output.contains(expectedHash))
    }
}
