package com.example.data.wre

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 9A: WRE Process and Job Manager
 * Tracks execution IDs (WRE-000001...), live processes, background tasks, and provides lifecycle controls.
 */
data class OutputChunk(
    val processId: String,
    val text: String,
    val isStderr: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class WreProcessManager {

    private val processSequence = AtomicInteger(1000)
    private val activeProcesses = ConcurrentHashMap<String, WastiProcess>()
    private val jobs = ConcurrentHashMap<String, WastiJob>()
    private val _outputFlow = MutableSharedFlow<OutputChunk>(extraBufferCapacity = 500, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outputFlow: SharedFlow<OutputChunk> = _outputFlow.asSharedFlow()

    fun createProcess(request: ExecutionRequest, providerName: String = "NativeCommandProvider"): WastiProcess {
        val pid = "WRE-${processSequence.incrementAndGet()}"
        val process = WastiProcess(
            processId = pid,
            executionRequest = request,
            status = ExecutionStatus.QUEUED,
            startTime = System.currentTimeMillis(),
            providerName = providerName
        )
        activeProcesses[pid] = process
        return process
    }

    fun emitStdoutChunk(pid: String, chunk: String, isStderr: Boolean = false) {
        val process = activeProcesses[pid]
        if (process != null) {
            if (isStderr) {
                process.stderr.append(chunk)
            } else {
                process.stdout.append(chunk)
            }
        }
        _outputFlow.tryEmit(OutputChunk(processId = pid, text = chunk, isStderr = isStderr))
    }

    fun getProcess(pid: String): WastiProcess? = activeProcesses[pid]

    fun listActiveProcesses(): List<WastiProcess> = activeProcesses.values.toList()

    fun updateProcessStatus(pid: String, status: ExecutionStatus, exitCode: Int? = null) {
        val p = activeProcesses[pid] ?: return
        val updated = p.copy(
            status = status,
            exitCode = exitCode,
            endTime = if (status != ExecutionStatus.RUNNING && status != ExecutionStatus.QUEUED) System.currentTimeMillis() else null
        )
        activeProcesses[pid] = updated
    }

    fun submitJob(name: String, request: ExecutionRequest, isBackground: Boolean = false): WastiJob {
        val jobId = "JOB-${processSequence.incrementAndGet()}"
        val job = WastiJob(
            jobId = jobId,
            name = name,
            request = request,
            status = ExecutionStatus.QUEUED,
            isBackground = isBackground
        )
        jobs[jobId] = job
        return job
    }

    fun getJob(jobId: String): WastiJob? = jobs[jobId]

    fun listJobs(): List<WastiJob> = jobs.values.sortedByDescending { it.submittedAt }

    fun updateJobResult(jobId: String, result: ExecutionResult) {
        val job = jobs[jobId] ?: return
        jobs[jobId] = job.copy(
            status = result.status,
            completedAt = System.currentTimeMillis(),
            result = result
        )
    }

    fun killProcess(pid: String): Boolean {
        val p = activeProcesses[pid]
        return if (p != null && (p.status == ExecutionStatus.RUNNING || p.status == ExecutionStatus.QUEUED)) {
            updateProcessStatus(pid, ExecutionStatus.CANCELLED, exitCode = 130)
            true
        } else {
            false
        }
    }
}
