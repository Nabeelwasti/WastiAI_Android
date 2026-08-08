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
import com.example.ui.components.WastiVoiceCallModal
import com.example.util.WastiSpeechSanitizer
import kotlinx.coroutines.launch
import java.util.Locale

data class ActiveAttachment(
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
    onSelectConversation: (String) -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (String) -> Unit = {},
    onClearChatHistory: () -> Unit = {},
    onSendMessage: (prompt: String, imageInlineData: String?, mimeType: String) -> Unit,
    onEditAndResendMessage: (messageId: String, newContent: String) -> Unit = { _, _ -> },
    onCreateNewConversation: (String) -> Unit,
    onCancelGeneration: () -> Unit = {},
    triggerVoiceCallSignal: Int = 0
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var promptInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionTitle by remember { mutableStateOf("") }

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    var showWebScanDialog by remember { mutableStateOf(false) }
    var webUrlInput by remember { mutableStateOf("") }

    var activeAttachment by remember { mutableStateOf<ActiveAttachment?>(null) }

    // Real Camera Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val base64 = bitmapToBase64(bitmap)
            activeAttachment = ActiveAttachment(
                name = "Camera_Capture_${System.currentTimeMillis() % 10000}.jpg",
                mimeType = "image/jpeg",
                base64Data = base64,
                bitmap = bitmap,
                isImage = true
            )
        }
    }

    // Real Gallery / Photos Picker Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = getFileName(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val base64 = uriToBase64(context, uri)
            activeAttachment = ActiveAttachment(
                name = name,
                mimeType = mime,
                base64Data = base64,
                isImage = true
            )
        }
    }

    // Real Device Storage / File Manager Launcher
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = getFileName(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val base64 = uriToBase64(context, uri)

            if (mime.startsWith("image/")) {
                activeAttachment = ActiveAttachment(
                    name = name,
                    mimeType = mime,
                    base64Data = base64,
                    isImage = true
                )
            } else {
                // Read text / code file content directly into prompt if plain text
                val textContent = try {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                } catch (_: Exception) { null }

                if (!textContent.isNullOrBlank()) {
                    promptInput += "\n\n[Attached File: $name]\n```\n${textContent.take(4000)}\n```\n"
                } else {
                    activeAttachment = ActiveAttachment(
                        name = name,
                        mimeType = mime,
                        base64Data = base64,
                        isImage = false
                    )
                }
            }
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
                onSendMessage(prompt, null, "image/jpeg")
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
                    onEditMessage = { text ->
                        promptInput = text
                    },
                    onEditAndResendMessage = { messageId, newPrompt ->
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

        // Active Attachment Preview Badge
        activeAttachment?.let { att ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (att.isImage) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Attached: ${att.name}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(
                        onClick = { activeAttachment = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove Attachment", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
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
                                    galleryLauncher.launch("image/*")
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
                        } else if (promptInput.isNotBlank() || activeAttachment != null) {
                            val textToSend = promptInput
                            val att = activeAttachment
                            promptInput = ""
                            activeAttachment = null
                            focusManager.clearFocus()

                            WastiIntentParser.parseAndExecute(context, textToSend)
                            onSendMessage(textToSend, att?.base64Data, att?.mimeType ?: "image/jpeg")
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
    onEditMessage: (String) -> Unit,
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
                        onEditMessage(message.content)
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

                        if (!isUser && (content.contains("Respected Hiring Client") || content.contains("Drafted Pitch") || content.contains("Match Score") || content.contains("Lead Radar"))) {
                            val context = LocalContext.current
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚡ 1-Tap Client Outreach Dispatch",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { com.example.data.core.LeadRadarRepository.dispatchViaWhatsApp(context, content) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("WhatsApp", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { com.example.data.core.LeadRadarRepository.dispatchViaEmail(context, "Proposal / Outreach Pitch", content) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Email", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { onCopyText(content) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Pitch", fontSize = 11.sp)
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

    val (ttsTextToSpeak, targetLocale) = when {
        isUrduScript -> Pair(cleanText, Locale("ur", "PK"))
        isRomanUrduConverted -> Pair(convertedPureUrdu, Locale("ur", "PK"))
        else -> {
            val lower = cleanText.lowercase()
            val romanUrduKeywords = setOf("hai", "hain", "kya", "kaise", "ap", "aap", "mein", "main", "ho", "nahi", "nahin", "bohot", "bht", "ji", "ha", "shukriya", "salam", "shukria", "kaam", "karo", "karein", "jee", "ye", "yeh", "wo", "woh")
            val words = lower.split("\\s+".toRegex())
            if (words.any { it in romanUrduKeywords }) {
                Pair(convertedPureUrdu, Locale("ur", "PK"))
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
