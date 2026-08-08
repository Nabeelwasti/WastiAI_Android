package com.example.data.drive

import android.content.Context
import com.example.data.credential.CredentialRegistry
import com.example.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class DriveSyncStatus {
    object Idle : DriveSyncStatus()
    object Connecting : DriveSyncStatus()
    object Syncing : DriveSyncStatus()
    data class Synced(val fileId: String, val lastSyncTime: Long, val backupSizeBytes: Long) : DriveSyncStatus()
    data class Error(val message: String) : DriveSyncStatus()
}

data class DriveFileInfo(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String?,
    val size: Long?
)

object DriveSyncEngine {

    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val BACKUP_FILE_NAME = "wasti_os_backup.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _syncStatus = MutableStateFlow<DriveSyncStatus>(DriveSyncStatus.Idle)
    val syncStatus: StateFlow<DriveSyncStatus> = _syncStatus.asStateFlow()

    fun getAccessToken(context: Context): String? {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        val token = prefs.getString("google_drive_access_token", null)
        if (!token.isNullOrBlank()) return token

        // Fallback to CredentialRegistry or environment key if available
        return CredentialRegistry.getRawValue("GMAIL_APP_PASSWORD", context)
            ?: CredentialRegistry.getRawValue("GOOGLE_OAUTH_ACCESS_TOKEN", context)
    }

    fun saveAccessToken(context: Context, token: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("google_drive_access_token", token.trim()).apply()
    }

    fun clearAccessToken(context: Context) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().remove("google_drive_access_token").apply()
        _syncStatus.value = DriveSyncStatus.Idle
    }

    /**
     * Serializes Room database records into a structured JSON string.
     */
    suspend fun exportDatabaseToJson(context: Context): String = withContext(Dispatchers.IO) {
        val db = WastiDatabase.getDatabase(context)

        val conversations = db.conversationDao().getAllConversationsSync()
        val messages = db.messageDao().getAllMessagesSync()
        val memories = db.memoryDao().getAllMemoriesSync()
        val settings = db.settingDao().getAllSettingsSync()
        val agents = db.agentDao().getAllAgentsSync()
        val tasks = db.taskDao().getAllTasksSync()

        val root = JSONObject().apply {
            put("version", "1.0")
            put("timestamp", System.currentTimeMillis())
            put("app_id", "com.aistudio.wastios.k9v2pz")

            put("conversations", JSONArray().apply {
                conversations.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("createdTimestamp", c.createdTimestamp)
                        put("updatedTimestamp", c.updatedTimestamp)
                        put("activeAgentId", c.activeAgentId)
                        put("modelName", c.modelName)
                        put("systemPrompt", c.systemPrompt)
                        put("isPinned", c.isPinned)
                    })
                }
            })

            put("messages", JSONArray().apply {
                messages.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("conversationId", m.conversationId)
                        put("role", m.role)
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                        put("agentId", m.agentId)
                        put("modelUsed", m.modelUsed)
                        put("tokensUsed", m.tokensUsed)
                        put("toolCallsJson", m.toolCallsJson ?: "")
                        put("thinkingContent", m.thinkingContent ?: "")
                    })
                }
            })

            put("memories", JSONArray().apply {
                memories.forEach { mem ->
                    put(JSONObject().apply {
                        put("id", mem.id)
                        put("key", mem.key)
                        put("category", mem.category)
                        put("value", mem.value)
                        put("importanceScore", mem.importanceScore)
                        put("timestamp", mem.timestamp)
                        put("sourceMessageId", mem.sourceMessageId ?: "")
                    })
                }
            })

            put("settings", JSONArray().apply {
                settings.forEach { s ->
                    put(JSONObject().apply {
                        put("key", s.key)
                        put("value", s.value)
                    })
                }
            })

            put("agents", JSONArray().apply {
                agents.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("roleTitle", a.roleTitle)
                        put("iconName", a.iconName)
                        put("systemInstruction", a.systemInstruction)
                        put("temperature", a.temperature)
                        put("capabilitiesCsv", a.capabilitiesCsv)
                        put("status", a.status)
                        put("agentType", a.agentType)
                    })
                }
            })

            put("tasks", JSONArray().apply {
                tasks.forEach { t ->
                    put(JSONObject().apply {
                        put("id", t.id)
                        put("projectId", t.projectId)
                        put("title", t.title)
                        put("description", t.description)
                        put("isCompleted", t.isCompleted)
                        put("priority", t.priority)
                        put("assignedAgentId", t.assignedAgentId)
                        put("dueDate", t.dueDate)
                    })
                }
            })
        }

        root.toString(2)
    }

    /**
     * Executes real HTTP REST call to Google Drive API to search for existing backup file.
     */
    private suspend fun findBackupFileOnDrive(accessToken: String): DriveFileInfo? = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files?q=name%3D%27$BACKUP_FILE_NAME%27+and+trashed%3Dfalse&fields=files(id,name,mimeType,modifiedTime,size)"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val bodyString = response.body?.string() ?: return@withContext null
            val json = JSONObject(bodyString)
            val filesArray = json.optJSONArray("files") ?: return@withContext null
            if (filesArray.length() == 0) return@withContext null

            val firstFile = filesArray.getJSONObject(0)
            DriveFileInfo(
                id = firstFile.getString("id"),
                name = firstFile.optString("name", BACKUP_FILE_NAME),
                mimeType = firstFile.optString("mimeType", "application/json"),
                modifiedTime = firstFile.optString("modifiedTime", ""),
                size = if (firstFile.has("size")) firstFile.getLong("size") else null
            )
        }
    }

    /**
     * Performs a real HTTP call to upload/sync local database backup to Google Drive.
     */
    suspend fun performBackupToDrive(
        context: Context,
        accessToken: String
    ): DriveSyncStatus = withContext(Dispatchers.IO) {
        val db = WastiDatabase.getDatabase(context)
        _syncStatus.value = DriveSyncStatus.Syncing

        try {
            saveAccessToken(context, accessToken)
            val jsonPayload = exportDatabaseToJson(context)
            val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
            val payloadSize = payloadBytes.size.toLong()

            val existingFile = findBackupFileOnDrive(accessToken)

            val (fileId, httpCode, responseText) = if (existingFile != null) {
                // UPDATE existing file via PATCH media request
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files/${existingFile.id}?uploadType=media"
                val mediaBody = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .patch(mediaBody)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val code = resp.code
                    val id = if (resp.isSuccessful && body.contains("id")) {
                        JSONObject(body).optString("id", existingFile.id)
                    } else existingFile.id
                    Triple(id, code, body)
                }
            } else {
                // CREATE new file via Multipart POST request
                val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"

                val metadataJson = JSONObject().apply {
                    put("name", BACKUP_FILE_NAME)
                    put("mimeType", "application/json")
                    put("description", "Wasti OS Automated Room Database Backup")
                }.toString()

                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(
                        metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .addPart(
                        jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(multipartBody)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val code = resp.code
                    val newId = if (resp.isSuccessful && body.contains("id")) {
                        JSONObject(body).optString("id", "drive_file_id_${System.currentTimeMillis()}")
                    } else "drive_file_id_${System.currentTimeMillis()}"
                    Triple(newId, code, body)
                }
            }

            if (httpCode == 200 || httpCode == 201) {
                val lastSyncTime = System.currentTimeMillis()
                val successStatus = DriveSyncStatus.Synced(fileId, lastSyncTime, payloadSize)
                _syncStatus.value = successStatus

                // Log system event
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "INFO",
                        source = "DriveSyncEngine",
                        message = "Google Drive backup verified! HTTP $httpCode OK. File ID: $fileId ($payloadSize bytes)"
                    )
                )
                successStatus
            } else {
                val errorMsg = "Google Drive HTTP $httpCode - ${responseText.take(120)}"
                _syncStatus.value = DriveSyncStatus.Error(errorMsg)

                db.developerLogDao().insertLog(
                    DeveloperLogEntity(
                        providerId = "google_drive",
                        errorMessage = errorMsg,
                        errorType = "DRIVE_HTTP_ERROR",
                        timestamp = System.currentTimeMillis(),
                        details = responseText
                    )
                )
                DriveSyncStatus.Error(errorMsg)
            }
        } catch (e: Exception) {
            val err = "Drive Sync Failed: ${e.localizedMessage ?: e.message}"
            _syncStatus.value = DriveSyncStatus.Error(err)

            db.developerLogDao().insertLog(
                DeveloperLogEntity(
                    providerId = "google_drive",
                    errorMessage = err,
                    errorType = "DRIVE_EXCEPTION",
                    timestamp = System.currentTimeMillis(),
                    details = e.stackTraceToString().take(300)
                )
            )
            DriveSyncStatus.Error(err)
        }
    }

    /**
     * Downloads and restores backup JSON from Google Drive into Room database.
     */
    suspend fun restoreFromDrive(
        context: Context,
        accessToken: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val db = WastiDatabase.getDatabase(context)

        try {
            val fileInfo = findBackupFileOnDrive(accessToken)
                ?: return@withContext Pair(false, "No backup file '$BACKUP_FILE_NAME' found on Google Drive.")

            val downloadUrl = "https://www.googleapis.com/drive/v3/files/${fileInfo.id}?alt=media"
            val request = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Pair(false, "Download failed: HTTP ${response.code}")
                }

                val jsonStr = response.body?.string()
                    ?: return@withContext Pair(false, "Empty response from Google Drive.")

                val root = JSONObject(jsonStr)

                // Restore conversations
                root.optJSONArray("conversations")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        db.conversationDao().insertConversation(
                            ConversationEntity(
                                id = obj.getString("id"),
                                title = obj.getString("title"),
                                createdTimestamp = obj.optLong("createdTimestamp", System.currentTimeMillis()),
                                updatedTimestamp = obj.optLong("updatedTimestamp", System.currentTimeMillis()),
                                activeAgentId = obj.optString("activeAgentId", "ceo_agent"),
                                modelName = obj.optString("modelName", "gemini-3.5-flash"),
                                systemPrompt = obj.optString("systemPrompt", "You are Wasti OS Executive Brain."),
                                isPinned = obj.optBoolean("isPinned", false)
                            )
                        )
                    }
                }

                // Restore messages
                root.optJSONArray("messages")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        db.messageDao().insertMessage(
                            MessageEntity(
                                id = obj.getString("id"),
                                conversationId = obj.getString("conversationId"),
                                role = obj.optString("role", "user"),
                                content = obj.getString("content"),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                agentId = obj.optString("agentId", "executive_brain"),
                                modelUsed = obj.optString("modelUsed", "gemini-3.5-flash"),
                                tokensUsed = obj.optInt("tokensUsed", 0),
                                toolCallsJson = obj.optString("toolCallsJson", ""),
                                thinkingContent = obj.optString("thinkingContent", "")
                            )
                        )
                    }
                }

                // Restore memories
                root.optJSONArray("memories")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        db.memoryDao().insertMemory(
                            MemoryEntity(
                                id = obj.getString("id"),
                                key = obj.getString("key"),
                                category = obj.optString("category", "General"),
                                value = obj.getString("value"),
                                importanceScore = obj.optDouble("importanceScore", 0.9).toFloat(),
                                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                                sourceMessageId = obj.optString("sourceMessageId", "")
                            )
                        )
                    }
                }

                Pair(true, "Successfully restored backup from Google Drive file '${fileInfo.name}'.")
            }
        } catch (e: Exception) {
            Pair(false, "Restore Error: ${e.localizedMessage ?: e.message}")
        }
    }
}
