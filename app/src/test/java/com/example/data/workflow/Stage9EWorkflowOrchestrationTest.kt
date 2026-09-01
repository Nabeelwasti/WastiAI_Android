package com.example.data.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.tool.ToolRegistry
import com.example.data.wre.ExecutionRequest
import com.example.data.wre.ExecutionStatus
import com.example.data.wre.WreManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 9E: Unified Workflow Execution & Autonomous Capability Orchestration Tests
 * Covers Tests A through J:
 * - Test A: Simple Autonomous Workflow Lifecycle (Receive -> Plan -> Execute -> Observe -> Verify -> Result)
 * - Test B: Multi-Step Dependency Workflow Execution
 * - Test C: Existing Capability Discovery & Reuse
 * - Test D: Dynamic Capability Creation & Promotion (Self-Expanding Capability System)
 * - Test E: Bounded Retry & Truthful Failure Handling
 * - Test F: Security Policy & Emergency Stop Enforcement
 * - Test G: Workflow Cancellation
 * - Test H: Background Workflow Job Manager & Asynchronous Tracking
 * - Test I: Verification Truth & Evidence Capture
 * - Test J: Unified Execution Fabric & WRE Integration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage9EWorkflowOrchestrationTest {

    private lateinit var context: Context
    private lateinit var wreManager: WreManager
    private lateinit var capabilityOrchestrator: AutonomousCapabilityOrchestrator
    private lateinit var workflowEngine: UnifiedWorkflowEngine
    private lateinit var jobManager: WorkflowJobManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        com.example.data.di.WastiServiceLocator.init(context)
        wreManager = WreManager.getInstance(context)
        capabilityOrchestrator = AutonomousCapabilityOrchestrator(context)
        workflowEngine = UnifiedWorkflowEngine(context = context, capabilityOrchestrator = capabilityOrchestrator)
        jobManager = WorkflowJobManager(context = context, workflowEngine = workflowEngine)
    }

    @Test
    fun testA_SimpleWorkflowLifecycle() = runBlocking {
        val task = WorkflowTask(
            originalRequest = "Check device system status and memory"
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertNotNull(result)
        assertTrue(result.isSuccess)
        assertEquals(AutonomousWorkflowState.COMPLETED, task.currentState)
        assertTrue(result.stepsExecuted > 0)
        assertNotNull(result.verificationEvidence)
        assertTrue(task.observations.isNotEmpty())
        assertTrue(task.verificationResults.isNotEmpty())
    }

    @Test
    fun testB_MultiStepDependencyWorkflow() = runBlocking {
        val step1 = WorkflowStep(
            name = "Step 1 - Initialize Environment",
            capabilityId = "system_info",
            description = "Get device details"
        )
        val step2 = WorkflowStep(
            name = "Step 2 - Execute Post-Init Command",
            capabilityId = "terminal",
            parameters = mapOf("command" to "echo 'STEP_2_PROCESSED'"),
            dependsOnStepIds = listOf(step1.stepId),
            description = "Dependent terminal execution"
        )

        val task = WorkflowTask(
            originalRequest = "Execute multi-step dependent workflow",
            steps = mutableListOf(step1, step2),
            plan = WorkflowPlan(
                goal = "Multi-step test",
                interpretedIntent = "EXECUTE_DEPENDENCY_WORKFLOW",
                steps = listOf(step1, step2),
                requiredCapabilities = listOf("system_info", "terminal")
            )
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertTrue(result.isSuccess)
        assertEquals(2, result.stepsExecuted)
        assertEquals(WorkflowStepState.VERIFIED, step1.state)
        assertEquals(WorkflowStepState.VERIFIED, step2.state)
        assertTrue(step2.executionResult?.output?.contains("STEP_2_PROCESSED") == true)
    }

    @Test
    fun testC_ExistingCapabilityDiscoveryAndReuse() = runBlocking {
        // Resolve known existing tool (e.g. terminal or memory_search)
        val res = capabilityOrchestrator.resolveCapability("terminal", targetContext = context)

        assertTrue(res is CapabilityResolutionResult.ExistingTool || res is CapabilityResolutionResult.NativeCapability)
        // Ensure it didn't create a dynamic duplicate
        assertNull(ToolRegistry.getTool("wre_tool_terminal_duplicate"))
    }

    @Test
    fun testD_DynamicCapabilityCreationAndPromotion() = runBlocking {
        val uniqueCapName = "calc_stage9e_test_${System.currentTimeMillis()}"
        val scriptContent = buildString {
            appendLine("#!/bin/sh")
            appendLine("echo \"DYNAMIC_CALC_OK: 999\"")
        }

        // Resolve missing capability -> orchestrator will create, test, and register it
        val res = capabilityOrchestrator.resolveCapability(
            capabilityId = uniqueCapName,
            description = "Dynamic mathematical evaluator",
            scriptContentOverride = scriptContent,
            targetContext = context
        )

        assertTrue(res is CapabilityResolutionResult.DynamicCreatedTool)
        val dynamicRes = res as CapabilityResolutionResult.DynamicCreatedTool
        assertNotNull(dynamicRes.tool)
        assertTrue(dynamicRes.verificationEvidence.contains("exitCode=0"))

        // Verify registered in ToolRegistry
        val registeredTool = ToolRegistry.getTool(dynamicRes.toolId)
        assertNotNull(registeredTool)

        // Execute dynamic tool through UnifiedExecutionFabric
        val execReq = UnifiedExecutionRequest(
            capabilityId = dynamicRes.toolId,
            parameters = emptyMap()
        )
        val execRes = UnifiedExecutionFabric.instance.execute(execReq, context)
        assertEquals(UnifiedExecutionStatus.VERIFIED, execRes.status)
        assertTrue(execRes.output.contains("DYNAMIC_CALC_OK: 999"))
    }

    @Test
    fun testE_BoundedRetryAndTruthfulFailureHandling() = runBlocking {
        val failingStep = WorkflowStep(
            name = "Invalid Command Step",
            capabilityId = "terminal",
            parameters = mapOf("command" to "non_existent_binary_12345_xyz"),
            description = "Expect failure and bounded retry"
        )

        val task = WorkflowTask(
            originalRequest = "Execute failing step",
            steps = mutableListOf(failingStep),
            plan = WorkflowPlan(
                goal = "Test failure",
                interpretedIntent = "TEST_FAILURE",
                steps = listOf(failingStep),
                requiredCapabilities = listOf("terminal")
            )
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertFalse(result.isSuccess)
        assertEquals(AutonomousWorkflowState.FAILED, result.finalState)
        assertEquals(WorkflowStepState.FAILED, failingStep.state)
        assertTrue(failingStep.retryCount > 0)
        assertNotNull(result.error)
    }

    @Test
    fun testF_SecurityPolicyAndEmergencyStop() = runBlocking {
        val emergencyStop = WastiEmergencyStopController()
        emergencyStop.triggerEmergencyStop("Security breach detected in simulation")

        val securedEngine = UnifiedWorkflowEngine(
            context = context,
            emergencyStopController = emergencyStop,
            capabilityOrchestrator = capabilityOrchestrator
        )

        val task = WorkflowTask(
            originalRequest = "Perform action during emergency stop"
        )

        val result = securedEngine.executeWorkflow(task, context)

        assertFalse(result.isSuccess)
        assertEquals(AutonomousWorkflowState.BLOCKED, result.finalState)
        assertTrue(result.summary.contains("Emergency Stop", ignoreCase = true))
    }

    @Test
    fun testG_WorkflowCancellation() = runBlocking {
        val task = WorkflowTask(
            originalRequest = "Long running cancellation test",
            isCancelled = true
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertFalse(result.isSuccess)
        assertEquals(AutonomousWorkflowState.BLOCKED, result.finalState)
        assertTrue(result.summary.contains("cancelled", ignoreCase = true))
    }

    @Test
    fun testH_BackgroundWorkflowJobManager() = runBlocking {
        val task = WorkflowTask(
            originalRequest = "Background telemetry sync"
        )

        val job = jobManager.submitWorkflow(task, "Background Job 1", context)

        assertNotNull(job)
        assertEquals(task.taskId, job.taskId)
        assertTrue(job.status == WorkflowJobStatus.QUEUED || job.status == WorkflowJobStatus.RUNNING || job.status == WorkflowJobStatus.COMPLETED)

        // Wait brief moment for background execution to settle
        var checks = 0
        while (job.status != WorkflowJobStatus.COMPLETED && checks < 20) {
            kotlinx.coroutines.delay(100)
            checks++
        }

        assertEquals(WorkflowJobStatus.COMPLETED, job.status)
        assertNotNull(job.result)
        assertTrue(job.result!!.isSuccess)
    }

    @Test
    fun testI_VerificationTruthAndEvidence() = runBlocking {
        val step = WorkflowStep(
            name = "Truthful Verification Step",
            capabilityId = "terminal",
            parameters = mapOf("command" to "echo 'TRUTHFUL_VERIFICATION_EVIDENCE'"),
            description = "Verify real evidence"
        )

        val task = WorkflowTask(
            originalRequest = "Verify evidence collection",
            steps = mutableListOf(step),
            plan = WorkflowPlan(
                goal = "Verify truth",
                interpretedIntent = "VERIFY_TRUTH",
                steps = listOf(step),
                requiredCapabilities = listOf("terminal")
            )
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertTrue(result.isSuccess)
        assertNotNull(result.verificationEvidence)
        assertEquals(UnifiedVerificationStatus.VERIFIED, step.verificationStatus)
        assertNotNull(step.verificationEvidence)
        assertTrue(step.verificationEvidence!!.isNotBlank())
    }

    @Test
    fun testJ_UnifiedExecutionFabricAndWreIntegration() = runBlocking {
        // Execute WRE command through UnifiedExecutionFabric via WorkflowStep
        val step = WorkflowStep(
            name = "WRE Native Step",
            capabilityId = "terminal",
            parameters = mapOf("command" to "pwd"),
            description = "Check WRE workspace"
        )

        val task = WorkflowTask(
            originalRequest = "Execute WRE Native Command via Workflow",
            steps = mutableListOf(step),
            plan = WorkflowPlan(
                goal = "WRE Native",
                interpretedIntent = "WRE_EXECUTION",
                steps = listOf(step),
                requiredCapabilities = listOf("terminal")
            )
        )

        val result = workflowEngine.executeWorkflow(task, context)

        assertTrue(result.isSuccess)
        assertNotNull(step.executionResult)
        assertEquals("WastiNativeExecutionProvider", step.executionResult?.executor)
        assertEquals(UnifiedExecutionStatus.VERIFIED, step.executionResult?.status)
    }
}
