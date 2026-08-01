package com.example.assistant.memory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/**
 * MemoryMigrator copies existing JSON memory file (assistant_memory.json) into the new Room database.
 * It is safe to run multiple times — it will skip already-imported items if duplicates are detected by timestamp+content.
 */
object MemoryMigrator {
    private const val LEGACY_FILE = "assistant_memory.json"

    suspend fun migrateIfNeeded(context: Context, db: MemoryDatabase) {
        try {
            val f = File(context.filesDir, LEGACY_FILE)
            if (!f.exists()) {
                Log.i("MemoryMigrator", "No legacy memory file found; skipping migration")
                return
            }

            val txt = f.bufferedReader().use { it.readText() }
            val arr = JSONArray(txt)
            val dao = db.memoryDao()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ts = obj.optLong("timestamp", System.currentTimeMillis())
                val type = obj.optString("type", "note")
                val content = obj.optString("content", "")

                // Simple dedupe check: check recent items for same content+timestamp
                val recent = dao.recent(50)
                val exists = recent.any { it.content == content && it.timestamp == ts }
                if (!exists) {
                    val item = MemoryItem(timestamp = ts, type = type, content = content)
                    dao.insert(item)
                }
            }

            // Optionally rename legacy file to preserve backup
            val backup = File(context.filesDir, "$LEGACY_FILE.bak")
            f.renameTo(backup)

            Log.i("MemoryMigrator", "Migration complete: imported ${'$'}{arr.length()} items")
        } catch (e: Exception) {
            Log.e("MemoryMigrator", "Migration failed", e)
        }
    }
}
