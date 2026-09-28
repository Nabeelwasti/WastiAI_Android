package com.example.assistant

import android.content.Context
import android.util.Log
import com.example.assistant.memory.MemoryItem
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemoryProvenanceCategory
import com.example.data.memory.model.MemoryTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Compatibility and storage adapter for the assistant layer.
 * Delegates active persistence, indexing, and retrieval to the Room-backed MemoryManager
 * while supporting legacy file migration from earlier offline iterations.
 */
class MemoryStore(private val context: Context) {
    private val fileName = "assistant_memory.json"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Synchronous / fire-and-forget memory persistence delegating to MemoryManager.
     */
    fun addMemory(type: String, content: String) {
        scope.launch {
            addMemorySuspend(type, content)
        }
    }

    /**
     * Suspendable memory persistence delegating directly to active MemoryManager.
     */
    suspend fun addMemorySuspend(
        type: String,
        content: String,
        tier: MemoryTier = MemoryTier.USER_MEMORY,
        provenance: MemoryProvenanceCategory = MemoryProvenanceCategory.IMPORTED
    ) {
        try {
            MemoryManager.saveMemory(
                key = type.ifBlank { "Assistant Memory" },
                category = type.ifBlank { "General" },
                value = content,
                importanceScore = DEFAULT_IMPORTANCE,
                tier = tier,
                provenanceCategory = provenance
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist memory via MemoryManager", e)
        }
    }

    /**
     * Retrieves recent memories as a list of JSONObjects for legacy consumer compatibility,
     * sourced directly from the active MemoryManager.
     */
    fun recent(limit: Int = DEFAULT_LIMIT): List<JSONObject> {
        val items = recentMemoryItems(limit)
        return items.map { item ->
            JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("type", item.type)
                put("content", item.content)
            }
        }
    }

    /**
     * Retrieves recent assistant MemoryItems sourced directly from the active MemoryManager.
     */
    fun recentMemoryItems(limit: Int = DEFAULT_LIMIT): List<MemoryItem> {
        val activeMemories = MemoryManager.memoriesFlow.value
        val count = if (limit <= 0) DEFAULT_LIMIT else limit
        return activeMemories
            .takeLast(count)
            .reversed()
            .map { MemoryItem.fromActiveMemoryItem(it) }
    }

    /**
     * Checks if a legacy assistant_memory.json file exists on disk, parses its records,
     * imports them into the active MemoryManager, and archives the file cleanly.
     * Returns the count of successfully migrated memory items.
     */
    suspend fun migrateLegacyFileIfExists(): Int = withContext(Dispatchers.IO) {
        val legacyFile = File(context.filesDir, fileName)
        if (!legacyFile.exists()) {
            return@withContext 0
        }

        try {
            val jsonText = FileInputStream(legacyFile).bufferedReader().use { it.readText() }
            if (jsonText.isBlank()) {
                legacyFile.delete()
                return@withContext 0
            }

            val array = JSONArray(jsonText)
            var migratedCount = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val type = obj.optString("type", "General")
                val content = obj.optString("content", "")
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

                if (content.isNotBlank()) {
                    val compatItem = MemoryItem(
                        id = timestamp,
                        content = content,
                        type = type,
                        timestamp = timestamp,
                        tier = MemoryTier.USER_MEMORY,
                        provenanceCategory = MemoryProvenanceCategory.IMPORTED
                    )
                    MemoryManager.saveMemory(
                        key = compatItem.type,
                        category = compatItem.type,
                        value = compatItem.content,
                        importanceScore = DEFAULT_IMPORTANCE,
                        tier = compatItem.tier,
                        provenanceCategory = compatItem.provenanceCategory
                    )
                    migratedCount++
                }
            }

            // Archive the file after successful ingestion to avoid re-migration
            val archiveFile = File(context.filesDir, "$fileName.migrated")
            if (legacyFile.renameTo(archiveFile)) {
                Log.i(TAG, "Successfully migrated and archived $migratedCount legacy memory items.")
            } else {
                legacyFile.delete()
                Log.i(TAG, "Successfully migrated $migratedCount legacy memory items (file cleaned).")
            }

            migratedCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed during legacy memory migration", e)
            0
        }
    }

    companion object {
        private const val TAG = "MemoryStore"
        private const val DEFAULT_LIMIT = 50
        private const val DEFAULT_IMPORTANCE = 0.85f
    }
}

