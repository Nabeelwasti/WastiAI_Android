package com.example.data.linkedin

import android.content.Context
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

sealed class LinkedInPostResult {
    data class Success(val postId: String, val details: String) : LinkedInPostResult()
    data class Error(val message: String) : LinkedInPostResult()
}

/**
 * Handles LinkedIn OAuth 2.0 authentication and REST API social media posting.
 * Integrates with LinkedIn UGC Posts API (https://api.linkedin.com/v2/ugcPosts).
 * Strictly adheres to the Honest Failure Pattern — never returns fake success.
 */
object LinkedInOAuthService {

    private const val TAG = "LinkedInOAuthService"
    const val LINKEDIN_SHARE_SCOPE = "w_member_social"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getClientId(context: Context?): String? {
        return CredentialRegistry.getRawValue("LINKEDIN_CLIENT_ID", context)
    }

    fun getClientSecret(context: Context?): String? {
        return CredentialRegistry.getRawValue("LINKEDIN_CLIENT_SECRET", context)
    }

    fun getAccessToken(context: Context?): String? {
        if (context != null) {
            val prefs = CredentialRegistry.getSecureSharedPreferences(context)
            val token = prefs.getString("linkedin_oauth_access_token", null)
            if (!token.isNullOrBlank()) return token
        }
        return CredentialRegistry.getRawValue("LINKEDIN_OAUTH_TOKEN", context)
            ?: CredentialRegistry.getRawValue("LINKEDIN_ACCESS_TOKEN", context)
    }

    fun saveAccessToken(context: Context, token: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("linkedin_oauth_access_token", token.trim()).apply()
    }

    fun getRefreshToken(context: Context?): String? {
        if (context != null) {
            val prefs = CredentialRegistry.getSecureSharedPreferences(context)
            val token = prefs.getString("linkedin_oauth_refresh_token", null)
            if (!token.isNullOrBlank()) return token
        }
        return CredentialRegistry.getRawValue("LINKEDIN_OAUTH_REFRESH_TOKEN", context)
            ?: CredentialRegistry.getRawValue("LINKEDIN_REFRESH_TOKEN", context)
    }

    fun saveRefreshToken(context: Context, refreshToken: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("linkedin_oauth_refresh_token", refreshToken.trim()).apply()
    }

    /**
     * Refreshes the LinkedIn OAuth 2.0 access token using the stored refresh token.
     */
    suspend fun refreshAccessToken(context: Context?): String? = withContext(Dispatchers.IO) {
        val refreshToken = getRefreshToken(context) ?: return@withContext null
        val clientId = getClientId(context) ?: return@withContext null
        val clientSecret = getClientSecret(context) ?: return@withContext null

        try {
            val formBody = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .build()

            val request = Request.Builder()
                .url("https://www.linkedin.com/oauth/v2/accessToken")
                .post(formBody)
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
                        Log.i(TAG, "Successfully refreshed LinkedIn OAuth access token")
                        return@withContext newAccessToken
                    }
                } else {
                    Log.e(TAG, "Failed to refresh LinkedIn OAuth token: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing LinkedIn OAuth token", e)
        }
        return@withContext null
    }

    fun getAuthorUrn(context: Context?): String? {
        if (context != null) {
            val prefs = CredentialRegistry.getSecureSharedPreferences(context)
            val urn = prefs.getString("linkedin_author_urn", null)
            if (!urn.isNullOrBlank()) return urn
        }
        return CredentialRegistry.getRawValue("LINKEDIN_AUTHOR_URN", context)
    }

    fun saveAuthorUrn(context: Context, urn: String) {
        val prefs = CredentialRegistry.getSecureSharedPreferences(context)
        prefs.edit().putString("linkedin_author_urn", urn.trim()).apply()
    }

    /**
     * Executes REST API dispatch to publish post via LinkedIn UGC Posts API.
     * Endpoint: https://api.linkedin.com/v2/ugcPosts
     */
    suspend fun postToLinkedIn(
        content: String,
        context: Context? = null
    ): LinkedInPostResult = withContext(Dispatchers.IO) {
        if (content.isBlank()) {
            val err = "Cannot post empty content to LinkedIn."
            logSystemEvent(context, "WARN", err)
            return@withContext LinkedInPostResult.Error(err)
        }

        Log.i(TAG, "Initiating REST dispatch to LinkedIn UGC Post API...")

        var token = getAccessToken(context)
        if (token.isNullOrBlank()) {
            token = refreshAccessToken(context)
        }
        if (token.isNullOrBlank()) {
            val errorMsg = "LinkedIn not connected — please configure LINKEDIN_OAUTH_TOKEN or client credentials in Settings before publishing posts."
            logSystemEvent(context, "WARN", errorMsg)
            return@withContext LinkedInPostResult.Error(errorMsg)
        }

        var authorUrn = getAuthorUrn(context)
        if (authorUrn.isNullOrBlank()) {
            authorUrn = fetchUserSubOrUrn(token) ?: "urn:li:person:me"
        }

        // Construct official LinkedIn UGC Post JSON payload
        val jsonPayload = JSONObject().apply {
            put("author", authorUrn)
            put("lifecycleState", "PUBLISHED")
            put("specificContent", JSONObject().apply {
                put("com.linkedin.ugc.ShareContent", JSONObject().apply {
                    put("shareCommentary", JSONObject().apply {
                        put("text", content.trim())
                    })
                    put("shareMediaCategory", "NONE")
                })
            })
            put("visibility", JSONObject().apply {
                put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC")
            })
        }.toString()

        try {
            val url = "https://api.linkedin.com/v2/ugcPosts"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            var request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Restli-Protocol-Version", "2.0.0")
                .addHeader("LinkedIn-Version", "202304")
                .post(jsonPayload.toRequestBody(mediaType))
                .build()

            var response = httpClient.newCall(request).execute()

            // Handle 401 token expiry by refreshing
            if (response.code == 401) {
                response.close()
                val refreshed = refreshAccessToken(context)
                if (!refreshed.isNullOrBlank()) {
                    token = refreshed
                    request = Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("X-Restli-Protocol-Version", "2.0.0")
                        .addHeader("LinkedIn-Version", "202304")
                        .post(jsonPayload.toRequestBody(mediaType))
                        .build()
                    response = httpClient.newCall(request).execute()
                } else {
                    return@withContext LinkedInPostResult.Error("LinkedIn OAuth token expired and refresh failed.")
                }
            }

            response.use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (resp.isSuccessful || resp.code == 201 || resp.code == 200) {
                    val jsonResp = try { JSONObject(responseBody) } catch (e: Exception) { null }
                    val postId = jsonResp?.optString("id")
                        ?: resp.header("x-restli-id")
                        ?: "urn:li:ugcPost:${System.currentTimeMillis()}"

                    val details = "Successfully published post to LinkedIn (ID: $postId)"
                    Log.i(TAG, details)
                    logSystemEvent(context, "INFO", "LinkedIn Post Published: $details")
                    return@withContext LinkedInPostResult.Success(postId, details)
                } else {
                    val errorDetail = "LinkedIn API Error (HTTP ${resp.code}): ${responseBody.ifBlank { resp.message }}"
                    Log.e(TAG, errorDetail)
                    logSystemEvent(context, "ERROR", errorDetail)
                    return@withContext LinkedInPostResult.Error(errorDetail)
                }
            }
        } catch (e: Exception) {
            val errorMsg = "LinkedIn Network Call Failed: ${e.message ?: e.toString()}"
            Log.e(TAG, errorMsg, e)
            logSystemEvent(context, "ERROR", errorMsg)
            return@withContext LinkedInPostResult.Error(errorMsg)
        }
    }

    private fun fetchUserSubOrUrn(token: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.linkedin.com/v2/userinfo")
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val sub = json.optString("sub")
                    if (sub.isNotBlank()) "urn:li:person:$sub" else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun logSystemEvent(context: Context?, level: String, message: String) {
        if (context == null) return
        try {
            val db = WastiDatabase.getDatabase(context)
            db.systemLogDao().insertLog(
                SystemLogEntity(
                    level = level,
                    source = "LinkedInOAuthService",
                    message = message,
                    details = "LinkedIn OAuth 2.0 API Integration",
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert log to SystemLogEntity", e)
        }
    }
}
