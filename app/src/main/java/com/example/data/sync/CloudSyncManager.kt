package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.db.InvoiceEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.ProspectEntity
import com.example.data.db.WastiDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class SyncResult {
    data class Success(val prospectsSynced: Int, val invoicesSynced: Int, val memoriesSynced: Int) : SyncResult()
    data class SnapshotSuccess(val snapshotPath: String, val sizeBytes: Long, val sha256Checksum: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

object CloudSyncManager {

    private const val TAG = "CloudSyncManager"

    private fun getFirestore(context: Context? = null): FirebaseFirestore? {
        val appContext = context ?: com.example.WastiApplication.instance?.applicationContext
        return com.example.data.cloud.WastiFirebaseIntegrity.getSafeFirestore(appContext)
    }

    /**
     * Checks whether a memory entity contains private keys, API credentials, or secrets that must not be synced to cloud.
     */
    fun isSensitiveMemory(memory: MemoryEntity): Boolean {
        val cat = memory.category.trim().uppercase()
        val key = memory.key.trim().uppercase()
        val sensitiveCategories = setOf("SECRET", "CREDENTIAL", "CREDENTIALS", "PASSWORD", "AUTH", "TOKEN", "API_KEY", "VAULT", "KEY")
        if (cat in sensitiveCategories) return true
        val sensitiveIndicators = listOf("API_KEY", "SECRET", "PASSWORD", "TOKEN", "BEARER", "PRIVATE_KEY", "CREDENTIAL", "AUTH_KEY", "CLIENT_SECRET")
        return sensitiveIndicators.any { key.contains(it) }
    }

    /**
     * Creates a genuine compressed snapshot archive of the SQLite database files with SHA-256 verification.
     * Stored strictly in noBackupFilesDir to prevent auto-backup leakage.
     */
    suspend fun createDatabaseSnapshotArchive(context: Context): SyncResult = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(WastiDatabase.WASTI_DATABASE_NAME)
            val walFile = File(dbFile.parentFile, "${WastiDatabase.WASTI_DATABASE_NAME}-wal")
            val shmFile = File(dbFile.parentFile, "${WastiDatabase.WASTI_DATABASE_NAME}-shm")

            if (!dbFile.exists()) {
                return@withContext SyncResult.Error("Database file ${WastiDatabase.WASTI_DATABASE_NAME} does not exist on disk.")
            }

            // Strictly use noBackupFilesDir to ensure database snapshots are never leaked into cloud auto-backups or adb backups
            val backupDir = File(context.noBackupFilesDir, "backups").apply { if (!exists()) mkdirs() }

            // Clean up legacy snapshots in filesDir if any exist
            val legacyBackupDir = File(context.filesDir, "backups")
            if (legacyBackupDir.exists()) {
                try { legacyBackupDir.deleteRecursively() } catch (_: Throwable) {}
            }

            val archiveFile = File(backupDir, "wasti_db_backup_${System.currentTimeMillis()}.zip")

            ZipOutputStream(FileOutputStream(archiveFile)).use { zos ->
                listOf(dbFile, walFile, shmFile).filter { it.exists() }.forEach { file ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }

            // Prune older archives, retaining at most 3 latest snapshots to bound storage footprint
            val existingArchives = backupDir.listFiles { f -> f.extension == "zip" } ?: emptyArray()
            if (existingArchives.size > 3) {
                existingArchives.sortedByDescending { it.lastModified() }.drop(3).forEach { oldArchive ->
                    try { oldArchive.delete() } catch (_: Throwable) {}
                }
            }

            val md = MessageDigest.getInstance("SHA-256")
            val checksum = FileInputStream(archiveFile).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }

            Log.i(TAG, "Created verified DB snapshot archive: ${archiveFile.absolutePath} (${archiveFile.length()} bytes, sha256=$checksum)")
            SyncResult.SnapshotSuccess(archiveFile.absolutePath, archiveFile.length(), checksum)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create database snapshot archive", e)
            SyncResult.Error(e.message ?: "Failed to create database snapshot")
        }
    }

    suspend fun backupToCloud(db: WastiDatabase, userId: String): SyncResult = withContext(Dispatchers.IO) {
        try {
            val firestore = getFirestore()
            if (firestore == null) {
                Log.w(TAG, "Cloud backup skipped: Firebase is not initialized or configured in this environment.")
                return@withContext SyncResult.Error("Firebase is not initialized or configured. Please provide google-services.json or Firebase configuration.")
            }
            val sanitizedUserId = if (userId.isBlank()) "default_user" else userId

            val prospects = db.prospectDao().getAllProspectsSync()
            var prospectsCount = 0
            for (p in prospects) {
                val data = mapOf(
                    "id" to p.id,
                    "clientName" to p.clientName,
                    "companyName" to p.companyName,
                    "country" to p.country,
                    "region" to p.region,
                    "email" to p.email,
                    "phone" to p.phone,
                    "whatsappNumber" to p.whatsappNumber,
                    "websiteUrl" to p.websiteUrl,
                    "paymentInfo" to p.paymentInfo,
                    "leadSource" to p.leadSource,
                    "opportunityNature" to p.opportunityNature,
                    "status" to p.status,
                    "aiDraftedMessage" to p.aiDraftedMessage,
                    "timestamp" to p.timestamp,
                    "title" to p.title,
                    "link" to p.link,
                    "description" to p.description,
                    "pubDate" to p.pubDate,
                    "category" to p.category,
                    "matchScore" to p.matchScore,
                    "matchedSkillsCsv" to p.matchedSkillsCsv,
                    "draftedPitch" to p.draftedPitch,
                    "clientEmail" to p.clientEmail
                )
                firestore.collection("users").document(sanitizedUserId)
                    .collection("prospects").document(p.id)
                    .set(data, SetOptions.merge()).awaitTask()
                prospectsCount++
            }

            val invoices = db.invoiceDao().getAllInvoicesSync()
            var invoicesCount = 0
            for (inv in invoices) {
                val data = mapOf(
                    "id" to inv.id,
                    "clientName" to inv.clientName,
                    "projectMilestone" to inv.projectMilestone,
                    "amountUsd" to inv.amountUsd,
                    "currency" to inv.currency,
                    "status" to inv.status,
                    "issueDate" to inv.issueDate,
                    "dueDate" to inv.dueDate,
                    "clientFeedback" to (inv.clientFeedback ?: ""),
                    "timestamp" to inv.timestamp
                )
                firestore.collection("users").document(sanitizedUserId)
                    .collection("invoices").document(inv.id)
                    .set(data, SetOptions.merge()).awaitTask()
                invoicesCount++
            }

            val memories = db.memoryDao().getAllMemoriesSync()
            val safeMemories = memories.filterNot { isSensitiveMemory(it) }
            val sensitiveExcludedCount = memories.size - safeMemories.size
            if (sensitiveExcludedCount > 0) {
                Log.w(TAG, "Cloud Backup: Excluded $sensitiveExcludedCount sensitive credential/secret memory records from cloud sync.")
            }

            var memoriesCount = 0
            for (m in safeMemories) {
                val data = mapOf(
                    "id" to m.id,
                    "key" to m.key,
                    "category" to m.category,
                    "value" to m.value,
                    "importanceScore" to m.importanceScore,
                    "timestamp" to m.timestamp,
                    "sourceMessageId" to (m.sourceMessageId ?: "")
                )
                firestore.collection("users").document(sanitizedUserId)
                    .collection("memories").document(m.id)
                    .set(data, SetOptions.merge()).awaitTask()
                memoriesCount++
            }

            Log.i(TAG, "Cloud Backup successful for $sanitizedUserId: $prospectsCount prospects, $invoicesCount invoices, $memoriesCount memories (Excluded $sensitiveExcludedCount sensitive).")
            SyncResult.Success(prospectsCount, invoicesCount, memoriesCount)
        } catch (e: Exception) {
            Log.e(TAG, "Backup to cloud failed: ${e.message}", e)
            SyncResult.Error(e.message ?: "Backup failed")
        }
    }

    suspend fun restoreFromCloud(db: WastiDatabase, userId: String): SyncResult = withContext(Dispatchers.IO) {
        try {
            val firestore = getFirestore()
            if (firestore == null) {
                Log.w(TAG, "Cloud restore skipped: Firebase is not initialized or configured in this environment.")
                return@withContext SyncResult.Error("Firebase is not initialized or configured. Please provide google-services.json or Firebase configuration.")
            }
            val sanitizedUserId = if (userId.isBlank()) "default_user" else userId

            val prospectsSnapshot = firestore.collection("users").document(sanitizedUserId)
                .collection("prospects").get().awaitTask()
            var prospectsRestored = 0
            for (doc in prospectsSnapshot.documents) {
                val d = doc.data ?: continue
                val p = ProspectEntity(
                    id = doc.id,
                    clientName = d["clientName"] as? String ?: "",
                    companyName = d["companyName"] as? String ?: "",
                    country = d["country"] as? String ?: "",
                    region = d["region"] as? String ?: "",
                    email = d["email"] as? String ?: "",
                    phone = d["phone"] as? String ?: "",
                    whatsappNumber = d["whatsappNumber"] as? String ?: "",
                    websiteUrl = d["websiteUrl"] as? String ?: "",
                    paymentInfo = d["paymentInfo"] as? String ?: "",
                    leadSource = d["leadSource"] as? String ?: "Google X-Ray",
                    opportunityNature = d["opportunityNature"] as? String ?: "Video Editing",
                    status = d["status"] as? String ?: "NEW",
                    aiDraftedMessage = d["aiDraftedMessage"] as? String ?: "",
                    timestamp = (d["timestamp"] as? Long) ?: System.currentTimeMillis(),
                    title = d["title"] as? String ?: "",
                    link = d["link"] as? String ?: "",
                    description = d["description"] as? String ?: "",
                    pubDate = d["pubDate"] as? String ?: "",
                    category = d["category"] as? String ?: "",
                    matchScore = (d["matchScore"] as? Long)?.toInt() ?: 85,
                    matchedSkillsCsv = d["matchedSkillsCsv"] as? String ?: "",
                    draftedPitch = d["draftedPitch"] as? String ?: "",
                    clientEmail = d["clientEmail"] as? String ?: ""
                )
                db.prospectDao().insertProspect(p)
                prospectsRestored++
            }

            val invoicesSnapshot = firestore.collection("users").document(sanitizedUserId)
                .collection("invoices").get().awaitTask()
            var invoicesRestored = 0
            for (doc in invoicesSnapshot.documents) {
                val d = doc.data ?: continue
                val inv = InvoiceEntity(
                    id = doc.id,
                    clientName = d["clientName"] as? String ?: "",
                    projectMilestone = d["projectMilestone"] as? String ?: "",
                    amountUsd = (d["amountUsd"] as? Number)?.toDouble() ?: 0.0,
                    currency = d["currency"] as? String ?: "USD",
                    status = d["status"] as? String ?: "DRAFT",
                    issueDate = d["issueDate"] as? String ?: "",
                    dueDate = d["dueDate"] as? String ?: "",
                    clientFeedback = (d["clientFeedback"] as? String).takeIf { !it.isNull_or_blank() },
                    timestamp = (d["timestamp"] as? Long) ?: System.currentTimeMillis()
                )
                db.invoiceDao().insertInvoice(inv)
                invoicesRestored++
            }

            val memoriesSnapshot = firestore.collection("users").document(sanitizedUserId)
                .collection("memories").get().awaitTask()
            var memoriesRestored = 0
            for (doc in memoriesSnapshot.documents) {
                val d = doc.data ?: continue
                val key = d["key"] as? String ?: ""
                val category = d["category"] as? String ?: "Fact"
                val m = MemoryEntity(
                    id = doc.id,
                    key = key,
                    category = category,
                    value = d["value"] as? String ?: "",
                    importanceScore = (d["importanceScore"] as? Number)?.toFloat() ?: 0.9f,
                    timestamp = (d["timestamp"] as? Long) ?: System.currentTimeMillis(),
                    sourceMessageId = (d["sourceMessageId"] as? String).takeIf { !it.isNull_or_blank() }
                )
                if (isSensitiveMemory(m)) {
                    Log.w(TAG, "Cloud Restore: Refusing to restore sensitive/credential memory '$key' from remote Firestore.")
                    continue
                }
                db.memoryDao().insertMemory(m)
                memoriesRestored++
            }

            Log.i(TAG, "Cloud Restore successful for $sanitizedUserId: $prospectsRestored prospects, $invoicesRestored invoices, $memoriesRestored memories.")
            SyncResult.Success(prospectsRestored, invoicesRestored, memoriesRestored)
        } catch (e: Exception) {
            Log.e(TAG, "Restore from cloud failed: ${e.message}", e)
            SyncResult.Error(e.message ?: "Restore failed")
        }
    }

    suspend fun syncToLocal(context: Context, userId: String): SyncResult {
        val db = WastiDatabase.getDatabase(context)
        val restoreRes = restoreFromCloud(db, userId)
        val backupRes = backupToCloud(db, userId)
        return if (restoreRes is SyncResult.Success) restoreRes else backupRes
    }

    private fun String?.isNull_or_blank(): Boolean = this == null || this.isBlank()

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result -> continuation.resume(result) }
            addOnFailureListener { exception -> continuation.resumeWithException(exception) }
            addOnCanceledListener { continuation.cancel() }
        }
}
