package com.example.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.device.WastiDeviceController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CapabilityRealityAndDeviceTest {

    private lateinit var context: Context
    private lateinit var fabric: UnifiedExecutionFabric
    private lateinit var registry: CapabilityRealityRegistry
    private lateinit var workspaceManager: WorkspaceManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        registry = CapabilityRealityRegistry()
        fabric = UnifiedExecutionFabric(realityRegistry = registry, appContext = context)
        workspaceManager = WorkspaceManager(context)
    }

    @Test
    fun testCapabilityRealityRegistryReport() {
        val report = registry.getSystemRealityReport()
        assertNotNull(report)
        assertTrue(report.isNotEmpty())
        
        val categories = report.map { it.category }.distinct()
        assertTrue(categories.contains("EXECUTION"))
        assertTrue(categories.contains("STORAGE"))
        assertTrue(categories.contains("SECURITY"))
    }

    @Test
    fun testUnifiedExecutionFabricWasmSandboxedExecution() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "TERMINAL",
            parameters = mapOf("action" to "execute_code", "language" to "wasm")
        )
        val result = fabric.execute(req, context)
        assertNotNull(result)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
        assertTrue(result.output.contains("WASM") || result.output.contains("Fuel"))
    }

    @Test
    fun testUnifiedExecutionFabricMemorySearch() = runBlocking {
        val req = UnifiedExecutionRequest(
            capabilityId = "MEMORY_SEARCH",
            parameters = mapOf("query" to "Wasti AI")
        )
        val result = fabric.execute(req, context)
        assertNotNull(result)
        assertEquals(UnifiedVerificationStatus.VERIFIED, result.verificationStatus)
        assertEquals(UnifiedExecutionStatus.COMPLETED, result.status)
    }

    @Test
    fun testDeviceControllerTruthfulStateWhenInactive() {
        val backResult = WastiDeviceController.performBack(context)
        assertFalse(backResult.success)
        assertEquals("SERVICE_INACTIVE", backResult.actionType)

        val homeResult = WastiDeviceController.performHome(context)
        assertFalse(homeResult.success)
        assertEquals("SERVICE_INACTIVE", homeResult.actionType)

        val typeResult = WastiDeviceController.typeText(context, "Test Message")
        assertFalse(typeResult.success)
        assertEquals("SERVICE_INACTIVE", typeResult.actionType)
    }

    @Test
    fun testWorkspaceManagerFileOperations() = runBlocking {
        val testFileName = "test_doc.txt"
        val writeRes = workspaceManager.writeFile(testFileName, "Hello Wasti AI")
        assertTrue(writeRes.isSuccess)

        val appendRes = workspaceManager.appendFile(testFileName, "\nAppended line")
        assertTrue(appendRes.isSuccess)

        val content = workspaceManager.readFile(testFileName).getOrNull()
        assertNotNull(content)
        assertTrue(content!!.contains("Hello Wasti AI"))
        assertTrue(content.contains("Appended line"))

        val meta = workspaceManager.inspectMetadata(testFileName).getOrNull()
        assertNotNull(meta)
        assertTrue(meta!!.sizeBytes > 0)
        assertFalse(meta.isDirectory)

        val searchRes = workspaceManager.searchFiles("doc")
        assertTrue(searchRes.isSuccess)
        assertTrue(searchRes.getOrNull()?.any { it.contains("test_doc.txt") } == true)

        val copyRes = workspaceManager.copyFile(testFileName, "test_doc_copy.txt")
        assertTrue(copyRes.isSuccess)

        val moveRes = workspaceManager.moveFile("test_doc_copy.txt", "test_doc_moved.txt")
        assertTrue(moveRes.isSuccess)

        workspaceManager.deleteFile(testFileName)
        workspaceManager.deleteFile("test_doc_moved.txt")
    }

    @Test
    fun testCapabilityPlannerGoalDeconstruction() {
        val planner = CapabilityPlanner(registry)
        val plan = planner.createPlan("Find syntax error and fix file MainActivity.kt")
        assertNotNull(plan)
        assertTrue(plan.nodes.isNotEmpty())
        assertTrue(plan.nodes.any { it.capabilityId == "files" })
        assertTrue(plan.nodes.any { it.capabilityId == "debug_project" })
    }

    @Test
    fun testVerificationPlannerCriteriaGeneration() {
        val planner = VerificationPlanner()
        val plan = planner.createVerificationPlan(
            actionId = "act_1",
            capabilityId = "files",
            actionName = "write_file",
            parameters = mapOf("filePath" to "test.py")
        )
        assertNotNull(plan)
        assertTrue(plan.criteria.any { it.type == VerificationCriterionType.FILE_EXISTS_AND_NONEMPTY })
    }

    @Test
    fun testRecoveryPlannerRemediation() {
        val planner = RecoveryPlanner(registry)
        val failedReq = UnifiedExecutionRequest(
            capabilityId = "device_control",
            parameters = mapOf("action" to "simulate_tap")
        )
        val failedRes = UnifiedExecutionResult(
            requestId = "req_1",
            capabilityId = "device_control",
            status = UnifiedExecutionStatus.AUTHENTICATION_REQUIRED,
            verificationStatus = UnifiedVerificationStatus.UNAVAILABLE,
            output = "Wasti Accessibility Service is inactive."
        )
        val recoveryPlan = planner.createRecoveryPlan(failedReq, failedRes)
        assertNotNull(recoveryPlan)
        assertEquals(RecoveryStrategy.REQUEST_USER_PERMISSION, recoveryPlan.recommendedStrategy)
    }

    @Test
    fun testWorkflowTemplateEngineInstantiation() {
        val engine = WorkflowTemplateEngine()
        val templates = engine.listTemplates()
        assertTrue(templates.isNotEmpty())

        val instantiated = engine.instantiateWorkflow(
            "tmpl_dev_patch",
            mapOf("projectId" to "proj_123", "filePath" to "app/src/main/Main.kt")
        )
        assertNotNull(instantiated)
        assertEquals(3, instantiated!!.steps.size)
        assertEquals("app/src/main/Main.kt", instantiated.steps[0].inputParameters["path"])
    }

    @Test
    fun testExplainabilityEngineAuditTrace() {
        val explainEngine = WastiExplainabilityEngine()
        val req = UnifiedExecutionRequest(
            capabilityId = "wasm_sandbox",
            parameters = mapOf("action" to "execute")
        )
        val res = UnifiedExecutionResult(
            requestId = "req_wasm",
            capabilityId = "wasm_sandbox",
            status = UnifiedExecutionStatus.COMPLETED,
            verificationStatus = UnifiedVerificationStatus.VERIFIED,
            output = "Calculated in sandbox"
        )
        val explanation = explainEngine.explainExecution("Run compute task", req, res)
        assertNotNull(explanation)
        assertTrue(explanation.isTruthfullyVerified)
        assertTrue(explanation.selectionRationale.contains("WASM"))
    }

    @Test
    fun testAutonomousSkillEvolutionLearningAndPromotion() = runBlocking {
        val evolutionEngine = AutonomousSkillEvolutionEngine(context)
        val plan = CapabilityPlanner(registry).createPlan("Run WASM math calculation")
        val audits = listOf(
            ExecutionAuditEntity(
                auditId = "audit_1",
                taskId = "task_learn_1",
                userGoal = "Run WASM math calculation",
                capabilityId = "wasm_sandbox",
                actionName = "execute",
                executionDestination = "LOCAL_WASM_SANDBOX",
                status = "COMPLETED",
                verificationStatus = "VERIFIED",
                verificationEvidence = "WASM compute verified",
                error = null,
                executionDurationMs = 15L
            )
        )

        val learnedSkill = evolutionEngine.learnFromExecution(
            originatingTaskId = "task_learn_1",
            userGoal = "Run WASM math calculation",
            planGraph = plan,
            executionAudits = audits
        )

        assertNotNull(learnedSkill)
        assertEquals("SANDBOX_EXPERIMENTAL", learnedSkill!!.promotionTier)
        assertEquals("ACTIVE", learnedSkill.operationalStatus)

        // Verify promotion
        evolutionEngine.recordExecutionOutcome(learnedSkill.skillId, wasVerified = true)
        evolutionEngine.recordExecutionOutcome(learnedSkill.skillId, wasVerified = true)
        evolutionEngine.recordExecutionOutcome(learnedSkill.skillId, wasVerified = true)
        evolutionEngine.recordExecutionOutcome(learnedSkill.skillId, wasVerified = true)
        evolutionEngine.recordExecutionOutcome(learnedSkill.skillId, wasVerified = true)

        val activeSkills = evolutionEngine.getActiveLearnedSkills()
        assertTrue(activeSkills.any { it.skillId == learnedSkill.skillId })
    }

    @Test
    fun testUniversalAutonomousExecutionLoopExecution() = runBlocking {
        val loop = UniversalAutonomousExecutionLoop(context)
        val outcome = loop.executeGoal("Search notes memory for Wasti AI")
        assertNotNull(outcome)
        assertTrue(outcome.isComplete)
        assertNotNull(outcome.plannedGraph)
        assertTrue(outcome.plannedGraph!!.nodes.isNotEmpty())
    }
}

