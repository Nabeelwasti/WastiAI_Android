package com.example.assistant.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import com.example.data.drive.DriveSyncEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Performs a real, scheduled backup of the Wasti AI database:
 * 1. Copies the live Room database file to persistent local storage (NOT cache — cache can
 *    be cleared by Android at any time without warning, so backups must never live there).
 * 2. If a Google Drive OAuth access token has already been saved (via DriveSyncEngine after
 *    the user completes Drive sign-in), also pushes a real backup to Drive using the
 *    already-implemented DriveSyncEngine.performBackupToDrive().
 * 3. Prunes old local backups, keeping the most recent MAX_LOCAL_BACKUPS.
 *
 * This replaces a previous version that only wrote a placeholder text string to the cache
 * directory and never actually copied the database or uploaded anything.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WastiSyncWorker"
        private const val DB_NAME = "wasti_os_database"
        private const val MAX_LOCAL_BACKUPS = 24 // at hourly runs, ~1 day of rolling history
    }

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            val context = applicationContext
            val dbFile = context.getDatabasePath(DB_NAME)

            if (!dbFile.exists()) {
                Log.w(TAG, "Database file not found yet at ${dbFile.absolutePath}; skipping this backup cycle.")
                return ListenableWorker.Result.success()
            }

            // 1. Real local backup: persistent app-specific external storage, not cache.
            val backupDir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val backupFile = File(backupDir, "wasti_backup_$timestamp.db")

            dbFile.copyTo(backupFile, overwrite = true)
            Log.i(TAG, "Local backup written: ${backupFile.absolutePath} (${backupFile.length()} bytes)")

            pruneOldBackups(backupDir)

            // 2. Real Drive backup, only if the user has already connected Drive (has a saved
            //    access token). We never prompt or block on this here — background workers
            //    can't launch an OAuth consent screen. Drive connection happens in Settings.
            val driveToken = DriveSyncEngine.getAccessToken(context)
            if (!driveToken.isNullOrBlank()) {
                try {
                    DriveSyncEngine.performBackupToDrive(context, driveToken)
                    Log.i(TAG, "Drive backup completed.")
                } catch (e: Exception) {
                    Log.e(TAG, "Drive backup failed (local backup still succeeded)", e)
                }
            } else {
                Log.d(TAG, "No Drive access token saved yet — local backup only this cycle.")
            }

            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Backup cycle failed", e)
            ListenableWorker.Result.retry()
        }
    }

    private fun pruneOldBackups(backupDir: File) {
        val backups = backupDir.listFiles { f -> f.name.startsWith("wasti_backup_") && f.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        if (backups.size > MAX_LOCAL_BACKUPS) {
            backups.drop(MAX_LOCAL_BACKUPS).forEach { old ->
                if (old.delete()) {
                    Log.d(TAG, "Pruned old backup: ${old.name}")
                }
            }
        }
    }
}
