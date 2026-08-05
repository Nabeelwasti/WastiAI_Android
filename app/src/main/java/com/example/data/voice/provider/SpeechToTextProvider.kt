package com.example.data.voice.provider

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

enum class STTState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

data class STTResult(
    val transcript: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f,
    val errorMsg: String? = null
)

interface SpeechToTextProvider {
    val id: String
    val name: String
    val currentState: StateFlow<STTState>
    fun isHardwareAvailable(context: Context): Boolean
    fun startListening(context: Context, languageTag: String = "en-US", onResult: (STTResult) -> Unit)
    fun stopListening()
    fun destroy()
}
