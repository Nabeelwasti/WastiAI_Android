package com.example.data.voice.provider

import com.example.data.api.ElevenLabsClient
import com.example.data.credential.CredentialRegistry
import com.example.data.voice.model.SpeechLanguage
import com.example.data.voice.model.SpeechSynthesisRequest
import com.example.data.voice.model.SpeechSynthesisResult
import com.example.data.voice.model.VoiceGender
import com.example.data.voice.model.VoiceProfile

class ElevenLabsSpeechProvider : SpeechProvider {
    override val id: String = "elevenlabs"
    override val name: String = "ElevenLabs Neural Voice AI"

    override fun isAvailable(): Boolean {
        return CredentialRegistry.getRawValue("ELEVENLABS_API_KEY").isNotBlank()
    }

    override suspend fun getAvailableVoices(): List<VoiceProfile> {
        return listOf(
            VoiceProfile("21m00Tcm4TlvDq8ikWAM", "Rachel (Wasti HD Female)", id, VoiceGender.FEMALE, SpeechLanguage.ENGLISH),
            VoiceProfile("AZnzlk1XvdvUeBnXmlld", "Domi (Wasti Energetic)", id, VoiceGender.FEMALE, SpeechLanguage.ENGLISH),
            VoiceProfile("EXAVITQu4vr4xnSDxMaL", "Bella (Wasti Conversational)", id, VoiceGender.FEMALE, SpeechLanguage.ENGLISH),
            VoiceProfile("ErXwobaYiN019PkySvjV", "Antoni (Wasti Expressive Male)", id, VoiceGender.MALE, SpeechLanguage.ENGLISH)
        )
    }

    override suspend fun synthesizeSpeech(request: SpeechSynthesisRequest): SpeechSynthesisResult {
        val apiKey = CredentialRegistry.getRawValue("ELEVENLABS_API_KEY")
        if (apiKey.isBlank()) {
            return SpeechSynthesisResult(
                providerId = id,
                isSuccess = false,
                errorMessage = "ElevenLabs API key is missing in CredentialRegistry."
            )
        }

        val startTime = System.currentTimeMillis()
        val voiceId = request.voiceProfile?.id ?: ElevenLabsClient.DEFAULT_VOICE_ID

        return try {
            val audioBytes = ElevenLabsClient.synthesizeSpeech(
                text = request.text,
                voiceId = voiceId
            )
            val elapsed = System.currentTimeMillis() - startTime
            if (audioBytes != null && audioBytes.isNotEmpty()) {
                SpeechSynthesisResult(
                    audioBytes = audioBytes,
                    providerId = id,
                    isSuccess = true,
                    durationMs = elapsed
                )
            } else {
                SpeechSynthesisResult(
                    providerId = id,
                    isSuccess = false,
                    errorMessage = "ElevenLabs returned empty audio payload."
                )
            }
        } catch (e: Exception) {
            SpeechSynthesisResult(
                providerId = id,
                isSuccess = false,
                errorMessage = e.message ?: "ElevenLabs synthesis failed."
            )
        }
    }
}
