package com.example.data.proactive

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.db.ProactiveTaskDao
import com.example.data.db.ProactiveTaskEntity
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.node.NodeHealthState
import com.example.data.node.NodeTrustState
import com.example.data.node.WastiNodeManager
import com.example.data.server.WastiWebSocketServer
import com.example.data.workflow.AutonomousCapabilityOrchestrator
import com.example.data.workflow.CapabilityResolutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class ProactiveTaskState {
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
    FAILOVER
}

enum class ProactiveTriggerType {
    ONE_TIME_DELAYED,
    RECURRING_INTERVAL,
    MEMORY_REMINDER,
    SYSTEM_EVENT,
    CAPABILITY_MAINTENANCE,
    FAILOVER_RECOVERY
}

enum class ProactiveEngineState {
    IDLE,
    RUNNING,
    PAUSED,
    EMERGENCY_STOPPED
}

data class ProactiveAutonomousTask(
    val taskId: String = UUID.randomUUID().toString(),
    val correlationId: String = "corr_${UUID.randomUUID()}",
    val title: String,
    val prompt: String,
    val origin: CommandOrigin = CommandOrigin.BACKGROUND_WORKER,
    val priority: AgentTaskPriority = AgentTaskPriority.MEDIUM,
    var state: ProactiveTaskState = ProactiveTaskState.SCHEDULED,
    val triggerType: ProactiveTriggerType = ProactiveTriggerType.ONE_TIME_DELAYED,
    val createdAt: Long = System.currentTimeMillis(),
    var scheduledAt: Long = System.currentTimeMillis(),
    val intervalMs: Long = 0L,
    var retryCount: Int = 0,
    val maxRetries: Int = 3,
    var nextRetryAt: Long = 0L,
    val requiredCapabilities: List<String> = emptyList(),
    val preferredNode: String? = null,
    var selectedNode: String? = null,
    var leaseOwnerNode: String? = null,
    var leaseExpiresAt: Long = 0L,
    val idempotencyKey: String? = null,
    var verificationEvidence: String? = null,
    var lastError: String? = null,
    val isIdempotent: Boolean = true,
    val executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
    var completedAt: Long? = null
)

fun ProactiveAutonomousTask.toEntity(): ProactiveTaskEntity {
    return ProactiveTaskEntity(
        taskId = taskId,
        correlationId = correlationId,
        title = title,
        prompt = prompt,
        origin = origin.name,
        priority = priority.name,
        state = state.name,
        triggerType = triggerType.name,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        intervalMs = intervalMs,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt,
        requiredCapabilitiesCsv = requiredCapabilities.joinToString(","),
        preferredNode = preferredNode,
        selectedNode = selectedNode,
        leaseOwnerNode = leaseOwnerNode,
        leaseExpiresAt = leaseExpiresAt,
        idempotencyKey = idempotencyKey,
        verificationEvidence = verificationEvidence,
        lastError = lastError,
        isIdempotent = isIdempotent,
        executionMode = executionMode.name,
        completedAt = completedAt,
        updatedAt = System.currentTimeMillis()
    )
}

fun ProactiveTaskEntity.toDomain(): ProactiveAutonomousTask {
    val originEnum = try { CommandOrigin.valueOf(origin) } catch (_: Exception) { CommandOrigin.BACKGROUND_WORKER }
    val priorityEnum = try { AgentTaskPriority.valueOf(priority) } catch (_: Exception) { AgentTaskPriority.MEDIUM }
    val stateEnum = try { ProactiveTaskState.valueOf(state) } catch (_: Exception) { ProactiveTaskState.SCHEDULED }
    val triggerEnum = try { ProactiveTriggerType.valueOf(triggerType) } catch (_: Exception) { ProactiveTriggerType.ONE_TIME_DELAYED }
    val execModeEnum = try { ExecutionMode.valueOf(executionMode) } catch (_: Exception) { ExecutionMode.AUTONOMOUS }
    val capList = if (requiredCapabilitiesCsv.isBlank()) emptyList() else requiredCapabilitiesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    return ProactiveAutonomousTask(
        taskId = taskId,
        correlationId = correlationId,
        title = title,
        prompt = prompt,
        origin = originEnum,
        priority = priorityEnum,
        state = stateEnum,
        triggerType = triggerEnum,
        createdAt = createdAt,
        scheduledAt = scheduledAt,
        intervalMs = intervalMs,
        retryCount = retryCount,
        maxRetries = maxRetries,
        nextRetryAt = nextRetryAt,
        requiredCapabilities = capList,
        preferredNode = preferredNode,
        selectedNode = selectedNode,
        leaseOwnerNode = leaseOwnerNode,
        leaseExpiresAt = leaseExpiresAt,
        idempotencyKey = idempotencyKey,
        verificationEvidence = verificationEvidence,
        lastError = lastError,
        isIdempotent = isIdempotent,
        executionMode = execModeEnum,
        completedAt = completedAt
    )
}

/**
 * Stage 15 & 16: Canonical Proactive Autonomous Engine for Wasti AI OS.
 * Operates in the background independently of the Chat UI lifecycle.
 * Durable persistence and reboot recovery backed by Room Database.
 * Integrates Stage 14 Self-Evolving Capabilities, multi-node task leasing,
 * failover, and verified execution through the Unified Execution Fabric.
 */
class WastiProactiveAutonomousEngine(
    private val context: Context?,
    private val eventBus: AgentEventBus = WastiServiceLocator.agentEventBus,
    private val emergencyStopController: WastiEmergencyStopController = WastiServiceLocator.emergencyStopController,
    private val securityPolicyEngine: WastiSecurityPolicyEngine = WastiServiceLocator.securityPolicyEngine,
    private val executionFabric: UnifiedExecutionFabric = WastiServiceLocator.executionFabric,
    private val nodeManager: WastiNodeManager = WastiServiceLocator.nodeManager,
    private val taskDao: ProactiveTaskDao? = null
) {
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isEngineRunning = AtomicBoolean(false)
    private var engineLoopJob: Job? = null

    private val tasksMap = ConcurrentHashMap<String, ProactiveAutonomousTask>()
    private val activeExecutionJobs = ConcurrentHashMap<String, Job>()
    private val idempotencyRegistry = ConcurrentHashMap<String, Long>()

    private val _tasksFlow = MutableStateFlow<List<ProactiveAutonomousTask>>(emptyList())
    val tasksFlow: StateFlow<List<ProactiveAutonomousTask>> = _tasksFlow.asStateFlow()

    private val _engineStateFlow = MutableStateFlow(ProactiveEngineState.IDLE)
    val engineStateFlow: StateFlow<ProactiveEngineState> = _engineStateFlow.asStateFlow()

    private val effectiveDao: ProactiveTaskDao? by lazy {
        taskDao ?: context?.let { ctx ->
            try {
                WastiDatabase.getDatabase(ctx).proactiveTaskDao()
            } catch (e: Throwable) {
                Log.w(TAG, "Could not obtain ProactiveTaskDao: ${e.message}")
                null
            }
        }
    }

    /**
     * Stage 16: Durable reboot and process recovery scanner.
     * Reconciles interrupted tasks, resets idempotent tasks to SCHEDULED,
     * marks non-idempotent interrupted tasks as FAILED, and loads state from Room.
     */
    suspend fun recoverOnBootOrProcessStart(): Int {
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Beginning Stage 16 persistent reboot/startup recovery scan...")
        val dao = effectiveDao ?: return 0
        var recoveredCount = 0

        try {
            val now = System.currentTimeMillis()
            val allPersisted = dao.getAllTasksSync()

            for (entity in allPersisted) {
                val task = entity.toDomain()

                // 1. Process death recovery for tasks that were RUNNING or in FAILOVER when app stopped
                if (entity.state == ProactiveTaskState.RUNNING.name || entity.state == ProactiveTaskState.FAILOVER.name) {
                    if (task.isIdempotent && task.retryCount < task.maxRetries) {
                        task.state = ProactiveTaskState.SCHEDULED
                        task.scheduledAt = now
                        task.retryCount += 1
                        task.leaseOwnerNode = null
                        task.leaseExpiresAt = 0L
                        dao.updateTask(task.toEntity())
                        eventBus.tryEmit(
                            AgentEvent.ProactiveTaskRecovered(
                                proactiveTaskId = task.taskId,
                                title = task.title,
                                previousState = entity.state,
                                newState = ProactiveTaskState.SCHEDULED.name
                            )
                        )
                        recoveredCount++
                        Log.w(TAG, "Recovered interrupted idempotent task: ${task.title} (re-queued as SCHEDULED)")
                    } else if (!task.isIdempotent) {
                        task.state = ProactiveTaskState.FAILED
                        task.lastError = "Process killed during non-idempotent execution. Marked FAILED to avoid duplicate side effects."
                        task.leaseOwnerNode = null
                        task.leaseExpiresAt = 0L
                        dao.updateTask(task.toEntity())
                        eventBus.tryEmit(
                            AgentEvent.ProactiveTaskRecovered(
                                proactiveTaskId = task.taskId,
                                title = task.title,
                                previousState = entity.state,
                                newState = ProactiveTaskState.FAILED.name
                            )
                        )
                        recoveredCount++
                        Log.e(TAG, "Marked interrupted non-idempotent task as FAILED for safety: ${task.title}")
                    }
                } else if (entity.state == ProactiveTaskState.SCHEDULED.name) {
                    // Reclaim expired remote leases
                    if (task.leaseOwnerNode != null && task.leaseExpiresAt in 1..now) {
                        task.leaseOwnerNode = null
                        task.leaseExpiresAt = 0L
                        dao.updateTask(task.toEntity())
                    }
                }

                // Populate in-memory map & idempotency cache
                tasksMap[task.taskId] = task
                task.idempotencyKey?.let { key ->
                    idempotencyRegistry[key] = task.createdAt
                }
            }

            updateTasksFlow()
            val durationMs = System.currentTimeMillis() - startTime
            eventBus.tryEmit(
                AgentEvent.RebootRecoveryCompleted(
                    recoveredTaskCount = recoveredCount,
                    durationMs = durationMs
                )
            )
            Log.i(TAG, "Reboot/startup recovery completed: ${allPersisted.size} tasks loaded, $recoveredCount tasks reconciled in ${durationMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Error during reboot recovery: ${e.message}", e)
        }
        return recoveredCount
    }

    @Synchronized
    fun startAutonomousEngine() {
        if (isEngineRunning.get()) return
        if (emergencyStopController.isEmergencyStopped) {
            _engineStateFlow.value = ProactiveEngineState.EMERGENCY_STOPPED
            Log.w(TAG, "Cannot start Proactive Autonomous Engine: Emergency Stop is active.")
            return
        }

        isEngineRunning.set(true)
        _engineStateFlow.value = ProactiveEngineState.RUNNING

        engineLoopJob = engineScope.launch {
            Log.i(TAG, "Wasti Proactive Autonomous Engine starting — running recovery check.")
            recoverOnBootOrProcessStart()

            while (isEngineRunning.get()) {
                try {
                    if (emergencyStopController.isEmergencyStopped) {
                        handleEmergencyStopTriggered("Emergency Stop Active in Loop")
                        break
                    }

                    // Check for expired node leases and failovers
                    checkAndTriggerFailover()

                    // Evaluate and run due tasks
                    evaluateAndRunDueTasks()

                    delay(POLL_INTERVAL_MS)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in proactive engine loop cycle: ${e.message}", e)
                    delay(POLL_INTERVAL_MS)
                }
            }
            _engineStateFlow.value = ProactiveEngineState.IDLE
        }
    }

    @Synchronized
    fun stopAutonomousEngine() {
        if (!isEngineRunning.get()) return
        isEngineRunning.set(false)
        engineLoopJob?.cancel()
        engineLoopJob = null

        // Cancel all currently running task jobs
        for ((taskId, job) in activeExecutionJobs) {
            job.cancel()
            tasksMap[taskId]?.let { task ->
                if (task.state == ProactiveTaskState.RUNNING) {
                    task.state = ProactiveTaskState.SCHEDULED
                    persistTaskAsync(task)
                }
            }
        }
        activeExecutionJobs.clear()
        _engineStateFlow.value = ProactiveEngineState.IDLE
        updateTasksFlow()
        Log.i(TAG, "Wasti Proactive Autonomous Engine stopped.")
    }

    fun scheduleTask(task: ProactiveAutonomousTask): ProactiveAutonomousTask {
        // Idempotency check
        task.idempotencyKey?.let { key ->
            val lastSeen = idempotencyRegistry[key]
            if (lastSeen != null && System.currentTimeMillis() - lastSeen < IDEMPOTENCY_WINDOW_MS) {
                val existing = tasksMap.values.find { it.idempotencyKey == key }
                if (existing != null) {
                    Log.w(TAG, "Idempotent task ignored: Key $key already scheduled as ${existing.taskId}")
                    return existing
                }
            }

            effectiveDao?.let { dao ->
                val existingInDb = runBlocking(Dispatchers.IO) {
                    dao.getTaskByIdempotencyKey(key)
                }
                if (existingInDb != null) {
                    val domain = existingInDb.toDomain()
                    tasksMap[domain.taskId] = domain
                    idempotencyRegistry[key] = domain.createdAt
                    return domain
                }
            }
            idempotencyRegistry[key] = System.currentTimeMillis()
        }

        tasksMap[task.taskId] = task
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.ProactiveTaskScheduled(
                proactiveTaskId = task.taskId,
                title = task.title,
                scheduledAt = task.scheduledAt,
                isRecurring = task.intervalMs > 0
            )
        )

        broadcastTaskUpdate(task, "TASK_SCHEDULED")
        Log.i(TAG, "Proactive task scheduled & persisted: ${task.title} (ID: ${task.taskId}, due in ${task.scheduledAt - System.currentTimeMillis()}ms)")
        return task
    }

    fun scheduleDelayedTask(
        title: String,
        prompt: String,
        delayMs: Long,
        requiredCapabilities: List<String> = emptyList(),
        idempotencyKey: String? = null,
        origin: CommandOrigin = CommandOrigin.BACKGROUND_WORKER
    ): ProactiveAutonomousTask {
        val task = ProactiveAutonomousTask(
            title = title,
            prompt = prompt,
            origin = origin,
            scheduledAt = System.currentTimeMillis() + delayMs,
            triggerType = ProactiveTriggerType.ONE_TIME_DELAYED,
            requiredCapabilities = requiredCapabilities,
            idempotencyKey = idempotencyKey
        )
        return scheduleTask(task)
    }

    fun scheduleRecurringTask(
        title: String,
        prompt: String,
        intervalMs: Long,
        requiredCapabilities: List<String> = emptyList(),
        idempotencyKey: String? = null,
        origin: CommandOrigin = CommandOrigin.BACKGROUND_WORKER
    ): ProactiveAutonomousTask {
        val task = ProactiveAutonomousTask(
            title = title,
            prompt = prompt,
            origin = origin,
            scheduledAt = System.currentTimeMillis() + intervalMs,
            intervalMs = intervalMs,
            triggerType = ProactiveTriggerType.RECURRING_INTERVAL,
            requiredCapabilities = requiredCapabilities,
            idempotencyKey = idempotencyKey
        )
        return scheduleTask(task)
    }

    fun cancelTask(taskId: String, reason: String = "User requested cancellation"): Boolean {
        val task = tasksMap[taskId] ?: return false
        activeExecutionJobs[taskId]?.cancel()
        activeExecutionJobs.remove(taskId)

        task.state = ProactiveTaskState.CANCELLED
        task.lastError = reason
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.ProactiveTaskCancelled(
                proactiveTaskId = taskId,
                reason = reason
            )
        )

        broadcastTaskUpdate(task, "TASK_CANCELLED")
        Log.i(TAG, "Proactive task cancelled: $taskId ($reason)")
        return true
    }

    fun acquireTaskLease(taskId: String, nodeId: String, leaseDurationMs: Long = 30000L): Boolean {
        val task = tasksMap[taskId] ?: return false
        val now = System.currentTimeMillis()
        if (task.leaseOwnerNode != null && task.leaseOwnerNode != nodeId && task.leaseExpiresAt > now) {
            Log.w(TAG, "Cannot acquire lease for task $taskId: Already leased to ${task.leaseOwnerNode}")
            return false
        }

        task.leaseOwnerNode = nodeId
        task.leaseExpiresAt = now + leaseDurationMs
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.TaskLeaseAcquired(
                proactiveTaskId = taskId,
                ownerNodeId = nodeId,
                leaseExpiresAt = task.leaseExpiresAt
            )
        )
        broadcastTaskUpdate(task, "LEASE_ACQUIRED")
        return true
    }

    fun renewTaskLease(taskId: String, nodeId: String, leaseDurationMs: Long = 30000L): Boolean {
        val task = tasksMap[taskId] ?: return false
        if (task.leaseOwnerNode != nodeId) return false

        task.leaseExpiresAt = System.currentTimeMillis() + leaseDurationMs
        updateTasksFlow()
        persistTaskAsync(task)
        return true
    }

    fun releaseTaskLease(taskId: String, nodeId: String): Boolean {
        val task = tasksMap[taskId] ?: return false
        if (task.leaseOwnerNode != nodeId) return false

        task.leaseOwnerNode = null
        task.leaseExpiresAt = 0L
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.TaskLeaseLost(
                proactiveTaskId = taskId,
                previousOwnerNodeId = nodeId,
                reason = "Lease explicitly released"
            )
        )
        broadcastTaskUpdate(task, "LEASE_RELEASED")
        return true
    }

    fun checkAndTriggerFailover(heartbeatTimeoutMs: Long = 45000L): List<ProactiveAutonomousTask> {
        val now = System.currentTimeMillis()
        val failedOverTasks = mutableListOf<ProactiveAutonomousTask>()

        for (task in tasksMap.values) {
            val ownerNodeId = task.leaseOwnerNode ?: continue
            if (ownerNodeId == "LOCAL" || ownerNodeId == "DEVICE_HOST") continue

            val node = nodeManager.getNode(ownerNodeId)
            val isNodeDead = node == null ||
                    node.trustState == NodeTrustState.REVOKED ||
                    node.trustState == NodeTrustState.DISCONNECTED ||
                    node.healthState == NodeHealthState.OFFLINE ||
                    (now - node.lastPingTimestamp) > heartbeatTimeoutMs ||
                    task.leaseExpiresAt < now

            if (isNodeDead && (task.state == ProactiveTaskState.RUNNING || task.state == ProactiveTaskState.SCHEDULED)) {
                Log.w(TAG, "Node $ownerNodeId is unresponsive/expired. Triggering failover for task ${task.taskId}")

                eventBus.tryEmit(
                    AgentEvent.TaskFailoverStarted(
                        proactiveTaskId = task.taskId,
                        failedNodeId = ownerNodeId,
                        targetNodeId = "LOCAL"
                    )
                )

                task.state = ProactiveTaskState.FAILOVER
                task.leaseOwnerNode = "LOCAL"
                task.leaseExpiresAt = now + 30000L
                task.selectedNode = "LOCAL"

                if (task.isIdempotent) {
                    task.state = ProactiveTaskState.SCHEDULED
                    task.scheduledAt = now
                    persistTaskAsync(task)
                    eventBus.tryEmit(
                        AgentEvent.TaskFailoverCompleted(
                            proactiveTaskId = task.taskId,
                            targetNodeId = "LOCAL",
                            isSuccess = true
                        )
                    )
                    failedOverTasks.add(task)
                } else {
                    task.state = ProactiveTaskState.FAILED
                    task.lastError = "Node $ownerNodeId died during non-idempotent task execution."
                    persistTaskAsync(task)
                    eventBus.tryEmit(
                        AgentEvent.TaskFailoverCompleted(
                            proactiveTaskId = task.taskId,
                            targetNodeId = "LOCAL",
                            isSuccess = false
                        )
                    )
                }

                broadcastTaskUpdate(task, "TASK_FAILOVER")
            }
        }

        if (failedOverTasks.isNotEmpty()) {
            updateTasksFlow()
        }
        return failedOverTasks
    }

    suspend fun evaluateAndRunDueTasks() {
        val now = System.currentTimeMillis()
        if (emergencyStopController.isEmergencyStopped) return

        val dueTasks = tasksMap.values.filter { task ->
            (task.state == ProactiveTaskState.SCHEDULED || task.state == ProactiveTaskState.FAILOVER) &&
                    (task.scheduledAt <= now || (task.nextRetryAt in 1..now)) &&
                    (task.leaseOwnerNode == null || task.leaseOwnerNode == "LOCAL" || task.leaseOwnerNode == "DEVICE_HOST")
        }.sortedBy { it.priority.ordinal }

        for (task in dueTasks) {
            if (activeExecutionJobs.size >= MAX_CONCURRENT_PROACTIVE_TASKS) {
                break
            }
            if (activeExecutionJobs.containsKey(task.taskId)) {
                continue
            }

            executeTaskAsync(task)
        }
    }

    private fun executeTaskAsync(task: ProactiveAutonomousTask) {
        val job = engineScope.launch {
            val startTime = System.currentTimeMillis()
            task.state = ProactiveTaskState.RUNNING
            task.selectedNode = "LOCAL"
            task.leaseOwnerNode = "LOCAL"
            task.leaseExpiresAt = System.currentTimeMillis() + 60000L
            updateTasksFlow()
            persistTaskAsync(task)

            eventBus.emit(
                AgentEvent.ProactiveTaskStarted(
                    proactiveTaskId = task.taskId,
                    title = task.title,
                    origin = task.origin.name
                )
            )
            broadcastTaskUpdate(task, "TASK_STARTED")

            try {
                // 1. Security check
                if (emergencyStopController.isEmergencyStopped) {
                    throw IllegalStateException("Emergency Stop active. Proactive execution blocked.")
                }

                // 2. Stage 14 Dynamic Capability Resolution
                for (capId in task.requiredCapabilities) {
                    eventBus.emit(
                        AgentEvent.CapabilityRequested(
                            capabilityId = capId,
                            requesterTaskId = task.taskId
                        )
                    )

                    val orchestrator = WastiServiceLocator.autonomousCapabilityOrchestrator
                    eventBus.emit(AgentEvent.CapabilityProvisioningStarted(capabilityId = capId))

                    val result = orchestrator.resolveCapability(
                        capabilityId = capId,
                        description = "Dynamic provisioning for proactive task ${task.title}"
                    )

                    when (result) {
                        is CapabilityResolutionResult.DynamicCreatedTool,
                        is CapabilityResolutionResult.ExistingTool,
                        is CapabilityResolutionResult.NativeCapability -> {
                            eventBus.emit(AgentEvent.CapabilityProvisioningCompleted(capabilityId = capId, isSuccess = true))
                        }
                        is CapabilityResolutionResult.SecurityBlocked -> {
                            eventBus.emit(AgentEvent.CapabilityProvisioningCompleted(capabilityId = capId, isSuccess = false))
                            throw IllegalStateException("Security policy blocked required capability '$capId': ${result.reason}")
                        }
                        is CapabilityResolutionResult.ResolutionFailed -> {
                            eventBus.emit(AgentEvent.CapabilityProvisioningCompleted(capabilityId = capId, isSuccess = false))
                            throw IllegalStateException("Failed to resolve or develop required capability '$capId': ${result.reason}")
                        }
                    }
                }

                // 3. Execution through Canonical OS Runtime
                val runtime = WastiOSRuntime.getInstance(context)
                val submissionResult = runtime.submitCommand(
                    command = task.prompt,
                    origin = task.origin,
                    executionMode = task.executionMode
                )

                val durationMs = System.currentTimeMillis() - startTime
                when (submissionResult) {
                    is CommandSubmissionResult.Accepted -> {
                        task.state = ProactiveTaskState.COMPLETED
                        task.completedAt = System.currentTimeMillis()
                        task.verificationEvidence = "Executed via WastiOSRuntime (Command: ${submissionResult.commandId})"
                        task.leaseOwnerNode = null
                        task.leaseExpiresAt = 0L
                    }
                    is CommandSubmissionResult.ImmediateSuccess -> {
                        task.state = ProactiveTaskState.COMPLETED
                        task.completedAt = System.currentTimeMillis()
                        task.verificationEvidence = submissionResult.verificationEvidence ?: "Output: ${submissionResult.output.take(100)}"
                        task.leaseOwnerNode = null
                        task.leaseExpiresAt = 0L
                    }
                    is CommandSubmissionResult.Rejected -> {
                        throw IllegalStateException("Command submission rejected: ${submissionResult.reason}")
                    }
                }

                persistTaskAsync(task)

                eventBus.emit(
                    AgentEvent.ProactiveTaskCompleted(
                        proactiveTaskId = task.taskId,
                        summary = "Successfully executed: ${task.title}",
                        durationMs = durationMs
                    )
                )

                broadcastTaskUpdate(task, "TASK_COMPLETED")

                // Handle recurring reschedule
                if (task.intervalMs > 0) {
                    task.state = ProactiveTaskState.SCHEDULED
                    task.scheduledAt = System.currentTimeMillis() + task.intervalMs
                    task.retryCount = 0
                    task.nextRetryAt = 0L
                    persistTaskAsync(task)
                    Log.i(TAG, "Recurring task ${task.title} rescheduled for next run in ${task.intervalMs}ms")
                }
            } catch (e: CancellationException) {
                task.state = ProactiveTaskState.CANCELLED
                task.lastError = "Task execution cancelled"
                persistTaskAsync(task)
                broadcastTaskUpdate(task, "TASK_CANCELLED")
            } catch (e: Exception) {
                val errMsg = e.message ?: "Unknown execution error"
                Log.e(TAG, "Error executing proactive task ${task.taskId}: $errMsg", e)

                if (task.retryCount < task.maxRetries) {
                    task.retryCount++
                    task.nextRetryAt = System.currentTimeMillis() + (task.retryCount * 5000L)
                    task.state = ProactiveTaskState.SCHEDULED
                    task.lastError = "$errMsg (Retry ${task.retryCount}/${task.maxRetries})"
                    persistTaskAsync(task)

                    eventBus.emit(
                        AgentEvent.ProactiveTaskFailed(
                            proactiveTaskId = task.taskId,
                            error = errMsg,
                            willRetry = true
                        )
                    )
                } else {
                    task.state = ProactiveTaskState.FAILED
                    task.lastError = errMsg
                    task.leaseOwnerNode = null
                    persistTaskAsync(task)

                    eventBus.emit(
                        AgentEvent.ProactiveTaskFailed(
                            proactiveTaskId = task.taskId,
                            error = errMsg,
                            willRetry = false
                        )
                    )
                }

                broadcastTaskUpdate(task, "TASK_FAILED")
            } finally {
                activeExecutionJobs.remove(task.taskId)
                updateTasksFlow()
            }
        }

        activeExecutionJobs[task.taskId] = job
    }

    private fun handleEmergencyStopTriggered(reason: String) {
        _engineStateFlow.value = ProactiveEngineState.EMERGENCY_STOPPED
        for ((taskId, job) in activeExecutionJobs) {
            job.cancel()
            tasksMap[taskId]?.let {
                it.state = ProactiveTaskState.BLOCKED
                it.lastError = "Blocked by Emergency Stop: $reason"
                persistTaskAsync(it)
            }
        }
        activeExecutionJobs.clear()
        updateTasksFlow()
        Log.w(TAG, "Emergency Stop handled in Proactive Engine: $reason")
    }

    private fun broadcastTaskUpdate(task: ProactiveAutonomousTask, eventType: String) {
        val server = WastiWebSocketServer.getInstance(context)
        val json = JSONObject().apply {
            put("type", "PROACTIVE_TASK_UPDATE")
            put("eventType", eventType)
            put("taskId", task.taskId)
            put("title", task.title)
            put("state", task.state.name)
            put("leaseOwner", task.leaseOwnerNode ?: "NONE")
            put("timestamp", System.currentTimeMillis())
        }
        server.broadcastText(json.toString())
    }

    fun completeRunningTask(taskId: String, evidence: String) {
        val task = tasksMap[taskId] ?: return
        task.state = ProactiveTaskState.COMPLETED
        task.completedAt = System.currentTimeMillis()
        task.verificationEvidence = evidence
        task.leaseOwnerNode = null
        task.leaseExpiresAt = 0L
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.ProactiveTaskCompleted(
                proactiveTaskId = task.taskId,
                summary = "Completed: ${task.title}",
                durationMs = System.currentTimeMillis() - task.createdAt
            )
        )
        broadcastTaskUpdate(task, "TASK_COMPLETED")
    }

    fun failRunningTask(taskId: String, error: String) {
        val task = tasksMap[taskId] ?: return
        task.state = ProactiveTaskState.FAILED
        task.lastError = error
        task.leaseOwnerNode = null
        task.leaseExpiresAt = 0L
        updateTasksFlow()
        persistTaskAsync(task)

        eventBus.tryEmit(
            AgentEvent.ProactiveTaskFailed(
                proactiveTaskId = task.taskId,
                error = error,
                willRetry = false
            )
        )
        broadcastTaskUpdate(task, "TASK_FAILED")
    }

    fun failoverTask(taskId: String, targetNodeId: String = "local_android_node") {
        val task = tasksMap[taskId] ?: return
        val previousOwner = task.leaseOwnerNode ?: "UNKNOWN"
        task.state = ProactiveTaskState.FAILOVER
        task.leaseOwnerNode = targetNodeId
        task.selectedNode = targetNodeId
        task.leaseExpiresAt = System.currentTimeMillis() + 30000L

        eventBus.tryEmit(
            AgentEvent.TaskFailoverStarted(
                proactiveTaskId = taskId,
                failedNodeId = previousOwner,
                targetNodeId = targetNodeId
            )
        )

        if (task.isIdempotent) {
            task.state = ProactiveTaskState.SCHEDULED
            task.scheduledAt = System.currentTimeMillis()
            updateTasksFlow()
            persistTaskAsync(task)
            eventBus.tryEmit(
                AgentEvent.TaskFailoverCompleted(
                    proactiveTaskId = taskId,
                    targetNodeId = targetNodeId,
                    isSuccess = true
                )
            )
        } else {
            task.state = ProactiveTaskState.FAILED
            task.lastError = "Non-idempotent task failed over and terminated safely."
            updateTasksFlow()
            persistTaskAsync(task)
            eventBus.tryEmit(
                AgentEvent.TaskFailoverCompleted(
                    proactiveTaskId = taskId,
                    targetNodeId = targetNodeId,
                    isSuccess = false
                )
            )
        }
        broadcastTaskUpdate(task, "TASK_FAILOVER")
    }

    fun delegateTaskToRemoteNode(
        taskId: String,
        targetNode: com.example.data.node.WastiNode,
        leaseDurationMs: Long = 30000L
    ): Boolean {
        val task = tasksMap[taskId] ?: return false
        if (targetNode.isLocal) return false

        val securityPolicy = WastiServiceLocator.securityPolicyEngine
        val canDelegate = securityPolicy.canDelegateToNode(
            node = targetNode,
            taskLocality = com.example.data.node.NodeDataLocality.TRUSTED_LAN,
            requiredCapabilities = task.requiredCapabilities
        )
        if (!canDelegate) {
            Log.w(TAG, "Security policy rejected delegating task $taskId to node ${targetNode.nodeId}")
            return false
        }

        val acquired = acquireTaskLease(taskId, targetNode.nodeId, leaseDurationMs)
        if (!acquired) return false

        task.selectedNode = targetNode.nodeId
        updateTasksFlow()
        persistTaskAsync(task)

        val server = WastiWebSocketServer.getInstance(context)
        val offered = server.sendTaskOffer(
            nodeId = targetNode.nodeId,
            taskId = task.taskId,
            title = task.title,
            prompt = task.prompt,
            requiredCapabilities = task.requiredCapabilities,
            leaseDurationMs = leaseDurationMs
        )

        if (offered) {
            eventBus.tryEmit(
                AgentEvent.NodeTaskDelegated(
                    proactiveTaskId = task.taskId,
                    targetNodeId = targetNode.nodeId
                )
            )
            return true
        } else {
            // Rollback lease if offer failed to dispatch
            releaseTaskLease(taskId, targetNode.nodeId)
            return false
        }
    }

    private fun persistTaskAsync(task: ProactiveAutonomousTask) {
        val dao = effectiveDao ?: return
        engineScope.launch(Dispatchers.IO) {
            try {
                dao.insertTask(task.toEntity())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist proactive task ${task.taskId}: ${e.message}")
            }
        }
    }

    private fun updateTasksFlow() {
        _tasksFlow.value = tasksMap.values.toList()
    }

    fun getTask(taskId: String): ProactiveAutonomousTask? = tasksMap[taskId]

    fun getAllTasks(): List<ProactiveAutonomousTask> = tasksMap.values.toList()

    fun getActiveTasks(): List<ProactiveAutonomousTask> =
        tasksMap.values.filter { it.state == ProactiveTaskState.RUNNING }

    companion object {
        private const val TAG = "WastiProactiveEngine"
        private const val POLL_INTERVAL_MS = 1000L
        private const val IDEMPOTENCY_WINDOW_MS = 60000L
        private const val MAX_CONCURRENT_PROACTIVE_TASKS = 3

        @Volatile
        private var instance: WastiProactiveAutonomousEngine? = null

        fun getInstance(context: Context? = null): WastiProactiveAutonomousEngine {
            return instance ?: synchronized(this) {
                instance ?: WastiProactiveAutonomousEngine(
                    context = context?.applicationContext
                ).also { instance = it }
            }
        }
    }
}
