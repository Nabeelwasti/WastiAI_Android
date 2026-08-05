package com.example.data.voice.provider

import com.example.data.voice.model.SpeechSynthesisRequest
import com.example.data.voice.model.SpeechSynthesisResult
import com.example.data.voice.model.VoiceProfile

interface SpeechProvider {
    val id: String
    val name: String
    fun isAvailable(): Boolean
    suspend fun getAvailableVoices(): List<VoiceProfile>
    suspend fun synthesizeSpeech(request: SpeechSynthesisRequest): SpeechSynthesisResult
}
