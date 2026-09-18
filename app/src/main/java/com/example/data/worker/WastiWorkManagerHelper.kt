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
            } catch (_: Throwable) {
                // In Robolectric host tests, unconfigured WorkManager is safely skipped
                Log.d(TAG, "Host test environment detected: WorkManager skipped in JVM host harness")
                null
            }
        }

        return try {
            WorkManager.getInstance(appContext)
        } catch (_: IllegalStateException) {
            try {
                if (appContext is Configuration.Provider) {
                    // Current WorkManager/Kotlin contract exposes the provider configuration
                    // as a property. Preserve the application's authoritative configuration
                    // instead of constructing a second, divergent configuration here.
                    WorkManager.initialize(appContext, appContext.workManagerConfiguration)
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
