package com.example.data.voice.provider

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.credential.CredentialRegistry
import com.example.data.voice.model.SpeechLanguage
import com.example.data.voice.model.SpeechSynthesisRequest
import com.example.data.voice.model.SpeechSynthesisResult
import com.example.data.voice.model.VoiceGender
import com.example.data.voice.model.VoiceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

class AndroidTTSSpeechProvider(private val context: Context? = null) : SpeechProvider {
    override val id: String = "android_tts"
    override val name: String = "Android On-Device System TTS"

    private var ttsEngine: TextToSpeech? = null
    private var isInitialized = false
    private val initDeferred = CompletableDeferred<Boolean>()

    init {
        val targetContext = context ?: CredentialRegistry.appContext
        if (targetContext != null) {
            try {
                ttsEngine = TextToSpeech(targetContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isInitialized = true
                        initDeferred.complete(true)
                    } else {
                        Log.e("AndroidTTS", "Initialization failed with status: $status")
                        initDeferred.complete(false)
                    }
                }
            } catch (e: Exception) {
                Log.e("AndroidTTS", "Error instantiating TextToSpeech", e)
                initDeferred.complete(false)
            }
        } else {
            initDeferred.complete(false)
        }
    }

    override fun isAvailable(): Boolean = isInitialized || ttsEngine != null

    override suspend fun getAvailableVoices(): List<VoiceProfile> {
        val ready = withTimeoutOrNull(2000L) { initDeferred.await() } ?: isInitialized
        if (!ready || ttsEngine == null) {
            return listOf(
                VoiceProfile("android_default_en", "Android System Voice (English)", id, VoiceGender.NEUTRAL, SpeechLanguage.ENGLISH),
                VoiceProfile("android_default_ur", "Android System Voice (Urdu)", id, VoiceGender.NEUTRAL, SpeechLanguage.URDU)
            )
        }

        return try {
            val voices = ttsEngine?.voices
            if (!voices.isNullOrEmpty()) {
                voices.map { v ->
                    val lang = when {
                        v.locale.language.startsWith("ur") -> SpeechLanguage.URDU
                        v.locale.language.startsWith("pa") -> SpeechLanguage.PUNJABI
                        else -> SpeechLanguage.ENGLISH
                    }
                    VoiceProfile(
                        id = v.name,
                        name = "System: ${v.locale.displayLanguage} (${v.name})",
                        providerId = id,
                        gender = VoiceGender.NEUTRAL,
                        language = lang
                    )
                }.take(10)
            } else {
                listOf(
                    VoiceProfile("android_default_en", "Android System Voice (English)", id, VoiceGender.NEUTRAL, SpeechLanguage.ENGLISH),
                    VoiceProfile("android_default_ur", "Android System Voice (Urdu)", id, VoiceGender.NEUTRAL, SpeechLanguage.URDU)
                )
            }
        } catch (_: Exception) {
            listOf(
                VoiceProfile("android_default_en", "Android System Voice (English)", id, VoiceGender.NEUTRAL, SpeechLanguage.ENGLISH),
                VoiceProfile("android_default_ur", "Android System Voice (Urdu)", id, VoiceGender.NEUTRAL, SpeechLanguage.URDU)
            )
        }
    }

    override suspend fun synthesizeSpeech(request: SpeechSynthesisRequest): SpeechSynthesisResult = withContext(Dispatchers.IO) {
        val ready = withTimeoutOrNull(3000L) { initDeferred.await() } ?: isInitialized
        val tts = ttsEngine
        if (!ready || tts == null) {
            return@withContext SpeechSynthesisResult(
                audioBytes = null,
                providerId = id,
                isSuccess = false,
                errorMessage = "Android TextToSpeech engine is not initialized or unavailable on this device"
            )
        }

        val locale = when (request.language) {
            SpeechLanguage.URDU, SpeechLanguage.ROMAN_URDU -> Locale("ur", "PK")
            SpeechLanguage.PUNJABI -> Locale("pa", "PK")
            else -> Locale.US
        }

        val locResult = tts.setLanguage(locale)
        if (locResult == TextToSpeech.LANG_MISSING_DATA || locResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }

        tts.setSpeechRate(request.speechRate)
        tts.setPitch(request.pitch)

        val targetContext = context ?: CredentialRegistry.appContext
        val utteranceId = "tts_${UUID.randomUUID()}"
        val synthesisDone = CompletableDeferred<Boolean>()

        if (targetContext != null) {
            val tempAudioFile = File(targetContext.cacheDir, "$utteranceId.wav")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) synthesisDone.complete(true)
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) synthesisDone.complete(false)
                }
            })

            val params = Bundle()
            val synthResult = tts.synthesizeToFile(request.text, params, tempAudioFile, utteranceId)
            if (synthResult == TextToSpeech.SUCCESS) {
                val completed = withTimeoutOrNull(5000L) { synthesisDone.await() } ?: false
                if (completed && tempAudioFile.exists() && tempAudioFile.length() > 0) {
                    val bytes = tempAudioFile.readBytes()
                    tempAudioFile.delete()
                    return@withContext SpeechSynthesisResult(
                        audioBytes = bytes,
                        providerId = id,
                        isSuccess = true,
                        durationMs = (request.text.length * 60L).coerceAtLeast(100L)
                    )
                }
            }
        }

        // Direct speak playback fallback
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

        SpeechSynthesisResult(
            audioBytes = ByteArray(0),
            providerId = id,
            isSuccess = true,
            durationMs = (request.text.length * 60L).coerceAtLeast(100L)
        )
    }
}

