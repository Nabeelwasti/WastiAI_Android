package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AgentEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageEntity
import com.example.ui.components.CodeBlockView
import com.example.ui.components.WastiVoiceCallModal
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspaceScreen(
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    messages: List<MessageEntity>,
    agents: List<AgentEntity>,
    activeAgentId: String,
    isGenerating: Boolean,
    onSelectConversation: (String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onCreateNewConversation: (String) -> Unit
) {
    val context = LocalContext.current
    var promptInput by remember { mutableStateOf("") }
    var isThinkingVisible by remember { mutableStateOf(true) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }

    // TextToSpeech setup for Wasti multilingual voice response
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isVoiceActive by remember { mutableStateOf(true) }
    var showVoiceModal by remember { mutableStateOf(false) }

    if (showVoiceModal) {
        WastiVoiceCallModal(
            onDismiss = { showVoiceModal = false },
            onSendMessage = { prompt ->
                onSendMessage(prompt)
            },
            lastAiResponse = messages.lastOrNull { it.role == "assistant" }?.content
        )
    }

    DisposableEffect(context) {
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setPitch(0.85f)
                tts.setSpeechRate(1.0f)
            }
        }
        ttsEngine = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    // Android Multilingual Speech Recognizer Launcher (Supports Urdu, Punjabi, English, etc.)
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                promptInput = spokenText
            }
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
            val lastMsg = messages.last()
            if (lastMsg.role == "assistant" && isVoiceActive) {
                val cleanText = lastMsg.content.replace(Regex("```[\\s\\S]*?```"), "Code block generated.")
                ttsEngine?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "jarvis_auto_tts")
            }
        }
    }

    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("New AI Session") },
            text = {
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text("Session Title") },
                    placeholder = { Text("e.g. Architecture Strategy") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newSessionTitle.isNotBlank()) {
                            onCreateNewConversation(newSessionTitle)
                            newSessionTitle = ""
                            showNewSessionDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("chat_workspace_screen")
    ) {
        // Conversation Top Selector Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(conversations) { conv ->
                            val isSelected = conv.id == activeConversationId
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectConversation(conv.id) },
                                label = { Text(conv.title, fontSize = 12.sp) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isVoiceActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clickable { showVoiceModal = true }
                                .padding(end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isVoiceActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    contentDescription = "Wasti Voice Mode",
                                    tint = if (isVoiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isVoiceActive) "🎙️ Wasti Voice Call" else "Voice Off",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isVoiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { showNewSessionDialog = true },
                            modifier = Modifier.testTag("add_chat_session_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Session", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Active Agent Selector Pills
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(agents) { agent ->
                        val isAgentSelected = agent.id == activeAgentId
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectAgent(agent.id) },
                            color = if (isAgentSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (isAgentSelected) MaterialTheme.colorScheme.primary else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = agent.name,
                                    fontSize = 11.sp,
                                    fontWeight = if (isAgentSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isAgentSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        // Message Feed
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { msg ->
                MessageItem(
                    message = msg,
                    isThinkingVisible = isThinkingVisible,
                    ttsEngine = ttsEngine
                )
            }

            if (isGenerating) {
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Wasti OS $activeAgentId synthesizing response...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Quick Suggestion Chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val suggestions = listOf(
                "🎙️ Live Hands-Free Voice Call",
                "🌐 Scan Website: https://en.wikipedia.org/wiki/Artificial_intelligence",
                "How are you doing today Wasti?",
                "ایک نیا اینڈرائڈ ایپ پلان بنا کر دو",
                "کی حال اے واسطی؟"
            )
            items(suggestions) { suggestion ->
                SuggestionChip(
                    onClick = {
                        if (suggestion.contains("Voice Call")) {
                            showVoiceModal = true
                        } else {
                            promptInput = suggestion
                        }
                    },
                    label = { Text(suggestion, fontSize = 11.sp, fontWeight = if (suggestion.contains("Voice Call")) FontWeight.Bold else FontWeight.Normal) },
                    colors = if (suggestion.contains("Voice Call")) SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else SuggestionChipDefaults.suggestionChipColors()
                )
            }
        }

        // Input Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showVoiceModal = true },
                    modifier = Modifier.testTag("voice_input_button")
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Live Voice Chat Modal", tint = MaterialTheme.colorScheme.primary)
                }

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Talk to Wasti... (Tap Mic or type in Urdu/Punjabi/English)", fontSize = 12.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_prompt_input"),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = {
                        if (promptInput.isNotBlank() && !isGenerating) {
                            val textToSend = promptInput
                            promptInput = ""
                            onSendMessage(textToSend)
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("send_message_button"),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send Message", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: MessageEntity,
    isThinkingVisible: Boolean,
    ttsEngine: TextToSpeech?
) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isUser) "You" else message.agentId,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Model: ${message.modelUsed}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            if (!isUser) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val cleanText = message.content.replace(Regex("```[\\s\\S]*?```"), "Code block omitted.")
                        ttsEngine?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "message_tts")
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Read aloud",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Check if message content has code blocks
                val content = message.content
                if (content.contains("```")) {
                    val parts = content.split("```")
                    parts.forEachIndexed { index, part ->
                        if (index % 2 == 1) {
                            // Code block
                            val firstLineEnd = part.indexOf('\n')
                            val lang = if (firstLineEnd != -1) part.substring(0, firstLineEnd).trim() else "code"
                            val codeCode = if (firstLineEnd != -1) part.substring(firstLineEnd + 1) else part
                            CodeBlockView(code = codeCode, language = lang)
                        } else {
                            if (part.isNotBlank()) {
                                Text(
                                    text = part,
                                    fontSize = 14.sp,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = content,
                        fontSize = 14.sp,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
