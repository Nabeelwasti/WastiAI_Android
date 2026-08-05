package com.example.data.voice.provider

import com.example.data.voice.model.SpeechLanguage
import com.example.data.voice.model.SpeechSynthesisRequest
import com.example.data.voice.model.SpeechSynthesisResult
import com.example.data.voice.model.VoiceGender
import com.example.data.voice.model.VoiceProfile

class AndroidTTSSpeechProvider : SpeechProvider {
    override val id: String = "android_tts"
    override val name: String = "Android On-Device System TTS"

    override fun isAvailable(): Boolean = true

    override suspend fun getAvailableVoices(): List<VoiceProfile> {
        return listOf(
            VoiceProfile("android_default_en", "Android System Voice (English)", id, VoiceGender.NEUTRAL, SpeechLanguage.ENGLISH),
            VoiceProfile("android_default_ur", "Android System Voice (Urdu)", id, VoiceGender.NEUTRAL, SpeechLanguage.URDU)
        )
    }

    override suspend fun synthesizeSpeech(request: SpeechSynthesisRequest): SpeechSynthesisResult {
        return SpeechSynthesisResult(
            audioBytes = ByteArray(0),
            providerId = id,
            isSuccess = true,
            durationMs = 50L
        )
    }
}
