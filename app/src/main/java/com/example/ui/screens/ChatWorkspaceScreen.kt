package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AgentEntity
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageEntity
import com.example.data.voice.VoiceManager
import com.example.data.voice.provider.STTState
import com.example.data.device.WastiIntentParser
import com.example.ui.components.CodeBlockView
import com.example.ui.components.UniversalTaskTimelineStepper
import com.example.ui.components.WastiVoiceCallModal
import com.example.util.WastiSpeechSanitizer
import com.example.security.BiometricSecurityManager
import com.example.security.findFragmentActivity
import android.widget.Toast
import kotlinx.coroutines.launch
import java.util.Locale

data class ActiveAttachment(
    val uri: String? = null,
    val name: String,
    val mimeType: String,
    val base64Data: String?,
    val bitmap: android.graphics.Bitmap? = null,
    val isImage: Boolean = true
)

private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
    val outputStream = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
    return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
}

private fun uriToBase64(context: android.content.Context, uri: android.net.Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes()
        inputStream?.close()
        if (bytes != null) android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) else null
    } catch (_: Exception) {
        null
    }
}

private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) result = cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "attachment"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWorkspaceScreen(
    conversations: List<ConversationEntity>,
    activeConversationId: String?,
    messages: List<MessageEntity>,
    agents: List<AgentEntity>,
    activeAgentId: String,
    selectedModel: String = "wasti-super-ensemble",
    isGenerating: Boolean,
    lastOperationError: String? = null,
    onErrorShown: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (String) -> Unit = {},
    onClearChatHistory: () -> Unit = {},
    onSendMessage: (
        prompt: String,
        imageInlineData: String?,
        mimeType: String,
        attachedMediaUris: String,
        mediaList: List<com.example.data.ai.model.AttachedMediaData>
    ) -> Unit = { _, _, _, _, _ -> },
    onEditAndResendMessage: (messageId: String, newContent: String) -> Unit = { _, _ -> },
    onCreateNewConversation: (String) -> Unit,
    onCancelGeneration: () -> Unit = {},
    triggerVoiceCallSignal: Int = 0
) {
    val context = LocalContext.current
    LaunchedEffect(lastOperationError) {
    lastOperationError?.let { err ->
        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        onErrorShown()
    }
    }
    val focusManager = LocalFocusManager.current
    var promptInput by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }

    // Auto-restore draft prompt on initial composition
    LaunchedEffect(Unit) {
        val savedDraft = com.example.data.persistence.DraftPersistenceManager.getDraftPrompt(context)
        if (savedDraft.isNotBlank() && promptInput.isBlank()) {
            promptInput = savedDraft
        }
    }

    // Real-time auto-save unsubmitted prompt drafts
    LaunchedEffect(promptInput) {
        if (promptInput.isNotBlank()) {
            com.example.data.persistence.DraftPersistenceManager.saveDraftPrompt(context, promptInput)
        } else {
            com.example.data.persistence.DraftPersistenceManager.clearDraftPrompt(context)
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    var showWebScanDialog by remember { mutableStateOf(false) }
    var webUrlInput by remember { mutableStateOf("") }

    var activeAttachments by remember { mutableStateOf<List<ActiveAttachment>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val pendingDraft by com.example.data.core.WastiCore.pendingEmailDraft.collectAsState()
    val pendingLinkedInDraft by com.example.data.core.WastiCore.pendingLinkedInDraft.collectAsState()
    val toolProgressState by com.example.data.core.WastiCore.toolProgressState.collectAsState()

    // Real Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val base64 = bitmapToBase64(bitmap)
            val name = "Camera_Capture_${System.currentTimeMillis() % 10000}.jpg"
            activeAttachments = (activeAttachments + ActiveAttachment(
                uri = name,
                name = name,
                mimeType = "image/jpeg",
                base64Data = base64,
                bitmap = bitmap,
                isImage = true
            )).take(10)
        }
    }

    // Real Multi-Gallery / Photos Picker Launcher (PickMultipleVisualMedia max 10)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newAttachments = uris.mapNotNull { uri ->
                val name = getFileName(context, uri)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val base64 = uriToBase64(context, uri)
                ActiveAttachment(
                    uri = uri.toString(),
                    name = name,
                    mimeType = mime,
                    base64Data = base64,
                    isImage = true
                )
            }
            activeAttachments = (activeAttachments + newAttachments).take(10)
        }
    }

    // Real Device Storage / File Manager Multi-Launcher
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newAttachments = uris.mapNotNull { uri ->
                val name = getFileName(context, uri)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val base64 = uriToBase64(context, uri)
                val isImg = mime.startsWith("image/")

                if (!isImg) {
                    val textContent = try {
                        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                    } catch (_: Exception) { null }

                    if (!textContent.isNullOrBlank()) {
                        promptInput += "\n\n[Attached File: $name]\n```\n${textContent.take(4000)}\n```\n"
                    }
                }

                ActiveAttachment(
                    uri = uri.toString(),
                    name = name,
                    mimeType = mime,
                    base64Data = base64,
                    isImage = isImg
                )
            }
            activeAttachments = (activeAttachments + newAttachments).take(10)
        }
    }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    // TextToSpeech setup for Wasti multilingual voice response
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isVoiceActive by remember { mutableStateOf(true) }
    var isTtsSpeaking by remember { mutableStateOf(false) }
    var showVoiceModal by remember { mutableStateOf(false) }

    LaunchedEffect(triggerVoiceCallSignal) {
        if (triggerVoiceCallSignal > 0) {
            showVoiceModal = true
        }
    }

    // Speech-To-Text Provider Integration
    val sttProvider = remember { VoiceManager.sttProvider }
    val sttState by (sttProvider?.currentState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(STTState.IDLE) }).collectAsState()

    if (showVoiceModal) {
        WastiVoiceCallModal(
            onDismiss = { showVoiceModal = false },
            onSendMessage = { prompt ->
                onSendMessage(prompt, null, "image/jpeg", "", emptyList())
            },
            lastAiResponse = messages.lastOrNull { it.role == "assistant" }?.content
        )
    }

    DisposableEffect(context) {
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.setPitch(0.95f)
                tts.setSpeechRate(1.0f)
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isTtsSpeaking = true }
                    override fun onDone(utteranceId: String?) { isTtsSpeaking = false }
                    override fun onError(utteranceId: String?) { isTtsSpeaking = false }
                })
            }
        }
        ttsEngine = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    // Background Speech Recognizer integration (No external popup UI)
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            sttProvider?.startListening(
                context = context,
                languageTag = "en-US",
                onBeginningOfSpeech = {
                    // Voice Barge-in: interrupt AI TTS playback immediately upon user speech
                    ttsEngine?.stop()
                    VoiceManager.stopSpeaking()
                },
                onResult = { res ->
                    if (res.transcript.isNotBlank()) {
                        promptInput = res.transcript
                    }
                }
            )
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required for voice recognition.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val listState = rememberLazyListState()

    val filteredMessages = remember(messages, searchQuery) {
        if (searchQuery.isBlank()) {
            messages
        } else {
            messages.filter { it.content.contains(searchQuery, ignoreCase = true) }
        }
    }

    LaunchedEffect(filteredMessages.size) {
        if (filteredMessages.isNotEmpty() && searchQuery.isBlank()) {
            listState.animateScrollToItem(filteredMessages.size - 1)
            val lastMsg = filteredMessages.last()
            
            // Execute intent if message contains app launch / system action
            WastiIntentParser.parseAndExecute(context, lastMsg.content)

            if (lastMsg.role == "assistant" && isVoiceActive) {
                speakDualPipelineTts(ttsEngine, lastMsg.content)
            }
        }
    }

    // Dialog 1: New Chat Session
    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("New AI Workspace Session") },
            text = {
                OutlinedTextField(
                    value = newSessionTitle,
                    onValueChange = { newSessionTitle = it },
                    label = { Text("Session Title") },
                    placeholder = { Text("e.g. Architecture Strategy") },
                    modifier = Modifier.fillMaxWidth()
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

    // Dialog 2: Clear History Confirmation
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear Chat History") },
            text = { Text("Are you sure you want to clear all message logs in this active conversation? This operation cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearChatHistory()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All Messages")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 3: Scan Web URL Modal
    if (showWebScanDialog) {
        AlertDialog(
            onDismissRequest = { showWebScanDialog = false },
            title = { Text("🌐 Scan & Index Web URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a public URL to crawl, summarize, and index into Wasti OS Long-Term Knowledge Base:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = webUrlInput,
                        onValueChange = { webUrlInput = it },
                        label = { Text("Website URL") },
                        placeholder = { Text("https://example.com/article") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (webUrlInput.isNotBlank()) {
                            promptInput = "🌐 Scan Website: $webUrlInput"
                            webUrlInput = ""
                            showWebScanDialog = false
                        }
                    }
                ) {
                    Text("Index & Learn")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebScanDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
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
                        // Toggle Search Bar
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.SearchOff else Icons.Default.Search,
                                contentDescription = "Search Messages",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Clear Chat History Button
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Chat History",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        // Voice Call Trigger
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable { showVoiceModal = true }
                                .padding(end = 4.dp)
                                .testTag("conversation_bar_voice_call_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Wasti Live Voice Call",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isVoiceActive) "🎙️ Live Voice (Active)" else "🎙️ Live Voice",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
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

                // Active Engine Banner (Unified Wasti AI)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34D399))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "⚡ Wasti AI (Unified Orchestration Engine)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Unified Task Execution Timeline Stepper
                UniversalTaskTimelineStepper()

                // Expandable Search Bar
                if (isSearchActive) {
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search messages in this session...", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
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
            items(filteredMessages) { msg ->
                MessageItem(
                    message = msg,
                    ttsEngine = ttsEngine,
                    onCopyText = { text ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Wasti Message", text)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "Copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onEditMessage = { msgId, text ->
                        editingMessageId = msgId
                        promptInput = text
                    },
                    onEditAndResendMessage = { messageId, newPrompt ->
                        editingMessageId = null
                        onEditAndResendMessage(messageId, newPrompt)
                    }
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
                            text = "Wasti AI synthesizing response...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Hard TTS Interrupt Banner
        if (isTtsSpeaking) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speaking", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Wasti Speaking...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            ttsEngine?.stop()
                            isTtsSpeaking = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("STOP / INTERRUPT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

        // Task 43A: Active Editing Mode Banner
        editingMessageId?.let { editId ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Editing message...",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    TextButton(
                        onClick = {
                            editingMessageId = null
                            promptInput = ""
                        }
                    ) {
                        Text("Cancel", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Task 43B: Active Multi-Attachment Preview Chips
        if (activeAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeAttachments) { att ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (att.isImage) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = att.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { activeAttachments = activeAttachments.filter { it != att } },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove Attachment",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Pending Email Outreach Approval Card
        pendingDraft?.let { draft ->
            EmailDraftApprovalCard(
                draft = draft,
                onApprove = {
                    scope.launch {
                        val result = com.example.data.gmail.GmailOAuthService.sendEmailDetailed(
                            to = draft.to,
                            subject = draft.subject,
                            body = draft.body,
                            context = context
                        )
                        com.example.data.core.WastiCore.clearPendingEmailDraft()
                        when (result) {
                            is com.example.data.gmail.SendEmailResult.Success -> {
                                onSendMessage(
                                    "✅ [EMAIL APPROVED & SENT]\n\nRecipient: ${draft.to}\nSubject: ${draft.subject}\n\nEmail dispatched via Gmail OAuth 2.0 API.",
                                    null,
                                    "image/jpeg",
                                    "",
                                    emptyList()
                                )
                            }
                            is com.example.data.gmail.SendEmailResult.Error -> {
                                android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                                onSendMessage(
                                    "❌ [EMAIL FAILED]\n\nRecipient: ${draft.to}\nSubject: ${draft.subject}\n\nError: ${result.message}",
                                    null,
                                    "image/jpeg",
                                    "",
                                    emptyList()
                                )
                            }
                        }
                    }
                },
                onReject = {
                    promptInput = "Draft email to ${draft.to} with subject '${draft.subject}':\n${draft.body}"
                    com.example.data.core.WastiCore.clearPendingEmailDraft()
                }
            )
        }

        // Pending LinkedIn Post Approval Card
        pendingLinkedInDraft?.let { draft ->
            LinkedInDraftApprovalCard(
                draft = draft,
                onApprove = {
                    scope.launch {
                        val result = com.example.data.linkedin.LinkedInOAuthService.postToLinkedIn(
                            content = draft.content,
                            context = context
                        )
                        com.example.data.core.WastiCore.clearPendingLinkedInDraft()
                        when (result) {
                            is com.example.data.linkedin.LinkedInPostResult.Success -> {
                                onSendMessage(
                                    "✅ [LINKEDIN POST APPROVED & PUBLISHED]\n\nContent:\n${draft.content}\n\nPost ID: ${result.postId}\nPublished via LinkedIn OAuth 2.0 API.",
                                    null,
                                    "image/jpeg",
                                    "",
                                    emptyList()
                                )
                            }
                            is com.example.data.linkedin.LinkedInPostResult.Error -> {
                                android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_LONG).show()
                                onSendMessage(
                                    "❌ [LINKEDIN POST FAILED]\n\nContent:\n${draft.content}\n\nError: ${result.message}",
                                    null,
                                    "image/jpeg",
                                    "",
                                    emptyList()
                                )
                            }
                        }
                    }
                },
                onReject = {
                    promptInput = "Draft LinkedIn post:\n${draft.content}"
                    com.example.data.core.WastiCore.clearPendingLinkedInDraft()
                }
            )
        }

        // Task 41B: Real-Time Tool Execution Progress Banner
        AnimatedVisibility(
            visible = toolProgressState.isActive || toolProgressState.stage == com.example.data.core.ProgressStage.CONNECTING || toolProgressState.stage == com.example.data.core.ProgressStage.SCRAPING || toolProgressState.stage == com.example.data.core.ProgressStage.ANALYZING || toolProgressState.stage == com.example.data.core.ProgressStage.DISPATCHING || toolProgressState.stage == com.example.data.core.ProgressStage.VERIFYING,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "STAGE: ${toolProgressState.stage.name}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Text(
                            text = "Wasti Execution Engine",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = toolProgressState.statusMessage,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // Input Bar with Material OutlinedBox Alignment & Multimodal Attachment Options
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Multimodal Attachments '+' Button
                Box {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        modifier = Modifier.testTag("attachment_menu_button")
                    ) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "Attach File or Media", tint = MaterialTheme.colorScheme.primary)
                    }

                    DropdownMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("📸 Capture Device Camera Photo") },
                            onClick = {
                                showAttachmentMenu = false
                                try {
                                    cameraLauncher.launch(null)
                                } catch (_: Exception) {
                                    permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA))
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("🖼️ Pick Gallery / Google Photos") },
                            onClick = {
                                showAttachmentMenu = false
                                try {
                                    galleryLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        )
                                    )
                                } catch (_: Exception) {
                                    permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                                }
                            },
                            leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("📁 Open Device Storage / File Manager") },
                            onClick = {
                                showAttachmentMenu = false
                                try {
                                    documentLauncher.launch("*/*")
                                } catch (_: Exception) {}
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("🌐 Scan & Train on Web URL") },
                            onClick = {
                                showAttachmentMenu = false
                                showWebScanDialog = true
                            },
                            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("🎙️ Live Voice Call Modal") },
                            onClick = {
                                showAttachmentMenu = false
                                showVoiceModal = true
                            },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) }
                        )
                    }
                }

                // Dedicated Live Voice Call Modal Button
                IconButton(
                    onClick = { showVoiceModal = true },
                    modifier = Modifier.testTag("live_voice_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Open Live Voice Call",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = {
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (hasPermission) {
                            if (sttState == STTState.LISTENING) {
                                sttProvider?.stopListening()
                            } else {
                                sttProvider?.startListening(
                                    context = context,
                                    languageTag = "en-US",
                                    onBeginningOfSpeech = {
                                        // Voice Barge-in: interrupt AI TTS playback immediately upon user speech
                                        ttsEngine?.stop()
                                        VoiceManager.stopSpeaking()
                                    },
                                    onResult = { res ->
                                        if (res.transcript.isNotBlank()) {
                                            promptInput = res.transcript
                                        }
                                    }
                                )
                            }
                        } else {
                            recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.testTag("voice_input_button")
                ) {
                    val isListening = sttState == STTState.LISTENING
                    Icon(
                        imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Background Speech Recognition",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Command Wasti AI...", fontSize = 12.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_prompt_input"),
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp),
                    trailingIcon = {
                        if (promptInput.isNotEmpty()) {
                            IconButton(onClick = { promptInput = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear Input", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = {
                        if (isGenerating) {
                            onCancelGeneration()
                        } else if (promptInput.isNotBlank() || activeAttachments.isNotEmpty()) {
                            val textToSend = promptInput
                            val attachmentsToSend = activeAttachments
                            val targetEditId = editingMessageId

                            promptInput = ""
                            activeAttachments = emptyList()
                            editingMessageId = null
                            focusManager.clearFocus()

                            if (targetEditId != null) {
                                // Task 43A: Submitting edit invokes editMessageAndRegenerate without duplicate appending!
                                onEditAndResendMessage(targetEditId, textToSend)
                            } else {
                                val mediaUrisStr = attachmentsToSend.joinToString(",") { it.uri ?: it.name }
                                val mediaDataList = attachmentsToSend.mapNotNull { att ->
                                    if (att.base64Data != null) {
                                        com.example.data.ai.model.AttachedMediaData(
                                            base64Data = att.base64Data,
                                            mimeType = att.mimeType,
                                            uri = att.uri ?: att.name
                                        )
                                    } else null
                                }
                                val firstBase64 = mediaDataList.firstOrNull()?.base64Data
                                val firstMime = mediaDataList.firstOrNull()?.mimeType ?: "image/jpeg"

                                WastiIntentParser.parseAndExecute(context, textToSend)
                                onSendMessage(
                                    textToSend,
                                    firstBase64,
                                    firstMime,
                                    mediaUrisStr,
                                    mediaDataList
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .testTag(if (isGenerating) "stop_generation_button" else "send_message_button"),
                    containerColor = if (isGenerating) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ) {
                    if (isGenerating) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Generation",
                            tint = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageItem(
    message: MessageEntity,
    ttsEngine: TextToSpeech?,
    onCopyText: (String) -> Unit,
    onEditMessage: (messageId: String, content: String) -> Unit,
    onEditAndResendMessage: (messageId: String, newContent: String) -> Unit = { _, _ -> }
) {
    val isUser = message.role == "user"
    var isEditingInline by remember { mutableStateOf(false) }
    var editedText by remember(message.content) { mutableStateOf(message.content) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isUser) "You" else "Wasti AI",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (!isUser) {
                Text(
                    text = "Wasti AI Engine",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Copy Action Icon Button
            IconButton(
                onClick = { onCopyText(message.content) },
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy message",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }

            if (isUser) {
                // Toggle inline edit state
                IconButton(
                    onClick = {
                        isEditingInline = !isEditingInline
                        onEditMessage(message.id, message.content)
                    },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit message",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        speakDualPipelineTts(ttsEngine, message.content)
                    },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Read aloud",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
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
                // Task 43C: Media Vault Visual Chat Rendering
                if (message.attachedMediaUris.isNotBlank()) {
                    val mediaUris = remember(message.attachedMediaUris) {
                        message.attachedMediaUris.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    }
                    if (mediaUris.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            items(mediaUris) { uriStr ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 2.dp,
                                    modifier = Modifier.size(width = 110.dp, height = 80.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        val isImage = uriStr.contains("content:") ||
                                                uriStr.endsWith(".jpg", ignoreCase = true) ||
                                                uriStr.endsWith(".png", ignoreCase = true) ||
                                                uriStr.endsWith(".jpeg", ignoreCase = true) ||
                                                uriStr.endsWith(".webp", ignoreCase = true) ||
                                                uriStr.contains("Camera_Capture") ||
                                                uriStr.contains("image")

                                        if (isImage) {
                                            coil.compose.AsyncImage(
                                                model = uriStr,
                                                contentDescription = "Attached Media",
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                            )
                                        } else {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                                    contentDescription = "Attached File",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = uriStr.substringAfterLast('/'),
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (isEditingInline && isUser) {
                    // Inline editor for exact index editing
                    Column {
                        OutlinedTextField(
                            value = editedText,
                            onValueChange = { editedText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Edit Prompt (Index: ${message.id.take(8)})", fontSize = 11.sp) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { isEditingInline = false }) {
                                Text("Cancel", fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (editedText.isNotBlank()) {
                                        isEditingInline = false
                                        onEditAndResendMessage(message.id, editedText)
                                    }
                                }
                            ) {
                                Text("Save & Resend", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    // SelectionContainer fixes Text Selection Limitation: enables native word-by-word highlighting and copying!
                    SelectionContainer {
                        val content = message.content
                        if (content.contains("```")) {
                            val parts = content.split("```")
                            Column {
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
                            }
                        } else {
                            Text(
                                text = content,
                                fontSize = 14.sp,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (!isUser && (content.contains("Respected Hiring Client") || content.contains("Drafted Pitch") || content.contains("Match Score") || content.contains("Lead Radar") || content.contains("Opportunity") || content.contains("Prospect"))) {
                            val context = LocalContext.current
                            val extractedEmail = com.example.data.core.LeadRadarRepository.extractEmail(content)
                            val extractedPhone = com.example.data.core.LeadRadarRepository.extractPhone(content)

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚡ One-Tap Action Hub",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { com.example.data.core.LeadRadarRepository.dispatchWhatsAppDirect(context, extractedPhone, content) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("WhatsApp", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { com.example.data.core.LeadRadarRepository.dispatchEmailDirect(context, extractedEmail, "Proposal / Outreach Pitch", content) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Email", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { com.example.data.core.LeadRadarRepository.dispatchCallDirect(context, extractedPhone) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Call", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = { onCopyText(content) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun speakDualPipelineTts(ttsEngine: TextToSpeech?, text: String) {
    if (ttsEngine == null || text.isBlank()) return
    val cleanText = WastiSpeechSanitizer.sanitizeForSpeech(text)
    if (cleanText.isBlank()) return

    // Dual-Pipeline TTS Routing
    val isUrduScript = cleanText.any { it in '\u0600'..'\u06FF' }
    val convertedPureUrdu = com.example.util.WastiUrduLanguageEngine.romanUrduToPureUrduScript(cleanText)
    val isRomanUrduConverted = !isUrduScript && convertedPureUrdu != cleanText

    val urduLocale = Locale.forLanguageTag("ur-PK")
    val (ttsTextToSpeak, targetLocale) = when {
        isUrduScript -> Pair(cleanText, urduLocale)
        isRomanUrduConverted -> Pair(convertedPureUrdu, urduLocale)
        else -> {
            val lower = cleanText.lowercase()
            val romanUrduKeywords = setOf("hai", "hain", "kya", "kaise", "ap", "aap", "mein", "main", "ho", "nahi", "nahin", "bohot", "bht", "ji", "ha", "shukriya", "salam", "shukria", "kaam", "karo", "karein", "jee", "ye", "yeh", "wo", "woh")
            val words = lower.split("\\s+".toRegex())
            if (words.any { it in romanUrduKeywords }) {
                Pair(convertedPureUrdu, urduLocale)
            } else {
                Pair(cleanText, Locale.getDefault())
            }
        }
    }

    try {
        val result = ttsEngine.setLanguage(targetLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsEngine.language = Locale.ENGLISH
        }
    } catch (e: Exception) {
        android.util.Log.e("ChatWorkspaceTTS", "Error setting TTS language", e)
    }

    val params = android.os.Bundle()
    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "chat_tts_dual_pipeline")
    ttsEngine.speak(ttsTextToSpeak, TextToSpeech.QUEUE_FLUSH, params, "chat_tts_dual_pipeline")
}

@Composable
fun EmailDraftApprovalCard(
    draft: com.example.data.core.EmailDraft,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardContext = LocalContext.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("email_draft_approval_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Draft Email",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✉️ Email Outreach Draft (Approval Required)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "PAUSED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "To: ${draft.to}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = "Subject: ${draft.subject}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = draft.body,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("reject_email_draft_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject / Edit", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        val activity = cardContext.findFragmentActivity()
                        if (activity != null) {
                            BiometricSecurityManager.authenticate(
                                activity = activity,
                                title = "Thumbprint Authorization Required",
                                subtitle = "Scan thumbprint to execute Gmail OAuth send",
                                onSuccess = onApprove,
                                onError = { err ->
                                    Toast.makeText(cardContext, "Biometric authorization failed: $err", Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            onApprove()
                        }
                    },
                    modifier = Modifier.testTag("approve_email_draft_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Thumbprint Approve & Send", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun LinkedInDraftApprovalCard(
    draft: com.example.data.core.LinkedInDraft,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardContext = LocalContext.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("linkedin_draft_approval_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Draft LinkedIn Post",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "💼 LinkedIn Social Post Draft (Approval Required)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "PAUSED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Post Content:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = draft.content,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.testTag("reject_linkedin_draft_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject / Edit", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = {
                        val activity = cardContext.findFragmentActivity()
                        if (activity != null) {
                            BiometricSecurityManager.authenticate(
                                activity = activity,
                                title = "Thumbprint Authorization Required",
                                subtitle = "Scan thumbprint to execute LinkedIn OAuth post",
                                onSuccess = onApprove,
                                onError = { err ->
                                    Toast.makeText(cardContext, "Biometric authorization failed: $err", Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            onApprove()
                        }
                    },
                    modifier = Modifier.testTag("approve_linkedin_draft_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Thumbprint Approve & Post", fontSize = 12.sp)
                }
            }
        }
    }
}
