package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.AgentEventBus
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.InMemoryAgentMemoryStore
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.tool.ToolDefinition
import com.example.data.tool.ToolRegistry
import com.example.data.tool.WastiTool
import com.example.data.workflow.AutonomousCapabilityOrchestrator
import com.example.data.workflow.CapabilityResolutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Stage 14: Self-Evolving Capability Engine, Autonomous Development & Execution Intelligence Tests
 * Verifies the autonomous self-evolution pipeline with canonical truth validation:
 * - Test A: Existing Capability Reuse (ToolRegistry & Native capabilities)
 * - Test B: Missing Capability Detection & Dynamic Design
 * - Test C: Security Policy Rejection on Dangerous Pattern
 * - Test D: Bounded Self-Correction Loop & Patch Application
 * - Test E: Bounded Retry Exhaustion & Rollback
 * - Test F: Capability registration & Unified Execution
 * - Test G: Emergency Stop Interruption
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage14SelfEvolvingCapabilityEngineTest {

    private lateinit var context: Context
    private lateinit var eventBus: AgentEventBus
    private lateinit var memoryStore: InMemoryAgentMemoryStore
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var orchestrator: AutonomousCapabilityOrchestrator
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val testWorkspaceDir = File(context.filesDir, "wasti_workspace")
        if (testWorkspaceDir.exists()) testWorkspaceDir.deleteRecursively()
        testWorkspaceDir.mkdirs()
        eventBus = AgentEventBus.getInstance()
        memoryStore = InMemoryAgentMemoryStore()
        emergencyStop = WastiEmergencyStopController()
        orchestrator = AutonomousCapabilityOrchestrator(
            context = context,
            eventBus = eventBus,
            memoryContract = memoryStore,
            emergencyStopController = emergencyStop
        )
    }

    @After
    fun tearDown() {
        try {
            val testWorkspaceDir = File(context.filesDir, "wasti_workspace")
            if (testWorkspaceDir.exists()) testWorkspaceDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    @Test
    fun testA_ExistingCapabilityReuse() = runTest(testDispatcher) {
        val existingToolId = "test_custom_converter"
        val mockTool = object : WastiTool {
            override val definition = ToolDefinition(
                id = existingToolId,
                name = "Test Custom Converter",
                category = "Testing",
                description = "Existing converter tool"
            )
            override suspend fun execute(parameters: Map<String, Any>): String = "converted"
        }
        ToolRegistry.registerTool(mockTool)
        val result = orchestrator.resolveCapability(existingToolId, "Convert temperature units")
        assertTrue(result is CapabilityResolutionResult.ExistingTool)
        val existingResult = result as CapabilityResolutionResult.ExistingTool
        assertEquals(existingToolId, existingResult.toolId)
        assertEquals("Test Custom Converter", existingResult.tool.definition.name)
        val nativeResult = orchestrator.resolveCapability("send_whatsapp")
        assertTrue(nativeResult is CapabilityResolutionResult.NativeCapability)
        assertEquals("send_whatsapp", (nativeResult as CapabilityResolutionResult.NativeCapability).capabilityId)
    }

    @Test
    fun testB_MissingCapabilityDetectionAndDesign() = runTest(testDispatcher) {
        val uniqueCapId = "auto_image_optimizer_${System.currentTimeMillis()}"
        val events = mutableListOf<AgentEvent>()
        val job = launch { eventBus.events.collect { events.add(it) } }
        val result = orchestrator.resolveCapability(uniqueCapId, "Dynamically optimize PNG and WebP assets")
        job.cancel()
        assertTrue(result is CapabilityResolutionResult.DynamicCreatedTool)
        val dynamicRes = result as CapabilityResolutionResult.DynamicCreatedTool
        assertTrue(dynamicRes.toolId.contains(uniqueCapId))
        assertTrue(dynamicRes.verificationEvidence.contains("exitCode=0") || dynamicRes.verificationEvidence.contains("passed") || dynamicRes.verificationEvidence.contains("WRE"))
        assertTrue(events.any { it is AgentEvent.CapabilityDesignStarted && it.capabilityId == uniqueCapId })
        assertTrue(events.any { it is AgentEvent.CapabilityBuildStarted && it.capabilityId == uniqueCapId })
        assertTrue(events.any { it is AgentEvent.CapabilityBuildCompleted && it.capabilityId == uniqueCapId && it.isSuccess })
        assertTrue(events.any { it is AgentEvent.CapabilityTestStarted && it.capabilityId == uniqueCapId })
        assertTrue(events.any { it is AgentEvent.CapabilityTestCompleted && it.capabilityId == uniqueCapId && it.isSuccess })
        assertTrue(events.any { it is AgentEvent.CapabilityVerificationStarted && it.capabilityId == uniqueCapId })
        assertTrue(events.any { it is AgentEvent.CapabilityVerified && it.capabilityId == uniqueCapId })
        assertTrue(events.any { it is AgentEvent.CapabilityPromoted && it.capabilityId == uniqueCapId })
    }

    @Test
    fun testC_SecurityRejectionOnDangerousPattern() = runTest(testDispatcher) {
        val dangerousCapId = "danger_cleaner_${System.currentTimeMillis()}"
        val dangerousScript = "#!/bin/sh\nrm -rf / --no-preserve-root\n"
        val events = mutableListOf<AgentEvent>()
        val job = launch { eventBus.events.collect { events.add(it) } }
        val result = orchestrator.resolveCapability(dangerousCapId, "Dangerous filesystem cleaner", scriptContentOverride = dangerousScript)
        job.cancel()
        assertTrue(result is CapabilityResolutionResult.SecurityBlocked)
        val blocked = result as CapabilityResolutionResult.SecurityBlocked
        assertTrue(blocked.reason.contains("Forbidden script pattern"))
        assertEquals(dangerousCapId, blocked.capabilityId)
        assertTrue(events.any { it is AgentEvent.SecurityBlocked })
        assertTrue(events.any { it is AgentEvent.CapabilityRejected && it.capabilityId == dangerousCapId })
    }

    @Test
    fun testD_BoundedSelfCorrectionAndPatchApplication() = runTest(testDispatcher) {
        val faultyCapId = "faulty_then_repaired_${System.currentTimeMillis()}"
        val events = mutableListOf<AgentEvent>()
        val job = launch { eventBus.events.collect { events.add(it) } }
        val result = orchestrator.resolveCapability(
            capabilityId = faultyCapId,
            description = "Faulty capability that recovers via self-correction",
            scriptContentOverride = "#!/bin/sh\nexit 1\n",
            maxCorrectionAttempts = 2
        )
        job.cancel()
        assertTrue(result is CapabilityResolutionResult.DynamicCreatedTool)
        assertTrue(events.any { it is AgentEvent.SelfCorrectionStarted })
        assertTrue(events.any { it is AgentEvent.SelfCorrectionCompleted && it.isFixed })
        assertTrue(events.any { it is AgentEvent.CapabilityPromoted })
    }

    @Test
    fun testE_BoundedRetryExhaustionAndRollback() = runTest(testDispatcher) {
        val unrecoverableCapId = "unrecoverable_${System.currentTimeMillis()}"
        val events = mutableListOf<AgentEvent>()
        val job = launch { eventBus.events.collect { events.add(it) } }
        val result = orchestrator.resolveCapability(
            capabilityId = unrecoverableCapId,
            description = "Unrecoverable script",
            scriptContentOverride = "#!/bin/sh\nexit 127\n",
            maxCorrectionAttempts = 0
        )
        job.cancel()
        assertTrue(result is CapabilityResolutionResult.ResolutionFailed)
        val failed = result as CapabilityResolutionResult.ResolutionFailed
        assertTrue(failed.reason.contains("failed") || failed.reason.contains("WRE"))
        assertTrue(events.any { it is AgentEvent.RollbackStarted })
        assertTrue(events.any { it is AgentEvent.RollbackCompleted && it.isSuccess })
        assertTrue(events.any { it is AgentEvent.CapabilityRejected && it.capabilityId == unrecoverableCapId })
    }

    @Test
    fun testF_CapabilityPromotionAndUnifiedExecution() = runTest(testDispatcher) {
        val promCapId = "dynamic_text_summarizer_${System.currentTimeMillis()}"
        val result = orchestrator.resolveCapability(promCapId, "Summarize text dynamically")
        assertTrue(result is CapabilityResolutionResult.DynamicCreatedTool)
        val dynamicCreated = result as CapabilityResolutionResult.DynamicCreatedTool
        val registeredTool = ToolRegistry.getTool(dynamicCreated.toolId)
        assertNotNull(registeredTool)
        val reality = UnifiedExecutionFabric.instance.realityRegistry.getCapabilityReality(dynamicCreated.toolId)
        assertNotNull(reality)
        assertEquals("DYNAMIC_WRE", reality.category)
        assertEquals(CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED, reality.realityState)
        val output = dynamicCreated.tool.execute(mapOf("arguments" to listOf("sample input text")))
        assertNotNull(output)
        assertTrue(output.contains("Executing") || output.isNotEmpty())
    }

    @Test
    fun testG_EmergencyStopInterruption() = runTest(testDispatcher) {
        val haltedCapId = "halted_cap_${System.currentTimeMillis()}"
        emergencyStop.triggerEmergencyStop("Manual test stop triggered")
        val result = orchestrator.resolveCapability(haltedCapId, "Capability triggered under emergency stop")
        assertTrue(result is CapabilityResolutionResult.ResolutionFailed)
        val failed = result as CapabilityResolutionResult.ResolutionFailed
        assertTrue(failed.reason.contains("Emergency stop"))
    }
}
