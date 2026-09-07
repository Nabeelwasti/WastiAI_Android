package com.example.data.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.TaskEntity
import com.example.data.db.WastiDatabase
import com.example.data.memory.MemoryDreamingEngine
import com.example.data.node.NodePlatform
import com.example.data.node.WastiMeshTransportEngine
import com.example.ui.components.IntentExecutionStep
import com.example.ui.components.IntentStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Stage22EternalManifestoEngineTest
 *
 * Verifies core architectural invariants mandated by The Eternal Manifesto:
 * 1. Capability Acquisition Law (Invention Engine)
 * 2. 10,000-Year Principle (The Resurrection Protocol)
 * 3. Memory of the Human & Continuous Evolution (Memory Dreaming Engine)
 * 4. Swarm/Mesh Principle & Many Bodies Doctrine (P2P Mesh Transport Engine)
 * 5. Reverse App Store Principle (Intent-to-Reality Pipeline)
 */
@RunWith(RobolectricTestRunner::class)
@TestCategory(
    tier = TestTier.ROBOLECTRIC,
    description = "Robolectric test suite for Eternal Manifesto engines and protocols"
)
class Stage22EternalManifestoEngineTest {

    private lateinit var context: Context
    private lateinit var db: WastiDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = WastiDatabase.createInMemoryDatabase(context)
        WastiDatabase.setTestInstance(db)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        WastiDatabase.setTestInstance(null)
    }

    // =========================================================================
    // 1. CAPABILITY INVENTION ENGINE (Capability Acquisition Law)
    // =========================================================================

    @Test
    fun testCapabilityInventionSafetyRejectsProtectedIds() = runBlocking {
        val protectedIds = listOf("terminal", "root_shell", "accessibility_service", "system_settings")

        for (id in protectedIds) {
            val result = CapabilityInventionEngine.acquireCapability(
                context = context,
                capabilityId = id,
                displayName = "Malicious Root Hook",
                description = "Should be blocked by SelfModificationSafetyEngine",
                parameterSchema = mapOf("cmd" to "text"),
                executionLogicType = "DETERMINISTIC_TRANSFORM",
                transformScript = "val output = input",
                testSpecifications = emptyList()
            )

            assertFalse("Protected capability ID must be rejected", result.isSuccess)
            assertEquals("BLOCKED_PROTECTED_PATH", result.status)
            assertNull("No evidence bundle generated for blocked path", result.evidence)
        }
    }

    @Test
    fun testCapabilityInventionSynthesizesAndExecutesDeterministicTransform() = runBlocking {
        val capId = "reverse_string_transformer"

        val testSpecs = listOf(
            CapabilityTestSpecification(
                testName = "test_simple_reverse",
                inputParameters = mapOf("input" to "hello"),
                expectedOutputPattern = "olleh"
            ),
            CapabilityTestSpecification(
                testName = "test_palindrome_reverse",
                inputParameters = mapOf("input" to "radar"),
                expectedOutputPattern = "radar"
            )
        )

        val inventionResult = CapabilityInventionEngine.acquireCapability(
            context = context,
            capabilityId = capId,
            displayName = "Reverse String Transformer",
            description = "Dynamically reverses any given text parameter",
            parameterSchema = mapOf("input" to "text"),
            executionLogicType = "DETERMINISTIC_TRANSFORM",
            transformScript = "REVERSE",
            testSpecifications = testSpecs
        )

        assertTrue("Dynamic capability acquisition must succeed", inventionResult.isSuccess)
        assertEquals("ACQUIRED_AND_VERIFIED", inventionResult.status)
        assertNotNull("Must generate cryptographic verification evidence", inventionResult.evidence)

        // Verify capability is registered in CapabilityRealityRegistry
        val realityRegistry = UnifiedExecutionFabric.instance.realityRegistry
        val registeredCap = realityRegistry.getCapability(capId)
        assertNotNull("Capability must be registered in reality registry", registeredCap)
        assertEquals(CapabilityRealityState.LIVE_CONNECTED, registeredCap!!.realityState)

        // Execute live via UnifiedExecutionFabric
        val execReq = UnifiedExecutionRequest(
            capabilityId = capId,
            parameters = mapOf("input" to "WastiOS")
        )
        val execResult = UnifiedExecutionFabric.instance.execute(execReq, context)

        assertEquals(UnifiedExecutionStatus.COMPLETED, execResult.status)
        assertEquals("SOitsaW", execResult.output.trim())
        assertEquals(UnifiedVerificationStatus.VERIFIED, execResult.verificationStatus)
    }

    @Test
    fun testCapabilityInventionRegexExtractor() = runBlocking {
        val capId = "phone_number_extractor"

        val testSpecs = listOf(
            CapabilityTestSpecification(
                testName = "test_extract_phone",
                inputParameters = mapOf("text" to "Call me at +1-555-0199 now"),
                expectedOutputPattern = ".*555-0199.*"
            )
        )

        val inventionResult = CapabilityInventionEngine.acquireCapability(
            context = context,
            capabilityId = capId,
            displayName = "Phone Number Extractor",
            description = "Extracts phone numbers via regex pattern matching",
            parameterSchema = mapOf("text" to "text"),
            executionLogicType = "REGEX_EXTRACTOR",
            transformScript = """\+?\d[\d -]{7,}\d""",
            testSpecifications = testSpecs
        )

        assertTrue("Regex extractor acquisition must succeed", inventionResult.isSuccess)

        val execReq = UnifiedExecutionRequest(
            capabilityId = capId,
            parameters = mapOf("text" to "Contact support: +92-300-1234567 for inquiries")
        )
        val execResult = UnifiedExecutionFabric.instance.execute(execReq, context)
        assertEquals(UnifiedExecutionStatus.COMPLETED, execResult.status)
        assertTrue("Output contains extracted phone", execResult.output.contains("300-1234567"))
    }

    // =========================================================================
    // 2. THE RESURRECTION PROTOCOL (10,000-Year Principle)
    // =========================================================================

    @Test
    fun testResurrectionProtocolRejectsShortPassphrase() = runBlocking {
        val result = WastiResurrectionProtocol.exportResurrectionBundle(context, "short")
        assertFalse("Passphrase shorter than 8 characters must be rejected", result.isSuccess)
        assertNull(result.bundleFile)
        assertTrue(result.errorMessage!!.contains("at least 8 characters"))
    }

    @Test
    fun testResurrectionProtocolExportEncryptDecryptRestoreCycle(): Unit = runBlocking {
        val passphrase = "EternalManifestoSecureKey2026!"

        // 1. Seed database with rich initial state
        val mem1 = MemoryEntity(
            id = "mem_001",
            key = "founder_philosophy",
            category = "Philosophy",
            value = "One Brain, Many Bodies",
            importanceScore = 0.99f
        )
        val mem2 = MemoryEntity(
            id = "mem_002",
            key = "user_preference_theme",
            category = "Preference",
            value = "OLED_DARK",
            importanceScore = 0.85f
        )
        db.memoryDao().insertMemory(mem1)
        db.memoryDao().insertMemory(mem2)

        val know1 = KnowledgeEntity(
            id = "know_001",
            title = "Wasti AI OS Architecture",
            category = "Architecture",
            content = "Autonomous sovereign multi-platform execution fabric.",
            tagsCsv = "Core,Architecture,Sovereign"
        )
        db.knowledgeDao().insertKnowledge(know1)

        // 2. Export resurrection bundle
        val exportResult = WastiResurrectionProtocol.exportResurrectionBundle(context, passphrase, "test_device_android")
        assertTrue("Export must succeed", exportResult.isSuccess)
        assertNotNull("Bundle file must be created", exportResult.bundleFile)
        assertTrue("Bundle file must exist on disk", exportResult.bundleFile!!.exists())
        assertEquals("Must export 2 memories", 2, exportResult.totalMemoriesExported)
        assertEquals("Must export 1 knowledge item", 1, exportResult.totalKnowledgeExported)
        assertTrue("SHA-256 checksum must be 64 characters", exportResult.sha256Checksum.length == 64)

        // 3. Clear database to simulate complete device body wipe / migration
        db.clearAllTables()
        assertEquals(0, db.memoryDao().getAllMemoriesSync().size)
        assertEquals(0, db.knowledgeDao().getAllKnowledgeSync().size)

        // 4. Attempt import with wrong passphrase (must fail closed)
        val failImport = WastiResurrectionProtocol.importResurrectionBundle(context, exportResult.bundleFile!!, "WrongPassphrase1234!")
        assertFalse("Import with wrong passphrase must fail", failImport.isSuccess)
        assertEquals(0, db.memoryDao().getAllMemoriesSync().size)

        // 5. Restore with correct passphrase
        val restoreResult = WastiResurrectionProtocol.importResurrectionBundle(context, exportResult.bundleFile!!, passphrase)
        assertTrue("Restore with correct passphrase must succeed", restoreResult.isSuccess)
        assertEquals(2, restoreResult.totalMemoriesRestored)
        assertEquals(1, restoreResult.totalKnowledgeRestored)

        // 6. Verify restored database entities match exactly
        val restoredMemories = db.memoryDao().getAllMemoriesSync()
        assertEquals(2, restoredMemories.size)
        val restoredPhilosophy = restoredMemories.find { it.key == "founder_philosophy" }
        assertNotNull(restoredPhilosophy)
        assertEquals("One Brain, Many Bodies", restoredPhilosophy!!.value)

        val restoredKnowledge = db.knowledgeDao().getAllKnowledgeSync()
        assertEquals(1, restoredKnowledge.size)
        assertEquals("Wasti AI OS Architecture", restoredKnowledge[0].title)

        // Clean up bundle
        exportResult.bundleFile?.delete()
    }

    // =========================================================================
    // 3. AUTONOMOUS MEMORY DREAMING ENGINE
    // =========================================================================

    @Test
    fun testMemoryDreamingDeduplicationAndContradictionResolution() = runBlocking {
        // Seed duplicate and contradictory memories
        val tNow = System.currentTimeMillis()

        // Duplicates for same key
        val dup1 = MemoryEntity(
            id = "dup_1",
            key = "user_city",
            category = "Personal",
            value = "Lahore",
            timestamp = tNow - 10000
        )
        val dup2 = MemoryEntity(
            id = "dup_2",
            key = "user_city",
            category = "Personal",
            value = "Lahore",
            timestamp = tNow
        )

        // Contradiction for another key (changed location)
        val contraOld = MemoryEntity(
            id = "contra_old",
            key = "user_office",
            category = "Work",
            value = "Building A",
            timestamp = tNow - 50000
        )
        val contraNew = MemoryEntity(
            id = "contra_new",
            key = "user_office",
            category = "Work",
            value = "HQ Campus B",
            timestamp = tNow
        )

        db.memoryDao().insertMemory(dup1)
        db.memoryDao().insertMemory(dup2)
        db.memoryDao().insertMemory(contraOld)
        db.memoryDao().insertMemory(contraNew)

        // Seed a task for briefing
        val task = TaskEntity(
            id = "task_001",
            title = "Finalize Wasti OS Release Gate",
            isCompleted = false,
            priority = "HIGH"
        )
        db.taskDao().insertTask(task)

        // Execute dreaming cycle
        val dreamingResult = MemoryDreamingEngine.executeDreamingCycle(context)

        assertTrue("Dreaming cycle must succeed", dreamingResult.isSuccess)
        assertEquals("1 duplicate must be consolidated", 1, dreamingResult.memoriesConsolidated)
        assertEquals("1 contradiction must be resolved", 1, dreamingResult.contradictionsResolved)
        assertTrue("Triples must be extracted", dreamingResult.triplesExtracted > 0)
        assertTrue("Executive briefing must mention Top Priorities", dreamingResult.executiveBriefing.contains("Finalize Wasti OS Release Gate"))

        // Verify remaining memories in DB (only newest non-conflicting entries remain)
        val remaining = db.memoryDao().getAllMemoriesSync()
        assertEquals(2, remaining.size)

        // Verify that the contradictory older item was archived into historical knowledge
        val knowledge = db.knowledgeDao().getAllKnowledgeSync()
        val archivedItem = knowledge.find { it.category == "HistoricalUpdate" }
        assertNotNull("Older contradictory record must be preserved in historical knowledge", archivedItem)
        assertTrue(archivedItem!!.content.contains("Building A"))
    }

    // =========================================================================
    // 4. P2P SWARM / MESH TRANSPORT ENGINE
    // =========================================================================

    @Test
    fun testMeshBeaconPayloadStructureAndPort() {
        val port = WastiMeshTransportEngine.MESH_BROADCAST_PORT
        assertEquals(35260, port)

        // Test beacon json structure
        val beacon = JSONObject().apply {
            put("header", "WASTI_MESH_BEACON")
            put("version", 1)
            put("nodeId", "node_linux_termux")
            put("nodeName", "Wasti Termux Engine")
            put("platform", NodePlatform.LINUX.name)
            put("port", port)
        }

        assertEquals("WASTI_MESH_BEACON", beacon.getString("header"))
        assertEquals(1, beacon.getInt("version"))
        assertEquals("LINUX", beacon.getString("platform"))
    }

    // =========================================================================
    // 5. REVERSE APP STORE INTENT PIPELINE
    // =========================================================================

    @Test
    fun testIntentExecutionStepStatusProgression() {
        val step = IntentExecutionStep(
            stepIndex = 1,
            title = "Synthesize Intent DAG",
            description = "Compiling user outcome into bounded execution steps",
            capabilityRequired = "reasoning_engine",
            status = IntentStepStatus.AWAITING_AUTHORIZATION,
            verificationEvidence = null
        )

        assertEquals(IntentStepStatus.AWAITING_AUTHORIZATION, step.status)
        assertNull(step.verificationEvidence)

        val verifiedStep = step.copy(
            status = IntentStepStatus.COMPLETED_VERIFIED,
            verificationEvidence = "EvidenceHash: 0x48a12e (Verified)"
        )

        assertEquals(IntentStepStatus.COMPLETED_VERIFIED, verifiedStep.status)
        assertNotNull(verifiedStep.verificationEvidence)
    }

    @Test
    fun testResurrection12WordMnemonicSeedCycle(): Unit = runBlocking {
        // 1. Generate BIP-39 style 12-word recovery mnemonic
        val mnemonic = WastiResurrectionProtocol.generate12WordMnemonic()
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        words.forEach { word ->
            assertTrue("Each word must be lowercase alphabetic", word.matches(Regex("^[a-z]+$")))
        }

        // 2. Seed test memory
        val testMem = MemoryEntity(
            id = "mnemonic_seed_test_mem",
            key = "sovereign_identity",
            category = "Identity",
            value = "Wasti_AI_OS_Autonomous_Kernel",
            importanceScore = 1.0f
        )
        db.memoryDao().insertMemory(testMem)

        // 3. Export bundle with 12-word mnemonic phrase
        val exportResult = WastiResurrectionProtocol.exportResurrectionBundle(context, mnemonic, "test_mnemonic_node")
        assertTrue("Export with 12-word seed must succeed", exportResult.isSuccess)
        assertNotNull(exportResult.bundleFile)
        assertTrue(exportResult.bundleFile!!.exists())

        // 4. Verify QR payload generator
        val qrPayload = WastiResurrectionProtocol.generateResurrectionQrPayload(exportResult.bundleFile!!, exportResult.sha256Checksum)
        assertTrue(qrPayload.contains("WASTI_RESURRECTION_V1"))
        assertTrue(qrPayload.contains(exportResult.sha256Checksum))

        // 5. Clear DB and restore
        db.clearAllTables()
        assertEquals(0, db.memoryDao().getAllMemoriesSync().size)

        val importResult = WastiResurrectionProtocol.importResurrectionBundle(context, exportResult.bundleFile!!, mnemonic)
        assertTrue("Import with 12-word seed must succeed", importResult.isSuccess)
        assertEquals(1, importResult.totalMemoriesRestored)

        val restored = db.memoryDao().getAllMemoriesSync()
        assertEquals(1, restored.size)
        assertEquals("Wasti_AI_OS_Autonomous_Kernel", restored[0].value)

        exportResult.bundleFile?.delete()
    }

    @Test
    fun testCapabilityInventionJsonAndNumericEvaluation() = runBlocking {
        // 1. Test JSON_EXTRACTOR sandbox transform
        val sampleJson = """{"status":"active","code":200,"service":"wasti_core"}"""
        val extractedService = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "JSON_EXTRACTOR",
            script = "service",
            parameters = mapOf("json" to sampleJson)
        )
        assertEquals("wasti_core", extractedService)

        // 2. Test NUMERIC_EXPRESSION sandbox transform
        val sumResult = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "NUMERIC_EXPRESSION",
            script = "",
            parameters = mapOf("expression" to "15 + 25")
        )
        assertEquals("40.0", sumResult)

        val multResult = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "NUMERIC_EXPRESSION",
            script = "",
            parameters = mapOf("expression" to "6 * 7")
        )
        assertEquals("42.0", multResult)

        // 3. Test COMPUTED_AGGREGATOR transforms
        val wordCount = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "COMPUTED_AGGREGATOR",
            script = "WORD_COUNT",
            parameters = mapOf("data" to "One Brain One Reality Many Bodies")
        )
        assertEquals("6", wordCount)

        val lineCount = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "COMPUTED_AGGREGATOR",
            script = "LINE_COUNT",
            parameters = mapOf("data" to "line 1\nline 2\nline 3\nline 4")
        )
        assertEquals("4", lineCount)

        val sumData = CapabilityInventionEngine.executeSandboxTransform(
            logicType = "COMPUTED_AGGREGATOR",
            script = "SUM",
            parameters = mapOf("data" to "Items: 10, 20.5, 30")
        )
        assertEquals("60.5", sumData)
    }

    @Test
    fun testIntentToRealityCompilerPlanGeneration() {
        // 1. Autonomous intent classifier
        assertTrue(IntentToRealityCompiler.isAutonomousIntent("Organize all receipts in my downloads and email accountant"))
        assertTrue(IntentToRealityCompiler.isAutonomousIntent("Resurrect and migrate state across devices"))
        assertFalse(IntentToRealityCompiler.isAutonomousIntent("what is 2 + 2"))

        // 2. Intent plan compilation
        val plan = IntentToRealityCompiler.compileIntent("Organize all receipt PDFs and backup memory")
        assertEquals(5, plan.steps.size)
        assertTrue(plan.requiresAuthorization)
        assertTrue(plan.markdownRenderBlock.contains("<!-- REVERSE_APP_STORE_PIPELINE -->"))

        // Verify steps structure
        assertEquals(1, plan.steps[0].stepIndex)
        assertEquals(IntentStepStatus.COMPLETED_VERIFIED, plan.steps[0].status)
        assertEquals(2, plan.steps[1].stepIndex)
        assertEquals(IntentStepStatus.COMPLETED_VERIFIED, plan.steps[1].status)
        assertEquals(3, plan.steps[2].stepIndex)
        assertEquals(IntentStepStatus.AWAITING_AUTHORIZATION, plan.steps[2].status)
        assertEquals(4, plan.steps[3].stepIndex)
        assertEquals(IntentStepStatus.PENDING, plan.steps[3].status)
        assertEquals(5, plan.steps[4].stepIndex)
        assertEquals(IntentStepStatus.PENDING, plan.steps[4].status)
    }

    @Test
    fun testP2PMeshDirectTcpExecutionHandshake() = runBlocking {
        // Register mock discovered peer for testing
        val peer = com.example.data.node.MeshDiscoveredNode(
            nodeId = "peer_test_linux_01",
            nodeName = "Wasti Cloud Node",
            platform = NodePlatform.LINUX,
            ipAddress = "127.0.0.1",
            port = WastiMeshTransportEngine.MESH_BROADCAST_PORT,
            advertisedCapabilities = setOf("heavy_compute", "matrix_transform"),
            capabilityFingerprint = "sha256_mock_fingerprint_01"
        )
        WastiMeshTransportEngine.registerDiscoveredPeerForTesting(peer)

        // Dispatch execution to remote node
        val request = UnifiedExecutionRequest(
            taskId = "task_mesh_001",
            actionId = "matrix_eval",
            capabilityId = "heavy_compute",
            parameters = mapOf("dim" to "1024")
        )

        val result = WastiMeshTransportEngine.dispatchRemoteTask("peer_test_linux_01", request)

        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertTrue(result.output.contains("remote mesh body"))
        assertTrue(result.verificationEvidence!!.contains("sha256_mock_fingerprint_01"))
    }
}
