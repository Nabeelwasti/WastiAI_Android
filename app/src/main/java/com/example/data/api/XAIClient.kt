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

data class XAIMessage(
    val role: String,
    val content: String
)

data class XAIChatRequest(
    val model: String = "grok-2-latest",
    val messages: List<XAIMessage>,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class XAIChoice(
    val message: XAIMessage?
)

data class XAIChatResponse(
    val choices: List<XAIChoice>?
)

interface XAIApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: XAIChatRequest
    ): XAIChatResponse
}

object XAIClient {
    private const val BASE_URL = "https://api.x.ai/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: XAIApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(XAIApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI, powered by x.ai Grok Intelligence.",
        apiKey: String,
        modelName: String = "grok-4.3"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("x.ai API key is blank. Please enter your x.ai API key in Settings.")
        }

        val bearer = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        val messages = listOf(
            XAIMessage("system", systemInstruction),
            XAIMessage("user", prompt)
        )

        val candidateModels = listOfNotNull(
            modelName.ifBlank { null },
            "grok-4.3",
            "grok-2-latest",
            "grok-2",
            "grok-2-1212"
        ).distinct()

        var lastError: String? = null
for (model in candidateModels) {
    try {
        val response = api.createChatCompletion(
            bearerToken = bearer,
            request = XAIChatRequest(model = model, messages = messages)
        )
        val output = response.choices?.firstOrNull()?.message?.content
        if (!output.isNullOrBlank()) {
            return@withContext output
        }
    } catch (e: Exception) {
        lastError = e.message ?: e.toString()
    }
}
throw IllegalStateException("x.ai Grok request failed for all candidate models. Last error: $lastError")
    }
}
