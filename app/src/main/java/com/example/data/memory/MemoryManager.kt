package com.example.data.memory

import android.util.Log
import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import com.example.data.db.MemoryDao
import com.example.data.db.MemoryEntity
import com.example.data.memory.embedding.DefaultEmbeddingService
import com.example.data.memory.embedding.EmbeddingService
import com.example.data.memory.graph.KnowledgeGraphEngine
import com.example.data.memory.model.EmbeddingVector
import com.example.data.memory.model.MemoryItem
import com.example.data.memory.model.MemoryObservabilityStats
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.memory.model.MemorySearchResult
import com.example.data.memory.model.SearchType
import com.example.data.memory.policy.MemoryPolicyEngine
import com.example.data.memory.storage.VectorIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

object MemoryManager {

    val embeddingService: EmbeddingService = DefaultEmbeddingService()
    val vectorIndex = VectorIndex()
    val knowledgeGraphEngine = KnowledgeGraphEngine()
    val policyEngine = MemoryPolicyEngine()
    val retrievalEngine = com.example.data.memory.retrieval.MemoryRetrievalEngine(
        embeddingService = embeddingService,
        vectorIndex = vectorIndex,
        knowledgeGraphEngine = knowledgeGraphEngine
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private var memoryDao: MemoryDao? = null

    private val activeMemoriesMap = java.util.concurrent.ConcurrentHashMap<String, MemoryItem>()
    private val _memoriesFlow = MutableStateFlow<List<MemoryItem>>(emptyList())
    val memoriesFlow: StateFlow<List<MemoryItem>> = _memoriesFlow.asStateFlow()

    fun initialize(dao: MemoryDao) {
        this.memoryDao = dao
        scope.launch {
            loadMemoriesFromDatabase()
        }
    }

    private suspend fun loadMemoriesFromDatabase() = withContext(Dispatchers.IO) {
        try {
            val dao = memoryDao ?: return@withContext
            val list = dao.getMemoriesList()
            activeMemoriesMap.clear()

            list.forEach { entity ->
                val embedding = embeddingService.generateEmbedding(entity.value)
                val item = MemoryItem(
                    id = entity.id,
                    key = entity.key,
                    category = entity.category,
                    value = entity.value,
                    importanceScore = entity.importanceScore,
                    timestamp = entity.timestamp,
                    sourceMessageId = entity.sourceMessageId,
                    embedding = embedding
                )
                activeMemoriesMap[entity.id] = item
                vectorIndex.indexVector(entity.id, embedding, "{\"key\":\"${entity.key}\"}")
            }
            _memoriesFlow.value = activeMemoriesMap.values.toList()
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to load memories from Room database", e)
        }
    }

    suspend fun processExplicitMemoryIntent(userPrompt: String, sourceMessageId: String? = null) = withContext(Dispatchers.IO) {
        val lower = userPrompt.trim().lowercase()
        val isExplicitMemory = lower.startsWith("remember") ||
                lower.contains("remember that") ||
                lower.contains("my favorite") ||
                lower.contains("always use") ||
                lower.contains("note that") ||
                lower.contains("don't forget") ||
                lower.contains("never forget")

        if (isExplicitMemory) {
            val key = when {
                lower.contains("favorite") -> "User Favorite Preference"
                lower.contains("always use") -> "User System Constraint"
                lower.contains("don't forget") || lower.contains("never forget") -> "User Critical Fact"
                else -> "User Stored Fact"
            }
            saveMemory(
                key = "$key (${userPrompt.take(25)}...)",
                category = "User Preferences",
                value = userPrompt.trim(),
                importanceScore = 0.95f,
                sourceMessageId = sourceMessageId
            )
            Log.i("MemoryManager", "Explicit memory intent processed & indexed: $key")
        }
    }

    suspend fun saveMemory(
        key: String,
        category: String,
        value: String,
        importanceScore: Float = 0.9f,
        sourceMessageId: String? = null
    ): MemoryItem = withContext(Dispatchers.IO) {
        val existingDuplicate = activeMemoriesMap.values.find {
            policyEngine.isDuplicate(it.value, value)
        }

        if (existingDuplicate != null) {
            val updated = existingDuplicate.copy(
                importanceScore = policyEngine.calculateUpdatedImportance(
                    currentScore = existingDuplicate.importanceScore,
                    accessCount = existingDuplicate.accessCount + 1,
                    isExplicitlyMarked = false
                ),
                lastAccessedTimestamp = System.currentTimeMillis(),
                accessCount = existingDuplicate.accessCount + 1
            )
            activeMemoriesMap[updated.id] = updated
            _memoriesFlow.value = activeMemoriesMap.values.toList()
            return@withContext updated
        }

        val id = "mem_${UUID.randomUUID()}"
        val embedding = embeddingService.generateEmbedding(value)
        val newItem = MemoryItem(
            id = id,
            key = key,
            category = category,
            value = value,
            importanceScore = importanceScore,
            timestamp = System.currentTimeMillis(),
            sourceMessageId = sourceMessageId,
            embedding = embedding
        )

        activeMemoriesMap[id] = newItem
        vectorIndex.indexVector(id, embedding, "{\"key\":\"$key\"}")

        try {
            memoryDao?.insertMemory(
                MemoryEntity(
                    id = id,
                    key = key,
                    category = category,
                    value = value,
                    importanceScore = importanceScore,
                    timestamp = newItem.timestamp,
                    sourceMessageId = sourceMessageId
                )
            )
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to persist memory entity to Room database", e)
        }

        _memoriesFlow.value = activeMemoriesMap.values.toList()
        WastiEventBus.emit(WastiEvent.MemoryUpdated(id, "CREATED"))
        return@withContext newItem
    }

    suspend fun hybridSearch(query: MemorySearchQuery): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val (results, _) = retrievalEngine.retrieve(query, activeMemoriesMap)
        results
    }

    suspend fun hybridSearchWithExplanations(query: MemorySearchQuery): Pair<List<MemorySearchResult>, List<com.example.data.memory.retrieval.RetrievalExplanation>> = withContext(Dispatchers.IO) {
        retrievalEngine.retrieve(query, activeMemoriesMap)
    }

    suspend fun retrieveRelevantContextPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        try {
            val (results, explanations) = retrievalEngine.retrieve(
                MemorySearchQuery(
                    queryText = prompt,
                    topK = 5,
                    minImportance = 0.3f
                ),
                activeMemoriesMap
            )

            if (results.isEmpty()) return@withContext ""

            val contextLines = results.mapIndexed { idx, res ->
                val expl = explanations.getOrNull(idx)
                val reasonStr = expl?.provenanceReason ?: "Relevance: ${"%.2f".format(res.relevanceScore)}"
                "${idx + 1}. [${res.memory.category}] ${res.memory.key}: ${res.memory.value} ($reasonStr)"
            }

            val graphSummary = knowledgeGraphEngine.getGraphSummary()

            """
            [WAS TI PLATFORM ENTERPRISE MEMORY RETRIEVAL]:
            ${contextLines.joinToString("\n")}

            $graphSummary
            [END MEMORY CONTEXT]
            """.trimIndent()
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to retrieve relevant context prompt", e)
            ""
        }
    }

    fun getObservabilityStats(): MemoryObservabilityStats {
        val totalActive = activeMemoriesMap.values.count { !it.isArchived }
        val totalArchived = activeMemoriesMap.values.count { it.isArchived }
        val graph = knowledgeGraphEngine.getGraphSnapshot()

        return MemoryObservabilityStats(
            totalActiveMemories = totalActive,
            totalArchivedMemories = totalArchived,
            totalVectorsIndexed = vectorIndex.size(),
            totalGraphNodes = graph.nodes.size,
            totalGraphEdges = graph.edges.size,
            averageVectorLength = 768,
            storageUsageBytes = (totalActive + totalArchived) * 1024L,
            lastCleanupTimestamp = System.currentTimeMillis()
        )
    }

    suspend fun deleteMemory(id: String) = withContext(Dispatchers.IO) {
        activeMemoriesMap.remove(id)
        vectorIndex.removeVector(id)
        _memoriesFlow.value = activeMemoriesMap.values.toList()
        try {
            memoryDao?.deleteMemoryById(id)
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to delete memory entity from Room database", e)
        }
        WastiEventBus.emit(WastiEvent.MemoryUpdated(id, "DELETED"))
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
}
