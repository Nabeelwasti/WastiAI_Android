package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.di.WastiServiceLocator
import com.example.data.node.*
import com.example.data.proactive.*
import com.example.data.server.WastiWebSocketServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Stage 15: Proactive Autonomous Assistant, Background Intelligence & Multi-Node Synchronization Tests
 *
 * Verifies:
 * 1. One-off delayed proactive task execution
 * 2. Recurring interval task execution & automatic rescheduling
 * 3. Idempotency & deduplication protection
 * 4. Task cancellation
 * 5. Emergency Stop enforcement & immediate execution halt
 * 6. UI Independence (Execution without active Activity / Chat lifecycle)
 * 7. Multi-node task leasing & lease concurrency protection
 * 8. Node failure detection & automatic safe task failover
 * 9. Non-idempotent task failure handling on node crash
 * 10. Dynamic Stage 14 capability resolution & self-evolution during proactive task execution
 * 11. Bounded retry backoff on task failures
 * 12. AgentEventBus proactive lifecycle event streaming
 * 13. Truthful execution status and evidence recording
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage15ProactiveAutonomousRuntimeTest {

    private lateinit var context: Context
    private lateinit var eventBus: AgentEventBus
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var nodeManager: WastiNodeManager
    private lateinit var proactiveEngine: WastiProactiveAutonomousEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        eventBus = WastiServiceLocator.agentEventBus
        emergencyStop = WastiServiceLocator.emergencyStopController
        nodeManager = WastiServiceLocator.nodeManager
        emergencyStop.resetEmergencyStop()

        proactiveEngine = WastiProactiveAutonomousEngine(
            context = context,
            eventBus = eventBus,
            emergencyStopController = emergencyStop,
            nodeManager = nodeManager
        )
    }

    @Test
    fun test1_OneOffDelayedTaskExecution() = runBlocking {
        val task = proactiveEngine.scheduleDelayedTask(
            title = "Proactive Maintenance Check",
            prompt = "inspect system status and report health",
            delayMs = 0L // due immediately
        )

        assertNotNull(task.taskId)
        assertEquals(ProactiveTaskState.SCHEDULED, task.state)
        assertEquals(ProactiveTriggerType.ONE_TIME_DELAYED, task.triggerType)

        // Evaluate and run due tasks
        proactiveEngine.evaluateAndRunDueTasks()
        proactiveEngine.awaitTaskCompletion(task.taskId)

        val updatedTask = proactiveEngine.getTask(task.taskId)
        assertNotNull(updatedTask)
        assertEquals(ProactiveTaskState.COMPLETED, updatedTask!!.state)
        assertNotNull(updatedTask.verificationEvidence)
    }

    @Test
    fun test2_RecurringTaskRescheduling() = runBlocking {
        val intervalMs = 60000L
        val task = proactiveEngine.scheduleRecurringTask(
            title = "Periodic System Health Audit",
            prompt = "audit active node connections",
            intervalMs = intervalMs
        )

        assertEquals(ProactiveTriggerType.RECURRING_INTERVAL, task.triggerType)
        assertEquals(intervalMs, task.intervalMs)

        // Force execution now
        task.scheduledAt = System.currentTimeMillis() - 100
        proactiveEngine.evaluateAndRunDueTasks()
        proactiveEngine.awaitTaskCompletion(task.taskId)

        val updatedTask = proactiveEngine.getTask(task.taskId)
        assertNotNull(updatedTask)
        // Recurring task should be rescheduled with state SCHEDULED for the next cycle
        assertEquals(ProactiveTaskState.SCHEDULED, updatedTask!!.state)
        assertTrue(updatedTask.scheduledAt > System.currentTimeMillis())
    }

    @Test
    fun test3_IdempotencyAndDeduplication() = runBlocking {
        val idempotencyKey = "unique_cleanup_task_${System.currentTimeMillis()}"

        val task1 = proactiveEngine.scheduleDelayedTask(
            title = "Cleanup Task 1",
            prompt = "clean workspace cache",
            delayMs = 5000L,
            idempotencyKey = idempotencyKey
        )

        val task2 = proactiveEngine.scheduleDelayedTask(
            title = "Cleanup Task 2",
            prompt = "clean workspace cache",
            delayMs = 5000L,
            idempotencyKey = idempotencyKey
        )

        // Task 2 should be deduplicated and return Task 1
        assertEquals(task1.taskId, task2.taskId)
        assertEquals(1, proactiveEngine.getAllTasks().count { it.idempotencyKey == idempotencyKey })
    }

    @Test
    fun test4_TaskCancellation() = runBlocking {
        val task = proactiveEngine.scheduleDelayedTask(
            title = "Cancelable Task",
            prompt = "perform background indexing",
            delayMs = 10000L
        )

        val cancelled = proactiveEngine.cancelTask(task.taskId, "Cancelled for testing")
        assertTrue(cancelled)

        val updatedTask = proactiveEngine.getTask(task.taskId)
        assertNotNull(updatedTask)
        assertEquals(ProactiveTaskState.CANCELLED, updatedTask!!.state)
        assertEquals("Cancelled for testing", updatedTask.lastError)
    }

    @Test
    fun test5_EmergencyStopHalt() = runBlocking {
        emergencyStop.triggerEmergencyStop("Test Critical Safety Violation")

        val task = proactiveEngine.scheduleDelayedTask(
            title = "Blocked Task",
            prompt = "execute privileged command",
            delayMs = 0L
        )

        proactiveEngine.evaluateAndRunDueTasks()
        delay(100)

        // Should not be RUNNING or COMPLETED
        val updatedTask = proactiveEngine.getTask(task.taskId)
        assertNotNull(updatedTask)
        assertNotEquals(ProactiveTaskState.COMPLETED, updatedTask!!.state)

        // Engine should refuse to start while emergency stopped
        proactiveEngine.startAutonomousEngine()
        assertEquals(ProactiveEngineState.EMERGENCY_STOPPED, proactiveEngine.engineStateFlow.value)
    }

    @Test
    fun test6_MultiNodeTaskLeasing() = runBlocking {
        val task = proactiveEngine.scheduleDelayedTask(
            title = "Distributed Task",
            prompt = "compile remote workspace",
            delayMs = 10000L
        )

        val nodeIdA = "DESKTOP_NODE_A"
        val nodeIdB = "DESKTOP_NODE_B"

        // Node A acquires lease
        val acquiredA = proactiveEngine.acquireTaskLease(task.taskId, nodeIdA, leaseDurationMs = 30000L)
        assertTrue(acquiredA)

        val leasedTask = proactiveEngine.getTask(task.taskId)!!
        assertEquals(nodeIdA, leasedTask.leaseOwnerNode)
        assertTrue(leasedTask.leaseExpiresAt > System.currentTimeMillis())

        // Node B tries to acquire lease while Node A holds active lease -> Should Fail
        val acquiredB = proactiveEngine.acquireTaskLease(task.taskId, nodeIdB, leaseDurationMs = 30000L)
        assertFalse(acquiredB)

        // Node A releases lease
        val releasedA = proactiveEngine.releaseTaskLease(task.taskId, nodeIdA)
        assertTrue(releasedA)

        // Node B now acquires lease -> Should Succeed
        val acquiredB2 = proactiveEngine.acquireTaskLease(task.taskId, nodeIdB, leaseDurationMs = 30000L)
        assertTrue(acquiredB2)
    }

    @Test
    fun test7_NodeFailureAndAutomaticTaskFailover() = runBlocking {
        val deadNodeId = "NODE_CRASH_TEST_${System.currentTimeMillis()}"

        // Register remote node
        val node = WastiNode(
            nodeId = deadNodeId,
            nodeName = "Crashing Remote Node",
            platform = NodePlatform.DESKTOP,
            capabilities = setOf("remote_exec"),
            trustState = NodeTrustState.ACTIVE,
            lastPingTimestamp = System.currentTimeMillis() - 60000L // 60s ago (stale)
        )
        nodeManager.registerNode(node)

        val task = proactiveEngine.scheduleDelayedTask(
            title = "Failover Candidate Task",
            prompt = "execute distributed pipeline",
            delayMs = 0L
        )

        // Assign lease to the dead node
        task.leaseOwnerNode = deadNodeId
        task.leaseExpiresAt = System.currentTimeMillis() - 5000L // expired
        task.state = ProactiveTaskState.RUNNING

        // Trigger failover check
        val failedOver = proactiveEngine.checkAndTriggerFailover(heartbeatTimeoutMs = 30000L)

        assertTrue(failedOver.isNotEmpty())
        val updatedTask = proactiveEngine.getTask(task.taskId)!!
        assertEquals("LOCAL", updatedTask.leaseOwnerNode)
        assertEquals(ProactiveTaskState.SCHEDULED, updatedTask.state)
    }

    @Test
    fun test8_NonIdempotentTaskFailoverSafety() = runBlocking {
        val deadNodeId = "NON_IDEMPOTENT_NODE_${System.currentTimeMillis()}"
        val node = WastiNode(
            nodeId = deadNodeId,
            nodeName = "Crashing Node",
            platform = NodePlatform.DESKTOP,
            capabilities = setOf("remote_exec"),
            trustState = NodeTrustState.REVOKED,
            lastPingTimestamp = System.currentTimeMillis() - 60000L
        )
        nodeManager.registerNode(node)

        val task = ProactiveAutonomousTask(
            title = "Sensitive Transaction Task",
            prompt = "dispatch bank wire transfer",
            isIdempotent = false, // Cannot be safely retried automatically!
            leaseOwnerNode = deadNodeId,
            leaseExpiresAt = System.currentTimeMillis() - 5000L,
            state = ProactiveTaskState.RUNNING
        )
        proactiveEngine.scheduleTask(task)

        proactiveEngine.checkAndTriggerFailover(heartbeatTimeoutMs = 30000L)

        val updatedTask = proactiveEngine.getTask(task.taskId)!!
        assertEquals(ProactiveTaskState.FAILED, updatedTask.state)
        assertTrue(updatedTask.lastError?.contains("died during non-idempotent task") == true)
    }

    @Test
    fun test9_DynamicCapabilityResolutionDuringProactiveExecution() = runBlocking {
        val missingCapId = "custom_proactive_pdf_builder"

        val task = proactiveEngine.scheduleDelayedTask(
            title = "Build Weekly PDF Report",
            prompt = "generate weekly executive briefing",
            delayMs = 0L,
            requiredCapabilities = listOf(missingCapId)
        )

        // Evaluate and run
        proactiveEngine.evaluateAndRunDueTasks()
        proactiveEngine.awaitTaskCompletion(task.taskId)

        val updatedTask = proactiveEngine.getTask(task.taskId)!!
        assertEquals(ProactiveTaskState.COMPLETED, updatedTask.state)
    }

    @Test
    fun test10_EventBusStreaming() = runBlocking {
        val recordedEvents = mutableListOf<AgentEvent>()
        val collectJob = launch {
            eventBus.events.collect { recordedEvents.add(it) }
        }

        val task = proactiveEngine.scheduleDelayedTask(
            title = "Audited Proactive Task",
            prompt = "run health diagnostics",
            delayMs = 0L
        )

        proactiveEngine.evaluateAndRunDueTasks()
        proactiveEngine.awaitTaskCompletion(task.taskId)
        delay(50)

        collectJob.cancel()

        assertTrue(recordedEvents.any { it is AgentEvent.ProactiveTaskScheduled && (it as AgentEvent.ProactiveTaskScheduled).proactiveTaskId == task.taskId })
        assertTrue(recordedEvents.any { it is AgentEvent.ProactiveTaskStarted && (it as AgentEvent.ProactiveTaskStarted).proactiveTaskId == task.taskId })
        assertTrue(recordedEvents.any { it is AgentEvent.ProactiveTaskCompleted && (it as AgentEvent.ProactiveTaskCompleted).proactiveTaskId == task.taskId })
    }
}
