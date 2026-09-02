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

    fun getRefreshToken(context: Context?): String? {
        if (context != null) {
            val prefs = CredentialRegistry.getSecureSharedPreferences(context)
            val token = prefs.getString("gmail_oauth_refresh_token", null)
            if (!token.isNullOrBlank()) return token
        }
        return CredentialRegistry.getRawValue("GMAIL_OAUTH_REFRESH_TOKEN", context)
            ?: CredentialRegistry.getRawValue("GOOGLE_REFRESH_TOKEN", context)
    }

    fun saveRefreshToken(context: Context, refreshToken: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("gmail_oauth_refresh_token", refreshToken.trim()).apply()
    }

    /**
     * Refreshes the Gmail/Google OAuth access token using the stored refresh token.
     */
    suspend fun refreshAccessToken(context: Context?): String? = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken(context) ?: return@withContext null
        val clientId = CredentialRegistry.getRawValue("DRIVE_CLIENT_ID", context)
            ?: CredentialRegistry.getRawValue("GOOGLE_WEB_CLIENT_ID", context)
            ?: return@withContext null
        val clientSecret = CredentialRegistry.getRawValue("DRIVE_CLIENT_SECRET", context) ?: ""

        try {
            val formBuilder = okhttp3.FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
            if (clientSecret.isNotBlank()) {
                formBuilder.add("client_secret", clientSecret)
            }

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBuilder.build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val newAccessToken = json.optString("access_token", "")
                    if (newAccessToken.isNotBlank()) {
                        if (context != null) {
                            saveAccessToken(context, newAccessToken)
                        }
                        Log.i(TAG, "Successfully refreshed Gmail OAuth access token")
                        return@withContext newAccessToken
                    }
                } else {
                    Log.e(TAG, "Failed to refresh Google OAuth token: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing Google OAuth token", e)
        }
        return@withContext null
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

        var token = getAccessToken(context)
        if (token.isNullOrBlank()) {
            token = refreshAccessToken(context)
        }
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
            var request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            var response = httpClient.newCall(request).execute()
            
            // Retry on 401 Unauthorized by attempting a token refresh
            if (response.code == 401) {
                response.close()
                val refreshedToken = refreshAccessToken(context)
                if (!refreshedToken.isNullOrBlank()) {
                    token = refreshedToken
                    request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .post(jsonPayload.toRequestBody(mediaType))
                        .build()
                    response = httpClient.newCall(request).execute()
                } else {
                    return@withContext SendEmailResult.Error("Gmail OAuth token expired and refresh failed.")
                }
            }

            response.use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
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
                    Log.e(TAG, "Gmail API returned HTTP ${resp.code}: $responseBody")
                    return@withContext SendEmailResult.Error("Gmail API returned HTTP ${resp.code}: $responseBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing Gmail REST API call", e)
            return@withContext SendEmailResult.Error("Gmail dispatch failed: ${e.message ?: "Unknown error"}")
        }
    }
}
