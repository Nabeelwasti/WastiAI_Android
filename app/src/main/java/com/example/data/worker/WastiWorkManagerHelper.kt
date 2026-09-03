package com.example.data.worker

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.data.security.WastiSecureStorage

/**
 * Universal WorkManager accessor with safe environment-aware handling
 * for physical Android runtime vs JVM/Robolectric test suites.
 */
object WastiWorkManagerHelper {
    private const val TAG = "WastiWorkManager"

    @Synchronized
    fun getWorkManager(context: Context): WorkManager? {
        val appContext = context.applicationContext ?: context
        if (WastiSecureStorage.isRobolectricHost) {
            return try {
                WorkManager.getInstance(appContext)
            } catch (e: Throwable) {
                // In Robolectric host tests, unconfigured WorkManager is safely skipped
                Log.d(TAG, "Host test environment detected: WorkManager skipped in JVM host harness")
                null
            }
        }

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
                Log.e(TAG, "FATAL: WorkManager initialization failed on physical Android runtime: ${initErr.message}", initErr)
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL: WorkManager unavailable on physical Android runtime: ${e.message}", e)
            null
        }
    }
}
