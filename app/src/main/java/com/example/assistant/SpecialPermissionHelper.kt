package com.example.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers to open system settings pages so the user can grant special permissions.
 * These cannot be granted programmatically and must be enabled by the user.
 */
object SpecialPermissionHelper {
    fun openOverlayPermissionSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
        activity.startActivity(intent)
    }

    fun openAllFilesAccessSettings(activity: Activity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        }
        activity.startActivity(intent)
    }

    fun openUsageAccessSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        activity.startActivity(intent)
    }

    fun openWriteSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + activity.packageName))
        activity.startActivity(intent)
    }

    fun openAccessibilitySettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivity(intent)
    }

    fun openNotificationPolicyAccess(activity: Activity) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        activity.startActivity(intent)
    }

    /**
     * Checks whether the user has actually enabled WastiAccessibilityService in system
     * Settings. This CANNOT be true unless the user manually toggled it on — Android does
     * not allow apps to self-grant accessibility access. Use this to show a real "enable
     * screen reading" prompt in the UI instead of silently failing when it's off.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/com.example.service.WastiAccessibilityService"
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
