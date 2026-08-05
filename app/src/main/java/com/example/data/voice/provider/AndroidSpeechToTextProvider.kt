package com.example.data.voice.provider

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechToTextProvider : SpeechToTextProvider {

    override val id: String = "android_stt"
    override val name: String = "Android Native Speech Recognizer"

    private val _currentState = MutableStateFlow(STTState.IDLE)
    override val currentState: StateFlow<STTState> = _currentState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    override fun isHardwareAvailable(context: Context): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            Log.e("AndroidSpeechToText", "Hardware check error", e)
            false
        }
    }

    override fun startListening(
        context: Context,
        languageTag: String,
        onResult: (STTResult) -> Unit
    ) {
        if (!isHardwareAvailable(context)) {
            _currentState.value = STTState.ERROR
            onResult(
                STTResult(
                    transcript = "",
                    isFinal = true,
                    errorMsg = "Speech recognition hardware or service unavailable on this device/emulator."
                )
            )
            return
        }

        try {
            stopListening()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _currentState.value = STTState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    _currentState.value = STTState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _currentState.value = STTState.PROCESSING
                }

                override fun onError(error: Int) {
                    _currentState.value = STTState.ERROR
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
                        else -> "Speech recognition error code: $error"
                    }
                    onResult(STTResult(transcript = "", isFinal = true, errorMsg = errorMsg))
                }

                override fun onResults(results: Bundle?) {
                    _currentState.value = STTState.IDLE
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    onResult(STTResult(transcript = text, isFinal = true))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        onResult(STTResult(transcript = text, isFinal = false))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer = recognizer
            recognizer.startListening(intent)
        } catch (e: Exception) {
            _currentState.value = STTState.ERROR
            Log.e("AndroidSpeechToText", "Failed to start listening", e)
            onResult(STTResult(transcript = "", isFinal = true, errorMsg = e.localizedMessage))
        }
    }

    override fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
            _currentState.value = STTState.IDLE
        } catch (e: Exception) {
            Log.e("AndroidSpeechToText", "Error stopping listener", e)
        }
    }

    override fun destroy() {
        stopListening()
    }
}
