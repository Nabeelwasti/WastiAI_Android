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

data class GroqMessage(
    val role: String,
    val content: String
)

data class GroqChatRequest(
    val model: String = "llama-3.3-70b-versatile",
    val messages: List<GroqMessage>,
    val temperature: Float = 0.7f
)

data class GroqChoice(
    val message: GroqMessage?
)

data class GroqChatResponse(
    val choices: List<GroqChoice>?
)

interface GroqApi {
    @POST("openai/v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") bearerToken: String,
        @Body request: GroqChatRequest
    ): GroqChatResponse
}

object GroqClient {
    private const val BASE_URL = "https://api.groq.com/"
    val defaultGroqKey: String
        get() = try {
            com.example.data.credential.CredentialRegistry.getRawValue("GROQ_API_KEY")
                ?: com.example.BuildConfig.GROQ_API_KEY
        } catch (e: Throwable) {
            try { com.example.BuildConfig.GROQ_API_KEY } catch (t: Throwable) { "" }
        }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: GroqApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GroqApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "STRICT LANGUAGE MATCHING MANDATE: You MUST reply in the EXACT SAME language, dialect, and script used by the user in their prompt. If the user prompts in English, reply strictly in English. If the user prompts in Urdu script (اردو), reply in Urdu script. If the user prompts in Roman Urdu, reply in Roman Urdu. If the user prompts in Spanish, French, Punjabi, German, Hindi, or any other language, reply in that exact language. NEVER default to Roman Urdu or any other language unless the user specifically wrote in that language.",
        modelName: String = "llama-3.3-70b-versatile",
        customApiKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = if (!customApiKey.isNullOrBlank()) customApiKey else defaultGroqKey
        val bearer = "Bearer $apiKey"

        val resolvedModel = when {
            modelName.contains("70b", ignoreCase = true) -> "llama-3.3-70b-versatile"
            modelName.contains("8b", ignoreCase = true) -> "llama-3.1-8b-instant"
            else -> "llama-3.3-70b-versatile"
        }

        val messages = listOf(
            GroqMessage("system", systemInstruction),
            GroqMessage("user", prompt)
        )

        try {
            val response = api.createChatCompletion(
                bearerToken = bearer,
                request = GroqChatRequest(model = resolvedModel, messages = messages)
            )
            val output = response.choices?.firstOrNull()?.message?.content
            if (!output.isNullOrBlank()) {
                output
            } else {
                "No output received from Groq API."
            }
        } catch (e: Exception) {
            // Fallback gracefully
            throw e
        }
    }
}
