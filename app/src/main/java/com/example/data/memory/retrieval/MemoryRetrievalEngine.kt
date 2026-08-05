package com.example.data.memory.retrieval

import com.example.data.memory.embedding.EmbeddingService
import com.example.data.memory.graph.KnowledgeGraphEngine
import com.example.data.memory.model.MemoryItem
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.memory.model.MemorySearchResult
import com.example.data.memory.model.SearchType
import com.example.data.memory.storage.VectorIndex
import java.util.concurrent.TimeUnit

data class RetrievalPolicy(
    val vectorWeight: Float = 0.50f,
    val keywordWeight: Float = 0.20f,
    val recencyWeight: Float = 0.15f,
    val importanceWeight: Float = 0.15f
)

data class RetrievalExplanation(
    val memoryId: String,
    val memoryKey: String,
    val vectorSimilarity: Float,
    val keywordMatchScore: Float,
    val recencyScore: Float,
    val importanceScore: Float,
    val finalWeightedScore: Float,
    val retrievalStrategy: String = "MultiFactor4D",
    val provenanceReason: String
)

class MemoryRetrievalEngine(
    private val embeddingService: EmbeddingService,
    private val vectorIndex: VectorIndex,
    private val knowledgeGraphEngine: KnowledgeGraphEngine,
    var policy: RetrievalPolicy = RetrievalPolicy()
) {

    suspend fun retrieve(
        query: MemorySearchQuery,
        memoriesMap: Map<String, MemoryItem>
    ): Pair<List<MemorySearchResult>, List<RetrievalExplanation>> {
        val queryEmbedding = embeddingService.generateEmbedding(query.queryText)
        val vectorResults = vectorIndex.searchNearest(queryEmbedding, topK = query.topK * 3)

        val results = mutableListOf<MemorySearchResult>()
        val explanations = mutableListOf<RetrievalExplanation>()
        val now = System.currentTimeMillis()

        vectorResults.forEach { (indexedVector, simScore) ->
            val memory = memoriesMap[indexedVector.id]
            if (memory != null && !memory.isArchived) {
                if (query.category == null || memory.category.equals(query.category, ignoreCase = true)) {
                    val keywordScore = calculateKeywordMatchScore(query.queryText, memory.value)
                    val recencyScore = calculateRecencyScore(now, memory.lastAccessedTimestamp)
                    val importanceScore = memory.importanceScore

                    val weightedScore = (simScore * policy.vectorWeight) +
                            (keywordScore * policy.keywordWeight) +
                            (recencyScore * policy.recencyWeight) +
                            (importanceScore * policy.importanceWeight)

                    if (weightedScore >= query.minImportance) {
                        val searchResult = MemorySearchResult(
                            memory = memory,
                            relevanceScore = weightedScore,
                            vectorSimilarity = simScore,
                            textMatchScore = keywordScore,
                            matchType = SearchType.HYBRID
                        )
                        results.add(searchResult)

                        val explanation = RetrievalExplanation(
                            memoryId = memory.id,
                            memoryKey = memory.key,
                            vectorSimilarity = simScore,
                            keywordMatchScore = keywordScore,
                            recencyScore = recencyScore,
                            importanceScore = importanceScore,
                            finalWeightedScore = weightedScore,
                            provenanceReason = "Matched via 4D Hybrid Retrieval (Sim: ${"%.2f".format(simScore)}, Keyword: ${"%.2f".format(keywordScore)}, Recency: ${"%.2f".format(recencyScore)})"
                        )
                        explanations.add(explanation)
                    }
                }
            }
        }

        val sortedResults = results.sortedByDescending { it.relevanceScore }.take(query.topK)
        val sortedExplanations = explanations.sortedByDescending { it.finalWeightedScore }.take(query.topK)

        return Pair(sortedResults, sortedExplanations)
    }

    private fun calculateKeywordMatchScore(query: String, text: String): Float {
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryWords.isEmpty()) return 0.0f

        val lowerText = text.lowercase()
        var matches = 0
        queryWords.forEach { word ->
            if (lowerText.contains(word)) matches++
        }
        return matches.toFloat() / queryWords.size.toFloat()
    }

    private fun calculateRecencyScore(now: Long, timestamp: Long): Float {
        val ageHours = TimeUnit.MILLISECONDS.toHours(now - timestamp)
        return when {
            ageHours <= 1 -> 1.0f
            ageHours <= 24 -> 0.8f
            ageHours <= 168 -> 0.5f
            else -> 0.2f
        }
    }
}
