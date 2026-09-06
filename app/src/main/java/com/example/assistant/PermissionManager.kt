package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Enterprise Runtime Permission Sentinel for Wasti AI OS.
 * Provides central, truthful capability checks and rationale flows for dangerous
 * and special runtime permissions across Android 8 through Android 14+ (API 34/35).
 */
object PermissionManager {

    /**
     * Checks if RECORD_AUDIO runtime permission is granted.
     */
    fun hasRecordAudio(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if POST_NOTIFICATIONS runtime permission is granted (Android 13+, API 33).
     * On earlier versions, checks if notifications are enabled via NotificationManagerCompat.
     */
    fun hasPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Checks if SYSTEM_ALERT_WINDOW (overlay permission) is granted via Settings.canDrawOverlays.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Checks if SCHEDULE_EXACT_ALARM is allowed (Android 12+, API 31).
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * Checks if calendar read/write permissions are granted.
     */
    fun hasCalendarPermissions(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Checks if Bluetooth permissions are granted.
     * On Android 12+ (API 31+), checks BLUETOOTH_CONNECT and BLUETOOTH_SCAN.
     * On older versions, checks BLUETOOTH and BLUETOOTH_ADMIN.
     */
    fun hasBluetoothPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val scan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            connect && scan
        } else {
            val bt = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
            val btAdmin = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
            bt && btAdmin
        }
    }

    /**
     * Returns a map of all dangerous and special permissions with their current grant status.
     */
    fun getPermissionAuditMap(context: Context): Map<String, Boolean> {
        return mapOf(
            "RECORD_AUDIO" to hasRecordAudio(context),
            "POST_NOTIFICATIONS" to hasPostNotifications(context),
            "SYSTEM_ALERT_WINDOW" to canDrawOverlays(context),
            "SCHEDULE_EXACT_ALARM" to canScheduleExactAlarms(context),
            "CALENDAR" to hasCalendarPermissions(context),
            "BLUETOOTH" to hasBluetoothPermissions(context)
        )
    }

    /* ---------------------------------------------------------------------- */
    /* [P0-36] PERMISSION-TRUTH: Explicit User Consent & OS Grant Segregation */
    /* ---------------------------------------------------------------------- */

    enum class PermissionTruthStatus {
        DECLARED_ONLY,
        FULLY_AUTHORIZED,
        DENIED_BY_OS,
        DENIED_BY_USER_POLICY,
        NOT_DECLARED
    }

    data class PermissionTruthReport(
        val permission: String,
        val isDeclaredInManifest: Boolean,
        val isGrantedByOs: Boolean,
        val isUserConsentGranted: Boolean,
        val canExecute: Boolean,
        val status: PermissionTruthStatus,
        val details: String
    )

    private val userConsentMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun setUserConsent(permissionOrCapability: String, consented: Boolean) {
        userConsentMap[permissionOrCapability] = consented
    }

    fun hasUserConsent(permissionOrCapability: String): Boolean {
        return userConsentMap[permissionOrCapability] ?: defaultConsentFor(permissionOrCapability)
    }

    fun clearUserConsents() {
        userConsentMap.clear()
    }

    private fun defaultConsentFor(permissionOrCapability: String): Boolean {
        val sensitive = setOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            "SYSTEM_ALERT_WINDOW",
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            "ANDROID_CONTROL",
            "ACCESSIBILITY"
        )
        // Sensitive permissions require explicit opt-in / user consent policy
        return permissionOrCapability !in sensitive
    }

    fun isDeclaredInManifest(context: Context, permission: String): Boolean {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(permission) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun isPermissionGrantedByOs(context: Context, permission: String): Boolean {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> hasRecordAudio(context)
            Manifest.permission.POST_NOTIFICATIONS -> hasPostNotifications(context)
            "SYSTEM_ALERT_WINDOW", Manifest.permission.SYSTEM_ALERT_WINDOW -> canDrawOverlays(context)
            Manifest.permission.SCHEDULE_EXACT_ALARM -> canScheduleExactAlarms(context)
            Manifest.permission.READ_CALENDAR -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            Manifest.permission.WRITE_CALENDAR -> ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            Manifest.permission.BLUETOOTH_CONNECT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            }
            Manifest.permission.CALL_PHONE -> ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            else -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Truth Invariant [P0-36]:
     * Declared != Granted.
     * Declared in AndroidManifest.xml is only a static declaration of capability.
     * Live execution strictly requires OS runtime grant AND user consent.
     */
    fun verifyPermissionTruth(context: Context, permission: String): PermissionTruthReport {
        val declared = isDeclaredInManifest(context, permission)
        val osGranted = isPermissionGrantedByOs(context, permission)
        val consentGranted = hasUserConsent(permission)

        val status = when {
            !declared -> PermissionTruthStatus.NOT_DECLARED
            !osGranted -> PermissionTruthStatus.DECLARED_ONLY
            !consentGranted -> PermissionTruthStatus.DENIED_BY_USER_POLICY
            else -> PermissionTruthStatus.FULLY_AUTHORIZED
        }

        val canExecute = declared && osGranted && consentGranted

        val details = when (status) {
            PermissionTruthStatus.NOT_DECLARED -> "Permission [$permission] is NOT declared in AndroidManifest.xml"
            PermissionTruthStatus.DECLARED_ONLY -> "Permission [$permission] is declared in manifest but NOT granted by Android OS"
            PermissionTruthStatus.DENIED_BY_USER_POLICY -> "Permission [$permission] is granted by Android OS but blocked by user consent policy"
            PermissionTruthStatus.FULLY_AUTHORIZED -> "Permission [$permission] is declared, granted by OS, and consented by user policy"
            PermissionTruthStatus.DENIED_BY_OS -> "Permission [$permission] explicitly denied by OS"
        }

        return PermissionTruthReport(
            permission = permission,
            isDeclaredInManifest = declared,
            isGrantedByOs = osGranted,
            isUserConsentGranted = consentGranted,
            canExecute = canExecute,
            status = status,
            details = details
        )
    }

    fun canExecuteWithPermission(context: Context, permission: String): Boolean {
        return verifyPermissionTruth(context, permission).canExecute
    }

    /**
     * Displays an explanation dialog before requesting permission if rationale is required.
     */
    fun showRationaleAndRequest(
        activity: Activity,
        permission: String,
        launcher: ActivityResultLauncher<String>,
        message: String
    ) {
        try {
            if (activity.shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(activity)
                    .setTitle("Permission Needed")
                    .setMessage(message)
                    .setPositiveButton("Grant") { dialog: DialogInterface, _ ->
                        launcher.launch(permission)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                launcher.launch(permission)
            }
        } catch (t: Throwable) {
            // fail-safe: try to launch anyway
            try { launcher.launch(permission) } catch (_: Throwable) {}
        }
    }
}
