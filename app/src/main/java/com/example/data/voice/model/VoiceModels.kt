package com.example.data.voice.model

enum class SpeechLanguage(val code: String, val displayName: String) {
    ENGLISH("en-US", "English (US)"),
    URDU("ur-PK", "Urdu (Pakistan)"),
    ROMAN_URDU("ur-Roman", "Roman Urdu (Phonetic)"),
    PUNJABI("pa-PK", "Punjabi"),
    AUTO("auto", "Auto Detect Language")
}

enum class VoiceGender {
    MALE,
    FEMALE,
    NEUTRAL
}

data class VoiceProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val gender: VoiceGender = VoiceGender.NEUTRAL,
    val language: SpeechLanguage = SpeechLanguage.ENGLISH,
    val sampleRateHertz: Int = 24000
)

data class SpeechSynthesisRequest(
    val text: String,
    val language: SpeechLanguage = SpeechLanguage.ENGLISH,
    val voiceProfile: VoiceProfile? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

data class SpeechSynthesisResult(
    val audioBytes: ByteArray? = null,
    val providerId: String,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null,
    val durationMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SpeechSynthesisResult
        if (audioBytes != null) {
            if (other.audioBytes == null) return false
            if (!audioBytes.contentEquals(other.audioBytes)) return false
        } else if (other.audioBytes != null) return false
        if (providerId != other.providerId) return false
        if (isSuccess != other.isSuccess) return false
        if (errorMessage != other.errorMessage) return false
        if (durationMs != other.durationMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioBytes?.contentHashCode() ?: 0
        result = 31 * result + providerId.hashCode()
        result = 31 * result + isSuccess.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + durationMs.hashCode()
        return result
    }
}
