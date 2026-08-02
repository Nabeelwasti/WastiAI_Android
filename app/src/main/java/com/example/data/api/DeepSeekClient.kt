package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class DeepSeekMessage(
    val role: String,
    val content: String
)

data class DeepSeekChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<DeepSeekMessage>,
    val temperature: Float = 0.7f
)

data class DeepSeekChoice(
    val message: DeepSeekMessage?
)

data class DeepSeekChatResponse(
    val choices: List<DeepSeekChoice>?
)

interface DeepSeekApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: DeepSeekChatRequest
    ): DeepSeekChatResponse
}

object DeepSeekClient {
    private const val BASE_URL = "https://api.deepseek.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: DeepSeekApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeepSeekApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI Coding & Math Engine.",
        apiKey: String,
        modelName: String = "deepseek-chat"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ""
        val bearer = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        val messages = listOf(
            DeepSeekMessage("system", systemInstruction),
            DeepSeekMessage("user", prompt)
        )
        try {
            val response = api.createChatCompletion(
                bearerToken = bearer,
                request = DeepSeekChatRequest(model = modelName, messages = messages)
            )
            response.choices?.firstOrNull()?.message?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
