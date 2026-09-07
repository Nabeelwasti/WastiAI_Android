package com.example.data.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.example.data.memory.MemoryDreamingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * [The Eternal Manifesto: The Memory of the Human & Continuous Evolution Engine]
 *
 * Dedicated overnight sleep-time memory consolidation worker for Wasti AI OS.
 * Runs strictly when:
 * 1. Device is plugged in and charging (`setRequiresCharging(true)`).
 * 2. Device is in idle sleep mode (`setRequiresDeviceIdle(true)` on API 23+).
 * 3. Network is unmetered or not roaming (`NetworkType.NOT_ROAMING`).
 */
class MemoryDreamingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MemoryDreamingWorker"
        const val WORK_NAME = "wasti_memory_dreaming_periodic_worker"

        fun schedulePeriodicDreaming(context: Context): Boolean {
            val wm = WastiWorkManagerHelper.getWorkManager(context) ?: run {
                Log.w(TAG, "MemoryDreamingWorker scheduling deferred: WorkManager unavailable in environment.")
                return false
            }

            return try {
                val constraintBuilder = Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.NOT_ROAMING)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    constraintBuilder.setRequiresDeviceIdle(true)
                }

                val request = PeriodicWorkRequestBuilder<MemoryDreamingWorker>(
                    24, TimeUnit.HOURS,
                    4, TimeUnit.HOURS // 4-hour flex window overnight
                )
                    .setConstraints(constraintBuilder.build())
                    .build()

                wm.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

                WastiWorkManagerLifecycleTracker.recordWorkEnqueued(WORK_NAME, "MemoryDreamingWorker", isPeriodic = true)
                Log.i(TAG, "MemoryDreamingWorker successfully scheduled for overnight charging/idle execution.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule MemoryDreamingWorker", e)
                false
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        WastiWorkManagerLifecycleTracker.recordWorkStarted(WORK_NAME, "MemoryDreamingWorker", runAttemptCount)
        try {
            Log.i(TAG, "Starting overnight Memory Dreaming consolidation cycle...")
            val result = MemoryDreamingEngine.executeDreamingCycle(applicationContext)

            val summary = "Consolidated ${result.memoriesConsolidated} memories, resolved ${result.contradictionsResolved} contradictions, extracted ${result.triplesExtracted} triples."
            Log.i(TAG, "Overnight dreaming complete: $summary in ${result.durationMs}ms.")

            WastiWorkManagerLifecycleTracker.recordWorkFinished(
                WORK_NAME,
                "MemoryDreamingWorker",
                true,
                summary
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during MemoryDreamingWorker execution: ${e.message}", e)
            WastiWorkManagerLifecycleTracker.recordWorkFinished(
                WORK_NAME,
                "MemoryDreamingWorker",
                false,
                "Exception: ${e.message}"
            )
            Result.retry()
        }
    }
}
