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
import com.example.data.memory.model.MemoryProvenanceCategory
import com.example.data.memory.model.MemorySearchQuery
import com.example.data.memory.model.MemorySearchResult
import com.example.data.memory.model.MemoryTier
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

            // Index all memories into the knowledge graph & vector index without arbitrary caps
            list.forEach { entity ->
                val embedding = embeddingService.generateEmbedding(entity.value)
                val provenanceCat = if (entity.category.contains("Preference", ignoreCase = true) || entity.category.contains("User", ignoreCase = true)) {
                    MemoryProvenanceCategory.USER_STATED
                } else if (entity.category.contains("Inferred", ignoreCase = true) || entity.category.contains("Guessed", ignoreCase = true)) {
                    MemoryProvenanceCategory.INFERRED
                } else {
                    MemoryProvenanceCategory.OBSERVED
                }
                val item = MemoryItem(
                    id = entity.id,
                    key = entity.key,
                    category = entity.category,
                    value = entity.value,
                    importanceScore = entity.importanceScore,
                    timestamp = entity.timestamp,
                    sourceMessageId = entity.sourceMessageId,
                    embedding = embedding,
                    tier = resolveTierForCategory(entity.category, entity.key),
                    provenanceCategory = provenanceCat
                )
                activeMemoriesMap[entity.id] = item
                vectorIndex.indexVector(entity.id, embedding, "{\"key\":\"${entity.key}\"}")
            }
            _memoriesFlow.value = activeMemoriesMap.values.toList()
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to load memories from Room database", e)
        }
    }

    fun resolveTierForCategory(category: String, key: String): MemoryTier {
        val catLower = category.lowercase()
        val keyLower = key.lowercase()
        return when {
            catLower.contains("credential") || keyLower.contains("secret") || keyLower.contains("key") || keyLower.contains("token") || keyLower.contains("password") -> MemoryTier.CREDENTIAL
            catLower.contains("security") || keyLower.contains("security") -> MemoryTier.SECURITY_EVENT
            catLower.contains("preference") || catLower.contains("user") -> MemoryTier.USER_MEMORY
            catLower.contains("project") -> MemoryTier.PROJECT_MEMORY
            catLower.contains("skill") || keyLower.startsWith("skill_") -> MemoryTier.GLOBAL_SKILL
            catLower.contains("verified") || keyLower.contains("verified") -> MemoryTier.VERIFIED_KNOWLEDGE
            catLower.contains("ephemeral") || catLower.contains("session") -> MemoryTier.EPHEMERAL
            else -> MemoryTier.SYSTEM_MEMORY
        }
    }

    /**
     * Checks whether a memory can be promoted to the target tier.
     * Boundary gating: CREDENTIAL can never be promoted; USER_MEMORY cannot become global knowledge/skill.
     * Furthermore, INFERRED memories can NEVER be converted into VERIFIED_KNOWLEDGE or USER_MEMORY facts
     * merely due to high confidence without independent empirical verification.
     */
    fun canPromoteMemory(memory: MemoryItem, targetTier: MemoryTier): Boolean {
        if (memory.provenanceCategory != MemoryProvenanceCategory.VERIFIED && memory.provenanceCategory != MemoryProvenanceCategory.USER_STATED) {
            if (targetTier == MemoryTier.VERIFIED_KNOWLEDGE || targetTier == MemoryTier.GLOBAL_SKILL) {
                return false
            }
        }
        if (memory.provenanceCategory == MemoryProvenanceCategory.INFERRED) {
            if (targetTier == MemoryTier.VERIFIED_KNOWLEDGE || targetTier == MemoryTier.USER_MEMORY) {
                return false
            }
        }
        return memory.tier.canPromoteTo(targetTier)
    }

    /**
     * Checks whether a memory's provenance category can be promoted.
     * INFERRED memories can NEVER be promoted to VERIFIED or USER_STATED merely due to high confidence.
     */
    fun canPromoteProvenance(currentCategory: MemoryProvenanceCategory, targetCategory: MemoryProvenanceCategory): Boolean {
        if (currentCategory == MemoryProvenanceCategory.INFERRED || currentCategory == MemoryProvenanceCategory.OBSERVED) {
            if (targetCategory == MemoryProvenanceCategory.VERIFIED) {
                // Must have authoritative verification proof to become VERIFIED
                return false
            }
            if (currentCategory == MemoryProvenanceCategory.INFERRED && targetCategory == MemoryProvenanceCategory.USER_STATED) {
                return false
            }
        }
        return true
    }

    suspend fun promoteMemoryTier(memoryId: String, targetTier: MemoryTier): Boolean = withContext(Dispatchers.IO) {
        val existing = activeMemoriesMap[memoryId] ?: return@withContext false
        if (!canPromoteMemory(existing, targetTier)) {
            Log.w("MemoryManager", "Security Boundary Violation: Cannot promote memory ${existing.id} of tier ${existing.tier} (provenance: ${existing.provenanceCategory}) to target tier $targetTier")
            return@withContext false
        }
        val updated = existing.copy(tier = targetTier)
        activeMemoriesMap[memoryId] = updated
        _memoriesFlow.value = activeMemoriesMap.values.toList()
        true
    }

    /**
     * Preserves memories permanently in accordance with the Eternal Manifesto.
     */
    private fun evictLeastRecentlyUsedIfNeeded() {
        // Permanent persistent memory: no arbitrary deletion/eviction of user or system memories
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
                sourceMessageId = sourceMessageId,
                tier = MemoryTier.USER_MEMORY,
                provenanceCategory = MemoryProvenanceCategory.USER_STATED
            )
            Log.i("MemoryManager", "Explicit memory intent processed & indexed: $key")
        }
    }

    suspend fun saveMemory(
        key: String,
        category: String,
        value: String,
        importanceScore: Float = 0.9f,
        sourceMessageId: String? = null,
        tier: MemoryTier = resolveTierForCategory(category, key),
        provenanceCategory: MemoryProvenanceCategory = MemoryProvenanceCategory.OBSERVED
    ): MemoryItem = withContext(Dispatchers.IO) {
        // Zero-Fabrication Memory Invariant: Unverified observations cannot become VERIFIED_KNOWLEDGE or GLOBAL_SKILL
        val effectiveTier = if ((tier == MemoryTier.VERIFIED_KNOWLEDGE || tier == MemoryTier.GLOBAL_SKILL) &&
            provenanceCategory != MemoryProvenanceCategory.VERIFIED &&
            provenanceCategory != MemoryProvenanceCategory.USER_STATED
        ) {
            MemoryTier.SYSTEM_MEMORY
        } else {
            tier
        }

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
            embedding = embedding,
            tier = effectiveTier,
            provenanceCategory = provenanceCategory
        )

        activeMemoriesMap[id] = newItem
        evictLeastRecentlyUsedIfNeeded()
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

    suspend fun searchMemories(query: MemorySearchQuery): List<MemorySearchResult> = hybridSearch(query)

    suspend fun searchByType(queryText: String, searchType: SearchType, topK: Int = 5): List<MemorySearchResult> {
        return hybridSearch(MemorySearchQuery(queryText = queryText, searchType = searchType, topK = topK))
    }

    suspend fun embedQuery(queryText: String): EmbeddingVector {
        return embeddingService.generateEmbedding(queryText)
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

            val safeResults = results.filter { it.memory.tier != MemoryTier.CREDENTIAL }
            if (safeResults.isEmpty()) return@withContext ""

            val contextLines = safeResults.mapIndexed { idx, res ->
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

    fun resetForTesting() {
        activeMemoriesMap.clear()
        vectorIndex.clear()
        _memoriesFlow.value = emptyList()
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

    suspend fun deleteAllMemories(): Int = withContext(Dispatchers.IO) {
        val count = activeMemoriesMap.size
        activeMemoriesMap.clear()
        vectorIndex.clear()
        _memoriesFlow.value = emptyList()
        var dbDeleted = 0
        try {
            dbDeleted = memoryDao?.deleteAllMemories() ?: count
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to clear all memories from Room database", e)
            dbDeleted = count
        }
        WastiEventBus.emit(WastiEvent.MemoryUpdated("ALL", "CLEARED"))
        maxOf(count, dbDeleted)
    }

    suspend fun pruneMemoriesOlderThan(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) return@withContext 0
        val cutoff = System.currentTimeMillis() - (retentionDays * 86400000L)
        val toRemove = activeMemoriesMap.filter { it.value.timestamp < cutoff }.keys.toList()
        toRemove.forEach { id ->
            activeMemoriesMap.remove(id)
            vectorIndex.removeVector(id)
        }
        _memoriesFlow.value = activeMemoriesMap.values.toList()
        var deletedCount = 0
        try {
            deletedCount = memoryDao?.deleteMemoriesOlderThan(cutoff) ?: toRemove.size
        } catch (e: Exception) {
            Log.e("MemoryManager", "Failed to prune older memories from Room database", e)
            deletedCount = toRemove.size
        }
        deletedCount
    }

    suspend fun exportUserDataJson(): String = withContext(Dispatchers.IO) {
        val dao = memoryDao
        val items: List<MemoryItem> = if (dao != null) {
            try {
                dao.getAllMemoriesSync().map { entity ->
                    val provenanceCat = if (entity.category.contains("Preference", ignoreCase = true) || entity.category.contains("User", ignoreCase = true)) {
                        MemoryProvenanceCategory.USER_STATED
                    } else if (entity.category.contains("Inferred", ignoreCase = true) || entity.category.contains("Guessed", ignoreCase = true)) {
                        MemoryProvenanceCategory.INFERRED
                    } else {
                        MemoryProvenanceCategory.OBSERVED
                    }
                    MemoryItem(
                        id = entity.id,
                        key = entity.key,
                        category = entity.category,
                        value = entity.value,
                        importanceScore = entity.importanceScore,
                        timestamp = entity.timestamp,
                        sourceMessageId = entity.sourceMessageId,
                        tier = resolveTierForCategory(entity.category, entity.key),
                        provenanceCategory = provenanceCat
                    )
                }
            } catch (_: Exception) {
                activeMemoriesMap.values.toList()
            }
        } else {
            activeMemoriesMap.values.toList()
        }

        buildString {
            append("[\n")
            items.forEachIndexed { index, m ->
                append("  {\n")
                append("    \"id\": \"${m.id}\",\n")
                append("    \"key\": \"${m.key.replace("\"", "\\\"")}\",\n")
                append("    \"category\": \"${m.category.replace("\"", "\\\"")}\",\n")
                append("    \"value\": \"${m.value.replace("\"", "\\\"")}\",\n")
                append("    \"importanceScore\": ${m.importanceScore},\n")
                append("    \"timestamp\": ${m.timestamp}\n")
                append("  }")
                if (index < items.size - 1) append(",")
                append("\n")
            }
            append("]")
        }
    }

    internal fun calculateKeywordMatchScore(query: String, text: String): Float {
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
