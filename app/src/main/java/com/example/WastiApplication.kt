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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
        } catch (e: Throwable) {
            Log.w("WastiApplication", "Firebase auto-initialization skipped/deferred: ${e.message}")
        }

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
                // Critical Phase 1: Security, Credentials & Database Foundation
                currentStage = StartupStage.CREDENTIALS
                AppStartupManager.updateStageProgress(currentStage, "Initializing Credentials & Security...", 0.20f)
                val t1 = System.currentTimeMillis()
                try {
                    com.example.data.credential.CredentialRegistry.appContext = this@WastiApplication.applicationContext
                    com.example.data.credential.CredentialRegistry.seedDefaultCredentialsIfMissing(this@WastiApplication)
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Error setting CredentialRegistry appContext", e)
                    AppStartupManager.recordWarning(currentStage, "Failed to initialize CredentialRegistry context: ${e.message}")
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - t1)

                // Critical Phase 2: Room DB Warmup
                currentStage = StartupStage.DATABASE
                AppStartupManager.updateStageProgress(currentStage, "Checking Database & Persistence...", 0.35f)
                val tDb = System.currentTimeMillis()
                val db = try {
                    val instance = WastiDatabase.getDatabase(this@WastiApplication)
                    instance.openHelper.writableDatabase
                    instance
                } catch (e: Throwable) {
                    Log.e("WastiApplication", "Error initializing WastiDatabase", e)
                    AppStartupManager.recordWarning(currentStage, "Database initialization warning: ${e.message}")
                    null
                }
                AppStartupManager.recordStageCompletion(currentStage, System.currentTimeMillis() - tDb)

                // Parallel Phase: Concurrently warm up independent subsystems (AI Engine, Memory, Voice, Operations, Workers)
                AppStartupManager.updateStageProgress(StartupStage.AI_ENGINE, "Warming subsystems concurrently...", 0.60f)

                kotlinx.coroutines.coroutineScope {
                    val aiJob = async(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
                        val providers = com.example.data.ai.AIManager.capabilityRegistry.getAllProviders()
                        Log.d("WastiApplication", "AIManager initialized with ${providers.size} providers")
                        AppStartupManager.recordStageCompletion(StartupStage.AI_ENGINE, System.currentTimeMillis() - t)
                    }

                    val opsJob = async(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
                        try {
                            com.example.data.ops.OperationsManager.refreshStats()
                        } catch (e: Throwable) {
                            Log.e("WastiApplication", "OperationsManager startup warning", e)
                            AppStartupManager.recordWarning(StartupStage.OPERATIONS_ENGINE, "Telemetry stats fallback: ${e.message}")
                        }
                        AppStartupManager.recordStageCompletion(StartupStage.OPERATIONS_ENGINE, System.currentTimeMillis() - t)
                    }

                    val voiceJob = async(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
                        try {
                            val activeVoice = com.example.data.voice.VoiceManager.activeProviderId.value
                            Log.d("WastiApplication", "VoiceManager ready with active provider: $activeVoice")
                        } catch (e: Throwable) {
                            Log.e("WastiApplication", "VoiceManager startup warning", e)
                            AppStartupManager.recordWarning(StartupStage.VOICE_ENGINE, "Voice degraded: ${e.message}")
                        }
                        AppStartupManager.recordStageCompletion(StartupStage.VOICE_ENGINE, System.currentTimeMillis() - t)
                    }

                    val memoryJob = async(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
                        try {
                            com.example.data.log.DeveloperLogger.initialize(this@WastiApplication)
                            if (db != null) {
                                com.example.data.memory.MemoryManager.initialize(db.memoryDao())
                            }
                        } catch (e: Throwable) {
                            Log.e("WastiApplication", "MemoryManager startup warning", e)
                            AppStartupManager.recordWarning(StartupStage.MEMORY_ENGINE, "Memory fallback: ${e.message}")
                        }
                        AppStartupManager.recordStageCompletion(StartupStage.MEMORY_ENGINE, System.currentTimeMillis() - t)
                    }

                    val workerJob = async(Dispatchers.IO) {
                        val t = System.currentTimeMillis()
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
                            com.example.data.worker.ProactiveReconciliationWorker.schedulePeriodicReconciliation(this@WastiApplication)
                        } catch (e: Throwable) {
                            Log.e("WastiApplication", "Failed to schedule SyncWorker safely", e)
                            AppStartupManager.recordWarning(StartupStage.SYNC_WORKER, "Sync worker deferred: ${e.message}")
                        }
                        AppStartupManager.recordStageCompletion(StartupStage.SYNC_WORKER, System.currentTimeMillis() - t)
                    }

                    // Await all parallel warmup jobs
                    kotlinx.coroutines.awaitAll(aiJob, opsJob, voiceJob, memoryJob, workerJob)
                }

                // Stage 8: Complete
                currentStage = StartupStage.COMPLETE
                AppStartupManager.updateStageProgress(currentStage, "Wasti AI OS Core Ready", 1.0f)
                delay(100) // Smooth transition feel
                AppStartupManager.setReady()
                Log.i("WastiApplication", "All core subsystems initialized concurrently & successfully. AppStartupManager set to Ready.")
            } catch (e: Throwable) {
                Log.e("WastiApplication", "Error during startup in stage [${currentStage.name}]", e)
                if (currentStage.isCritical) {
                    AppStartupManager.setFatalError(currentStage, "Critical subsystem initialization failure: ${e.localizedMessage}", e)
                } else {
                    AppStartupManager.recordWarning(currentStage, "Optional subsystem startup issue handled via fallback: ${e.localizedMessage}")
                    AppStartupManager.setReady()
                }
            }
        }
    }
}


