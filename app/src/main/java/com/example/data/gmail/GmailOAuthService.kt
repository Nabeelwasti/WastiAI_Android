package com.example.data.gmail

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.credential.CredentialRegistry
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SendEmailResult {
    data class Success(val messageId: String, val details: String) : SendEmailResult()
    data class Error(val message: String) : SendEmailResult()
}

object GmailOAuthService {

    const val GMAIL_SEND_SCOPE = "https://www.googleapis.com/auth/gmail.send"
    private const val TAG = "GmailOAuthService"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getAccessToken(context: Context?): String? {
        if (context != null) {
            val prefs = CredentialRegistry.getSecureSharedPreferences(context)
            val token = prefs.getString("gmail_oauth_access_token", null)
            if (!token.isNullOrBlank()) return token
        }
        return CredentialRegistry.getRawValue("GMAIL_OAUTH_TOKEN", context)
            ?: CredentialRegistry.getRawValue("GOOGLE_OAUTH_ACCESS_TOKEN", context)
    }

    fun saveAccessToken(context: Context, token: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("gmail_oauth_access_token", token.trim()).apply()
    }

    /**
     * Executes OAuth 2.0 Gmail REST API dispatch for outreach emails.
     * Requires https://www.googleapis.com/auth/gmail.send OAuth scope.
     */
    suspend fun sendEmail(
        to: String,
        subject: String,
        body: String,
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val result = sendEmailDetailed(to, subject, body, context)
        result is SendEmailResult.Success
    }

    suspend fun sendEmailDetailed(
        to: String,
        subject: String,
        body: String,
        context: Context? = null
    ): SendEmailResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Initiating Secure Gmail OAuth 2.0 email dispatch to: $to")

        val token = getAccessToken(context)
        if (token.isNullOrBlank()) {
            return@withContext SendEmailResult.Error("Gmail not connected — go to Settings to connect your Google account before sending outreach emails.")
        }

        // Construct RFC 2822 raw message
        val rawMimeMessage = buildString {
            append("To: ").append(to.trim()).append("\r\n")
            append("Subject: ").append(subject.trim()).append("\r\n")
            append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
            append("MIME-Version: 1.0\r\n\r\n")
            append(body.trim())
        }

        val base64Raw = Base64.encodeToString(
            rawMimeMessage.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val jsonPayload = JSONObject().apply {
            put("raw", base64Raw)
        }.toString()

        try {
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val respJson = JSONObject(responseBody)
                    val msgId = respJson.optString("id", "gmail_msg_${System.currentTimeMillis()}")
                    Log.i(TAG, "Email successfully sent via Gmail API! Message ID: $msgId")

                    if (context != null) {
                        try {
                            val db = WastiDatabase.getDatabase(context)
                            db.systemLogDao().insertLog(
                                SystemLogEntity(
                                    level = "INFO",
                                    source = "GmailOAuthService",
                                    message = "Gmail OAuth 2.0 outreach email sent to $to (ID: $msgId)"
                                )
                            )
                        } catch (_: Exception) {}
                    }

                    return@withContext SendEmailResult.Success(msgId, "Email sent via Gmail API")
                } else {
                    Log.e(TAG, "Gmail API returned HTTP ${response.code}: $responseBody")
                    return@withContext SendEmailResult.Error("Gmail API returned HTTP ${response.code}: $responseBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gmail REST API call", e)
            return@withContext SendEmailResult.Error("Gmail dispatch failed: ${e.message ?: "Unknown error"}")
        }
    }
}
