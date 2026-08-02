package com.example.data.api

import com.example.data.credential.CredentialRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class GitHubRepoItem(
    val name: String,
    val full_name: String,
    val description: String?,
    val open_issues_count: Int?
)

object GitHubApiClient {
    private const val BASE_URL = "https://api.github.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun getRecentRepositories(): List<GitHubRepoItem> = withContext(Dispatchers.IO) {
        val pat = CredentialRegistry.getRawValue("GITHUB_FINE_GRAINED_PAT").ifBlank {
            CredentialRegistry.getRawValue("GITHUB_PAT")
        }
        if (pat.isBlank()) return@withContext emptyList()

        val request = Request.Builder()
            .url("$BASE_URL/user/repos?sort=updated&per_page=5")
            .addHeader("Authorization", "Bearer $pat")
            .addHeader("Accept", "application/vnd.github+json")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                val adapter = moshi.adapter<List<GitHubRepoItem>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, GitHubRepoItem::class.java)
                )
                adapter.fromJson(json) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
