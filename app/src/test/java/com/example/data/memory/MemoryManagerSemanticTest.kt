package com.example.data.memory

import com.example.data.memory.embedding.DefaultEmbeddingService
import com.example.data.memory.model.EmbeddingVector
import com.example.data.memory.storage.VectorIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.data.core.TestCategory
import com.example.data.core.TestTier

@TestCategory(
    tier = TestTier.UNIT,
    description = "JVM unit test of VectorIndex and deterministic harmonic embedding fallback"
)
class MemoryManagerSemanticTest {

    @Test
    fun testVectorIndexAndCosineSimilarity() = runBlocking {
        val index = VectorIndex()
        val embeddingService = DefaultEmbeddingService()

        val vec1 = embeddingService.generateEmbedding("Kotlin coroutines and flow architecture")
        val vec2 = embeddingService.generateEmbedding("Kotlin coroutines asynchronous programming")
        val vec3 = embeddingService.generateEmbedding("Chocolate cake baking recipe with eggs and sugar")

        // Truthful Provenance: Assert deterministic fallback is explicitly tagged and not neural
        assertFalse(vec1.isNeuralEmbedding)
        assertEquals("DETERMINISTIC_MATHEMATICAL_FALLBACK", vec1.engineType)
        assertTrue(vec1.modelName.contains("NON_NEURAL_FALLBACK"))
        assertEquals(384, vec1.vectorLength)

        index.indexVector("doc_1", vec1, "{\"title\":\"Kotlin Coroutines\"}")
        index.indexVector("doc_2", vec2, "{\"title\":\"Asynchronous Kotlin\"}")
        index.indexVector("doc_3", vec3, "{\"title\":\"Cake Recipe\"}")

        assertEquals(3, index.size())

        // Search for query similar to doc 1 and 2
        val query = embeddingService.generateEmbedding("Kotlin asynchronous coroutines")
        val searchResults = index.searchNearest(query, topK = 2)

        assertEquals(2, searchResults.size)
        val topMatchId = searchResults[0].first.id
        assertTrue(topMatchId == "doc_1" || topMatchId == "doc_2")

        // Search for cake query
        val cakeQuery = embeddingService.generateEmbedding("baking ingredients sugar cake")
        val cakeResults = index.searchNearest(cakeQuery, topK = 1)
        assertEquals("doc_3", cakeResults[0].first.id)
    }

    @Test
    fun testVectorIndexRemoval() {
        val index = VectorIndex()
        val vec = EmbeddingVector("default", "local", 3, floatArrayOf(1.0f, 0.0f, 0.0f))
        index.indexVector("item_1", vec)
        assertEquals(1, index.size())

        index.removeVector("item_1")
        assertEquals(0, index.size())
    }

    @Test
    fun testMemoryDataGovernanceCompliance() = runBlocking {
        MemoryManager.resetForTesting()

        // Create memories
        MemoryManager.saveMemory(key = "user_pref", category = "PREFERENCE", value = "prefers dark theme", importanceScore = 0.9f)
        MemoryManager.saveMemory(key = "user_device", category = "DEVICE", value = "runs Android 14", importanceScore = 0.8f)

        // 1. Verify JSON export for data portability (GDPR)
        val exportedJson = MemoryManager.exportUserDataJson()
        assertTrue("Exported JSON must contain pref key", exportedJson.contains("user_pref"))
        assertTrue("Exported JSON must contain dark theme", exportedJson.contains("prefers dark theme"))

        // 2. Verify complete deletion (Right-to-be-forgotten)
        val deletedCount = MemoryManager.deleteAllMemories()
        assertEquals(2, deletedCount)
        val statsAfter = MemoryManager.getObservabilityStats()
        assertEquals(0, statsAfter.totalActiveMemories)
        assertEquals(0, statsAfter.totalVectorsIndexed)
    }

    @Test
    fun testInferenceToFactProtection() = runBlocking {
        MemoryManager.resetForTesting()

        // Create an INFERRED memory
        val inferredMemory = MemoryManager.saveMemory(
            key = "user_inferred_diet",
            category = "Inferred Interests",
            value = "User might be vegetarian based on recipe queries",
            importanceScore = 0.99f, // Even with 99% confidence
            provenanceCategory = com.example.data.memory.model.MemoryProvenanceCategory.INFERRED
        )

        assertEquals(com.example.data.memory.model.MemoryProvenanceCategory.INFERRED, inferredMemory.provenanceCategory)

        // Promotion to VERIFIED_KNOWLEDGE must be prohibited
        assertFalse(
            "INFERRED memory must never be promoted to VERIFIED_KNOWLEDGE merely due to high confidence",
            MemoryManager.canPromoteMemory(inferredMemory, com.example.data.memory.model.MemoryTier.VERIFIED_KNOWLEDGE)
        )

        // Promotion to USER_MEMORY must also be prohibited
        assertFalse(
            "INFERRED memory must never be promoted to USER_MEMORY fact merely due to high confidence",
            MemoryManager.canPromoteMemory(inferredMemory, com.example.data.memory.model.MemoryTier.USER_MEMORY)
        )

        // promoteMemoryTier must return false
        val promoted = MemoryManager.promoteMemoryTier(inferredMemory.id, com.example.data.memory.model.MemoryTier.VERIFIED_KNOWLEDGE)
        assertFalse(promoted)

        // canPromoteProvenance must reject INFERRED -> VERIFIED or USER_STATED
        assertFalse(
            MemoryManager.canPromoteProvenance(
                com.example.data.memory.model.MemoryProvenanceCategory.INFERRED,
                com.example.data.memory.model.MemoryProvenanceCategory.VERIFIED
            )
        )
        assertFalse(
            MemoryManager.canPromoteProvenance(
                com.example.data.memory.model.MemoryProvenanceCategory.INFERRED,
                com.example.data.memory.model.MemoryProvenanceCategory.USER_STATED
            )
        )
    }
}
