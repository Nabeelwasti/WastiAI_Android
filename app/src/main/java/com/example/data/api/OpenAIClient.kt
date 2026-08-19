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

data class OpenAIMessage(
    val role: String,
    val content: String
)

data class OpenAIChatRequest(
    val model: String = "gpt-4o",
    val messages: List<OpenAIMessage>,
    val temperature: Float = 0.7f
)

data class OpenAIChoice(
    val message: OpenAIMessage?
)

data class OpenAIChatResponse(
    val choices: List<OpenAIChoice>?
)

interface OpenAIApi {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: OpenAIChatRequest
    ): OpenAIChatResponse
}

object OpenAIClient {
    private const val BASE_URL = "https://api.openai.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: OpenAIApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenAIApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti AI, powered by OpenAI intelligence.",
        apiKey: String,
        modelName: String = "gpt-3.5-turbo"
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("OpenAI API key is blank.")
        }

        val bearer = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        val messages = listOf(
            OpenAIMessage("system", systemInstruction),
            OpenAIMessage("user", prompt)
        )

        val candidateModels = listOfNotNull(
            modelName.ifBlank { null },
            "gpt-3.5-turbo",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna"
        ).distinct()

        var lastError: String? = null
for (model in candidateModels) {
    try {
        val response = api.createChatCompletion(
            bearerToken = bearer,
            request = OpenAIChatRequest(model = model, messages = messages)
        )
        val output = response.choices?.firstOrNull()?.message?.content
        if (!output.isNullOrBlank()) {
            return@withContext output
        }
    } catch (e: Exception) {
        lastError = e.message ?: e.toString()
    }
}
throw IllegalStateException("OpenAI request failed for all candidate models. Last error: $lastError")
    }
}
