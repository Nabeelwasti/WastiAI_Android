package com.example.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.MemoryStore
import com.example.data.db.MemoryDao
import com.example.data.db.MemoryEntity
import com.example.data.db.WastiDatabase
import com.example.data.memory.model.MemoryItem
import com.example.data.memory.model.MemoryProvenanceCategory
import com.example.data.memory.model.MemoryTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryPersistenceAndMigrationTest {

    private lateinit var context: Context
    private lateinit var database: WastiDatabase
    private lateinit var memoryDao: MemoryDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, WastiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryDao = database.memoryDao()
        MemoryManager.resetForTesting()
        MemoryManager.initialize(memoryDao)
    }

    @After
    fun tearDown() {
        database.close()
        MemoryManager.resetForTesting()
    }

    @Test
    fun testAuthoritativeTierAndProvenancePersistedAndReloaded() = runBlocking {
        // Save memory with explicit USER_MEMORY tier and USER_STATED provenance
        val saved = MemoryManager.saveMemory(
            key = "Special User Habit",
            category = "Preferences",
            value = "User drinks green tea every morning at 7am",
            importanceScore = 0.95f,
            tier = MemoryTier.USER_MEMORY,
            provenanceCategory = MemoryProvenanceCategory.USER_STATED
        )

        assertEquals(MemoryTier.USER_MEMORY, saved.tier)
        assertEquals(MemoryProvenanceCategory.USER_STATED, saved.provenanceCategory)

        // Query Room directly
        val entityFromDb = memoryDao.getMemoryByKey("Special User Habit")
        assertNotNull(entityFromDb)
        assertEquals("USER_MEMORY", entityFromDb!!.tier)
        assertEquals("USER_STATED", entityFromDb.provenanceCategory)

        // Reset in-memory cache and reinitialize to simulate process restart / reload
        MemoryManager.resetForTesting()
        MemoryManager.initialize(memoryDao)

        // Wait for async load
        var loaded: List<MemoryItem> = emptyList()
        for (i in 1..20) {
            loaded = MemoryManager.memoriesFlow.value
            if (loaded.isNotEmpty()) break
            kotlinx.coroutines.delay(20)
        }

        assertEquals(1, loaded.size)
        val reloadedItem = loaded.first()
        assertEquals("Special User Habit", reloadedItem.key)
        assertEquals(MemoryTier.USER_MEMORY, reloadedItem.tier)
        assertEquals(MemoryProvenanceCategory.USER_STATED, reloadedItem.provenanceCategory)
    }

    @Test
    fun testDatabaseWriteFailureThrowsTruthfullyAndDoesNotPretendSuccess() = runBlocking {
        // Close database to force subsequent writes to fail
        database.close()

        try {
            MemoryManager.saveMemory(
                key = "Failing Key",
                category = "Test",
                value = "Failing Value"
            )
            fail("Expected exception when writing to closed database")
        } catch (e: Exception) {
            // Truthful persistence: exception is propagated rather than swallowed
            assertTrue("Exception message or class must indicate closed db or error", e.message != null || e is IllegalStateException)
        }
    }

    @Test
    fun testLosslessExportUserDataJsonWithComplexCharacters() = runBlocking {
        val complexValue = "Test with \"quotes\", \\backslashes\\, newline\nand\rcarriage-return,\ttab, and Unicode: 🚀 🌟 日本語."
        MemoryManager.saveMemory(
            key = "Complex \"Escaped\" Key \\ \n \t",
            category = "EdgeCases \"Cat\"",
            value = complexValue,
            importanceScore = 0.88f,
            tier = MemoryTier.PROJECT_MEMORY,
            provenanceCategory = MemoryProvenanceCategory.OBSERVED
        )

        val exportedJson = MemoryManager.exportUserDataJson()
        assertTrue("Exported JSON must not be blank", exportedJson.isNotBlank())

        // Parse back with standard JSON library to prove lossless integrity
        val parsedArray = JSONArray(exportedJson)
        assertEquals(1, parsedArray.length())
        val obj = parsedArray.getJSONObject(0)
        assertEquals("Complex \"Escaped\" Key \\ \n \t", obj.getString("key"))
        assertEquals("EdgeCases \"Cat\"", obj.getString("category"))
        assertEquals(complexValue, obj.getString("value"))
        assertEquals("PROJECT_MEMORY", obj.getString("tier"))
        assertEquals("OBSERVED", obj.getString("provenanceCategory"))
    }

    @Test
    fun testLegacyMigrationPreservesCleanAndMalformedRecordsWithoutDataLoss() = runBlocking {
        val memoryStore = MemoryStore(context)
        val legacyFile = File(context.filesDir, "assistant_memory.json")

        // Construct legacy file containing clean, blank content, and malformed non-object items
        val rawJson = """
        [
            {"type": "Legacy Preference", "content": "Prefers dark theme", "timestamp": 1000},
            {"type": "Blank Item", "content": "", "timestamp": 2000},
            "raw_string_unsupported_record",
            {"type": "Legacy Fact", "content": "Works on Android OS", "timestamp": 3000}
        ]
        """.trimIndent()
        legacyFile.writeText(rawJson)

        val totalAccounted = memoryStore.migrateLegacyFileIfExists()
        assertEquals(4, totalAccounted)

        // Confirm legacy file was archived
        assertFalse("Original legacy file should be moved/archived", legacyFile.exists())
        val archiveFile = File(context.filesDir, "assistant_memory.json.migrated")
        assertTrue("Archive file should exist", archiveFile.exists())

        // Confirm all 4 items are in memory / database
        val allMemories = memoryDao.getAllMemoriesSync()
        assertEquals(4, allMemories.size)

        // Verify clean records have IMPORTED provenance
        val darkTheme = allMemories.find { it.value == "Prefers dark theme" }
        assertNotNull(darkTheme)
        assertEquals("IMPORTED", darkTheme!!.provenanceCategory)

        // Verify malformed records were quarantined
        val quarantined = allMemories.filter { it.category == "Quarantined Legacy Migration" }
        assertEquals(2, quarantined.size)
        assertTrue(quarantined.any { it.value.contains("raw_string_unsupported_record") })
        assertTrue(quarantined.any { it.value.contains("Blank Item") })

        archiveFile.delete()
        Unit
    }
}
