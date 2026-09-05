package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Stage 10/16 Doze Shield: Utility helper for managing battery optimization / Doze mode exemptions
 * to keep Wasti AI OS background execution daemon resilient across aggressive OEM power managers.
 */
object WastiBatteryOptimizationHelper {

    private const val TAG = "WastiBatteryHelper"

    /**
     * Checks if the app is currently whitelisted / ignoring battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking battery optimization status: ${e.message}")
            true
        }
    }

    /**
     * Returns an Intent to request ignoring battery optimizations for this app package.
     * Falls back to general battery optimization settings if direct request fails or is unavailable.
     */
    @SuppressLint("BatteryLife")
    fun createRequestIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating direct request intent, falling back to settings: ${e.message}")
                createBatteryOptimizationSettingsIntent()
            }
        } else {
            createBatteryOptimizationSettingsIntent()
        }
    }

    /**
     * Returns an Intent targeting the system Battery Optimization settings page.
     */
    fun createBatteryOptimizationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * Launches the system dialog / settings to request ignoring battery optimizations.
     * Safe to call from UI/Activity contexts; handles exceptions gracefully.
     */
    fun openIgnoreBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            val intent = createRequestIgnoreBatteryOptimizationsIntent(context)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch direct battery intent, attempting settings: ${e.message}")
            try {
                val fallbackIntent = createBatteryOptimizationSettingsIntent()
                context.startActivity(fallbackIntent)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Unable to launch battery optimization settings: ${fallbackEx.message}")
                false
            }
        }
    }
}
