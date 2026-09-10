package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import com.example.data.wre.*
import com.example.ui.viewmodel.WastiViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class TerminalLine(
    val id: String = UUID.randomUUID().toString(),
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

data class TerminalSessionTab(
    val id: String,
    val title: String,
    val mode: String = "wsh",
    val lines: MutableList<TerminalLine> = mutableListOf(),
    val history: MutableList<String> = mutableListOf(),
    var workingDir: String = "home/wasti"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalWorkspaceScreen(
    wreManager: WreManager,
    viewModel: WastiViewModel? = null,
    onNavigateBack: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    var currentInput by remember { mutableStateOf("") }

    // Multi-session tabs
    val sessionTabs = remember {
        mutableStateListOf(
            TerminalSessionTab(
                id = "tab_main",
                title = "1: wsh",
                mode = "wsh",
                lines = mutableListOf(
                    TerminalLine(
                        text = "⚡ WASTI RUNTIME ENVIRONMENT (WRE) v2.0.0 [Universal Polyglot Fabric]",
                        type = TerminalLineType.SYSTEM
                    ),
                    TerminalLine(
                        text = "• Sovereign Runtimes: Python 3.11, Node.js v20, SQLite3, GCC/Clang, Git, SSH, Tmux, Pkg/Apt",
                        type = TerminalLineType.SYSTEM
                    ),
                    TerminalLine(
                        text = "• Virtual Workspace: /home/wasti (Type 'help', 'pkg list', 'sysinfo', 'hardware' or shell commands)",
                        type = TerminalLineType.SYSTEM
                    )
                )
            ),
            TerminalSessionTab(
                id = "tab_py",
                title = "2: python",
                mode = "python",
                lines = mutableListOf(
                    TerminalLine(
                        text = "Python 3.11.0 (Wasti Sovereign Runtime Engine, AST Evaluator & Bytecode Interpreter)",
                        type = TerminalLineType.SYSTEM
                    ),
                    TerminalLine(
                        text = "Type Python statements, expressions, math, or scripts (e.g. print(math.sqrt(144)))",
                        type = TerminalLineType.SYSTEM
                    )
                )
            ),
            TerminalSessionTab(
                id = "tab_node",
                title = "3: node",
                mode = "node",
                lines = mutableListOf(
                    TerminalLine(
                        text = "Welcome to Node.js v20.0.0 (Wasti Sovereign V8 JavaScript Interpreter)",
                        type = TerminalLineType.SYSTEM
                    ),
                    TerminalLine(
                        text = "Type JavaScript expressions or JSON transformations (e.g. JSON.stringify({os: 'Wasti', active: true}))",
                        type = TerminalLineType.SYSTEM
                    )
                )
            ),
            TerminalSessionTab(
                id = "tab_sql",
                title = "4: sqlite",
                mode = "sqlite",
                lines = mutableListOf(
                    TerminalLine(
                        text = "SQLite 3.42.0 Database Console (Wasti Sovereign Encrypted DB Engine)",
                        type = TerminalLineType.SYSTEM
                    ),
                    TerminalLine(
                        text = "Run SQL queries (e.g. SELECT * FROM memories LIMIT 5;)",
                        type = TerminalLineType.SYSTEM
                    )
                )
            )
        )
    }

    var activeTabIndex by remember { mutableIntStateOf(0) }
    val activeTab = sessionTabs.getOrElse(activeTabIndex) { sessionTabs[0] }

    val activeLines = remember(activeTabIndex, activeTab.lines.size) {
        activeTab.lines
    }

    var historyIndex by remember { mutableIntStateOf(-1) }
    var isExecuting by remember { mutableStateOf(false) }

    // Nano text editor dialog state
    var isEditorOpen by remember { mutableStateOf(false) }
    var editorFileName by remember { mutableStateOf("script.py") }
    var editorContent by remember { mutableStateOf("") }

    // Telemetry & extra key states
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    // Dynamic Autocompletions
    val suggestions = remember(currentInput, activeTab.workingDir) {
        wreManager.autocompleteEngine.getSuggestions(currentInput, activeTab.workingDir)
    }

    fun isNaturalLanguagePrompt(input: String): Boolean {
        val t = input.trim()
        val knownCommands = setOf(
            "help", "clear", "ls", "pwd", "cd", "cat", "echo", "mkdir", "rm", "cp", "mv",
            "wre", "sysinfo", "status", "env", "jobs", "ps", "kill", "touch", "grep",
            "find", "head", "tail", "wc", "chmod", "curl", "wget", "python", "python3", "node",
            "kotlinc", "java", "javac", "git", "diff", "tar", "zip", "unzip", "pip", "npm",
            "pkg", "apt", "sqlite3", "sql", "gcc", "clang", "ssh", "tmux", "neofetch", "htop", "tree"
        )
        val firstWord = t.substringBefore(" ").lowercase()
        if (knownCommands.contains(firstWord)) return false

        val words = t.split(Regex("\\s+"))
        if (words.size >= 3) {
            val nlKeywords = setOf(
                "create", "build", "make", "find", "search", "show", "how", "what", "why",
                "can", "please", "write", "generate", "analyze", "check", "fix", "run", "start", "stop", "open"
            )
            if (nlKeywords.contains(firstWord) || words.any { it.endsWith("?") }) {
                return true
            }
        }
        return false
    }

    fun submitCommand(rawCmd: String) {
        val trimmed = rawCmd.trim()
        if (trimmed.isEmpty()) return

        // Intercept nano / edit commands
        if (trimmed.startsWith("nano ") || trimmed.startsWith("wedit ") || trimmed.startsWith("edit ")) {
            val fname = trimmed.split(Regex("\\s+")).getOrNull(1) ?: "file.txt"
            editorFileName = fname
            val targetFile = wreManager.workspaceManager.resolve("${activeTab.workingDir}/$fname").getOrNull()
            editorContent = if (targetFile != null && targetFile.exists() && targetFile.isFile) {
                targetFile.readText()
            } else {
                "# Wasti Sovereign File Editor: $fname\n"
            }
            isEditorOpen = true
            currentInput = ""
            return
        }

        if (trimmed.equals("clear", ignoreCase = true)) {
            activeTab.lines.clear()
            activeTab.lines.add(
                TerminalLine(
                    text = "Terminal buffer cleared.",
                    type = TerminalLineType.SYSTEM
                )
            )
            currentInput = ""
            return
        }

        activeTab.history.add(trimmed)
        historyIndex = -1

        // Prefix depending on active mode
        val prefixPrompt = when (activeTab.mode) {
            "python" -> ">>> $trimmed"
            "node" -> "node> $trimmed"
            "sqlite" -> "sqlite> $trimmed"
            else -> "wasti@local:/${activeTab.workingDir}$ $trimmed"
        }

        activeTab.lines.add(
            TerminalLine(
                text = prefixPrompt,
                type = TerminalLineType.INPUT
            )
        )

        val effectiveCommand = when (activeTab.mode) {
            "python" -> if (trimmed.startsWith("python")) trimmed else "python3 -c \"$trimmed\""
            "node" -> if (trimmed.startsWith("node")) trimmed else "node -e \"$trimmed\""
            "sqlite" -> if (trimmed.startsWith("sql") || trimmed.startsWith("sqlite")) trimmed else "sqlite3 \"$trimmed\""
            else -> trimmed
        }

        currentInput = ""
        isExecuting = true

        coroutineScope.launch {
            val isAgentIntent = trimmed.startsWith("agent ") || trimmed.startsWith("wasti ") ||
                    trimmed.startsWith("ai ") || trimmed.startsWith("?") ||
                    isNaturalLanguagePrompt(trimmed)

            if (isAgentIntent && activeTab.mode == "wsh") {
                val prompt = when {
                    trimmed.startsWith("agent ") -> trimmed.removePrefix("agent ").trim()
                    trimmed.startsWith("wasti ") -> trimmed.removePrefix("wasti ").trim()
                    trimmed.startsWith("ai ") -> trimmed.removePrefix("ai ").trim()
                    trimmed.startsWith("?") -> trimmed.removePrefix("?").trim()
                    else -> trimmed
                }
                activeTab.lines.add(
                    TerminalLine(
                        text = "▶ Dispatching to Wasti OS Autonomous Multi-Agent Brain...",
                        type = TerminalLineType.SYSTEM
                    )
                )

                val result = com.example.data.transport.WastiCommandTransport.getInstance().dispatchCommand(
                    command = prompt,
                    origin = com.example.data.core.CommandOrigin.TERMINAL,
                    executionMode = com.example.data.agent.runtime.ExecutionMode.AUTONOMOUS
                )

                when (result) {
                    is com.example.data.core.CommandSubmissionResult.Accepted -> {
                        activeTab.lines.add(
                            TerminalLine(
                                text = "✓ Command Accepted [ID: ${result.commandId.take(8)}]: ${result.message}",
                                type = TerminalLineType.SUCCESS,
                                verified = true
                            )
                        )
                    }
                    is com.example.data.core.CommandSubmissionResult.ImmediateSuccess -> {
                        activeTab.lines.add(
                            TerminalLine(
                                text = result.output,
                                type = TerminalLineType.OUTPUT,
                                verified = true,
                                verificationEvidence = result.verificationEvidence
                            )
                        )
                    }
                    is com.example.data.core.CommandSubmissionResult.Rejected -> {
                        activeTab.lines.add(
                            TerminalLine(
                                text = "✗ Command Rejected: ${result.reason}",
                                type = TerminalLineType.ERROR
                            )
                        )
                    }
                }
                isExecuting = false
                if (activeTab.lines.isNotEmpty()) {
                    listState.animateScrollToItem(activeTab.lines.size - 1)
                }
                return@launch
            }

            val req = ExecutionRequest(
                command = effectiveCommand,
                workingDirectory = activeTab.workingDir,
                initiatedBy = "TerminalWorkspaceUI"
            )
            val result = wreManager.execute(req)

            // Update working dir if it was a cd command
            if (trimmed.startsWith("cd") && result.exitCode == 0 && result.stdout.contains("Working directory: ")) {
                val newDir = result.stdout.substringAfter("Working directory: ").trim().removePrefix("/")
                activeTab.workingDir = newDir
            }

            if (result.stdout.isNotBlank()) {
                activeTab.lines.add(
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
                activeTab.lines.add(
                    TerminalLine(
                        text = result.stderr,
                        type = TerminalLineType.ERROR,
                        exitCode = result.exitCode
                    )
                )
            }

            if (result.verified && result.verificationEvidence != null) {
                activeTab.lines.add(
                    TerminalLine(
                        text = "✓ Verified: ${result.verificationEvidence} (${result.durationMs}ms)",
                        type = TerminalLineType.SUCCESS,
                        verified = true,
                        verificationEvidence = result.verificationEvidence
                    )
                )
            }

            viewModel?.recordTerminalSession(
                command = trimmed,
                output = result.stdout,
                stderr = result.stderr,
                workingDirectory = activeTab.workingDir,
                status = result.status.name,
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                verified = result.verified,
                verificationEvidence = result.verificationEvidence
            )

            isExecuting = false
            if (activeTab.lines.isNotEmpty()) {
                listState.animateScrollToItem(activeTab.lines.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "WRE Native Terminal",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF10B981).copy(alpha = 0.2f)
                            ) {
                                Text(
                                    "SOVEREIGN PRO",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "wasti@local:/${activeTab.workingDir}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val allText = activeTab.lines.joinToString("\n") { it.text }
                        clipboardManager.setText(AnnotatedString(allText))
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Log",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                    IconButton(onClick = {
                        editorFileName = "new_script.py"
                        editorContent = "#!/usr/bin/env python3\nprint('Hello from Wasti Polyglot OS')\n"
                        isEditorOpen = true
                    }) {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = "Text Editor",
                            tint = Color(0xFF38BDF8)
                        )
                    }
                    IconButton(onClick = {
                        activeTab.lines.clear()
                        activeTab.lines.add(
                            TerminalLine(
                                text = "Terminal buffer cleared.",
                                type = TerminalLineType.SYSTEM
                            )
                        )
                    }) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF0B0F17)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0B0F17))
        ) {
            // Multi-Session Tab Bar
            ScrollableTabRow(
                selectedTabIndex = activeTabIndex,
                containerColor = Color(0xFF111827),
                contentColor = Color(0xFF38BDF8),
                edgePadding = 8.dp,
                divider = {}
            ) {
                sessionTabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = activeTabIndex == index,
                        onClick = { activeTabIndex = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (tab.mode) {
                                    "python" -> Color(0xFF38BDF8)
                                    "node" -> Color(0xFFFBBF24)
                                    "sqlite" -> Color(0xFFA78BFA)
                                    else -> Color(0xFF10B981)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tab.title,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = if (activeTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (activeTabIndex == index) Color.White else Color(0xFF64748B)
                                )
                            }
                        }
                    )
                }
            }

            // Quick Execution Action Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "neofetch", "pkg list", "rustc --version", "ffmpeg -version", "mesh",
                    "pkg install htop", "pip list", "npm list",
                    "python3 -c \"import math; print(math.pi)\"",
                    "node -e \"console.log(process.versions)\"",
                    "git status", "gcc --version", "sqlite3 \".tables\"",
                    "ssh-keygen -t ed25519", "tmux new -s wasti",
                    "keystore status", "tunnel status", "sysinfo", "tree", "ls -la"
                ).forEach { chipCmd ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1E293B),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.clickable { submitCommand(chipCmd) }
                    ) {
                        Text(
                            text = chipCmd,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF93C5FD),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Main Terminal Output Buffer
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                items(activeLines, key = { it.id }) { line ->
                    TerminalLineItem(line)
                }
            }

            // Autocomplete Suggestions Row
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestions.take(8).forEach { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF1F2937),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF374151)),
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

            // Termux-Style Extra Keys Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111827))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Key: ESC
                ExtraKeyButton("ESC") { currentInput = "" }

                // Key: TAB
                ExtraKeyButton("TAB") {
                    if (suggestions.isNotEmpty()) {
                        val firstSug = suggestions.first()
                        val tokens = WreCommandParser.tokenize(currentInput)
                        if (tokens.size <= 1 && !currentInput.endsWith(" ")) {
                            currentInput = "${firstSug.text} "
                        } else {
                            val lastToken = tokens.lastOrNull() ?: ""
                            currentInput = currentInput.removeSuffix(lastToken) + firstSug.text
                        }
                    }
                }

                // Key: CTRL
                ExtraKeyButton(
                    text = "CTRL",
                    isActive = isCtrlActive
                ) { isCtrlActive = !isCtrlActive }

                // Key: ALT
                ExtraKeyButton(
                    text = "ALT",
                    isActive = isAltActive
                ) { isAltActive = !isAltActive }

                // Key: UP History
                ExtraKeyButton("↑") {
                    if (activeTab.history.isNotEmpty()) {
                        if (historyIndex == -1) historyIndex = activeTab.history.size - 1
                        else if (historyIndex > 0) historyIndex--
                        currentInput = activeTab.history.getOrElse(historyIndex) { "" }
                    }
                }

                // Key: DOWN History
                ExtraKeyButton("↓") {
                    if (activeTab.history.isNotEmpty() && historyIndex != -1) {
                        if (historyIndex < activeTab.history.size - 1) {
                            historyIndex++
                            currentInput = activeTab.history[historyIndex]
                        } else {
                            historyIndex = -1
                            currentInput = ""
                        }
                    }
                }

                // Symbol Keys: | , - , / , ~ , \ , " , ' , $ , ; , & , ^C
                listOf("|", "-", "/", "~", "\\", "\"", "'", "$", ";", "&", "&&", "||").forEach { sym ->
                    ExtraKeyButton(sym) { currentInput += sym }
                }

                ExtraKeyButton("^C") {
                    activeTab.lines.add(TerminalLine(text = "^C", type = TerminalLineType.SYSTEM))
                    currentInput = ""
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            // Command Prompt & Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val inputModeBadge: Pair<String, Color>? = remember(currentInput, activeTab.mode) {
                    val trimmed = currentInput.trim()
                    when {
                        activeTab.mode == "python" || trimmed.startsWith("python") -> "PY" to Color(0xFF38BDF8)
                        activeTab.mode == "node" || trimmed.startsWith("node") || trimmed.startsWith("js") -> "JS" to Color(0xFFFBBF24)
                        activeTab.mode == "sqlite" || trimmed.startsWith("sql") -> "SQL" to Color(0xFFA78BFA)
                        trimmed.startsWith("git ") -> "GIT" to Color(0xFFF97316)
                        trimmed.startsWith("pkg ") || trimmed.startsWith("apt ") -> "PKG" to Color(0xFF34D399)
                        trimmed.startsWith("gcc ") || trimmed.startsWith("clang ") -> "C/C++" to Color(0xFFEC4899)
                        trimmed.startsWith("keystore") -> "SIGN" to Color(0xFF10B981)
                        trimmed.startsWith("tunnel") -> "TUNNEL" to Color(0xFFF472B6)
                        trimmed.startsWith("?") || trimmed.startsWith("ai ") || trimmed.startsWith("wasti ") || isNaturalLanguagePrompt(trimmed) -> "AI" to Color(0xFFA855F7)
                        else -> null
                    }
                }

                if (inputModeBadge != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = inputModeBadge.second.copy(alpha = 0.2f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = inputModeBadge.first,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = inputModeBadge.second,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = when (activeTab.mode) {
                        "python" -> ">>>"
                        "node" -> "js>"
                        "sqlite" -> "sql>"
                        else -> "$"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF38BDF8),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )

                TextField(
                    value = currentInput,
                    onValueChange = { currentInput = it },
                    placeholder = {
                        Text(
                            when (activeTab.mode) {
                                "python" -> "Python statement or script..."
                                "node" -> "JavaScript code or expr..."
                                "sqlite" -> "SQL query (e.g. SELECT * FROM memories;)"
                                else -> "Command (e.g. pkg list, python3, git, gcc, curl)..."
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
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
                        modifier = Modifier.size(22.dp),
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

    // Interactive Sovereign Nano / Wedit Text Editor Dialog
    if (isEditorOpen) {
        Dialog(onDismissRequest = { isEditorOpen = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Editor Top Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "nano / $editorFileName",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 13.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val targetFileResult = wreManager.workspaceManager.resolve("${activeTab.workingDir}/$editorFileName")
                                    targetFileResult.getOrNull()?.let { f ->
                                        f.parentFile?.mkdirs()
                                        f.writeText(editorContent)
                                        activeTab.lines.add(
                                            TerminalLine(
                                                text = "Wrote ${editorContent.length} chars to ${wreManager.workspaceManager.getVirtualPath(f)}",
                                                type = TerminalLineType.SUCCESS,
                                                verified = true,
                                                verificationEvidence = "Saved via Sovereign Nano Editor"
                                            )
                                        )
                                    }
                                    isEditorOpen = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Save & Exit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { isEditorOpen = false },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Cancel", fontSize = 11.sp)
                            }
                        }
                    }

                    // Editor Text Area
                    TextField(
                        value = editorContent,
                        onValueChange = { editorContent = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFF1F5F9),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color(0xFF38BDF8)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ExtraKeyButton(
    text: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isActive) Color(0xFF38BDF8) else Color(0xFF1E293B),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) Color(0xFF38BDF8) else Color(0xFF334155)
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) Color.Black else Color(0xFFE2E8F0),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
        )
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

