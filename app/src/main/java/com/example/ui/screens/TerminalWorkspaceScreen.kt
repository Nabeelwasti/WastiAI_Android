package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.wre.*
import kotlinx.coroutines.launch

data class TerminalLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val type: TerminalLineType = TerminalLineType.OUTPUT,
    val exitCode: Int? = null,
    val verified: Boolean = false,
    val verificationEvidence: String? = null
)

enum class TerminalLineType {
    INPUT,
    OUTPUT,
    ERROR,
    SYSTEM,
    SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalWorkspaceScreen(
    wreManager: WreManager,
    onNavigateBack: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    var currentInput by remember { mutableStateOf("") }
    val lines = remember {
        mutableStateListOf<TerminalLine>().apply {
            add(
                TerminalLine(
                    text = "WASTI RUNTIME ENVIRONMENT (WRE) v1.1.0 [Android Native]",
                    type = TerminalLineType.SYSTEM
                )
            )
            add(
                TerminalLine(
                    text = "Workspace: /home/wasti (Type 'help', 'wre pkg list', 'status' for commands)",
                    type = TerminalLineType.SYSTEM
                )
            )
        }
    }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var isExecuting by remember { mutableStateOf(false) }
    var currentWorkingDir by remember { mutableStateOf("home/wasti") }

    // Stage 9C: Dynamic Autocompletions
    val suggestions = remember(currentInput, currentWorkingDir) {
        wreManager.autocompleteEngine.getSuggestions(currentInput, currentWorkingDir)
    }

    fun submitCommand(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return

        if (trimmed.equals("clear", ignoreCase = true)) {
            lines.clear()
            lines.add(
                TerminalLine(
                    text = "WASTI RUNTIME ENVIRONMENT (WRE) v1.1.0 [Android Native]",
                    type = TerminalLineType.SYSTEM
                )
            )
            currentInput = ""
            return
        }

        history.add(trimmed)
        historyIndex = -1

        lines.add(
            TerminalLine(
                text = "wasti@local:/$currentWorkingDir$ $trimmed",
                type = TerminalLineType.INPUT
            )
        )

        currentInput = ""
        isExecuting = true

        coroutineScope.launch {
            val req = ExecutionRequest(
                command = trimmed,
                workingDirectory = currentWorkingDir,
                initiatedBy = "TerminalWorkspaceUI"
            )
            val result = wreManager.execute(req)

            // Update currentWorkingDir if it was a cd command
            if (trimmed.startsWith("cd") && result.exitCode == 0 && result.stdout.contains("Working directory: ")) {
                val newDir = result.stdout.substringAfter("Working directory: ").trim().removePrefix("/")
                currentWorkingDir = newDir
            }

            if (result.stdout.isNotBlank()) {
                lines.add(
                    TerminalLine(
                        text = result.stdout,
                        type = if (result.exitCode == 0) TerminalLineType.OUTPUT else TerminalLineType.ERROR,
                        exitCode = result.exitCode,
                        verified = result.verified,
                        verificationEvidence = result.verificationEvidence
                    )
                )
            }

            if (result.stderr.isNotBlank()) {
                lines.add(
                    TerminalLine(
                        text = result.stderr,
                        type = TerminalLineType.ERROR,
                        exitCode = result.exitCode
                    )
                )
            }

            if (result.verified && result.verificationEvidence != null) {
                lines.add(
                    TerminalLine(
                        text = "✓ Verified: ${result.verificationEvidence} (${result.durationMs}ms)",
                        type = TerminalLineType.SUCCESS,
                        verified = true,
                        verificationEvidence = result.verificationEvidence
                    )
                )
            }

            isExecuting = false
            if (lines.isNotEmpty()) {
                listState.animateScrollToItem(lines.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "WRE Terminal Workspace",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "wasti@local:/$currentWorkingDir",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val allText = lines.joinToString("\n") { it.text }
                        clipboardManager.setText(AnnotatedString(allText))
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Log",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        lines.clear()
                        lines.add(
                            TerminalLine(
                                text = "Terminal cleared.",
                                type = TerminalLineType.SYSTEM
                            )
                        )
                    }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = Color(0xFF0F141C)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F141C))
        ) {
            // Quick Command Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "help", "wre pkg list", "sysinfo", "status",
                    "ls -la", "pwd", "jobs", "ps", "env", "clear"
                ).forEach { chipCmd ->
                    SuggestionChip(
                        onClick = { submitCommand(chipCmd) },
                        label = {
                            Text(
                                chipCmd,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF90CAF9)
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1E293B)
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = Color(0xFF334155)
                        )
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Terminal Output Buffer
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(lines, key = { it.id }) { line ->
                    TerminalLineItem(line)
                }
            }

            // Autocomplete Suggestions Row (Stage 9C)
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161F2E))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestions.take(8).forEach { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF253347),
                            modifier = Modifier.clickable {
                                val tokens = WreCommandParser.tokenize(currentInput)
                                if (tokens.size <= 1 && !currentInput.endsWith(" ")) {
                                    currentInput = "${suggestion.text} "
                                } else {
                                    val lastToken = tokens.lastOrNull() ?: ""
                                    currentInput = currentInput.removeSuffix(lastToken) + suggestion.text
                                }
                            }
                        ) {
                            Text(
                                text = suggestion.displayText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (suggestion.isCommand) Color(0xFF4ADE80) else Color(0xFF60A5FA),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Command Prompt & Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161F2E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ">",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )

                TextField(
                    value = currentInput,
                    onValueChange = { currentInput = it },
                    placeholder = {
                        Text(
                            "Type command or script (e.g. sysinfo, ls | grep txt)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color(0xFF64748B)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terminal_input_field"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFF1F5F9),
                        fontSize = 13.sp
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitCommand(currentInput) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF38BDF8)
                    )
                )

                if (isExecuting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF38BDF8)
                    )
                } else {
                    IconButton(
                        onClick = { submitCommand(currentInput) },
                        modifier = Modifier.testTag("terminal_send_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Run Command",
                            tint = if (currentInput.isNotBlank()) Color(0xFF38BDF8) else Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalLineItem(line: TerminalLine) {
    val textColor = when (line.type) {
        TerminalLineType.INPUT -> Color(0xFF38BDF8)
        TerminalLineType.OUTPUT -> Color(0xFFE2E8F0)
        TerminalLineType.ERROR -> Color(0xFFF87171)
        TerminalLineType.SYSTEM -> Color(0xFF94A3B8)
        TerminalLineType.SUCCESS -> Color(0xFF4ADE80)
    }

    val prefix = when (line.type) {
        TerminalLineType.SYSTEM -> "[SYSTEM] "
        TerminalLineType.ERROR -> "[ERROR] "
        else -> ""
    }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$prefix${line.text}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = textColor,
            lineHeight = 16.sp
        )
    }
}
