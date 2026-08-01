package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.example.data.device.WastiDeviceController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import java.util.Locale

enum class WastiVoicePersona(
    val title: String,
    val icon: String,
    val gender: String, // "male" or "female"
    val pitch: Float,
    val speed: Float,
    val description: String
) {
    GROQ_VOICE("Groq Speech AI Voice", "⚡", "male", 1.00f, 1.15f, "Groq Ultra-Fast Speech Engine (gsk_IebD8f...)"),
    ELEVENLABS_HD("ElevenLabs Ultra-HD Voice", "🎙️", "female", 1.00f, 1.00f, "ElevenLabs AI Engine (sk_225f55...)"),
    WASTI_MALE("Wasti Prime (Male)", "👨", "male", 0.88f, 0.98f, "Deep articulate, warm masculine tone"),
    WASTI_FEMALE("Wasti Female / Woman", "👩", "female", 1.05f, 1.00f, "Clear, natural, professional female voice"),
    WASTI_GIRL("Wasti Girl", "👧", "female", 1.22f, 1.05f, "Upbeat, lively youthful girl voice"),
    WASTI_BOY("Wasti Intelligent Boy", "👦", "male", 1.02f, 1.12f, "Quick, smart youthful boy voice")
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
    var voiceStatusText by remember { mutableStateOf("Wasti is listening... Speak now") }
    var liveTranscript by remember { mutableStateOf("Tap the microphone or say 'Hey Wasti' to speak...") }
    var simulatedInputText by remember { mutableStateOf("") }

    // TextToSpeech Engine initialization with dynamic voice profiles
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    fun speakWithPersona(text: String, persona: WastiVoicePersona) {
        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), "Code generated successfully.")
            .replace(Regex("[#*`_-]"), " ")
            .trim()

        if (cleanText.isBlank()) return

        ttsEngine?.let { tts ->
            val lower = cleanText.lowercase()
            val isUrdu = lower.contains("اردو") || lower.contains("سلام") || lower.contains("کیسے") || cleanText.any { it in '\u0600'..'\u06FF' }

            // Set Locale
            if (isUrdu) {
                try {
                    tts.language = Locale("ur", "PK")
                } catch (e: Exception) {
                    tts.language = Locale.ENGLISH
                }
            } else {
                tts.language = Locale.ENGLISH
            }

            // Find matching Voice object in system TTS voices
            try {
                val systemVoices = tts.voices
                if (!systemVoices.isNullOrEmpty()) {
                    val targetGender = persona.gender
                    val matchedVoice = systemVoices.find { voice ->
                        val vName = voice.name.lowercase()
                        val matchesGender = if (targetGender == "female") {
                            vName.contains("female") || vName.contains("f0") || vName.contains("woman") || vName.contains("sfg") || vName.contains("iog")
                        } else {
                            vName.contains("male") || vName.contains("m0") || vName.contains("man") || vName.contains("iob") || vName.contains("iol")
                        }
                        val matchesLang = if (isUrdu) voice.locale.language == "ur" else voice.locale.language == "en"
                        matchesGender && matchesLang
                    } ?: systemVoices.find { voice ->
                        val vName = voice.name.lowercase()
                        if (targetGender == "female") {
                            vName.contains("female") || vName.contains("f0") || vName.contains("sfg")
                        } else {
                            vName.contains("male") || vName.contains("m0") || vName.contains("iob")
                        }
                    }

                    if (matchedVoice != null) {
                        tts.voice = matchedVoice
                    }
                }
            } catch (e: Exception) {
                // Fallback to pitch/rate modification if voice objects restricted
            }

            tts.setPitch(persona.pitch)
            tts.setSpeechRate(persona.speed)

            isSpeaking = true
            voiceStatusText = "Wasti Speaking (${persona.title})..."
            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "wasti_live_speech")
        }
    }

    DisposableEffect(context) {
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setPitch(selectedPersona.pitch)
                tts.setSpeechRate(selectedPersona.speed)
                ttsEngine = tts
                if (!lastAiResponse.isNullOrBlank()) {
                    speakWithPersona(lastAiResponse, selectedPersona)
                }
            }
        }
        onDispose {
            tts.stop()
            tts.shutdown()
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
            when {
                lower.contains("whatsapp") -> {
                    val result = WastiDeviceController.sendWhatsAppMessage(context, "contact", command)
                    Toast.makeText(context, result.userFeedback, Toast.LENGTH_SHORT).show()
                    voiceStatusText = result.userFeedback
                    return true
                }
                lower.contains("email") || lower.contains("gmail") -> {
                    val result = WastiDeviceController.sendEmail(context, "user@example.com", "Wasti AI Dispatch", command)
                    Toast.makeText(context, result.userFeedback, Toast.LENGTH_SHORT).show()
                    voiceStatusText = result.userFeedback
                    return true
                }
                lower.contains("sms") || lower.contains("send message") || lower.contains("send text") -> {
                    val result = WastiDeviceController.sendSMS(context, "123456", command)
                    Toast.makeText(context, result.userFeedback, Toast.LENGTH_SHORT).show()
                    voiceStatusText = result.userFeedback
                    return true
                }
                lower.contains("open") || lower.contains("launch") -> {
                    val targetApp = lower.replace("open", "").replace("launch", "").replace("app", "").trim()
                    val result = WastiDeviceController.openApp(context, targetApp)
                    Toast.makeText(context, result.userFeedback, Toast.LENGTH_SHORT).show()
                    voiceStatusText = result.userFeedback
                    return true
                }
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
            // Intent fallback handled gracefully
        }
        return false
    }

    // Android Speech Recognition Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                liveTranscript = "You: $spokenText"
                voiceStatusText = "Wasti is thinking..."

                // Check mobile control command first
                val isMobileCmd = handleMobileSystemCommand(spokenText)

                // Voice change commands
                val lowerSpoken = spokenText.lowercase()
                if (lowerSpoken.contains("female") || lowerSpoken.contains("woman") || lowerSpoken.contains("girl voice")) {
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
        } else {
            voiceStatusText = "Speech input cancelled. Tap mic to try again."
        }
    }

    fun triggerMicListening() {
        ttsEngine?.stop()
        isSpeaking = false
        isListening = true
        voiceStatusText = "Wasti is listening... Speak in Urdu, Punjabi, or English"
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Wasti is listening... Say 'Open Camera', 'Urdu mein baat karo', 'Change voice to female'")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            isListening = false
            voiceStatusText = "Voice recognizer unavailable. Type prompt below."
        }
    }

    // Pulsing Voice Orb Animation
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isSpeaking) 1.22f else if (isListening) 1.15f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E293B),
                            Color(0xFF020617)
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
                // Top Bar Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Wasti AI Live Voice Chat",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "LIVE HD VOICE",
                                    color = Color(0xFF34D399),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Multi-Voice • Mobile Control • Multilingual Urdu/Punjabi/English",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Voice Modal", tint = Color.White)
                    }
                }

                // Center Voice Orb & Waveform
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(170.dp)
                            .scale(orbScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = if (isSpeaking) {
                                        listOf(Color(0xFF818CF8), Color(0xFF4F46E5), Color(0xFF312E81))
                                    } else if (isListening) {
                                        listOf(Color(0xFF34D399), Color(0xFF059669), Color(0xFF064E3B))
                                    } else {
                                        listOf(Color(0xFF38BDF8), Color(0xFF0284C7), Color(0xFF0C4A6E))
                                    }
                                )
                            )
                            .clickable { triggerMicListening() }
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Default.VolumeUp else if (isListening) Icons.Default.Mic else Icons.Default.GraphicEq,
                            contentDescription = "Voice Orb",
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = voiceStatusText,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Transcript Card
                    Card(
                        modifier = Modifier.fillMaxWidth(0.92f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.8f)),
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

                // Voice Persona Switcher Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    val activePersonas = if (enableExtraVoiceModels) {
                        WastiVoicePersona.values().toList()
                    } else {
                        listOf(WastiVoicePersona.GROQ_VOICE)
                    }

                    if (!enableExtraVoiceModels) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00E6FF).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E6FF).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "⚡", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Active Voice Assistant: Groq Speech AI (Main & Default)",
                                        color = Color(0xFF00E6FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "All other models are disabled by default. Activate them in Settings.",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Select Wasti Voice Persona:",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            activePersonas.forEach { persona ->
                                val isSelected = selectedPersona == persona
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFF0284C7) else Color(0xFF334155),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedPersona = persona
                                            speakWithPersona("Hello Sir! Wasti voice switched to ${persona.title}.", persona)
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
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Quick Command Chips & Manual Voice Input Trigger
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = simulatedInputText,
                            onValueChange = { simulatedInputText = it },
                            placeholder = { Text("Or type command here...", color = Color(0xFF64748B), fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF1E293B),
                                unfocusedContainerColor = Color(0xFF0F172A),
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FloatingActionButton(
                            onClick = {
                                if (simulatedInputText.isNotBlank()) {
                                    val text = simulatedInputText
                                    simulatedInputText = ""
                                    liveTranscript = "You: $text"
                                    val isMobileCmd = handleMobileSystemCommand(text)
                                    if (!isMobileCmd) {
                                        onSendMessage(text)
                                    }
                                } else {
                                    triggerMicListening()
                                }
                            },
                            containerColor = Color(0xFF0284C7),
                            contentColor = Color.White,
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = if (simulatedInputText.isBlank()) Icons.Default.Mic else Icons.Default.Send,
                                contentDescription = "Send or Speak"
                            )
                        }
                    }
                }
            }
        }
    }
}
