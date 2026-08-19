package com.example.data.agent.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentEventBusAndRuntimeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testEventBusEmissionAndCollection() = runBlocking {
        val bus = AgentEventBus(replay = 10, extraBufferCapacity = 50)
        val event = AgentEvent.TaskCreated(taskId = TaskId("task_test_01"), prompt = "Test Prompt")

        val emitted = bus.tryEmit(event)
        assertTrue(emitted)

        val collected = bus.events.first()
        assertEquals(TaskId("task_test_01"), collected.taskId)
    }

    @Test
    fun testEmergencyStopBlocksTaskCreation() {
        val workspaceManager = WorkspaceManager(context)
        val taskManager = AgentTaskManager()
        val eventBus = AgentEventBus()
        val emergencyStopController = WastiEmergencyStopController()
        val capabilityRegistry = WastiCapabilityRegistry()
        val permissionModel = WastiPermissionModel()
        val auditLogger = WastiAuditLogger()
        val securityEngine = WastiSecurityPolicyEngine(workspaceManager, emergencyStopController)
        val toolRegistry = AgentToolRegistry()

        val toolRouter = WastiAgentToolRouter(
            registry = toolRegistry,
            securityPolicy = securityEngine,
            permissionModel = permissionModel,
            emergencyStop = emergencyStopController,
            capabilityRegistry = capabilityRegistry,
            auditLogger = auditLogger
        )

        val modelProvider = RuleBasedAgentModelProvider()
        val planner = AgentPlanner(modelProvider, toolRegistry, capabilityRegistry)
        val errorAnalyzer = ErrorAnalyzer()
        val selfCorrectionEngine = SelfCorrectionEngine(modelProvider, toolRouter, workspaceManager)

        val loopEngine = AgenticLoopEngine(
            taskManager = taskManager,
            eventBus = eventBus,
            planner = planner,
            toolRouter = toolRouter,
            errorAnalyzer = errorAnalyzer,
            correctionEngine = selfCorrectionEngine,
            emergencyStopController = emergencyStopController
        )

        val runtime = WastiAgentRuntimeImpl(
            taskManager = taskManager,
            eventBus = eventBus,
            loopEngine = loopEngine,
            emergencyStopController = emergencyStopController,
            toolRouter = toolRouter
        )

        // Submit task when running normally
        val taskRes1 = runtime.submitTaskResult("Test valid prompt", ExecutionMode.AUTONOMOUS)
        assertTrue(taskRes1.isSuccess)
        val taskId = taskRes1.getOrThrow().taskId
        assertNotNull(runtime.getTask(taskId))

        // Trigger emergency stop
        runtime.triggerEmergencyStop("Security violation detected")
        assertTrue(emergencyStopController.isEmergencyStopped)

        // Submit task while stopped -> MUST FAIL
        val taskRes2 = runtime.submitTaskResult("Another prompt", ExecutionMode.AUTONOMOUS)
        assertTrue(taskRes2.isFailure)
        assertTrue(taskRes2.exceptionOrNull()?.message?.contains("EMERGENCY_STOP_ACTIVE") == true)
    }
}
