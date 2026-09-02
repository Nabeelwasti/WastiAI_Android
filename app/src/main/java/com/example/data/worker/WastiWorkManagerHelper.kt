package com.example.data.worker

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Universal WorkManager accessor with safe initialization fallback
 * for JVM/Robolectric test suites and headless background runtimes.
 */
object WastiWorkManagerHelper {
    private const val TAG = "WastiWorkManager"

    @Synchronized
    fun getWorkManager(context: Context): WorkManager? {
        val appContext = context.applicationContext ?: context
        return try {
            WorkManager.getInstance(appContext)
        } catch (e: IllegalStateException) {
            try {
                if (appContext is Configuration.Provider) {
                    WorkManager.initialize(appContext, appContext.getWorkManagerConfiguration())
                } else {
                    val config = Configuration.Builder()
                        .setMinimumLoggingLevel(Log.INFO)
                        .build()
                    WorkManager.initialize(appContext, config)
                }
                WorkManager.getInstance(appContext)
            } catch (initErr: Throwable) {
                Log.w(TAG, "WorkManager initialization unavailable on host runtime: ${initErr.message}")
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "WorkManager unavailable: ${e.message}")
            null
        }
    }
}
