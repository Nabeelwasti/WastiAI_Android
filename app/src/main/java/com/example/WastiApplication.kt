package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.assistant.sync.SyncWorker
import com.example.data.core.AppStartupManager
import com.example.data.core.StartupStage
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class WastiApplication : Application() {

    companion object {
        var instance: WastiApplication? = null
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.example.data.di.WastiServiceLocator.init(this)
        Log.i("WastiApplication", "Wasti AI OS Application starting — initializing core subsystems")

        // Install Global Uncaught Exception Handler for Crash Telemetry & Debugging
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("WastiOS_CrashTelemetry", "FATAL UNCAUGHT EXCEPTION in thread [${thread.name}]", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        initializeSubsystems()
    }

    fun initializeSubsystems() {
        CoroutineScope(Dispatchers.Default).launch {
            AppStartupManager.startStartupTrace()
            var currentStage = StartupStage.CREDENTIALS

            try {
                // Stage 1: Credentials & Security (Critical)
                currentStage = StartupStage.CREDENTIALS
                AppStartupManager.updateStageProgress(currentStage, "Initializing Credentials & Security...", 0.15f)
                val t1 = System.currentTimeMillis()
                try {
                    com.example.data.credential.CredentialRegistry.appContext = this@WastiApplication.applicationContext
                    com.example.data.credential.CredentialRegistry.seedDefaultCredentialsIfMissing(this@WastiApplication)
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Error setting CredentialRegistry appContext", e)
                    AppStartupManager.recordWarning(currentStage, "Failed to initialize CredentialRegistry context: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t1)

                // Stage 2: AI Engine & Providers (Critical)
                currentStage = StartupStage.AI_ENGINE
                AppStartupManager.updateStageProgress(currentStage, "Warming AI Engine & Providers...", 0.35f)
                val t2 = System.currentTimeMillis()
                val providers = com.example.data.ai.AIManager.capabilityRegistry.getAllProviders()
                Log.d("WastiApplication", "AIManager initialized with ${providers.size} providers")
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t2)

                // Stage 3: Operations & Telemetry (Optional)
                currentStage = StartupStage.OPERATIONS_ENGINE
                AppStartupManager.updateStageProgress(currentStage, "Initializing Operations & Telemetry...", 0.50f)
                val t3 = System.currentTimeMillis()
                try {
                    com.example.data.ops.OperationsManager.refreshStats()
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "OperationsManager startup warning", e)
                    AppStartupManager.recordWarning(currentStage, "Telemetry stats fallback enabled: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t3)

                // Stage 4: Voice Synthesizer (Optional)
                currentStage = StartupStage.VOICE_ENGINE
                AppStartupManager.updateStageProgress(currentStage, "Configuring Voice Synthesizer...", 0.65f)
                val t4 = System.currentTimeMillis()
                try {
                    val activeVoice = com.example.data.voice.VoiceManager.activeProviderId.value
                    Log.d("WastiApplication", "VoiceManager ready with active provider: $activeVoice")
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "VoiceManager startup warning", e)
                    AppStartupManager.recordWarning(currentStage, "Voice engine operating in degraded mode: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t4)

                // Stage 5: Memory & Knowledge Graph (Optional)
                currentStage = StartupStage.MEMORY_ENGINE
                AppStartupManager.updateStageProgress(currentStage, "Mounting Memory & Knowledge Graph...", 0.80f)
                val t5 = System.currentTimeMillis()
                try {
                    com.example.data.log.DeveloperLogger.initialize(this@WastiApplication)
                    val db = WastiDatabase.getDatabase(this@WastiApplication)
                    com.example.data.memory.MemoryManager.initialize(db.memoryDao())
                    val memStats = com.example.data.memory.MemoryManager.getObservabilityStats()
                    Log.d("WastiApplication", "MemoryManager initialized: $memStats")
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "MemoryManager startup warning", e)
                    AppStartupManager.recordWarning(currentStage, "Memory engine fallback enabled: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t5)

                // Stage 6: Room DB & Migration (Critical)
                currentStage = StartupStage.DATABASE
                AppStartupManager.updateStageProgress(currentStage, "Checking Database & Persistence...", 0.90f)
                val t6 = System.currentTimeMillis()
                try {
                    val db = WastiDatabase.getDatabase(this@WastiApplication)
                    db.openHelper.writableDatabase
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Error initializing WastiDatabase", e)
                    AppStartupManager.recordWarning(currentStage, "Database initialization warning: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t6)

                // Stage 7: Background Sync Worker (Optional)
                currentStage = StartupStage.SYNC_WORKER
                AppStartupManager.updateStageProgress(currentStage, "Scheduling Periodic Backup & Lead/Invoice Sync...", 0.95f)
                val t7 = System.currentTimeMillis()
                try {
                    com.example.data.core.LeadRadarRepository.initDatabase(this@WastiApplication)
                    com.example.data.core.ClientInvoiceManager.initDatabase(this@WastiApplication)

                    val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS).build()
                    WorkManager.getInstance(this@WastiApplication).enqueueUniquePeriodicWork(
                        "wasti_sync_worker",
                        ExistingPeriodicWorkPolicy.KEEP,
                        syncRequest
                    )

                    com.example.data.worker.LeadSyncWorker.schedulePeriodicSync(this@WastiApplication)
                    com.example.data.worker.SelfEnhancementWorker.schedulePeriodicSelfEnhancement(this@WastiApplication)
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Failed to schedule SyncWorker safely", e)
                    AppStartupManager.recordWarning(currentStage, "Sync worker background scheduling deferred: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t7)

                // Stage 8: Complete
                currentStage = StartupStage.COMPLETE
                AppStartupManager.updateStageProgress(currentStage, "Wasti AI OS Core Ready", 1.0f)
                delay(200) // Smooth transition feel
                AppStartupManager.setReady()
                Log.i("WastiApplication", "All core subsystems initialized successfully. AppStartupManager set to Ready.")
            } catch (e: Throwable) {
                Log.e("WastiApplication", "Error during startup in stage [${currentStage.name}]", e)
                AppStartupManager.recordWarning(currentStage, "Subsystem startup issue resolved via fallback: ${e.localizedMessage}")
                AppStartupManager.setReady()
            }
        }
    }
}


