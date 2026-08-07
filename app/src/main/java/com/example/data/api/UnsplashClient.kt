package com.example.data.api

import com.example.data.credential.CredentialRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UnsplashPhotoUrls(val raw: String, val full: String, val regular: String, val small: String)
data class UnsplashPhotoItem(val id: String, val description: String?, val urls: UnsplashPhotoUrls)
data class UnsplashSearchResponse(val results: List<UnsplashPhotoItem>?)

object UnsplashClient {
    private const val BASE_URL = "https://api.unsplash.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun searchStockPhotos(query: String): List<UnsplashPhotoItem> = withContext(Dispatchers.IO) {
        val accessKey = CredentialRegistry.getRawValue("UNSPLASH_ACCESS_KEY")
        if (accessKey.isNullOrBlank()) return@withContext emptyList()

        val request = Request.Builder()
            .url("$BASE_URL/search/photos?query=${query.trim()}&per_page=3")
            .addHeader("Authorization", "Client-ID $accessKey")
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: ""
                val adapter = moshi.adapter(UnsplashSearchResponse::class.java)
                adapter.fromJson(json)?.results ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
