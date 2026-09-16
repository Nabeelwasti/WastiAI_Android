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

@RunWith(RobolectricTestRunner::class)
@TestCategory(tier = TestTier.ROBOLECTRIC, description = "Robolectric test suite for Eternal Manifesto engines and protocols")
class Stage22EternalManifestoEngineTest {
    private lateinit var context: Context
    private lateinit var db: WastiDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = WastiDatabase.createInMemoryDatabase(context)
        WastiDatabase.setTestInstance(db)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
    }
    @After fun tearDown() { WastiDatabase.setTestInstance(null) }

    @Test fun testCapabilityInventionSafetyRejectsProtectedIds() = runBlocking {
        for (id in listOf("terminal", "root_shell", "accessibility_service", "system_settings")) {
            val result = CapabilityInventionEngine.acquireCapability(context,id,"Malicious Root Hook","Should be blocked",mapOf("cmd" to "text"),"DETERMINISTIC_TRANSFORM","val output = input",emptyList())
            assertFalse(result.isSuccess); assertEquals("BLOCKED_PROTECTED_PATH",result.status); assertNull(result.evidence)
        }
    }

    @Test fun testCapabilityInventionSynthesizesAndExecutesDeterministicTransform() = runBlocking {
        val capId="reverse_string_transformer"
        val specs=listOf(
            CapabilityTestSpecification("test_simple_reverse",mapOf("input" to "hello"),"olleh"),
            CapabilityTestSpecification("test_palindrome_reverse",mapOf("input" to "radar"),"radar")
        )
        val result=CapabilityInventionEngine.acquireCapability(context,capId,"Reverse String Transformer","Dynamically reverses text",mapOf("input" to "text"),"DETERMINISTIC_TRANSFORM","REVERSE",specs)
        assertTrue(result.isSuccess)
        assertEquals("ACQUIRED_UNVERIFIED",result.status)
        assertNotNull(result.evidence)
        val reality=UnifiedExecutionFabric.instance.realityRegistry.getCapability(capId)
        assertNotNull(reality)
        assertEquals(CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,reality!!.realityState)
        val exec=UnifiedExecutionFabric.instance.execute(UnifiedExecutionRequest(capabilityId=capId,parameters=mapOf("input" to "WastiOS")),context)
        assertEquals(UnifiedExecutionStatus.COMPLETED,exec.status)
        assertEquals("SOitsaW",exec.output.trim())
        assertEquals(UnifiedVerificationStatus.UNVERIFIED,exec.verificationStatus)
    }

    @Test fun testCapabilityInventionRegexExtractor() = runBlocking {
        val capId="phone_number_extractor"
        val result=CapabilityInventionEngine.acquireCapability(context,capId,"Phone Number Extractor","Extracts phone numbers",mapOf("text" to "text"),"REGEX_EXTRACTOR","""\+?\d[\d -]{7,}\d""",listOf(CapabilityTestSpecification("test_extract_phone",mapOf("text" to "Call me at +1-555-0199 now"),".*555-0199.*")))
        assertTrue(result.isSuccess)
        val exec=UnifiedExecutionFabric.instance.execute(UnifiedExecutionRequest(capabilityId=capId,parameters=mapOf("text" to "Contact support: +92-300-1234567 for inquiries")),context)
        assertEquals(UnifiedExecutionStatus.COMPLETED,exec.status)
        assertEquals(UnifiedVerificationStatus.UNVERIFIED,exec.verificationStatus)
        assertTrue(exec.output.contains("300-1234567"))
    }

    @Test fun testResurrectionProtocolRejectsShortPassphrase() = runBlocking {
        val result=WastiResurrectionProtocol.exportResurrectionBundle(context,"short")
        assertFalse(result.isSuccess); assertNull(result.bundleFile); assertTrue(result.errorMessage!!.contains("at least 8 characters"))
    }

    @Test fun testResurrectionProtocolExportEncryptDecryptRestoreCycle(): Unit = runBlocking {
        db.memoryDao().insertMemory(MemoryEntity("mem_001","founder_philosophy","Philosophy","One Brain, Many Bodies",importanceScore=0.99f))
        db.memoryDao().insertMemory(MemoryEntity("mem_002","user_preference_theme","Preference","OLED_DARK",importanceScore=0.85f))
        db.knowledgeDao().insertKnowledge(KnowledgeEntity("know_001","Wasti AI OS Architecture","Architecture","Autonomous sovereign multi-platform execution fabric.","Core,Architecture,Sovereign"))
        val pass="EternalManifestoSecureKey2026!"
        val export=WastiResurrectionProtocol.exportResurrectionBundle(context,pass,"test_device_android")
        assertTrue(export.isSuccess); assertNotNull(export.bundleFile); assertTrue(export.bundleFile!!.exists()); assertEquals(2,export.totalMemoriesExported); assertEquals(1,export.totalKnowledgeExported); assertEquals(64,export.sha256Checksum.length)
        db.clearAllTables(); assertEquals(0,db.memoryDao().getAllMemoriesSync().size); assertEquals(0,db.knowledgeDao().getAllKnowledgeSync().size)
        val bad=WastiResurrectionProtocol.importResurrectionBundle(context,export.bundleFile!!,"WrongPassphrase1234!"); assertFalse(bad.isSuccess); assertEquals(0,db.memoryDao().getAllMemoriesSync().size)
        val restored=WastiResurrectionProtocol.importResurrectionBundle(context,export.bundleFile!!,pass); assertTrue(restored.isSuccess); assertEquals(2,restored.totalMemoriesRestored); assertEquals(1,restored.totalKnowledgeRestored)
        assertEquals("One Brain, Many Bodies",db.memoryDao().getAllMemoriesSync().find { it.key=="founder_philosophy" }!!.value)
        assertEquals("Wasti AI OS Architecture",db.knowledgeDao().getAllKnowledgeSync()[0].title)
        export.bundleFile?.delete()
    }

    @Test fun testMemoryDreamingDeduplicationAndContradictionResolution() = runBlocking {
        val now=System.currentTimeMillis()
        db.memoryDao().insertMemory(MemoryEntity("dup_1","user_city","Personal","Lahore",timestamp=now-10000)); db.memoryDao().insertMemory(MemoryEntity("dup_2","user_city","Personal","Lahore",timestamp=now))
        db.memoryDao().insertMemory(MemoryEntity("contra_old","user_office","Work","Building A",timestamp=now-50000)); db.memoryDao().insertMemory(MemoryEntity("contra_new","user_office","Work","HQ Campus B",timestamp=now))
        db.taskDao().insertTask(TaskEntity("task_001","Finalize Wasti OS Release Gate",isCompleted=false,priority="HIGH"))
        val result=MemoryDreamingEngine.executeDreamingCycle(context)
        assertTrue(result.isSuccess); assertEquals(1,result.memoriesConsolidated); assertEquals(1,result.contradictionsResolved); assertTrue(result.triplesExtracted>0); assertTrue(result.executiveBriefing.contains("Finalize Wasti OS Release Gate"))
        assertEquals(2,db.memoryDao().getAllMemoriesSync().size); val archived=db.knowledgeDao().getAllKnowledgeSync().find { it.category=="HistoricalUpdate" }; assertNotNull(archived); assertTrue(archived!!.content.contains("Building A"))
    }

    @Test fun testMeshBeaconPayloadStructureAndPort() {
        assertEquals(35260,WastiMeshTransportEngine.MESH_BROADCAST_PORT)
        val beacon=JSONObject().apply { put("header","WASTI_MESH_BEACON"); put("version",1); put("nodeId","node_linux_termux"); put("nodeName","Wasti Termux Engine"); put("platform",NodePlatform.LINUX.name); put("port",WastiMeshTransportEngine.MESH_BROADCAST_PORT) }
        assertEquals("WASTI_MESH_BEACON",beacon.getString("header")); assertEquals(1,beacon.getInt("version")); assertEquals("LINUX",beacon.getString("platform"))
    }

    @Test fun testIntentExecutionStepStatusProgression() {
        val step=IntentExecutionStep(1,"Synthesize Intent DAG","Compiling user outcome into bounded execution steps","reasoning_engine",IntentStepStatus.AWAITING_AUTHORIZATION,null)
        assertEquals(IntentStepStatus.AWAITING_AUTHORIZATION,step.status); assertNull(step.verificationEvidence)
        val verified=step.copy(status=IntentStepStatus.COMPLETED_VERIFIED,verificationEvidence="EvidenceHash: 0x48a12e (Verified)")
        assertEquals(IntentStepStatus.COMPLETED_VERIFIED,verified.status); assertNotNull(verified.verificationEvidence)
    }

    @Test fun testResurrection12WordMnemonicSeedCycle(): Unit = runBlocking {
        val mnemonic=WastiResurrectionProtocol.generate12WordMnemonic(); assertEquals(12,mnemonic.split(" ").size); mnemonic.split(" ").forEach { assertTrue(it.matches(Regex("^[a-z]+$"))) }
        db.memoryDao().insertMemory(MemoryEntity("mnemonic_seed_test_mem","sovereign_identity","Identity","Wasti_AI_OS_Autonomous_Kernel",importanceScore=1.0f))
        val export=WastiResurrectionProtocol.exportResurrectionBundle(context,mnemonic,"test_mnemonic_node"); assertTrue(export.isSuccess); assertNotNull(export.bundleFile); assertTrue(export.bundleFile!!.exists())
        val qr=WastiResurrectionProtocol.generateResurrectionQrPayload(export.bundleFile!!,export.sha256Checksum); assertTrue(qr.contains("WASTI_RESURRECTION_V1")); assertTrue(qr.contains(export.sha256Checksum))
        db.clearAllTables(); val imported=WastiResurrectionProtocol.importResurrectionBundle(context,export.bundleFile!!,mnemonic); assertTrue(imported.isSuccess); assertEquals(1,imported.totalMemoriesRestored); assertEquals("Wasti_AI_OS_Autonomous_Kernel",db.memoryDao().getAllMemoriesSync()[0].value); export.bundleFile?.delete()
    }

    @Test fun testCapabilityInventionJsonAndNumericEvaluation() = runBlocking {
        assertEquals("wasti_core",CapabilityInventionEngine.executeSandboxTransform("JSON_EXTRACTOR","service",mapOf("json" to """{"status":"active","code":200,"service":"wasti_core"}""")))
        assertEquals("40.0",CapabilityInventionEngine.executeSandboxTransform("NUMERIC_EXPRESSION","",mapOf("expression" to "15 + 25")))
        assertEquals("42.0",CapabilityInventionEngine.executeSandboxTransform("NUMERIC_EXPRESSION","",mapOf("expression" to "6 * 7")))
        assertEquals("6",CapabilityInventionEngine.executeSandboxTransform("COMPUTED_AGGREGATOR","WORD_COUNT",mapOf("data" to "One Brain One Reality Many Bodies")))
        assertEquals("4",CapabilityInventionEngine.executeSandboxTransform("COMPUTED_AGGREGATOR","LINE_COUNT",mapOf("data" to "line 1\nline 2\nline 3\nline 4")))
        assertEquals("60.5",CapabilityInventionEngine.executeSandboxTransform("COMPUTED_AGGREGATOR","SUM",mapOf("data" to "Items: 10, 20.5, 30")))
    }

    @Test fun testIntentToRealityCompilerPlanGeneration() {
        assertTrue(IntentToRealityCompiler.isAutonomousIntent("Organize all receipts in my downloads and email accountant")); assertTrue(IntentToRealityCompiler.isAutonomousIntent("Resurrect and migrate state across devices")); assertFalse(IntentToRealityCompiler.isAutonomousIntent("what is 2 + 2"))
        val plan=IntentToRealityCompiler.compileIntent("Organize all receipt PDFs and backup memory")
        assertEquals(5,plan.steps.size); assertTrue(plan.requiresAuthorization); assertTrue(plan.markdownRenderBlock.contains("<!-- REVERSE_APP_STORE_PIPELINE -->"))
        assertEquals(1,plan.steps[0].stepIndex); assertEquals(IntentStepStatus.PENDING,plan.steps[0].status); assertEquals(2,plan.steps[1].stepIndex); assertEquals(IntentStepStatus.PENDING,plan.steps[1].status); assertEquals(3,plan.steps[2].stepIndex); assertEquals(IntentStepStatus.AWAITING_AUTHORIZATION,plan.steps[2].status); assertEquals(4,plan.steps[3].stepIndex); assertEquals(IntentStepStatus.PENDING,plan.steps[3].status); assertEquals(5,plan.steps[4].stepIndex); assertEquals(IntentStepStatus.PENDING,plan.steps[4].status)
    }

    @Test fun testP2PMeshDirectTcpExecutionHandshake() = runBlocking {
        val peer=com.example.data.node.MeshDiscoveredNode("peer_test_linux_01","Wasti Cloud Node",NodePlatform.LINUX,"127.0.0.1",WastiMeshTransportEngine.MESH_BROADCAST_PORT,setOf("heavy_compute","matrix_transform"),"sha256_mock_fingerprint_01")
        WastiMeshTransportEngine.registerDiscoveredPeerForTesting(peer)
        val request=UnifiedExecutionRequest(taskId="task_mesh_001",actionId="matrix_eval",capabilityId="heavy_compute",parameters=mapOf("dim" to "1024"))
        val result=WastiMeshTransportEngine.dispatchRemoteTask("peer_test_linux_01",request)
        assertEquals(UnifiedExecutionStatus.FAILED,result.status)
        assertEquals(UnifiedVerificationStatus.UNVERIFIED,result.verificationStatus)
        assertTrue(result.output.isEmpty())
    }
}
