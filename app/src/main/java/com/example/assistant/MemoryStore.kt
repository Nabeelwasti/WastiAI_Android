package com.example.assistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Simple file-backed memory store. This avoids heavy Room dependencies during initial iteration.
 * Memory items are stored as JSON array in filesDir/assistant_memory.json
 */
class MemoryStore(private val context: Context) {
    private val fileName = "assistant_memory.json"

    fun addMemory(type: String, content: String) {
        try {
            val list = readAllInternal()
            val obj = JSONObject()
            obj.put("timestamp", System.currentTimeMillis())
            obj.put("type", type)
            obj.put("content", content)
            list.put(obj)
            writeAllInternal(list)
        } catch (e: Exception) {
            Log.e("MemoryStore", "Failed to add memory", e)
        }
    }

    fun recent(limit: Int = 50): List<JSONObject> {
        val list = readAllInternal()
        val out = mutableListOf<JSONObject>()
        for (i in (list.length() - 1) downTo 0) {
            out.add(list.getJSONObject(i))
            if (out.size >= limit) break
        }
        return out
    }

    private fun readAllInternal(): JSONArray {
        try {
            val f = File(context.filesDir, fileName)
            if (!f.exists()) return JSONArray()
            val fis = FileInputStream(f)
            val txt = fis.bufferedReader().use { it.readText() }
            fis.close()
            return JSONArray(txt)
        } catch (e: Exception) {
            Log.e("MemoryStore", "read failed", e)
            return JSONArray()
        }
    }

    private fun writeAllInternal(arr: JSONArray) {
        try {
            val f = File(context.filesDir, fileName)
            val fos = FileOutputStream(f)
            fos.bufferedWriter().use { it.write(arr.toString()) }
            fos.close()
        } catch (e: Exception) {
            Log.e("MemoryStore", "write failed", e)
        }
    }
}
