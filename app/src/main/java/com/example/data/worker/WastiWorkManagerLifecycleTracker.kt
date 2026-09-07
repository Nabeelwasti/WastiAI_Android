package com.example.data.worker

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [P0-35] WORKMANAGER-TRUTH: Canonical Durable WorkManager Lifecycle Tracker.
 *
 * Enforces zero-fabrication rules for asynchronous background work:
 * 1. Enqueued or accepted tasks (WorkManager Operation.SUCCESS) represent SCHEDULED/ENQUEUED
 *    state, NEVER COMPLETED or VERIFIED.
 * 2. Real execution states (ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED) are tracked
 *    truthfully via actual worker lifecycle hooks and WorkManager WorkInfo queries.
 * 3. Exposes reactive [workerLifecycleFlow] for observable system telemetry.
 */
enum class WorkLifecycleState {
    NOT_SCHEDULED,
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED
}

data class DurableWorkSnapshot(
    val workName: String,
    val workerClassName: String,
    val lifecycleState: WorkLifecycleState,
    val runAttemptCount: Int = 0,
    val lastRunTimestampMs: Long = 0L,
    val isPeriodic: Boolean = true,
    val durableEvidence: String? = null
) {
    val isCompletedOrSuccess: Boolean
        get() = lifecycleState == WorkLifecycleState.SUCCEEDED

    val isPendingOrScheduled: Boolean
        get() = lifecycleState == WorkLifecycleState.ENQUEUED || lifecycleState == WorkLifecycleState.NOT_SCHEDULED
}

object WastiWorkManagerLifecycleTracker {

    private const val TAG = "WorkManagerLifecycle"

    val KNOWN_WORKERS = listOf(
        "wasti_sync_worker",
        LeadSyncWorker.WORK_NAME,
        SelfEnhancementWorker.WORK_NAME,
        ProactiveReconciliationWorker.WORK_NAME,
        MemoryDreamingWorker.WORK_NAME
    )

    private val snapshots = ConcurrentHashMap<String, DurableWorkSnapshot>()
    private val _workerLifecycleFlow = MutableStateFlow<Map<String, DurableWorkSnapshot>>(emptyMap())
    val workerLifecycleFlow: StateFlow<Map<String, DurableWorkSnapshot>> = _workerLifecycleFlow.asStateFlow()

    init {
        // Initialize declared workers in NOT_SCHEDULED or ENQUEUED state, NEVER SUCCEEDED
        for (name in KNOWN_WORKERS) {
            snapshots[name] = DurableWorkSnapshot(
                workName = name,
                workerClassName = name,
                lifecycleState = WorkLifecycleState.NOT_SCHEDULED,
                isPeriodic = true
            )
        }
        _workerLifecycleFlow.value = snapshots.toMap()
    }

    /**
     * Record when a worker has been enqueued into WorkManager.
     * State becomes ENQUEUED, strictly not COMPLETED.
     */
    @Synchronized
    fun recordWorkEnqueued(workName: String, workerClassName: String, isPeriodic: Boolean = true) {
        val existing = snapshots[workName]
        snapshots[workName] = DurableWorkSnapshot(
            workName = workName,
            workerClassName = workerClassName,
            lifecycleState = WorkLifecycleState.ENQUEUED,
            runAttemptCount = existing?.runAttemptCount ?: 0,
            lastRunTimestampMs = existing?.lastRunTimestampMs ?: 0L,
            isPeriodic = isPeriodic,
            durableEvidence = "Enqueued into WorkManager pipeline"
        )
        _workerLifecycleFlow.value = snapshots.toMap()
        Log.d(TAG, "Work enqueued: $workName (State: ENQUEUED)")
    }

    /**
     * Record when a worker actively begins execution in doWork().
     */
    @Synchronized
    fun recordWorkStarted(workName: String, workerClassName: String, runAttempt: Int) {
        val existing = snapshots[workName]
        snapshots[workName] = DurableWorkSnapshot(
            workName = workName,
            workerClassName = workerClassName,
            lifecycleState = WorkLifecycleState.RUNNING,
            runAttemptCount = runAttempt,
            lastRunTimestampMs = existing?.lastRunTimestampMs ?: 0L,
            isPeriodic = existing?.isPeriodic ?: true,
            durableEvidence = "Worker actively executing doWork()"
        )
        _workerLifecycleFlow.value = snapshots.toMap()
        Log.i(TAG, "Work execution started: $workName (attempt: $runAttempt)")
    }

    /**
     * Record when a worker finishes doWork().
     */
    @Synchronized
    fun recordWorkFinished(
        workName: String,
        workerClassName: String,
        isSuccess: Boolean,
        evidence: String? = null
    ) {
        val existing = snapshots[workName]
        val now = System.currentTimeMillis()
        val finalState = if (isSuccess) WorkLifecycleState.SUCCEEDED else WorkLifecycleState.FAILED
        snapshots[workName] = DurableWorkSnapshot(
            workName = workName,
            workerClassName = workerClassName,
            lifecycleState = finalState,
            runAttemptCount = existing?.runAttemptCount ?: 1,
            lastRunTimestampMs = now,
            isPeriodic = existing?.isPeriodic ?: true,
            durableEvidence = evidence ?: if (isSuccess) "Completed with Result.success()" else "Failed in doWork()"
        )
        _workerLifecycleFlow.value = snapshots.toMap()
        Log.i(TAG, "Work execution finished: $workName (State: $finalState)")
    }

    /**
     * Query WorkManager directly for durable state if available.
     */
    fun queryActualWorkInfo(context: Context, workName: String): DurableWorkSnapshot? {
        val wm = WastiWorkManagerHelper.getWorkManager(context) ?: return snapshots[workName]
        return try {
            val workInfos = wm.getWorkInfosForUniqueWork(workName).get()
            if (workInfos.isNullOrEmpty()) {
                snapshots[workName]
            } else {
                val info = workInfos.first()
                val mappedState = when (info.state) {
                    WorkInfo.State.ENQUEUED -> WorkLifecycleState.ENQUEUED
                    WorkInfo.State.RUNNING -> WorkLifecycleState.RUNNING
                    WorkInfo.State.SUCCEEDED -> WorkLifecycleState.SUCCEEDED
                    WorkInfo.State.FAILED -> WorkLifecycleState.FAILED
                    WorkInfo.State.BLOCKED -> WorkLifecycleState.BLOCKED
                    WorkInfo.State.CANCELLED -> WorkLifecycleState.CANCELLED
                }
                val existing = snapshots[workName]
                val snapshot = DurableWorkSnapshot(
                    workName = workName,
                    workerClassName = existing?.workerClassName ?: workName,
                    lifecycleState = mappedState,
                    runAttemptCount = info.runAttemptCount,
                    lastRunTimestampMs = existing?.lastRunTimestampMs ?: 0L,
                    isPeriodic = existing?.isPeriodic ?: true,
                    durableEvidence = "Queried from WorkManager (ID: ${info.id}, State: ${info.state})"
                )
                snapshots[workName] = snapshot
                _workerLifecycleFlow.value = snapshots.toMap()
                snapshot
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to query WorkManager WorkInfo for $workName: ${e.message}")
            snapshots[workName]
        }
    }

    fun getSnapshot(workName: String): DurableWorkSnapshot? = snapshots[workName]

    fun getAllSnapshots(): List<DurableWorkSnapshot> = snapshots.values.toList()
}
