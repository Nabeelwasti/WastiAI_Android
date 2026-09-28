package com.example.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.memory.MemoryItem
import com.example.data.core.TestCategory
import com.example.data.core.TestTier
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemoryItem as ActiveMemoryItem
import com.example.data.memory.model.MemoryProvenanceCategory
import com.example.data.memory.model.MemoryTier
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@TestCategory(
    tier = TestTier.UNIT,
    description = "Tests for MemoryStore compatibility adapter and MemoryItem mapping to active MemoryManager"
)
class MemoryStoreTest {

    private lateinit var context: Context
    private lateinit var memoryStore: MemoryStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        memoryStore = MemoryStore(context)
        MemoryManager.resetForTesting()

        // Clean up any lingering test files in filesDir
        val legacyFile = File(context.filesDir, "assistant_memory.json")
        if (legacyFile.exists()) legacyFile.delete()
        val archiveFile = File(context.filesDir, "assistant_memory.json.migrated")
        if (archiveFile.exists()) archiveFile.delete()
    }

    @Test
    fun testMemoryItemBidirectionalMapping() {
        val originalItem = MemoryItem(
            id = 42L,
            content = "User prefers Kotlin multiplatform",
            type = "Preferences",
            timestamp = 1700000000000L,
            tier = MemoryTier.USER_MEMORY,
            provenanceCategory = MemoryProvenanceCategory.USER_STATED
        )

        val activeModel: ActiveMemoryItem = originalItem.toActiveMemoryItem()
        assertEquals("mem_compat_42", activeModel.id)
        assertEquals("Preferences", activeModel.key)
        assertEquals("Preferences", activeModel.category)
        assertEquals("User prefers Kotlin multiplatform", activeModel.value)
        assertEquals(1700000000000L, activeModel.timestamp)
        assertEquals(MemoryTier.USER_MEMORY, activeModel.tier)
        assertEquals(MemoryProvenanceCategory.USER_STATED, activeModel.provenanceCategory)

        val mappedBack = MemoryItem.fromActiveMemoryItem(activeModel)
        assertEquals(42L, mappedBack.id)
        assertEquals("User prefers Kotlin multiplatform", mappedBack.content)
        assertEquals("Preferences", mappedBack.type)
        assertEquals(1700000000000L, mappedBack.timestamp)
        assertEquals(MemoryTier.USER_MEMORY, mappedBack.tier)
        assertEquals(MemoryProvenanceCategory.USER_STATED, mappedBack.provenanceCategory)
    }

    @Test
    fun testMemoryStoreDelegatesPersistenceAndRetrieval() = runBlocking {
        memoryStore.addMemorySuspend(
            type = "Development",
            content = "Repository uses Clean Architecture and AndroidX",
            tier = MemoryTier.PROJECT_MEMORY,
            provenance = MemoryProvenanceCategory.VERIFIED
        )

        val recentItems = memoryStore.recentMemoryItems(limit = 10)
        assertEquals(1, recentItems.size)
        assertEquals("Repository uses Clean Architecture and AndroidX", recentItems[0].content)
        assertEquals("Development", recentItems[0].type)

        val recentJson = memoryStore.recent(limit = 10)
        assertEquals(1, recentJson.size)
        assertEquals("Repository uses Clean Architecture and AndroidX", recentJson[0].getString("content"))
        assertEquals("Development", recentJson[0].getString("type"))
    }

    @Test
    fun testMigrateLegacyFileIfExists() = runBlocking {
        // Write mock legacy JSON file
        val legacyFile = File(context.filesDir, "assistant_memory.json")
        val array = JSONArray().apply {
            put(JSONObject().apply {
                put("timestamp", 1690000000000L)
                put("type", "LegacyFact")
                put("content", "Historical assistant note 1")
            })
            put(JSONObject().apply {
                put("timestamp", 1690000001000L)
                put("type", "LegacyFact")
                put("content", "Historical assistant note 2")
            })
        }
        FileOutputStream(legacyFile).bufferedWriter().use { it.write(array.toString()) }
        assertTrue(legacyFile.exists())

        // Run migration
        val migratedCount = memoryStore.migrateLegacyFileIfExists()
        assertEquals(2, migratedCount)

        // Legacy file should be archived/renamed
        assertFalse(legacyFile.exists())
        val archiveFile = File(context.filesDir, "assistant_memory.json.migrated")
        assertTrue(archiveFile.exists())

        // Verify active MemoryManager received the memories
        val recentMemories = memoryStore.recentMemoryItems(limit = 10)
        assertEquals(2, recentMemories.size)
        val contents = recentMemories.map { it.content }
        assertTrue(contents.contains("Historical assistant note 1"))
        assertTrue(contents.contains("Historical assistant note 2"))
    }
}
