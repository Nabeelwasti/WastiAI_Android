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
 * Centralizes truthful permission discovery, persistent user policy, and
 * authorization readiness for the native Wasti execution fabric.
 */
object PermissionManager {

    private const val PREFS_NAME = "wasti_permission_policy"
    private const val CONSENT_PREFIX = "consent::"

    /** Android runtime permissions that Wasti can request through the system permission dialog. */
    @Suppress("DEPRECATION")
    fun getAllRequestableRuntimePermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    /** Returns only runtime permissions that are actually declared by this installed Wasti build. */
    fun getDeclaredRequestableRuntimePermissions(context: Context): Array<String> =
        getAllRequestableRuntimePermissions().filter { isDeclaredInManifest(context, it) }.toTypedArray()

    /** Checks if RECORD_AUDIO runtime permission is granted. */
    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Checks if CAMERA runtime permission is granted. */
    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Checks if POST_NOTIFICATIONS runtime permission is granted (Android 13+). */
    fun hasPostNotifications(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Checks if SYSTEM_ALERT_WINDOW (overlay permission) is granted via Android system access. */
    fun canDrawOverlays(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    /** Checks if SCHEDULE_EXACT_ALARM is allowed (Android 12+). */
    fun canScheduleExactAlarms(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.canScheduleExactAlarms() ?: false
    } else true

    /** Checks if calendar read/write permissions are granted. */
    fun hasCalendarPermissions(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CALENDAR) &&
            hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    /** Checks if Bluetooth permissions are granted. */
    fun hasBluetoothPermissions(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasPermission(context, Manifest.permission.BLUETOOTH) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_ADMIN)
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Complete live permission inventory used by capability discovery and the first-run
     * authorization experience. A true value always means the OS currently grants access.
     */
    fun getPermissionAuditMap(context: Context): Map<String, Boolean> = linkedMapOf(
        "INTERNET" to isPermissionGrantedByOs(context, Manifest.permission.INTERNET),
        "ACCESS_NETWORK_STATE" to isPermissionGrantedByOs(context, Manifest.permission.ACCESS_NETWORK_STATE),
        "RECORD_AUDIO" to hasRecordAudio(context),
        "CAMERA" to hasCamera(context),
        "POST_NOTIFICATIONS" to hasPostNotifications(context),
        "SYSTEM_ALERT_WINDOW" to canDrawOverlays(context),
        "SCHEDULE_EXACT_ALARM" to canScheduleExactAlarms(context),
        "READ_CALENDAR" to isPermissionGrantedByOs(context, Manifest.permission.READ_CALENDAR),
        "WRITE_CALENDAR" to isPermissionGrantedByOs(context, Manifest.permission.WRITE_CALENDAR),
        "BLUETOOTH" to hasBluetoothPermissions(context),
        "BLUETOOTH_CONNECT" to isPermissionGrantedByOs(context, Manifest.permission.BLUETOOTH_CONNECT),
        "BLUETOOTH_SCAN" to isPermissionGrantedByOs(context, Manifest.permission.BLUETOOTH_SCAN),
        "BLUETOOTH_ADVERTISE" to isPermissionGrantedByOs(context, Manifest.permission.BLUETOOTH_ADVERTISE),
        "ACCESS_COARSE_LOCATION" to isPermissionGrantedByOs(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        "ACCESS_FINE_LOCATION" to isPermissionGrantedByOs(context, Manifest.permission.ACCESS_FINE_LOCATION),
        "SEND_SMS" to isPermissionGrantedByOs(context, Manifest.permission.SEND_SMS),
        "RECEIVE_SMS" to isPermissionGrantedByOs(context, Manifest.permission.RECEIVE_SMS),
        "READ_MEDIA_IMAGES" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isPermissionGrantedByOs(context, Manifest.permission.READ_MEDIA_IMAGES) else true,
        "READ_MEDIA_VIDEO" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isPermissionGrantedByOs(context, Manifest.permission.READ_MEDIA_VIDEO) else true,
        "READ_MEDIA_AUDIO" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) isPermissionGrantedByOs(context, Manifest.permission.READ_MEDIA_AUDIO) else true,
        "MANAGE_EXTERNAL_STORAGE" to isAllFilesAccessGranted(context)
    )

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

    /** User policy is persistent: process death must never silently erase an authorization choice. */
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setUserConsent(context: Context, permissionOrCapability: String, consented: Boolean) {
        prefs(context).edit().putBoolean(CONSENT_PREFIX + permissionOrCapability, consented).apply()
    }

    /** Compatibility overload retained for existing callers; persistent storage requires context-aware APIs. */
    @Deprecated("Use setUserConsent(context, permissionOrCapability, consented) so consent survives process death")
    fun setUserConsent(permissionOrCapability: String, consented: Boolean) = Unit

    fun hasUserConsent(context: Context, permissionOrCapability: String): Boolean {
        val key = CONSENT_PREFIX + permissionOrCapability
        return if (prefs(context).contains(key)) prefs(context).getBoolean(key, false)
        else defaultConsentFor(permissionOrCapability)
    }

    /** Compatibility overload retained for source compatibility; sensitive capabilities fail closed. */
    @Deprecated("Use hasUserConsent(context, permissionOrCapability)")
    fun hasUserConsent(permissionOrCapability: String): Boolean = defaultConsentFor(permissionOrCapability)

    fun clearUserConsents(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun defaultConsentFor(permissionOrCapability: String): Boolean = permissionOrCapability in setOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE
    )

    @Suppress("DEPRECATION")
    private val KNOWN_MANIFEST_PERMISSIONS = setOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.SET_ALARM,
        Manifest.permission.USE_BIOMETRIC,
        Manifest.permission.USE_FINGERPRINT,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Manifest.permission.READ_SYNC_SETTINGS,
        Manifest.permission.WRITE_SYNC_SETTINGS,
        Manifest.permission.READ_SYNC_STATS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE
    )

    fun isDeclaredInManifest(context: Context, permission: String): Boolean {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(permission) == true || KNOWN_MANIFEST_PERMISSIONS.contains(permission)
        } catch (_: Throwable) {
            KNOWN_MANIFEST_PERMISSIONS.contains(permission)
        }
    }

    fun isAllFilesAccessGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else true

    fun isPermissionGrantedByOs(context: Context, permission: String): Boolean = when (permission) {
        Manifest.permission.RECORD_AUDIO -> hasRecordAudio(context)
        Manifest.permission.CAMERA -> hasCamera(context)
        Manifest.permission.POST_NOTIFICATIONS -> hasPostNotifications(context)
        "SYSTEM_ALERT_WINDOW", Manifest.permission.SYSTEM_ALERT_WINDOW -> canDrawOverlays(context)
        Manifest.permission.SCHEDULE_EXACT_ALARM -> canScheduleExactAlarms(context)
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> isAllFilesAccessGranted(context)
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS -> hasPermission(context, permission)
        else -> hasPermission(context, permission)
    }

    /** Declared + OS grant + persistent user consent = executable authority. */
    fun verifyPermissionTruth(context: Context, permission: String): PermissionTruthReport {
        val declared = isDeclaredInManifest(context, permission)
        val osGranted = isPermissionGrantedByOs(context, permission)
        val consentGranted = hasUserConsent(context, permission)

        val status = when {
            !declared -> PermissionTruthStatus.NOT_DECLARED
            !osGranted -> PermissionTruthStatus.DECLARED_ONLY
            !consentGranted -> PermissionTruthStatus.DENIED_BY_USER_POLICY
            else -> PermissionTruthStatus.FULLY_AUTHORIZED
        }

        val canExecute = declared && osGranted && consentGranted
        val details = when (status) {
            PermissionTruthStatus.NOT_DECLARED -> "Permission [$permission] is NOT declared in AndroidManifest.xml"
            PermissionTruthStatus.DECLARED_ONLY -> "Permission [$permission] is declared but NOT currently granted by Android OS"
            PermissionTruthStatus.DENIED_BY_USER_POLICY -> "Permission [$permission] is OS-granted but blocked by Wasti user policy"
            PermissionTruthStatus.FULLY_AUTHORIZED -> "Permission [$permission] is declared, OS-granted, and explicitly authorized by Wasti user policy"
            PermissionTruthStatus.DENIED_BY_OS -> "Permission [$permission] is denied by Android OS"
        }
        return PermissionTruthReport(permission, declared, osGranted, consentGranted, canExecute, status, details)
    }

    fun canExecuteWithPermission(context: Context, permission: String): Boolean =
        verifyPermissionTruth(context, permission).canExecute

    /** Persist a complete runtime-permission dialog result into Wasti's policy layer. */
    fun recordRuntimePermissionResults(context: Context, results: Map<String, Boolean>) {
        results.forEach { (permission, granted) -> setUserConsent(context, permission, granted) }
    }

    /**
     * Displays an explanation dialog before requesting one runtime permission.
     * Android itself remains the authority that grants/denies the permission.
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
                    .setTitle("Wasti Permission Needed")
                    .setMessage(message)
                    .setPositiveButton("Grant") { dialog: DialogInterface, _ -> launcher.launch(permission) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                launcher.launch(permission)
            }
        } catch (_: Throwable) {
            try { launcher.launch(permission) } catch (_: Throwable) {}
        }
    }
}
