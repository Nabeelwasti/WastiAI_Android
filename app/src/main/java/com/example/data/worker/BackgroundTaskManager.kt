package com.example.data.worker

import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskType {
    EMBEDDING_GENERATION,
    MEMORY_CLEANUP,
    GRAPH_REBUILD,
    DATABASE_MAINTENANCE,
    PLUGIN_UPDATES,
    TELEMETRY_SYNC,
    ANALYTICS
}

enum class TaskState {
    SCHEDULED,
    RUNNING,
    COMPLETED,
    FAILED
}

data class BackgroundJob(
    val id: String,
    val name: String,
    val type: TaskType,
    val intervalMs: Long = 0L,
    val isRecurring: Boolean = false,
    var state: TaskState = TaskState.SCHEDULED,
    var lastRunTimestamp: Long = 0L,
    var runCount: Int = 0
)

object BackgroundTaskManager {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, BackgroundJob>()
    private val jobCoroutines = ConcurrentHashMap<String, Job>()

    private val _jobsStateFlow = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val jobsStateFlow: StateFlow<List<BackgroundJob>> = _jobsStateFlow.asStateFlow()

    init {
        // Register default recurring background maintenance tasks
        scheduleRecurringTask(
            name = "Memory Decay & Cleanup",
            type = TaskType.MEMORY_CLEANUP,
            intervalMs = 300_000L // every 5 minutes
        ) {
            com.example.data.memory.MemoryManager.getObservabilityStats()
        }

        scheduleRecurringTask(
            name = "Telemetry & Cost Sync",
            type = TaskType.TELEMETRY_SYNC,
            intervalMs = 60_000L // every 1 minute
        ) {
            com.example.data.ops.OperationsManager.refreshStats()
        }
    }

    fun scheduleOneOffTask(
        name: String,
        type: TaskType,
        block: suspend () -> Unit
    ): String {
        val id = "job_${UUID.randomUUID()}"
        val job = BackgroundJob(
            id = id,
            name = name,
            type = type,
            isRecurring = false
        )
        activeJobs[id] = job
        updateJobsFlow()

        val coroutineJob = scope.launch {
            runJobInternal(job, block)
        }
        jobCoroutines[id] = coroutineJob
        return id
    }

    fun scheduleRecurringTask(
        name: String,
        type: TaskType,
        intervalMs: Long,
        block: suspend () -> Unit
    ): String {
        val id = "job_rec_${type.name.lowercase()}"
        val job = BackgroundJob(
            id = id,
            name = name,
            type = type,
            intervalMs = intervalMs,
            isRecurring = true
        )
        activeJobs[id] = job
        updateJobsFlow()

        val coroutineJob = scope.launch {
            while (true) {
                runJobInternal(job, block)
                delay(intervalMs)
            }
        }
        jobCoroutines[id] = coroutineJob
        return id
    }

    private suspend fun runJobInternal(job: BackgroundJob, block: suspend () -> Unit) {
        try {
            job.state = TaskState.RUNNING
            updateJobsFlow()
            block()
            job.state = TaskState.COMPLETED
            job.lastRunTimestamp = System.currentTimeMillis()
            job.runCount += 1
            WastiEventBus.emit(WastiEvent.SyncCompleted(job.name, true))
        } catch (e: Exception) {
            job.state = TaskState.FAILED
            WastiEventBus.emit(WastiEvent.SystemAlert("ERROR", "Background task failed: ${job.name} - ${e.message}"))
        } finally {
            updateJobsFlow()
        }
    }

    fun cancelTask(id: String) {
        jobCoroutines[id]?.cancel()
        jobCoroutines.remove(id)
        activeJobs.remove(id)
        updateJobsFlow()
    }

    private fun updateJobsFlow() {
        _jobsStateFlow.value = activeJobs.values.toList()
    }
}
