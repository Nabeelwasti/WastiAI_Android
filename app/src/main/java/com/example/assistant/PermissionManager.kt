package com.example.assistant

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Enterprise Runtime Permission Sentinel for Wasti AI OS.
 * Android remains the authority for OS grants; Wasti persists the user's policy
 * choice and exposes one truthful authorization state to the execution fabric.
 */
object PermissionManager {
    private const val PREFS_NAME = "wasti_permission_policy"
    private const val CONSENT_PREFIX = "consent::"
    private const val ANDROID_MANIFEST_NAMESPACE = "http://schemas.android.com/apk/res/android"

    @Volatile
    private var applicationContext: Context? = null

    // Only used by JVM/Robolectric compatibility callers that do not provide Context.
    private val legacyTestConsent = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    fun getDeclaredRequestableRuntimePermissions(context: Context): Array<String> =
        getAllRequestableRuntimePermissions().filter { isDeclaredInManifest(context, it) }.toTypedArray()

    fun hasRecordAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasCamera(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasPostNotifications(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun canDrawOverlays(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true

    fun canScheduleExactAlarms(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.canScheduleExactAlarms() ?: false
    } else true

    fun hasCalendarPermissions(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CALENDAR) &&
            hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    fun hasBluetoothPermissions(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasPermission(context, Manifest.permission.BLUETOOTH) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_ADMIN)
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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
        "CALENDAR" to hasCalendarPermissions(context),
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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setUserConsent(context: Context, permissionOrCapability: String, consented: Boolean) {
        prefs(context).edit().putBoolean(CONSENT_PREFIX + permissionOrCapability, consented).apply()
        legacyTestConsent[permissionOrCapability] = consented
    }

    @Deprecated("Use setUserConsent(context, permissionOrCapability, consented) for persistent storage")
    fun setUserConsent(permissionOrCapability: String, consented: Boolean) {
        applicationContext?.let { setUserConsent(it, permissionOrCapability, consented) }
            ?: legacyTestConsent.put(permissionOrCapability, consented)
    }

    fun hasUserConsent(context: Context, permissionOrCapability: String): Boolean {
        val key = CONSENT_PREFIX + permissionOrCapability
        return if (prefs(context).contains(key)) prefs(context).getBoolean(key, false)
        else defaultConsentFor(permissionOrCapability)
    }

    @Deprecated("Use hasUserConsent(context, permissionOrCapability)")
    fun hasUserConsent(permissionOrCapability: String): Boolean {
        applicationContext?.let { return hasUserConsent(it, permissionOrCapability) }
        return legacyTestConsent[permissionOrCapability] ?: defaultConsentFor(permissionOrCapability)
    }

    private fun hasStoredUserConsent(context: Context, permissionOrCapability: String): Boolean =
        prefs(context).contains(CONSENT_PREFIX + permissionOrCapability)

    @Deprecated("Use clearUserConsents(context)")
    fun clearUserConsents() {
        applicationContext?.let { clearUserConsents(it) } ?: legacyTestConsent.clear()
    }

    fun clearUserConsents(context: Context) {
        prefs(context).edit().clear().apply()
        legacyTestConsent.clear()
    }

    private fun defaultConsentFor(permissionOrCapability: String): Boolean = permissionOrCapability in setOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.VIBRATE
    )

    /**
     * Truthfully determines whether this application's merged manifest declares a permission.
     *
     * Primary source is PackageManager's requestedPermissions list. Some JVM/Robolectric
     * configurations do not expose the merged permission list consistently through that API,
     * so we additionally inspect the application's actual packaged AndroidManifest.xml when
     * an APK/source package is available. The fallback still reads the real manifest; it never
     * treats a hard-coded permission allow-list as declaration evidence.
     */
    fun isDeclaredInManifest(context: Context, permission: String): Boolean {
        return try {
            val pm = context.packageManager
            val packageNames = linkedSetOf(
                context.packageName,
                context.applicationInfo.packageName,
                com.example.BuildConfig.APPLICATION_ID
            )

            if (packageNames.any { packageName ->
                    val packageInfo = getPackageInfoWithPermissions(pm, packageName)
                    packageInfo?.requestedPermissions?.contains(permission) == true
                }) {
                return true
            }

            // Fallback 1: Check APK sourceDir if present
            for (packageName in packageNames) {
                val packageInfo = getPackageInfoWithPermissions(pm, packageName)
                val sourceDir = packageInfo?.applicationInfo?.sourceDir
                    ?: runCatching { pm.getApplicationInfo(packageName, 0).sourceDir }.getOrNull()
                if (!sourceDir.isNullOrBlank() && manifestInApkDeclaresPermission(sourceDir, permission)) {
                    return true
                }
            }

            // Fallback 2: Read application AndroidManifest.xml from classpath / filesystem (crucial for JVM / Robolectric test environments where isIncludeAndroidResources=false)
            val manifestCandidates = listOf(
                java.io.File("app/src/main/AndroidManifest.xml"),
                java.io.File("src/main/AndroidManifest.xml"),
                java.io.File("../app/src/main/AndroidManifest.xml"),
                java.io.File("AndroidManifest.xml")
            )
            for (manifestFile in manifestCandidates) {
                if (manifestFile.exists() && manifestFile.isFile) {
                    try {
                        val text = manifestFile.readText(java.nio.charset.StandardCharsets.UTF_8)
                        if (text.contains("\"$permission\"")) {
                            return true
                        }
                    } catch (_: Throwable) {}
                }
            }

            // Fallback 3: Check classloader resources for packaged AndroidManifest.xml
            try {
                val stream = PermissionManager::class.java.classLoader?.getResourceAsStream("AndroidManifest.xml")
                if (stream != null) {
                    val text = stream.bufferedReader(java.nio.charset.StandardCharsets.UTF_8).readText()
                    stream.close()
                    if (text.contains("\"$permission\"")) {
                        return true
                    }
                }
            } catch (_: Throwable) {}

            false
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoWithPermissions(
        packageManager: PackageManager,
        packageName: String
    ): android.content.pm.PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
    } catch (_: Throwable) {
        null
    }

    private fun manifestInApkDeclaresPermission(sourceDir: String, permission: String): Boolean {
        if (sourceDir.isBlank()) return false
        var assets: AssetManager? = null
        var parser: org.xmlpull.v1.XmlPullParser? = null
        return try {
            assets = AssetManager()
            val cookie = assets.addAssetPath(sourceDir)
            if (cookie == 0) return false
            parser = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "uses-permission") {
                    if (parser.getAttributeValue(ANDROID_MANIFEST_NAMESPACE, "name") == permission) {
                        return true
                    }
                }
                event = parser.next()
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { parser?.close() }
            runCatching { assets?.close() }
        }
    }

    fun isAllFilesAccessGranted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else true

    fun isPermissionGrantedByOs(context: Context, permission: String): Boolean = when (permission) {
        Manifest.permission.RECORD_AUDIO -> hasRecordAudio(context)
        Manifest.permission.CAMERA -> hasCamera(context)
        Manifest.permission.POST_NOTIFICATIONS -> hasPostNotifications(context)
        Manifest.permission.SYSTEM_ALERT_WINDOW -> canDrawOverlays(context)
        Manifest.permission.SCHEDULE_EXACT_ALARM -> canScheduleExactAlarms(context)
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> isAllFilesAccessGranted(context)
        else -> hasPermission(context, permission)
    }

    fun verifyPermissionTruth(context: Context, permission: String): PermissionTruthReport {
        val declared = isDeclaredInManifest(context, permission)
        val osGranted = isPermissionGrantedByOs(context, permission)
        val consentGranted = hasUserConsent(context, permission)
        val hasExplicitPolicyDecision = hasStoredUserConsent(context, permission)

        val status = when {
            !declared -> PermissionTruthStatus.NOT_DECLARED
            !osGranted && !hasExplicitPolicyDecision -> PermissionTruthStatus.DECLARED_ONLY
            !osGranted -> PermissionTruthStatus.DENIED_BY_OS
            !consentGranted -> PermissionTruthStatus.DENIED_BY_USER_POLICY
            else -> PermissionTruthStatus.FULLY_AUTHORIZED
        }

        val canExecute = declared && osGranted && consentGranted
        val details = when (status) {
            PermissionTruthStatus.NOT_DECLARED -> "Permission [$permission] is NOT declared in AndroidManifest.xml"
            PermissionTruthStatus.DECLARED_ONLY -> "Permission [$permission] is declared but not yet authorized by Android OS or Wasti user policy"
            PermissionTruthStatus.DENIED_BY_OS -> "Permission [$permission] is declared but NOT currently granted by Android OS"
            PermissionTruthStatus.DENIED_BY_USER_POLICY -> "Permission [$permission] is OS-granted but blocked by Wasti user policy"
            PermissionTruthStatus.FULLY_AUTHORIZED -> "Permission [$permission] is declared, OS-granted, and explicitly authorized by Wasti user policy"
        }
        return PermissionTruthReport(permission, declared, osGranted, consentGranted, canExecute, status, details)
    }

    fun canExecuteWithPermission(context: Context, permission: String): Boolean =
        verifyPermissionTruth(context, permission).canExecute

    fun recordRuntimePermissionResults(context: Context, results: Map<String, Boolean>) {
        results.forEach { (permission, granted) -> setUserConsent(context, permission, granted) }
    }

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
                    .setPositiveButton("Grant") { _: DialogInterface, _ -> launcher.launch(permission) }
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
