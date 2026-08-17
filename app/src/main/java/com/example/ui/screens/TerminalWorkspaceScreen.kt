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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
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
                    text = "WASTI RUNTIME ENVIRONMENT (WRE) v1.0.0 [Android Native]",
                    type = TerminalLineType.SYSTEM
                )
            )
            add(
                TerminalLine(
                    text = "Workspace: /home/wasti (Type 'help' for commands, 'status' for runtime capabilities)",
                    type = TerminalLineType.SYSTEM
                )
            )
        }
    }

    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }
    var isExecuting by remember { mutableStateOf(false) }
    var activePid by remember { mutableStateOf<String?>(null) }
    var currentWorkingDir by remember { mutableStateOf("/home/wasti") }

    fun executeTerminalCommand(rawCommand: String) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) return

        history.add(trimmed)
        historyIndex = -1

        lines.add(
            TerminalLine(
                text = "wasti@local:$currentWorkingDir$ $trimmed",
                type = TerminalLineType.INPUT
            )
        )

        val parts = trimmed.split("\\s+".toRegex())
        val cmd = parts.firstOrNull()?.lowercase() ?: ""

        if (cmd == "clear") {
            lines.clear()
            currentInput = ""
            return
        }

        if (cmd == "cd") {
            val target = if (parts.size > 1) parts[1] else "/home/wasti"
            val resolved = wreManager.workspaceManager.resolve(target)
            resolved.fold(
                onSuccess = { dir ->
                    if (dir.exists() && dir.isDirectory) {
                        currentWorkingDir = wreManager.workspaceManager.getVirtualPath(dir)
                        lines.add(
                            TerminalLine(
                                text = "Directory changed to $currentWorkingDir",
                                type = TerminalLineType.SYSTEM,
                                exitCode = 0,
                                verified = true
                            )
                        )
                    } else {
                        lines.add(
                            TerminalLine(
                                text = "cd: ${target}: No such directory",
                                type = TerminalLineType.ERROR,
                                exitCode = 1
                            )
                        )
                    }
                },
                onFailure = {
                    lines.add(
                        TerminalLine(
                            text = "cd: Access denied: ${it.message}",
                            type = TerminalLineType.ERROR,
                            exitCode = 1
                        )
                    )
                }
            )
            currentInput = ""
            return
        }

        isExecuting = true
        coroutineScope.launch {
            try {
                val req = ExecutionRequest(
                    command = trimmed,
                    workingDirectory = currentWorkingDir,
                    initiatedBy = "terminal_ui"
                )
                val res = wreManager.execute(req)

                if (res.stdout.isNotBlank()) {
                    lines.add(
                        TerminalLine(
                            text = res.stdout,
                            type = TerminalLineType.OUTPUT,
                            exitCode = res.exitCode,
                            verified = res.verified,
                            verificationEvidence = res.verificationEvidence
                        )
                    )
                }

                if (res.stderr.isNotBlank()) {
                    lines.add(
                        TerminalLine(
                            text = res.stderr,
                            type = TerminalLineType.ERROR,
                            exitCode = res.exitCode
                        )
                    )
                }

                lines.add(
                    TerminalLine(
                        text = "[Process ${res.executionId} completed with exit code ${res.exitCode} in ${res.durationMs}ms - Status: ${res.status}]",
                        type = if (res.exitCode == 0) TerminalLineType.SUCCESS else TerminalLineType.ERROR,
                        exitCode = res.exitCode,
                        verified = res.verified,
                        verificationEvidence = res.verificationEvidence
                    )
                )
            } catch (e: Exception) {
                lines.add(
                    TerminalLine(
                        text = "Execution error: ${e.localizedMessage}",
                        type = TerminalLineType.ERROR,
                        exitCode = 1
                    )
                )
            } finally {
                isExecuting = false
                currentInput = ""
                listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isExecuting) Color(0xFFF59E0B) else Color(0xFF10B981))
                        )
                        Column {
                            Text(
                                text = "Wasti Terminal",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                            Text(
                                text = "WRE Engine • $currentWorkingDir",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val allText = lines.joinToString("\n") { it.text }
                            clipboardManager.setText(AnnotatedString(allText))
                        },
                        modifier = Modifier.testTag("terminal_copy_all_btn")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Terminal Output")
                    }
                    IconButton(
                        onClick = { lines.clear() },
                        modifier = Modifier.testTag("terminal_clear_btn")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Terminal")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color(0xFFF8FAFC),
                    actionIconContentColor = Color(0xFF94A3B8)
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1120))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // Quick Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val quickCmds = listOf("help", "pwd", "ls", "status", "env", "ps", "jobs", "clear")
                    quickCmds.forEach { qCmd ->
                        SuggestionChip(
                            onClick = {
                                currentInput = qCmd
                                executeTerminalCommand(qCmd)
                            },
                            label = {
                                Text(
                                    text = qCmd,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF1E293B)
                            ),
                            border = null,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Input Command Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$",
                        color = Color(0xFF10B981),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    OutlinedTextField(
                        value = currentInput,
                        onValueChange = { currentInput = it },
                        placeholder = {
                            Text(
                                text = "Enter WRE command (e.g. ls, mkdir, echo, status)...",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("terminal_input_field"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color(0xFFF8FAFC),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (currentInput.isNotBlank() && !isExecuting) {
                                    executeTerminalCommand(currentInput)
                                }
                            }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1E293B),
                            unfocusedContainerColor = Color(0xFF0F172A),
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            cursorColor = Color(0xFF38BDF8)
                        )
                    )

                    if (isExecuting) {
                        IconButton(
                            onClick = {
                                activePid?.let { wreManager.processManager.killProcess(it) }
                                isExecuting = false
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEF4444))
                                .testTag("terminal_cancel_btn")
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Cancel Execution",
                                tint = Color.White
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (currentInput.isNotBlank()) {
                                    executeTerminalCommand(currentInput)
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF2563EB))
                                .testTag("terminal_send_btn")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Execute Command",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF050811))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            items(lines, key = { it.id }) { line ->
                val textColor = when (line.type) {
                    TerminalLineType.INPUT -> Color(0xFF38BDF8)
                    TerminalLineType.OUTPUT -> Color(0xFFE2E8F0)
                    TerminalLineType.ERROR -> Color(0xFFF87171)
                    TerminalLineType.SYSTEM -> Color(0xFF94A3B8)
                    TerminalLineType.SUCCESS -> Color(0xFF34D399)
                }

                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        text = line.text,
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )

                    if (line.verified && line.verificationEvidence != null) {
                        Text(
                            text = "✓ Verified: ${line.verificationEvidence}",
                            color = Color(0xFF10B981).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(start = 8.dp, top = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
