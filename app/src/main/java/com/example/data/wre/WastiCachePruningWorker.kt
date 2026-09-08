package com.example.data.wre

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background WorkManager Worker:
 * Automatically prunes stale scratch artifacts and temporary logs older than 7 days,
 * ensuring device storage hygiene without degrading active models or user memory.
 */
class WastiCachePruningWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val workspaceManager = WreWorkspaceManager(applicationContext)
            val report = workspaceManager.pruneStaleArtifacts()
            Log.i(
                TAG,
                "Autonomous cache pruning complete. Scanned: ${report.scannedFilesCount}, " +
                        "Pruned: ${report.prunedFilesCount}, Reclaimed: ${report.reclaimedBytes} bytes"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cache pruning worker encountered error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CachePruningWorker"
        const val WORK_NAME = "wasti_periodic_cache_prune"
    }
}
