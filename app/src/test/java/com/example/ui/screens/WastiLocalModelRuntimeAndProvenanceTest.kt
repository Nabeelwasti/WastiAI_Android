package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.QuantizationType
import com.example.data.ai.provider.WastiLocalBrainProvider
import com.example.data.ai.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
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

    @Test
    fun testWastiLocalModelRuntimeInferenceWithoutWeights() {
        runBlocking {
            val runtime = WastiLocalModelRuntime(context)
            val output = runtime.executeInference(
                modelId = "wasti-smollm",
                prompt = "Hello Wasti"
            )
            assertTrue(output.contains("not present locally") || output.contains("Download required"))
        }
    }

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
}
