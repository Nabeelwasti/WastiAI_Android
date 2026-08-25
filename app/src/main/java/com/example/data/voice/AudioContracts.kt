package com.example.data.voice

import com.example.data.voice.model.SpeechLanguage
import kotlinx.coroutines.flow.StateFlow

/**
 * Stage 19: Truthful Audio Reality States.
 */
enum class AudioRealityState {
    AVAILABLE,
    CONNECTED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    DEVICE_PENDING
}

data class AudioCaptureFormat(
    val sampleRate: Int = 16000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
    val bufferSizeBytes: Int = 2048
)

data class AudioChunk(
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val energyRms: Double = 0.0,
    val isSpeech: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunk
        return data.contentEquals(other.data) && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

enum class VadState {
    SILENCE,
    SPEECH_START,
    SPEECH_ACTIVE,
    SPEECH_END
}

interface AudioCaptureProvider {
    val id: String
    val displayName: String
    val realityState: AudioRealityState
    val isCapturing: Boolean

    suspend fun checkAvailability(): AudioRealityState
    fun startCapture(onChunk: (AudioChunk) -> Unit): Result<Unit>
    fun stopCapture()
}

interface AudioOutputProvider {
    val id: String
    val displayName: String
    val realityState: AudioRealityState
    val isPlaying: Boolean

    suspend fun checkAvailability(): AudioRealityState
    suspend fun playAudio(audioBytes: ByteArray, sampleRate: Int = 24000): Result<Unit>
    suspend fun synthesizeAndPlay(text: String, language: SpeechLanguage = SpeechLanguage.ENGLISH): Result<Unit>
    fun stopPlayback()
}

interface VoiceActivityDetector {
    val speechThresholdRms: Double
    val minSpeechDurationMs: Long
    val maxSilenceDurationMs: Long

    fun processChunk(chunk: AudioChunk): VadState
    fun reset()
}
