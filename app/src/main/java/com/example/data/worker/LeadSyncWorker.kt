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
import com.example.data.core.ClientInvoiceManager
import com.example.data.core.LeadRadarRepository
import com.example.data.core.WastiRootController
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
/ Background WorkManager Worker for Wasti AI OS.
/ Periodically polls Upwork / Freelance RSS tokens, evaluates leads,
/ persists high-match opportunities to Room SQLite, triggers push notifications,
/ and automatically reconciles Stripe invoice payments in the background.
*/
class LeadSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LeadSyncWorker"
        const val WORK_NAME = "wasti_lead_and_invoice_sync_worker"

        fun schedulePeriodicSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = PeriodicWorkRequestBuilder<LeadSyncWorker>(
                    15, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
                Log.i(TAG, "Lead & Stripe Invoice periodic WorkManager worker scheduled successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule LeadSyncWorker", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting background LeadSyncWorker execution...")
            val context = applicationContext

            // 1. Initialize DB connections
            LeadRadarRepository.initDatabase(context)
            ClientInvoiceManager.initDatabase(context)

            // 2. Poll Upwork / Freelance custom RSS feeds or target queries
            val lastQuery = LeadRadarRepository.lastSearchQuery.value.ifBlank { "Video Editing & Graphic Design" }
            val evaluatedLeads = LeadRadarRepository.scanAndEvaluateLeads(context, lastQuery)
            Log.d(TAG, "Background scan completed. Processed ${evaluatedLeads.size} leads.")

            // 3. Continuous Learning Loop: Log MatchScores and feedback into TrainingLog.json
            val matchScores = evaluatedLeads.map { Pair(it.title, it.matchScore) }
            val invoices = ClientInvoiceManager.invoicesFlow.value
            val feedbackList = invoices.mapNotNull { inv ->
                inv.clientFeedback?.let { Pair(inv.projectMilestone, it) }
            }
            WastiRootController.logTrainingMetrics(context, matchScores, feedbackList)

            // 4. Reconcile Stripe Webhook & Payment Intents
            val StripeSyncedInvoices = ClientInvoiceManager.syncPaymentsWithStripe(context)
            Log.d(TAG, "Stripe payment sync completed. Updated $StripeSyncedInvoices invoices.")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing LeadSyncWorker background task", e)
            Result.retry()
        }
    }
}
