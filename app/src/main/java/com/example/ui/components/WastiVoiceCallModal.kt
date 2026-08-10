package com.example.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.device.WastiDeviceController
import com.example.data.device.WastiIntentParser
import com.example.data.voice.VoiceManager
import com.example.data.voice.provider.STTState
import com.example.util.WastiSpeechSanitizer
import com.example.util.WastiUrduLanguageEngine
import java.util.Locale

enum class WastiVoicePersona(
    val title: String,
    val icon: String,
    val gender: String, // "male" or "female"
    val pitch: Float,
    val speed: Float,
    val description: String
) {
    GROQ_VOICE("Wasti Ultra-Fast Speech", "⚡", "male", 1.00f, 1.10f, "Wasti Ultra-Fast Natural Speech Engine"),
    ELEVENLABS_HD("Wasti HD Neural Voice", "🎙️", "female", 1.00f, 0.98f, "Wasti Neural HD Audio Engine"),
    WASTI_MALE("Wasti Prime (Male)", "👨", "male", 0.92f, 0.98f, "Deep articulate, warm natural masculine tone"),
    WASTI_FEMALE("Wasti Female / Woman", "👩", "female", 1.05f, 1.00f, "Clear, fluent, professional female voice"),
    WASTI_GIRL("Wasti Girl", "👧", "female", 1.20f, 1.02f, "Upbeat, lively youthful girl voice"),
    WASTI_BOY("Wasti Intelligent Boy", "👦", "male", 1.02f, 1.08f, "Quick, smart youthful boy voice")
}

@Composable
fun WastiVoiceCallModal(
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit,
    lastAiResponse: String?
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE) }
    val enableExtraVoiceModels = remember { prefs.getBoolean("enable_extra_voice_models", false) }

    var selectedPersona by remember { mutableStateOf(WastiVoicePersona.GROQ_VOICE) }
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var voiceStatusText by remember { mutableStateOf("Wasti Native Speech Engine • Tap mic or speak") }
    var liveTranscript by remember { mutableStateOf("Tap the microphone or say commands in English, Urdu, or Punjabi...") }
    var simulatedInputText by remember { mutableStateOf("") }
    var rmsAudioLevel by remember { mutableFloatStateOf(0.1f) }

    // Speech-To-Text Provider Observer
    val sttProvider = remember { VoiceManager.sttProvider }
    val sttState by (sttProvider?.currentState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(STTState.IDLE) }).collectAsState()

    LaunchedEffect(sttState) {
        when (sttState) {
            STTState.LISTENING -> {
                isListening = true
                voiceStatusText = "Listening... Speak now"
            }
            STTState.PROCESSING -> {
                isListening = false
                voiceStatusText = "Processing speech..."
            }
            STTState.ERROR -> {
                isListening = false
                voiceStatusText = "STT Error"
            }
            else -> {}
        }
    }

    val coroutineScope = rememberCoroutineScope()
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var micRecordingJob by remember { mutableStateOf<Job?>(null) }
    var startNativeListeningCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // MediaPlayer & TextToSpeech Engine
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Hard Stop / Interrupt Function
    fun stopAudioAndTTS() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        mediaPlayer = null

        try {
            ttsEngine?.stop()
        } catch (e: Exception) {
            // ignore
        }

        isSpeaking = false
        voiceStatusText = "Speech interrupted."
    }

    fun stopTTS() {
        stopAudioAndTTS()
    }

    // Permission launcher for microphone
    var startListeningOnPermissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceStatusText = "Permission granted! Tap mic to speak."
            startListeningOnPermissionGranted = true
        } else {
            voiceStatusText = "Microphone permission required for voice calls."
        }
    }

    // Direct background microphone stream recorder (for real-time soundwave visualization & Voice Barge-In interruption)
    fun startBackgroundAudioRecordMonitor() {
        micRecordingJob?.cancel()
        micRecordingJob = coroutineScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (minBufferSize <= 0) return@launch

            var audioRecord: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBufferSize.coerceAtLeast(2048)
                    )
                    if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                        val sessionId = audioRecord.audioSessionId
                        if (AcousticEchoCanceler.isAvailable() && sessionId != 0) {
                            try {
                                aec = AcousticEchoCanceler.create(sessionId)
                                aec?.enabled = true
                                Log.i("WastiVoiceCallModal", "AcousticEchoCanceler enabled on audio session $sessionId")
                            } catch (e: Exception) {
                                Log.w("WastiVoiceCallModal", "Failed enabling AcousticEchoCanceler", e)
                            }
                        }

                        audioRecord.startRecording()
                        val buffer = ShortArray(1024)

                        while (coroutineContext.isActive && (isListening || isSpeaking || (mediaPlayer != null && mediaPlayer?.isPlaying == true))) {
                            val read = audioRecord.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                var sum = 0.0
                                for (i in 0 until read) {
                                    sum += (buffer[i] * buffer[i]).toDouble()
                                }
                                val amplitude = Math.sqrt(sum / read) / 32768.0
                                withContext(Dispatchers.Main) {
                                    if (isListening) {
                                        rmsAudioLevel = (amplitude * 6.0).toFloat().coerceIn(0.15f, 1.0f)
                                    }

                                    // Task 37C: Voice Barge-In (Interruption)
                                    // If user speaks (mic detects audio spike above amplitude threshold) while AI audio is playing, stop playback and switch to listening mode
                                    val isAiSpeakingCurrently = isSpeaking || (mediaPlayer != null && mediaPlayer?.isPlaying == true)
                                    if (isAiSpeakingCurrently && amplitude > 0.22) {
                                        Log.i("WastiVoiceCallModal", ">>> Voice Barge-In Interruption triggered! Amplitude: $amplitude")
                                        stopAudioAndTTS()
                                        voiceStatusText = "Voice Barge-In! Speech interrupted • Listening..."
                                        startNativeListeningCallback?.invoke()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WastiVoiceCallModal", "Error in microphone audio record loop", e)
            } finally {
                try {
                    aec?.release()
                } catch (e: Exception) {}
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {}
            }
        }
    }

    fun startNativeListening() {
        // Hard stop any ongoing TTS speech before listening
        stopTTS()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (isListening) {
            // Toggle off if already listening
            isListening = false
            rmsAudioLevel = 0.1f
            speechRecognizer?.stopListening()
            micRecordingJob?.cancel()
            voiceStatusText = "Background listening stopped."
            return
        }

        isListening = true
        voiceStatusText = "Listening in background... Speak in Urdu or English"

        val recognizer = speechRecognizer
        if (recognizer != null) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                // Keep listening active using direct PCM AudioRecord monitor
            }
        }
        
        startBackgroundAudioRecordMonitor()
    }

    // Fallback Android System TextToSpeech Speaker
    fun fallbackToAndroidTts(textToSpeak: String, persona: WastiVoicePersona) {
        ttsEngine?.let { tts ->
            tts.stop()
            isSpeaking = true

            val langType = WastiUrduLanguageEngine.detectLanguage(textToSpeak)
            var isSouthAsianVoiceAvailable = false

            try {
                if (langType == WastiUrduLanguageEngine.LanguageType.PURE_URDU ||
                    langType == WastiUrduLanguageEngine.LanguageType.ROMAN_URDU ||
                    langType == WastiUrduLanguageEngine.LanguageType.PUNJABI) {
                    
                    val urResult = tts.setLanguage(Locale.forLanguageTag("ur-PK"))
                    if (urResult != TextToSpeech.LANG_MISSING_DATA && urResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                        isSouthAsianVoiceAvailable = true
                    } else {
                        val hiResult = tts.setLanguage(Locale.forLanguageTag("hi-IN"))
                        if (hiResult != TextToSpeech.LANG_MISSING_DATA && hiResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                            isSouthAsianVoiceAvailable = true
                        } else {
                            tts.setLanguage(Locale.US)
                        }
                    }
                } else {
                    tts.setLanguage(Locale.US)
                }
            } catch (e: Exception) {
                tts.setLanguage(Locale.ENGLISH)
            }

            val ttsText = if (isSouthAsianVoiceAvailable || langType == WastiUrduLanguageEngine.LanguageType.PURE_URDU) {
                WastiUrduLanguageEngine.prepareTextForTts(textToSpeak)
            } else {
                WastiSpeechSanitizer.sanitizeForSpeech(textToSpeak)
            }

            try {
                val systemVoices = tts.voices
                if (!systemVoices.isNullOrEmpty()) {
                    val targetGender = persona.gender
                    val matchedVoice = systemVoices.find { voice ->
                        val vName = voice.name.lowercase()
                        val matchesGender = if (targetGender == "female") {
                            vName.contains("female") || vName.contains("f0") || vName.contains("woman") || vName.contains("sfg")
                        } else {
                            vName.contains("male") || vName.contains("m0") || vName.contains("man") || vName.contains("iob")
                        }
                        val matchesLang = if (langType != WastiUrduLanguageEngine.LanguageType.ENGLISH) voice.locale.language == "ur" else voice.locale.language == "en"
                        matchesGender && matchesLang
                    } ?: systemVoices.find { voice ->
                        val vName = voice.name.lowercase()
                        if (targetGender == "female") vName.contains("female") || vName.contains("sfg")
                        else vName.contains("male") || vName.contains("iob")
                    }

                    if (matchedVoice != null) {
                        tts.voice = matchedVoice
                    }
                }
            } catch (e: Exception) {
                // Pitch/Rate fallback
            }

            tts.setPitch(persona.pitch)
            tts.setSpeechRate(persona.speed)

            voiceStatusText = "Wasti Speaking (${persona.title} - System TTS)..."
            
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    startBackgroundAudioRecordMonitor()
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).post {
                        voiceStatusText = "Wasti Voice Assistant • Ready"
                        startNativeListeningCallback?.invoke()
                    }
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).post {
                        voiceStatusText = "Speech playback complete."
                    }
                }
            })

            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "wasti_clean_speech")
            tts.speak(ttsText, TextToSpeech.QUEUE_FLUSH, params, "wasti_clean_speech")
        }
    }

    // High-quality ElevenLabs Neural Voice Synthesizer with MediaPlayer Pipeline
    fun speakWithPersona(rawText: String, persona: WastiVoicePersona) {
        if (rawText.isBlank()) return

        stopAudioAndTTS()

        coroutineScope.launch(Dispatchers.Main) {
            voiceStatusText = "Synthesizing ElevenLabs HD Voice..."
            
            val result = withContext(Dispatchers.IO) {
                VoiceManager.synthesizeSpeech(
                    text = rawText,
                    preferredProviderId = "elevenlabs"
                )
            }

            if (result.isSuccess && result.audioBytes != null && result.audioBytes.isNotEmpty() && result.providerId == "elevenlabs") {
                try {
                    val tempFile = File.createTempFile("wasti_elevenlabs_", ".mp3", context.cacheDir)
                    tempFile.deleteOnExit()
                    FileOutputStream(tempFile).use { fos ->
                        fos.write(result.audioBytes)
                        fos.flush()
                    }

                    val mp = MediaPlayer()
                    mp.setDataSource(tempFile.absolutePath)
                    mp.prepareAsync()

            mp.setOnPreparedListener { player ->
                mediaPlayer = player
                isSpeaking = true
                voiceStatusText = "Wasti ElevenLabs HD Speaking..."
                player.start()
                startBackgroundAudioRecordMonitor()
            }

                    mp.setOnCompletionListener { player ->
                        isSpeaking = false
                        try {
                            player.stop()
                            player.release()
                        } catch (e: Exception) {}
                        if (mediaPlayer == player) {
                            mediaPlayer = null
                        }
                        try {
                            tempFile.delete()
                        } catch (e: Exception) {}

                        Handler(Looper.getMainLooper()).post {
                            voiceStatusText = "Wasti HD Speech complete • Reopening mic..."
                            // Task 30B: Continuous conversation loop
                            startNativeListeningCallback?.invoke()
                        }
                    }

                    mp.setOnErrorListener { player, what, extra ->
                        Log.e("WastiVoiceCallModal", "MediaPlayer error ($what, $extra). Falling back to Android TTS.")
                        isSpeaking = false
                        try {
                            player.release()
                        } catch (e: Exception) {}
                        if (mediaPlayer == player) {
                            mediaPlayer = null
                        }
                        try {
                            tempFile.delete()
                        } catch (e: Exception) {}

                        fallbackToAndroidTts(rawText, persona)
                        true
                    }

                } catch (e: Exception) {
                    Log.e("WastiVoiceCallModal", "Failed to play ElevenLabs audio: ${e.message}", e)
                    fallbackToAndroidTts(rawText, persona)
                }
            } else {
                Log.w("WastiVoiceCallModal", "ElevenLabs synthesis failed or unconfigured (${result.errorMessage}). Falling back to Android system TTS.")
                fallbackToAndroidTts(rawText, persona)
            }
        }
    }

    DisposableEffect(context) {
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setPitch(selectedPersona.pitch)
                tts.setSpeechRate(selectedPersona.speed)
                ttsEngine = tts
                val disclosureText = "This call is AI-generated by Wasti AI. " + (lastAiResponse ?: "How may I assist you today, Sir?")
                speakWithPersona(disclosureText, selectedPersona)
            }
        }
        onDispose {
            try {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
            } catch (e: Exception) {}
            try {
                tts.stop()
                tts.shutdown()
            } catch (e: Exception) {}
        }
    }

    // React to new AI response
    LaunchedEffect(lastAiResponse) {
        if (!lastAiResponse.isNullOrBlank()) {
            liveTranscript = lastAiResponse
            speakWithPersona(lastAiResponse, selectedPersona)
        }
    }

    // Mobile System Commands Dispatcher
    fun handleMobileSystemCommand(command: String): Boolean {
        val lower = command.lowercase().trim()
        try {
            val intentResult = WastiIntentParser.parseAndExecute(context, command)
            if (intentResult.hasIntent && intentResult.actionResult != null) {
                Toast.makeText(context, intentResult.actionResult.userFeedback, Toast.LENGTH_SHORT).show()
                voiceStatusText = intentResult.actionResult.userFeedback
                return true
            }

            when {
                lower.contains("read screen") || lower.contains("what is on my screen") -> {
                    val screenSummary = WastiDeviceController.readScreenContent(context)
                    liveTranscript = screenSummary
                    voiceStatusText = "Wasti Screen Reader: Screen inspected."
                    return true
                }
                lower.contains("tap") || lower.contains("click") -> {
                    val result = WastiDeviceController.simulateTap(context, lower)
                    voiceStatusText = result.userFeedback
                    return true
                }
            }
        } catch (e: Exception) {
            // Graceful fallback
        }
        return false
    }

    // Process spoken or typed input with same-language logic & voice persona selector
    fun processSpokenText(spokenText: String) {
        if (spokenText.isBlank()) return
        liveTranscript = "You: $spokenText"
        voiceStatusText = "Wasti AI processing ($spokenText)..."

        val isMobileCmd = handleMobileSystemCommand(spokenText)

        val lowerSpoken = spokenText.lowercase()
        if (lowerSpoken.contains("female") || lowerSpoken.contains("woman")) {
            selectedPersona = WastiVoicePersona.WASTI_FEMALE
        } else if (lowerSpoken.contains("girl")) {
            selectedPersona = WastiVoicePersona.WASTI_GIRL
        } else if (lowerSpoken.contains("boy")) {
            selectedPersona = WastiVoicePersona.WASTI_BOY
        } else if (lowerSpoken.contains("male") || lowerSpoken.contains("man")) {
            selectedPersona = WastiVoicePersona.WASTI_MALE
        }

        if (!isMobileCmd) {
            onSendMessage(spokenText)
        }
    }

    // Background Native SpeechRecognizer without external Google dialog popups
    DisposableEffect(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    voiceStatusText = "Background Mic Listening • Speak in Urdu or English..."
                }

                override fun onBeginningOfSpeech() {
                    voiceStatusText = "Receiving live voice stream..."
                }

                override fun onRmsChanged(rmsdB: Float) {
                    rmsAudioLevel = ((rmsdB + 2f) / 12f).coerceIn(0.15f, 1.0f)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    voiceStatusText = "Analyzing speech input..."
                    rmsAudioLevel = 0.1f
                }

                override fun onError(error: Int) {
                    isListening = false
                    rmsAudioLevel = 0.1f
                    micRecordingJob?.cancel()
                    micRecordingJob = null
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Speech not recognized. Tap mic to try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timeout. Tap mic or prompt below."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        else -> "Background voice listener ready • Speak or tap prompt below"
                    }
                    voiceStatusText = errorMsg
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    rmsAudioLevel = 0.1f
                    micRecordingJob?.cancel()
                    micRecordingJob = null
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        processSpokenText(spokenText)
                    } else {
                        voiceStatusText = "Voice engine ready • Tap mic or select quick prompt below"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = partialMatches?.firstOrNull()
                    if (!partialText.isNullOrBlank()) {
                        liveTranscript = "Listening: $partialText..."
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer = recognizer
        }

        onDispose {
            speechRecognizer?.destroy()
            micRecordingJob?.cancel()
        }
    }

    SideEffect {
        startNativeListeningCallback = { startNativeListening() }
    }

    LaunchedEffect(startListeningOnPermissionGranted) {
        if (startListeningOnPermissionGranted) {
            startListeningOnPermissionGranted = false
            startNativeListening()
        }
    }

    // Dynamic Pulsing Voice Orb & Equalizer Bar Animations
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val orbPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isSpeaking) 1.25f else if (isListening) (1.1f + rmsAudioLevel * 0.25f) else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulseScale"
    )

    Dialog(
        onDismissRequest = {
            stopTTS()
            speechRecognizer?.stopListening()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A), // Deep Obsidian
                            Color(0xFF020617), // Pure Dark Obsidian
                            Color(0xFF082F49)  // Deep Cyan Accent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Wasti Native Voice Call",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF00E6FF).copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E6FF).copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "NATIVE • CYAN HD",
                                    color = Color(0xFF00E6FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Background Recognizer • Urdu / Punjabi / English • Regex TTS Clean",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = {
                        stopTTS()
                        speechRecognizer?.stopListening()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Voice Modal", tint = Color.White)
                    }
                }

                // Center Obsidian/Cyan Visualizer Orb & Soundwave Equalizer
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(180.dp)
                            .scale(orbPulseScale)
                            .clip(CircleShape)
                            .border(
                                width = 3.dp,
                                brush = Brush.sweepGradient(
                                    colors = listOf(Color(0xFF00E6FF), Color(0xFF0284C7), Color(0xFF00E6FF))
                                ),
                                shape = CircleShape
                            )
                            .background(
                                Brush.radialGradient(
                                    colors = if (isSpeaking) {
                                        listOf(Color(0xFF38BDF8), Color(0xFF0284C7), Color(0xFF0F172A))
                                    } else if (isListening) {
                                        listOf(Color(0xFF00E6FF), Color(0xFF0D9488), Color(0xFF020617))
                                    } else {
                                        listOf(Color(0xFF0EA5E9), Color(0xFF0369A1), Color(0xFF0F172A))
                                    }
                                )
                            )
                            .clickable { startNativeListening() }
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeUp else if (isListening) Icons.Default.Mic else Icons.Default.GraphicEq,
                            contentDescription = "Voice Orb",
                            tint = Color.White,
                            modifier = Modifier.size(68.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // In-App Cyan Audio Equalizer Waves (Background UI Override)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(36.dp)
                    ) {
                        val barHeights = listOf(
                            if (isListening || isSpeaking) (12f + rmsAudioLevel * 20f).dp else 8.dp,
                            if (isListening || isSpeaking) (24f + rmsAudioLevel * 10f).dp else 14.dp,
                            if (isListening || isSpeaking) (32f + rmsAudioLevel * 16f).dp else 22.dp,
                            if (isListening || isSpeaking) (20f + rmsAudioLevel * 14f).dp else 12.dp,
                            if (isListening || isSpeaking) (28f + rmsAudioLevel * 18f).dp else 18.dp,
                            if (isListening || isSpeaking) (16f + rmsAudioLevel * 8f).dp else 10.dp
                        )

                        barHeights.forEach { height ->
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(height)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (isListening) Color(0xFF00E6FF)
                                        else if (isSpeaking) Color(0xFF38BDF8)
                                        else Color(0xFF334155)
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = voiceStatusText,
                        color = Color(0xFF00E6FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Hard Interrupt Button (TTS Stop)
                    if (isSpeaking) {
                        Button(
                            onClick = { stopTTS() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Interrupt AI Speech", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "INTERRUPT AI (STOP SPEAKING)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // Transcript Display Card
                    Card(
                        modifier = Modifier.fillMaxWidth(0.95f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.85f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = liveTranscript,
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Voice Selector & Input Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Quick Voice Test Chips
                    Text(
                        text = "Quick Voice Prompts (Urdu / Roman Urdu / English):",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    val quickPrompts = listOf(
                        "سلام واسطی",
                        "Tum kaise ho?",
                        "Mera naam Nabeel hai",
                        "Aaj mausam kaisa hai?",
                        "Open WhatsApp",
                        "How are you today?"
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    ) {
                        items(quickPrompts) { promptText ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF1E293B),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable {
                                    stopTTS()
                                    processSpokenText(promptText)
                                }
                            ) {
                                Text(
                                    text = "🎙️ $promptText",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    val activePersonas = if (enableExtraVoiceModels) {
                        WastiVoicePersona.values().toList()
                    } else {
                        listOf(WastiVoicePersona.GROQ_VOICE, WastiVoicePersona.WASTI_MALE, WastiVoicePersona.WASTI_FEMALE)
                    }

                    Text(
                        text = "Voice Persona:",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        activePersonas.forEach { persona ->
                            val isSelected = selectedPersona == persona
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E6FF)) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedPersona = persona
                                        speakWithPersona("Voice switched to ${persona.title}.", persona)
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = persona.icon, fontSize = 18.sp)
                                    Text(
                                        text = persona.title.split(" ").first(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Command Input Bar & Mic Trigger
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = simulatedInputText,
                            onValueChange = { simulatedInputText = it },
                            placeholder = { Text("Type prompt or say 'Hey Wasti'...", color = Color(0xFF64748B), fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF0F172A),
                                focusedBorderColor = Color(0xFF00E6FF),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FloatingActionButton(
                            onClick = {
                                stopTTS()
                                if (simulatedInputText.isNotBlank()) {
                                    val text = simulatedInputText
                                    simulatedInputText = ""
                                    liveTranscript = "You: $text"
                                    val isMobileCmd = handleMobileSystemCommand(text)
                                    if (!isMobileCmd) {
                                        onSendMessage(text)
                                    }
                                } else {
                                    startNativeListening()
                                }
                            },
                            containerColor = Color(0xFF00E6FF),
                            contentColor = Color(0xFF020617),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = if (simulatedInputText.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send or Speak Native"
                            )
                        }
                    }
                }
            }
        }
    }
}

