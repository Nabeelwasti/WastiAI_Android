package com.example.data.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.db.WastiDatabase
import com.example.data.di.WastiServiceLocator
import com.example.data.proactive.WastiProactiveAutonomousEngine
import com.example.service.WastiForegroundExecutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Stage 16: Canonical Proactive Reconciliation Worker.
 * WorkManager-based periodic reconciliation that guarantees reboot durability and
 * task recovery even if the foreground execution daemon was killed by the OS.
 */
class ProactiveReconciliationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ProactiveReconciliationWorker"
        const val WORK_NAME = "wasti_proactive_reconciliation_worker"

        fun schedulePeriodicReconciliation(context: Context): Boolean {
            val wm = WastiWorkManagerHelper.getWorkManager(context) ?: run {
                Log.w(TAG, "ProactiveReconciliationWorker scheduling deferred: WorkManager unavailable in environment.")
                return false
            }
            return try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()

                val request = PeriodicWorkRequestBuilder<ProactiveReconciliationWorker>(
                    15, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                wm.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Log.i(TAG, "ProactiveReconciliationWorker scheduled successfully (15m interval).")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule ProactiveReconciliationWorker", e)
                false
            }
        }

        suspend fun performReconciliation(context: Context): Boolean {
            val db = WastiDatabase.getDatabase(context)
            val dao = db.proactiveTaskDao()
            val now = System.currentTimeMillis()

            // 1. Reclaim expired node leases
            val expiredLeases = dao.getExpiredLeaseTasks(now)
            for (task in expiredLeases) {
                Log.w(TAG, "Reconciling expired lease for task ${task.taskId} (node ${task.leaseOwnerNode})")
                dao.updateTaskLease(task.taskId, null, 0L)
            }

            // 2. Check for overdue scheduled tasks
            val dueTasks = dao.getDueScheduledTasks(now)
            if (dueTasks.isNotEmpty()) {
                Log.i(TAG, "Found ${dueTasks.size} due proactive tasks during reconciliation.")
                val engine = WastiProactiveAutonomousEngine.getInstance(context)
                engine.recoverOnBootOrProcessStart()

                if (!WastiForegroundExecutionService.isRunning) {
                    Log.i(TAG, "Starting foreground execution daemon to execute due tasks.")
                    WastiForegroundExecutionService.startDaemon(context)
                } else {
                    engine.evaluateAndRunDueTasks()
                }
            }
            return true
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting ProactiveReconciliationWorker cycle...")
            val context = applicationContext
            WastiServiceLocator.init(context)

            // Check emergency stop
            if (WastiServiceLocator.emergencyStopController.isEmergencyStopped) {
                Log.w(TAG, "Emergency Stop is active. Skipping reconciliation.")
                return@withContext Result.success()
            }

            performReconciliation(context)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in ProactiveReconciliationWorker: ${e.message}", e)
            Result.retry()
        }
    }
}
