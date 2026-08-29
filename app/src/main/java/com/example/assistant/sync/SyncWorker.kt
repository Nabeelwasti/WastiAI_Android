package com.example.assistant.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.example.data.db.WastiDatabase
import com.example.data.sync.CloudSyncManager
import com.example.data.sync.SyncResult

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            // 1. Create a genuine verified database snapshot archive
            val snapshotResult = CloudSyncManager.createDatabaseSnapshotArchive(applicationContext)
            
            // 2. Perform entity sync to cloud if database exists
            val db = WastiDatabase.getDatabase(applicationContext)
            val syncResult = CloudSyncManager.backupToCloud(db, "default_user")

            if (snapshotResult is SyncResult.SnapshotSuccess || syncResult is SyncResult.Success) {
                ListenableWorker.Result.success()
            } else {
                ListenableWorker.Result.retry()
            }
        } catch (e: Exception) {
            ListenableWorker.Result.retry()
        }
    }
}

