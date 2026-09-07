package com.example.data.memory

import android.content.Context
import android.util.Log
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * [The Eternal Manifesto: The Memory of the Human & Continuous Evolution Engine]
 *
 * "Memory of the Human: preserve knowledge, goals, preferences, decisions and creativity by consent.
 * Continuous Evolution Engine: use -> observe -> improve -> test -> verify -> deploy -> repeat."
 *
 * Autonomous Sleep-Time Memory Consolidation ("Dreaming"):
 * 1. Deduplicates short-term episodic records.
 * 2. Resolves temporal contradictions (latest truth prevails).
 * 3. Builds a structured Subject-Predicate-Object Knowledge Graph.
 * 4. Generates a proactive Executive Morning Briefing.
 */

data class KnowledgeTriple(
    val subject: String,
    val predicate: String,
    val obj: String,
    val confidence: Float = 0.95f,
    val timestamp: Long = System.currentTimeMillis()
)

data class MemoryDreamingResult(
    val isSuccess: Boolean,
    val memoriesScanned: Int,
    val memoriesConsolidated: Int,
    val contradictionsResolved: Int,
    val triplesExtracted: Int,
    val executiveBriefing: String,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

object MemoryDreamingEngine {

    private const val TAG = "MemoryDreamingEngine"

    @Volatile
    private var lastDreamingResult: MemoryDreamingResult? = null

    fun getLastDreamingResult(): MemoryDreamingResult? = lastDreamingResult

    /**
     * Executes the four-phase cognitive dreaming consolidation cycle.
     */
    suspend fun executeDreamingCycle(context: Context): MemoryDreamingResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Initiating autonomous Memory Dreaming consolidation cycle...")

        val db = WastiDatabase.getDatabase(context)
        val memories = db.memoryDao().getAllMemoriesSync()

        var consolidatedCount = 0
        var contradictionsCount = 0
        val extractedTriples = mutableListOf<KnowledgeTriple>()

        // -------------------------------------------------------------
        // Phase 1 & 2: Deduplication and Contradiction Resolution
        // -------------------------------------------------------------
        val keyGroups = memories.groupBy { it.key.trim().lowercase() }

        for ((key, group) in keyGroups) {
            if (group.size > 1) {
                // Sort by timestamp descending (newest first)
                val sorted = group.sortedByDescending { it.timestamp }
                val newest = sorted.first()
                val olderItems = sorted.drop(1)

                // Check for duplicate vs contradictory values
                for (older in olderItems) {
                    if (older.value.trim().equals(newest.value.trim(), ignoreCase = true)) {
                        // Exact duplicate: prune older item and consolidate importance
                        db.memoryDao().deleteMemoryById(older.id)
                        consolidatedCount++
                    } else {
                        // Contradiction / Update detected: archive older item into historical knowledge
                        db.memoryDao().deleteMemoryById(older.id)
                        val archivedKnowledge = KnowledgeEntity(
                            id = "history_${older.id}",
                            title = "Historical Memory: $key",
                            category = "HistoricalUpdate",
                            content = "Previous value: '${older.value}' updated to '${newest.value}' on ${newest.timestamp}",
                            tagsCsv = "Historical,Archived,MemoryUpdate",
                            dateAdded = System.currentTimeMillis()
                        )
                        db.knowledgeDao().insertKnowledge(archivedKnowledge)
                        contradictionsCount++
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // Phase 3: Semantic Knowledge Graph Triples Construction
        // -------------------------------------------------------------
        val remainingMemories = db.memoryDao().getAllMemoriesSync()

        for (mem in remainingMemories) {
            val triples = extractTriplesFromText(mem.key, mem.value)
            extractedTriples.addAll(triples)

            // Index key triples into Knowledge Graph table if not already indexed
            for (triple in triples) {
                val tripleTitle = "${triple.subject} -> ${triple.predicate}"
                val knowledgeEntity = KnowledgeEntity(
                    id = "triple_${UUID.nameUUIDFromBytes("${triple.subject}_${triple.predicate}_${triple.obj}".toByteArray())}",
                    title = tripleTitle,
                    category = "KnowledgeGraphTriple",
                    content = "${triple.subject} ${triple.predicate} ${triple.obj} (confidence: ${(triple.confidence * 100).toInt()}%)",
                    tagsCsv = "GraphTriple,${triple.predicate},Autonomic",
                    dateAdded = System.currentTimeMillis()
                )
                db.knowledgeDao().insertKnowledge(knowledgeEntity)
            }
        }

        // -------------------------------------------------------------
        // Phase 4: Proactive Executive Morning Briefing Generation
        // -------------------------------------------------------------
        val allTasks = db.taskDao().getAllTasksSync()
        val pendingTasks = allTasks.filter { !it.isCompleted }
        val allProjects = db.projectDao().getAllProjectsSync()

        val briefingBuilder = StringBuilder()
        briefingBuilder.append("### Wasti AI OS • Daily Executive Situational Briefing\n\n")
        briefingBuilder.append("**Cognitive Reality Matrix:**\n")
        briefingBuilder.append("• **Active Memories**: ${remainingMemories.size} consolidated ($consolidatedCount duplicates pruned, $contradictionsCount updates resolved).\n")
        briefingBuilder.append("• **Semantic Triples**: ${extractedTriples.size} active knowledge graph connections.\n")
        briefingBuilder.append("• **Active Projects**: ${allProjects.size} tracked across systems.\n")
        briefingBuilder.append("• **Pending Missions**: ${pendingTasks.size} tasks awaiting execution.\n\n")

        if (pendingTasks.isNotEmpty()) {
            briefingBuilder.append("**Top Priorities for Today:**\n")
            pendingTasks.take(5).forEachIndexed { index, task ->
                briefingBuilder.append("${index + 1}. **${task.title}** [Priority: ${task.priority}]\n")
            }
            briefingBuilder.append("\n")
        }

        briefingBuilder.append("*Engineered to evolve. Safeguards active. Ready for executive intent.*")

        val executiveBriefingText = briefingBuilder.toString()
        val duration = System.currentTimeMillis() - startTime

        val result = MemoryDreamingResult(
            isSuccess = true,
            memoriesScanned = memories.size,
            memoriesConsolidated = consolidatedCount,
            contradictionsResolved = contradictionsCount,
            triplesExtracted = extractedTriples.size,
            executiveBriefing = executiveBriefingText,
            durationMs = duration
        )

        lastDreamingResult = result
        Log.i(TAG, "Memory Dreaming cycle completed in ${duration}ms: $consolidatedCount consolidated, $contradictionsCount resolved, ${extractedTriples.size} triples.")
        result
    }

    /**
     * Extracts formal (Subject, Predicate, Object) triples from structured key-value memories.
     */
    private fun extractTriplesFromText(key: String, value: String): List<KnowledgeTriple> {
        val triples = mutableListOf<KnowledgeTriple>()
        val cleanKey = key.trim()
        val cleanValue = value.trim()

        when {
            cleanKey.contains("prefer", ignoreCase = true) || cleanKey.contains("likes", ignoreCase = true) -> {
                triples.add(KnowledgeTriple("User", "prefers", cleanValue, 0.95f))
            }
            cleanKey.contains("location", ignoreCase = true) || cleanKey.contains("city", ignoreCase = true) -> {
                triples.add(KnowledgeTriple("User", "isLocatedIn", cleanValue, 0.98f))
            }
            cleanKey.contains("role", ignoreCase = true) || cleanKey.contains("job", ignoreCase = true) || cleanKey.contains("title", ignoreCase = true) -> {
                triples.add(KnowledgeTriple("User", "hasRole", cleanValue, 0.95f))
            }
            cleanKey.contains("goal", ignoreCase = true) || cleanKey.contains("mission", ignoreCase = true) -> {
                triples.add(KnowledgeTriple("WastiOS", "pursuesGoal", cleanValue, 0.92f))
            }
            cleanKey.contains("rule", ignoreCase = true) || cleanKey.contains("policy", ignoreCase = true) -> {
                triples.add(KnowledgeTriple("WastiOS", "enforcesPolicy", cleanValue, 0.99f))
            }
            else -> {
                // General relation
                triples.add(KnowledgeTriple(cleanKey, "isDefinedAs", cleanValue, 0.85f))
            }
        }
        return triples
    }
}
