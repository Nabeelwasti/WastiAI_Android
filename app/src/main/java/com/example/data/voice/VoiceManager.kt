package com.example.data.voice

import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import com.example.data.voice.model.SpeechLanguage
import com.example.data.voice.model.SpeechSynthesisRequest
import com.example.data.voice.model.SpeechSynthesisResult
import com.example.data.voice.model.VoiceProfile
import com.example.data.voice.provider.AndroidSpeechToTextProvider
import com.example.data.voice.provider.AndroidTTSSpeechProvider
import com.example.data.voice.provider.ElevenLabsSpeechProvider
import com.example.data.voice.provider.SpeechProvider
import com.example.data.voice.provider.SpeechToTextProvider
import com.example.util.WastiSpeechSanitizer
import com.example.util.WastiUrduLanguageEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

object VoiceManager {

    private val providers = ConcurrentHashMap<String, SpeechProvider>()
    private val sttProviders = ConcurrentHashMap<String, SpeechToTextProvider>()

    private val _activeProviderId = MutableStateFlow("elevenlabs")
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    val sttProvider: SpeechToTextProvider by lazy {
        AndroidSpeechToTextProvider()
    }

    init {
        registerProvider(ElevenLabsSpeechProvider())
        registerProvider(AndroidTTSSpeechProvider())
        registerSTTProvider(sttProvider)
    }

    fun registerProvider(provider: SpeechProvider) {
        providers[provider.id] = provider
    }

    fun registerSTTProvider(provider: SpeechToTextProvider) {
        sttProviders[provider.id] = provider
    }

    fun getProvider(id: String): SpeechProvider? = providers[id]

    fun setActiveProvider(id: String) {
        if (providers.containsKey(id)) {
            _activeProviderId.value = id
        }
    }

    fun stopSpeaking() {
        _isSpeaking.value = false
        WastiEventBus.tryEmit(WastiEvent.VoiceSessionChanged(false, _activeProviderId.value))
    }

    suspend fun synthesizeSpeech(
        text: String,
        preferredProviderId: String? = null,
        targetLanguage: SpeechLanguage = SpeechLanguage.AUTO
    ): SpeechSynthesisResult {
        _isSpeaking.value = true
        
        // Multilingual preprocessing: English, Urdu, Roman Urdu
        val sanitizedText = WastiSpeechSanitizer.sanitizeForSpeech(text)
        val detectedLang = WastiUrduLanguageEngine.detectLanguage(sanitizedText)
        val processedText = if (targetLanguage == SpeechLanguage.ROMAN_URDU || detectedLang == WastiUrduLanguageEngine.LanguageType.ROMAN_URDU) {
            WastiUrduLanguageEngine.prepareTextForTts(sanitizedText)
        } else {
            sanitizedText
        }

        val request = SpeechSynthesisRequest(
            text = processedText,
            language = targetLanguage
        )

        val primaryProviderId = preferredProviderId ?: _activeProviderId.value
        val primaryProvider = providers[primaryProviderId]

        var result: SpeechSynthesisResult? = null

        if (primaryProvider != null && primaryProvider.isAvailable()) {
            result = primaryProvider.synthesizeSpeech(request)
        }

        // Failover to Android TTS if primary failed
        if (result == null || !result.isSuccess) {
            val fallbackProvider = providers["android_tts"]
            if (fallbackProvider != null) {
                result = fallbackProvider.synthesizeSpeech(request)
            }
        }

        val finalResult = result ?: SpeechSynthesisResult(
            providerId = "none",
            isSuccess = false,
            errorMessage = "No voice synthesis provider available."
        )

        _isSpeaking.value = false
        WastiEventBus.emit(WastiEvent.VoiceSessionChanged(false, finalResult.providerId))

        return finalResult
    }

    suspend fun getAllAvailableVoices(): List<VoiceProfile> {
        val list = mutableListOf<VoiceProfile>()
        providers.values.filter { it.isAvailable() }.forEach { provider ->
            list.addAll(provider.getAvailableVoices())
        }
        return list
    }
}
