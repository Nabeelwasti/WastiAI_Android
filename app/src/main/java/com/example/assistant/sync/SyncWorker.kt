package com.example.assistant.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.example.data.db.WastiDatabase
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.SyncResult

enum class SyncExecutionOutcome {
    FULL_SYNC_SUCCESS,
    PARTIAL_SYNC_SNAPSHOT_ONLY,
    PARTIAL_SYNC_ENTITY_ONLY,
    RETRYABLE_FAILURE,
    FAILED
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            // 1. Create a genuine verified database snapshot archive
            val snapshotResult = CloudSyncManager.createDatabaseSnapshotArchive(applicationContext)
            
            // 2. Perform entity sync to cloud if database exists
            val db = WastiDatabase.getDatabase(applicationContext)
            val syncResult = CloudSyncManager.backupToCloud(db, "default_user")

            val outcome = when {
                snapshotResult is SyncResult.SnapshotSuccess && syncResult is SyncResult.Success -> {
                    SyncExecutionOutcome.FULL_SYNC_SUCCESS
                }
                snapshotResult is SyncResult.SnapshotSuccess && syncResult !is SyncResult.Success -> {
                    SyncExecutionOutcome.PARTIAL_SYNC_SNAPSHOT_ONLY
                }
                snapshotResult !is SyncResult.SnapshotSuccess && syncResult is SyncResult.Success -> {
                    SyncExecutionOutcome.PARTIAL_SYNC_ENTITY_ONLY
                }
                else -> {
                    SyncExecutionOutcome.RETRYABLE_FAILURE
                }
            }

            Log.i(TAG, "Sync execution outcome: $outcome (Snapshot: $snapshotResult, Entities: $syncResult)")

            when (outcome) {
                SyncExecutionOutcome.FULL_SYNC_SUCCESS,
                SyncExecutionOutcome.PARTIAL_SYNC_SNAPSHOT_ONLY -> {
                    // Local snapshot secured successfully (cloud sync optional/deferred)
                    ListenableWorker.Result.success()
                }
                SyncExecutionOutcome.PARTIAL_SYNC_ENTITY_ONLY,
                SyncExecutionOutcome.RETRYABLE_FAILURE -> {
                    // Retry incomplete sync operations
                    if (runAttemptCount < 3) {
                        ListenableWorker.Result.retry()
                    } else {
                        ListenableWorker.Result.failure()
                    }
                }
                SyncExecutionOutcome.FAILED -> {
                    ListenableWorker.Result.failure()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing SyncWorker", e)
            if (runAttemptCount < 3) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }
    }
}

