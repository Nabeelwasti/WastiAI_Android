package com.example.data.workflow

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 9E: Workflow Job Manager.
 * Manages background asynchronous workflow jobs, progress streaming, job tracking,
 * cancellation, and life-cycle events.
 */
class WorkflowJobManager(
    private val context: Context? = null,
    private val workflowEngine: UnifiedWorkflowEngine = UnifiedWorkflowEngine.getInstance(context)
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val jobsMap = ConcurrentHashMap<String, WorkflowJob>()
    private val coroutineJobsMap = ConcurrentHashMap<String, Job>()

    private val _activeJobsFlow = MutableStateFlow<List<WorkflowJob>>(emptyList())
    val activeJobsFlow: StateFlow<List<WorkflowJob>> = _activeJobsFlow.asStateFlow()

    companion object {
        @Volatile
        private var instance: WorkflowJobManager? = null

        fun getInstance(context: Context? = null): WorkflowJobManager =
            instance ?: synchronized(this) {
                instance ?: WorkflowJobManager(context = context).also { instance = it }
            }
    }

    fun submitWorkflow(
        task: WorkflowTask,
        name: String = "Autonomous Workflow: ${task.originalRequest.take(30)}",
        targetContext: Context? = null
    ): WorkflowJob {
        val job = WorkflowJob(
            taskId = task.taskId,
            name = name,
            status = WorkflowJobStatus.QUEUED
        )
        jobsMap[job.jobId] = job
        updateFlow()

        val coroutineJob = scope.launch {
            job.status = WorkflowJobStatus.RUNNING
            updateFlow()

            val result = workflowEngine.executeWorkflow(task, targetContext ?: context)
            job.result = result
            job.progress = 1.0f
            job.status = if (result.isSuccess) WorkflowJobStatus.COMPLETED else if (task.isCancelled) WorkflowJobStatus.CANCELLED else WorkflowJobStatus.FAILED
            job.completedAt = System.currentTimeMillis()
            job.logs.addAll(task.logs)
            updateFlow()
        }

        coroutineJobsMap[job.jobId] = coroutineJob
        return job
    }

    fun cancelJob(jobId: String): Boolean {
        val job = jobsMap[jobId] ?: return false
        workflowEngine.cancelTask(job.taskId)
        val coroutine = coroutineJobsMap[jobId]
        coroutine?.cancel()
        job.status = WorkflowJobStatus.CANCELLED
        job.completedAt = System.currentTimeMillis()
        job.logs.add("[CANCELLED] Job manually cancelled by JobManager")
        updateFlow()
        return true
    }

    fun getJob(jobId: String): WorkflowJob? = jobsMap[jobId]

    fun getAllJobs(): List<WorkflowJob> = jobsMap.values.toList()

    private fun updateFlow() {
        _activeJobsFlow.value = jobsMap.values.toList()
    }
}
