package com.example.assistant

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog

/**
 * PermissionManager provides a friendly, just-in-time permission rationale flow.
 * It does not request permissions directly — it uses an ActivityResultLauncher provided
 * by the caller so the Activity/Fragment controls the lifecycle.
 */
object PermissionManager {
    fun showRationaleAndRequest(
        activity: Activity,
        permission: String,
        launcher: ActivityResultLauncher<String>,
        message: String
    ) {
        try {
            if (activity.shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(activity)
                    .setTitle("Permission needed")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> launcher.launch(permission) }
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
