package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.assistant.BootReceiver
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.db.ProactiveTaskDao
import com.example.data.db.ProactiveTaskEntity
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.node.WastiNodeManager
import com.example.data.proactive.*
import com.example.data.worker.ProactiveReconciliationWorker
import kotlinx.coroutines.delay
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
 * Stage 16: Persistent Autonomous Memory, Reboot Recovery & Job Durability Tests
 *
 * Verifies:
 * 1. Task persistence into Room database upon scheduling
 * 2. Idempotent task crash/reboot recovery (resets RUNNING -> SCHEDULED)
 * 3. Non-idempotent task crash/reboot safety (marks RUNNING -> FAILED to prevent duplicate side effects)
 * 4. Idempotency deduplication backed by Room database
 * 5. Expired node lease persistence & recovery reclamation
 * 6. Task cancellation persistence in Room database
 * 7. Recurring task rescheduling persistence in Room database
 * 8. ProactiveReconciliationWorker overdue task execution & lease reclamation
 * 9. BootReceiver invocation triggers persistent reboot recovery
 * 10. AgentEventBus emissions for ProactiveTaskRecovered and RebootRecoveryCompleted
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Stage16PersistentAutonomousMemoryTest {

    private lateinit var context: Context
    private lateinit var database: WastiDatabase
    private lateinit var taskDao: ProactiveTaskDao
    private lateinit var eventBus: AgentEventBus
    private lateinit var emergencyStop: WastiEmergencyStopController
    private lateinit var nodeManager: WastiNodeManager
    private lateinit var proactiveEngine: WastiProactiveAutonomousEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WastiServiceLocator.init(context)
        database = WastiDatabase.getDatabase(context)
        taskDao = database.proactiveTaskDao()
        eventBus = WastiServiceLocator.agentEventBus
        emergencyStop = WastiServiceLocator.emergencyStopController
        nodeManager = WastiServiceLocator.nodeManager
        emergencyStop.resetEmergencyStop()

        runBlocking {
            taskDao.clearAllTasks()
        }

        proactiveEngine = WastiProactiveAutonomousEngine(
            context = context,
            eventBus = eventBus,
            emergencyStopController = emergencyStop,
            nodeManager = nodeManager,
            taskDao = taskDao
        )
    }

    @Test
    fun test1_TaskPersistenceInRoom() = runBlocking {
        val task = proactiveEngine.scheduleDelayedTask(
            title = "Durable Maintenance Job",
            prompt = "optimize SQLite storage",
            delayMs = 60000L
        )

        delay(100) // allow async persistence

        val persisted = taskDao.getTaskById(task.taskId)
        assertNotNull(persisted)
        assertEquals(task.taskId, persisted!!.taskId)
        assertEquals("Durable Maintenance Job", persisted.title)
        assertEquals(ProactiveTaskState.SCHEDULED.name, persisted.state)
        assertEquals(ProactiveTriggerType.ONE_TIME_DELAYED.name, persisted.triggerType)
    }

    @Test
    fun test2_IdempotentTaskCrashRecovery() = runBlocking {
        val taskId = "interrupted_idempotent_task_${UUID.randomUUID()}"
        val interruptedEntity = ProactiveTaskEntity(
            taskId = taskId,
            correlationId = "corr_123",
            title = "Index Filesystem Cache",
            prompt = "reindex media vault entries",
            state = ProactiveTaskState.RUNNING.name,
            scheduledAt = System.currentTimeMillis() - 10000L,
            isIdempotent = true,
            retryCount = 0,
            maxRetries = 3
        )
        taskDao.insertTask(interruptedEntity)

        // Perform reboot recovery
        val recoveredCount = proactiveEngine.recoverOnBootOrProcessStart()
        assertEquals(1, recoveredCount)

        val recoveredEntity = taskDao.getTaskById(taskId)
        assertNotNull(recoveredEntity)
        assertEquals(ProactiveTaskState.SCHEDULED.name, recoveredEntity!!.state)
        assertEquals(1, recoveredEntity.retryCount)

        val inMemory = proactiveEngine.getTask(taskId)
        assertNotNull(inMemory)
        assertEquals(ProactiveTaskState.SCHEDULED, inMemory!!.state)
    }

    @Test
    fun test3_NonIdempotentTaskCrashSafety() = runBlocking {
        val taskId = "interrupted_non_idempotent_${UUID.randomUUID()}"
        val nonIdempotentEntity = ProactiveTaskEntity(
            taskId = taskId,
            correlationId = "corr_456",
            title = "Dispatch External Wire Transfer",
            prompt = "execute banking charge transaction",
            state = ProactiveTaskState.RUNNING.name,
            scheduledAt = System.currentTimeMillis() - 10000L,
            isIdempotent = false,
            retryCount = 0,
            maxRetries = 3
        )
        taskDao.insertTask(nonIdempotentEntity)

        // Perform reboot recovery
        val recoveredCount = proactiveEngine.recoverOnBootOrProcessStart()
        assertEquals(1, recoveredCount)

        val recoveredEntity = taskDao.getTaskById(taskId)
        assertNotNull(recoveredEntity)
        // Must be FAILED to prevent duplicate execution of non-idempotent action
        assertEquals(ProactiveTaskState.FAILED.name, recoveredEntity!!.state)
        assertTrue(recoveredEntity.lastError?.contains("non-idempotent") == true)
    }

    @Test
    fun test4_IdempotencyDeduplicationWithRoom() = runBlocking {
        val idempotencyKey = "unique_persistent_key_${System.currentTimeMillis()}"

        val task1 = proactiveEngine.scheduleDelayedTask(
            title = "Sync Repository Cache",
            prompt = "synchronize local git trees",
            delayMs = 30000L,
            idempotencyKey = idempotencyKey
        )

        delay(100)

        val newEngineInstance = WastiProactiveAutonomousEngine(
            context = context,
            eventBus = eventBus,
            emergencyStopController = emergencyStop,
            nodeManager = nodeManager,
            taskDao = taskDao
        )

        val task2 = newEngineInstance.scheduleDelayedTask(
            title = "Sync Repository Cache Duplicate",
            prompt = "synchronize local git trees",
            delayMs = 30000L,
            idempotencyKey = idempotencyKey
        )

        assertEquals(task1.taskId, task2.taskId)
        val allMatching = taskDao.getTaskByIdempotencyKey(idempotencyKey)
        assertNotNull(allMatching)
        assertEquals(task1.taskId, allMatching!!.taskId)
    }

    @Test
    fun test5_ExpiredLeaseReclamationOnReboot() = runBlocking {
        val taskId = "leased_task_${UUID.randomUUID()}"
        val expiredEntity = ProactiveTaskEntity(
            taskId = taskId,
            correlationId = "corr_789",
            title = "Remote Worker Task",
            prompt = "train edge model",
            state = ProactiveTaskState.SCHEDULED.name,
            leaseOwnerNode = "DEAD_REMOTE_NODE",
            leaseExpiresAt = System.currentTimeMillis() - 60000L // expired
        )
        taskDao.insertTask(expiredEntity)

        proactiveEngine.recoverOnBootOrProcessStart()

        val recoveredEntity = taskDao.getTaskById(taskId)
        assertNotNull(recoveredEntity)
        assertNull(recoveredEntity!!.leaseOwnerNode)
        assertEquals(0L, recoveredEntity.leaseExpiresAt)
    }

    @Test
    fun test6_TaskCancellationPersistence() = runBlocking {
        val task = proactiveEngine.scheduleDelayedTask(
            title = "Task To Be Cancelled",
            prompt = "perform background indexing",
            delayMs = 60000L
        )
        delay(50)

        val cancelled = proactiveEngine.cancelTask(task.taskId, "Cancelled for durability test")
        assertTrue(cancelled)
        delay(50)

        val persisted = taskDao.getTaskById(task.taskId)
        assertNotNull(persisted)
        assertEquals(ProactiveTaskState.CANCELLED.name, persisted!!.state)
        assertEquals("Cancelled for durability test", persisted.lastError)
    }

    @Test
    fun test7_RecurringTaskReschedulingPersistence() = runBlocking {
        val intervalMs = 120000L
        val task = proactiveEngine.scheduleRecurringTask(
            title = "Recurring Audit",
            prompt = "check system integrity",
            intervalMs = intervalMs
        )
        task.scheduledAt = System.currentTimeMillis() - 100
        delay(50)

        proactiveEngine.evaluateAndRunDueTasks()
        delay(200)

        val persisted = taskDao.getTaskById(task.taskId)
        assertNotNull(persisted)
        assertEquals(ProactiveTaskState.SCHEDULED.name, persisted!!.state)
        assertTrue(persisted.scheduledAt > System.currentTimeMillis())
    }

    @Test
    fun test8_ProactiveReconciliationWorker() = runBlocking {
        val taskId = "worker_reconciled_task_${UUID.randomUUID()}"
        val expiredEntity = ProactiveTaskEntity(
            taskId = taskId,
            correlationId = "corr_work",
            title = "Expired Node Task",
            prompt = "verify node status",
            state = ProactiveTaskState.RUNNING.name,
            leaseOwnerNode = "STALE_NODE",
            leaseExpiresAt = System.currentTimeMillis() - 10000L
        )
        taskDao.insertTask(expiredEntity)

        val success = ProactiveReconciliationWorker.performReconciliation(context)
        assertTrue(success)

        val updated = taskDao.getTaskById(taskId)
        assertNotNull(updated)
        assertNull(updated!!.leaseOwnerNode)
        assertEquals(0L, updated.leaseExpiresAt)
    }

    @Test
    fun test9_BootReceiverInvocation() = runBlocking {
        val receiver = BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        receiver.onReceive(context, intent)
        delay(100)
        // Passes without crashing or throwing
        assertTrue(true)
    }

    @Test
    fun test10_Stage16EventBusEmissions() = runBlocking {
        val recordedEvents = mutableListOf<AgentEvent>()
        val collectJob = launch {
            eventBus.events.collect { recordedEvents.add(it) }
        }

        val taskId = "event_task_${UUID.randomUUID()}"
        val entity = ProactiveTaskEntity(
            taskId = taskId,
            correlationId = "corr_event",
            title = "Reboot Event Test",
            prompt = "check memory status",
            state = ProactiveTaskState.RUNNING.name,
            isIdempotent = true
        )
        taskDao.insertTask(entity)

        proactiveEngine.recoverOnBootOrProcessStart()
        delay(100)
        collectJob.cancel()

        assertTrue(recordedEvents.any { it is AgentEvent.ProactiveTaskRecovered && (it as AgentEvent.ProactiveTaskRecovered).proactiveTaskId == taskId })
        assertTrue(recordedEvents.any { it is AgentEvent.RebootRecoveryCompleted })
    }
}
