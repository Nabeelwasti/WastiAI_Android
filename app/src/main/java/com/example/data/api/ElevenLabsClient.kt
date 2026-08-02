package com.example.data.api

import com.example.data.credential.CredentialRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

data class ElevenLabsTTSRequest(
    val text: String,
    val model_id: String = "eleven_multilingual_v2",
    val voice_settings: ElevenLabsVoiceSettings = ElevenLabsVoiceSettings()
)

data class ElevenLabsVoiceSettings(
    val stability: Float = 0.5f,
    val similarity_boost: Float = 0.75f,
    val style: Float = 0.0f,
    val use_speaker_boost: Boolean = true
)

interface ElevenLabsApi {
    @POST("v1/text-to-speech/{voice_id}")
    suspend fun textToSpeech(
        @Header("xi-api-key") apiKey: String,
        @Path("voice_id") voiceId: String,
        @Body request: ElevenLabsTTSRequest
    ): ResponseBody
}

object ElevenLabsClient {
    private const val BASE_URL = "https://api.elevenlabs.io/"
    const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel / Default Wasti HD Voice

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: ElevenLabsApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ElevenLabsApi::class.java)
    }

    suspend fun synthesizeSpeech(
        text: String,
        voiceId: String = DEFAULT_VOICE_ID,
        modelId: String = "eleven_multilingual_v2"
    ): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = CredentialRegistry.getRawValue("ELEVENLABS_API_KEY")
        if (apiKey.isBlank()) return@withContext null

        try {
            val response = api.textToSpeech(
                apiKey = apiKey,
                voiceId = voiceId,
                request = ElevenLabsTTSRequest(text = text, model_id = modelId)
            )
            response.bytes()
        } catch (e: Exception) {
            null
        }
    }
}
