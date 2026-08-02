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

data class OpenRouterMessage(
    val role: String,
    val content: String
)

data class OpenRouterChatRequest(
    val model: String = "meta-llama/llama-3.3-70b-instruct:free",
    val messages: List<OpenRouterMessage>,
    val temperature: Float = 0.7f
)

data class OpenRouterChoice(
    val message: OpenRouterMessage?
)

data class OpenRouterChatResponse(
    val choices: List<OpenRouterChoice>?
)

interface OpenRouterApi {
    @POST("api/v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: OpenRouterChatRequest
    ): OpenRouterChatResponse
}

object OpenRouterClient {
    private const val BASE_URL = "https://openrouter.ai/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: OpenRouterApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenRouterApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI, powered by OpenRouter gateway.",
        apiKey: String,
        modelName: String = "meta-llama/llama-3.3-70b-instruct:free"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext ""
        val bearer = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        val messages = listOf(
            OpenRouterMessage("system", systemInstruction),
            OpenRouterMessage("user", prompt)
        )
        try {
            val response = api.createChatCompletion(
                bearerToken = bearer,
                request = OpenRouterChatRequest(model = modelName, messages = messages)
            )
            response.choices?.firstOrNull()?.message?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
