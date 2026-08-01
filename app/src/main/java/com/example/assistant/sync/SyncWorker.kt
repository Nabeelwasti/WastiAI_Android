package com.example.assistant.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker

/**
 * Minimal SyncWorker implementation for CI/build purposes. Replace with real sync
 * logic that uploads encrypted memory to the backend when available.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        // no-op sync for CI
        return ListenableWorker.Result.success()
    }
}
