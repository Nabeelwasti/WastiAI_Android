package com.example

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.memory.MemoryDatabase
import com.example.assistant.memory.MemoryMigrator
import com.example.assistant.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WastiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("WastiApplication", "Application starting — initializing assistant components")

        try {
            com.example.data.credential.CredentialRegistry.appContext = this.applicationContext
        } catch (e: Throwable) {
            Log.e("WastiApplication", "Error setting CredentialRegistry appContext", e)
        }

        // Initialize Room DB and run memory migration in background safely
        try {
            val db = MemoryDatabase.getInstance(this)
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    MemoryMigrator.migrateIfNeeded(this@WastiApplication, db)
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Error during MemoryMigrator migration", e)
                }
            }
        } catch (e: Throwable) {
            Log.e("WastiApplication", "Error initializing MemoryDatabase", e)
        }

        // Schedule periodic sync worker to back up encrypted memory (runs every 4 hours by default)
        try {
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "wasti_sync_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.i("WastiApplication", "Scheduled SyncWorker (every 4 hours)")
        } catch (e: Throwable) {
            Log.e("WastiApplication", "Failed to schedule SyncWorker safely", e)
        }
    }
}
