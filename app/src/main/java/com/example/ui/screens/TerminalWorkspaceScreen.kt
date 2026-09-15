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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
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
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.example.data.tool.ToolRegistry
import com.example.data.tool.WastiTool
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class TerminalWorkspaceMode(val displayName: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CONSOLE("Terminal", Icons.Default.Terminal),
    CODE_STUDIO("Code Studio", Icons.Default.Code),
    PACKAGES("Packages", Icons.Default.Inventory2),
    FILES("Files", Icons.Default.Folder)
}

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
    onNavigateBack: () -> Unit = {},
    initialMode: TerminalWorkspaceMode = TerminalWorkspaceMode.CONSOLE,
    activeCodeContext: String = "fun main() {\n    println(\"Wasti OS Code Engine\")\n}",
    onCodeContextChange: (String) -> Unit = {},
    onSendMessageToChat: (prompt: String, codeContext: String) -> Unit = { _, _ -> }
) {
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var workspaceMode by remember { mutableStateOf(initialMode) }
    var codeStudioContent by remember(activeCodeContext) { mutableStateOf(activeCodeContext) }
    var codeStudioPath by remember { mutableStateOf("home/wasti/scripts/workspace_script.sh") }
    var codeStudioOutput by remember { mutableStateOf("") }
    var codeStudioStderr by remember { mutableStateOf("") }
    var codeStudioExitCode by remember { mutableStateOf<Int?>(null) }
    var codeStudioVerified by remember { mutableStateOf(false) }
    var codeStudioEvidence by remember { mutableStateOf<String?>(null) }
    var isCodeExecuting by remember { mutableStateOf(false) }
    var currentInput by remember { mutableStateOf("") }

    // Dialog and workspace states for unified Code/Terminal/Packages/Files
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileNameInput by remember { mutableStateOf("") }
    var showRegisterToolDialog by remember { mutableStateOf(false) }
    var registerToolName by remember { mutableStateOf("") }
    var registerToolDesc by remember { mutableStateOf("") }
    var showInstallBundleDialog by remember { mutableStateOf(false) }
    var installBundlePathOrJson by remember { mutableStateOf("") }
    var showTestToolDialog by remember { mutableStateOf<WastiTool?>(null) }
    var testToolArgs by remember { mutableStateOf("") }
    var toolSearchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("All") }
    var workspaceFileList by remember { mutableStateOf(listOf<String>()) }

    fun refreshWorkspaceFiles() {
        val list = mutableListOf<String>()
        val folders = listOf("home/wasti", "home/wasti/scripts", "home/wasti/bin", "projects", "scripts", "bin")
        folders.forEach { folder ->
            val dirRes = wreManager.workspaceManager.resolve(folder)
            val dir = dirRes.getOrNull()
            if (dir != null && dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    list.add(wreManager.workspaceManager.getVirtualPath(f).removePrefix("/"))
                }
            }
        }
        workspaceFileList = list.distinct().sorted()
    }

    LaunchedEffect(Unit) {
        refreshWorkspaceFiles()
    }

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

                    var showTerminalMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showTerminalMenu = true },
                            modifier = Modifier.testTag("terminal_overflow_menu_button")
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Terminal Menu",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                        DropdownMenu(
                            expanded = showTerminalMenu,
                            onDismissRequest = { showTerminalMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Script Editor (Nano)", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showTerminalMenu = false
                                    editorFileName = "new_script.py"
                                    editorContent = "#!/usr/bin/env python3\nprint('Hello from Wasti Polyglot OS')\n"
                                    isEditorOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Copy Entire Log", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showTerminalMenu = false
                                    val allText = activeTab.lines.joinToString("\n") { it.text }
                                    clipboardManager.setText(AnnotatedString(allText))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Terminal Buffer", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                onClick = {
                                    showTerminalMenu = false
                                    activeTab.lines.clear()
                                    activeTab.lines.add(
                                        TerminalLine(
                                            text = "Terminal buffer cleared.",
                                            type = TerminalLineType.SYSTEM
                                        )
                                    )
                                }
                            )
                        }
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
            // Unified Mode Selector: Terminal | Code Studio | Packages | Files
            TabRow(
                selectedTabIndex = workspaceMode.ordinal,
                containerColor = Color(0xFF0F172A),
                contentColor = Color(0xFF38BDF8)
            ) {
                TerminalWorkspaceMode.values().forEach { mode ->
                    Tab(
                        selected = workspaceMode == mode,
                        onClick = { workspaceMode = mode },
                        text = {
                            Text(
                                text = mode.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (workspaceMode == mode) FontWeight.Bold else FontWeight.Normal,
                                color = if (workspaceMode == mode) Color.White else Color(0xFF94A3B8)
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = mode.displayName,
                                modifier = Modifier.size(16.dp),
                                tint = if (workspaceMode == mode) Color(0xFF38BDF8) else Color(0xFF64748B)
                            )
                        }
                    )
                }
            }

            when (workspaceMode) {
                TerminalWorkspaceMode.CONSOLE -> {
                    Column(modifier = Modifier.fillMaxSize()) {
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
                    "net", "wifi", "mesh", "neofetch", "pkg list", "rustc --version", "ffmpeg -version",
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
        TerminalWorkspaceMode.CODE_STUDIO -> {
            CodeStudioWorkspacePanel(
                wreManager = wreManager,
                activeVirtualPath = codeStudioPath,
                onPathChange = { path ->
                    codeStudioPath = path
                    val fileRes = wreManager.workspaceManager.resolve(path)
                    val f = fileRes.getOrNull()
                    if (f != null && f.exists() && f.isFile) {
                        codeStudioContent = f.readText()
                        onCodeContextChange(codeStudioContent)
                    }
                },
                codeContent = codeStudioContent,
                onCodeContentChange = { newCode ->
                    codeStudioContent = newCode
                    onCodeContextChange(newCode)
                },
                consoleOutput = codeStudioOutput,
                consoleStderr = codeStudioStderr,
                consoleExitCode = codeStudioExitCode,
                consoleVerified = codeStudioVerified,
                consoleEvidence = codeStudioEvidence,
                isExecuting = isCodeExecuting,
                onExecute = {
                    coroutineScope.launch {
                        isCodeExecuting = true
                        codeStudioOutput = ""
                        codeStudioStderr = ""
                        codeStudioExitCode = null
                        codeStudioVerified = false
                        codeStudioEvidence = null

                        val targetFileRes = wreManager.workspaceManager.resolve(codeStudioPath)
                        val targetFile = targetFileRes.getOrNull()
                        if (targetFile != null) {
                            targetFile.parentFile?.mkdirs()
                            targetFile.writeText(codeStudioContent)
                        }

                        val commandToRun = if (codeStudioPath.endsWith(".sh") || !codeStudioPath.contains(".")) {
                            "sh $codeStudioPath"
                        } else if (codeStudioPath.endsWith(".py")) {
                            "python3 $codeStudioPath"
                        } else if (codeStudioPath.endsWith(".js")) {
                            "node $codeStudioPath"
                        } else {
                            "cat $codeStudioPath"
                        }

                        val req = ExecutionRequest(
                            command = commandToRun,
                            workingDirectory = "home/wasti",
                            initiatedBy = "TerminalWorkspace.CodeStudio"
                        )
                        val res = wreManager.execute(req)
                        codeStudioOutput = res.stdout
                        codeStudioStderr = res.stderr
                        codeStudioExitCode = res.exitCode
                        codeStudioVerified = res.verified
                        codeStudioEvidence = res.verificationEvidence
                        isCodeExecuting = false

                        viewModel?.recordTerminalSession(
                            command = commandToRun,
                            output = res.stdout,
                            stderr = res.stderr,
                            workingDirectory = "home/wasti",
                            status = res.status.name,
                            exitCode = res.exitCode,
                            durationMs = res.durationMs,
                            verified = res.verified,
                            verificationEvidence = res.verificationEvidence
                        )
                    }
                },
                onSave = {
                    val fileRes = wreManager.workspaceManager.resolve(codeStudioPath)
                    val file = fileRes.getOrNull()
                    if (file != null) {
                        file.parentFile?.mkdirs()
                        file.writeText(codeStudioContent)
                        refreshWorkspaceFiles()
                        Toast.makeText(context, "Saved to $codeStudioPath", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to resolve path", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenRegisterTool = {
                    registerToolName = codeStudioPath.substringAfterLast('/').substringBeforeLast('.')
                    registerToolDesc = "Dynamic WRE tool created from $codeStudioPath"
                    showRegisterToolDialog = true
                },
                onExportWasti = {
                    val pkgName = codeStudioPath.substringAfterLast('/').substringBeforeLast('.')
                    wreManager.packageManager.installOrUpdateScriptPackage(
                        name = pkgName,
                        scriptContent = codeStudioContent,
                        description = "Exported from Code Studio"
                    )
                    val expRes = wreManager.packageManager.exportPackage(pkgName)
                    if (expRes.isSuccess) {
                        val f = expRes.getOrThrow()
                        refreshWorkspaceFiles()
                        Toast.makeText(context, "Exported .wasti bundle: ${f.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export error: ${expRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                workspaceFileList = workspaceFileList,
                onRefreshFiles = {
                    refreshWorkspaceFiles()
                    Toast.makeText(context, "Workspace refreshed (${workspaceFileList.size} files)", Toast.LENGTH_SHORT).show()
                },
                onNewFile = { showNewFileDialog = true },
                onClearConsole = {
                    codeStudioOutput = ""
                    codeStudioStderr = ""
                    codeStudioExitCode = null
                },
                onAskAIChat = { prompt, code ->
                    onSendMessageToChat(prompt, code)
                }
            )
        }
        TerminalWorkspaceMode.PACKAGES -> {
            WrePackagesWorkspacePanel(
                wreManager = wreManager,
                searchQuery = toolSearchQuery,
                onSearchQueryChange = { toolSearchQuery = it },
                selectedCategory = selectedCategoryFilter,
                onCategoryChange = { selectedCategoryFilter = it },
                onInstallBundleClick = { showInstallBundleDialog = true },
                onTestToolClick = { tool ->
                    showTestToolDialog = tool
                    testToolArgs = ""
                },
                onExportToolClick = { pkgName ->
                    val exp = wreManager.packageManager.exportPackage(pkgName)
                    if (exp.isSuccess) {
                        Toast.makeText(context, "Exported ${pkgName}.wasti", Toast.LENGTH_SHORT).show()
                    }
                },
                onUninstallToolClick = { pkgName ->
                    wreManager.packageManager.removePackage(pkgName)
                    Toast.makeText(context, "Uninstalled $pkgName", Toast.LENGTH_SHORT).show()
                }
            )
        }
        TerminalWorkspaceMode.FILES -> {
            WorkspaceFilesPanel(
                wreManager = wreManager,
                workspaceFileList = workspaceFileList,
                onRefresh = {
                    refreshWorkspaceFiles()
                    Toast.makeText(context, "Refreshed files", Toast.LENGTH_SHORT).show()
                },
                onNewFile = { showNewFileDialog = true },
                onOpenFile = { path ->
                    val fileRes = wreManager.workspaceManager.resolve(path)
                    val f = fileRes.getOrNull()
                    if (f != null && f.exists() && f.isFile) {
                        codeStudioPath = path
                        codeStudioContent = f.readText()
                        onCodeContextChange(codeStudioContent)
                        workspaceMode = TerminalWorkspaceMode.CODE_STUDIO
                    }
                },
                onRunInTerminal = { path ->
                    workspaceMode = TerminalWorkspaceMode.CONSOLE
                    val cmd = if (path.endsWith(".sh")) "sh $path" else if (path.endsWith(".py")) "python3 $path" else "cat $path"
                    submitCommand(cmd)
                },
                onDeleteFile = { path ->
                    val f = wreManager.workspaceManager.resolve(path).getOrNull()
                    if (f != null && f.exists()) {
                        f.delete()
                        refreshWorkspaceFiles()
                        Toast.makeText(context, "Deleted $path", Toast.LENGTH_SHORT).show()
                    }
                }
            )
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

    // Dialog: New File
    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create New Workspace File") },
            text = {
                Column {
                    Text("Enter relative path inside /home/wasti (e.g. scripts/test.sh, data/config.json):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFileNameInput,
                        onValueChange = { newFileNameInput = it },
                        placeholder = { Text("scripts/new_tool.sh") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = newFileNameInput.trim().removePrefix("/")
                        if (path.isNotEmpty()) {
                            val fileRes = wreManager.workspaceManager.resolve(path)
                            val file = fileRes.getOrNull()
                            if (file != null) {
                                file.parentFile?.mkdirs()
                                file.writeText("#!/bin/sh\n# $path\necho 'Running $path'\n")
                                codeStudioPath = path
                                codeStudioContent = file.readText()
                                onCodeContextChange(codeStudioContent)
                                refreshWorkspaceFiles()
                                showNewFileDialog = false
                            }
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Register Tool
    if (showRegisterToolDialog) {
        AlertDialog(
            onDismissRequest = { showRegisterToolDialog = false },
            title = { Text("Register Tool in Wasti ToolRegistry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Make this script an autonomous capability that the Chat Brain, Floating Bubble, and Multi-Agent Orchestrator can execute.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = registerToolName,
                        onValueChange = { registerToolName = it },
                        label = { Text("Tool Name / Identifier") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = registerToolDesc,
                        onValueChange = { registerToolDesc = it },
                        label = { Text("Description & Capabilities") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (registerToolName.isNotBlank()) {
                            val res = wreManager.packageManager.installOrUpdateScriptPackage(
                                name = registerToolName.trim(),
                                scriptContent = codeStudioContent,
                                description = registerToolDesc.ifBlank { "Dynamic capability" },
                                runtime = if (codeStudioPath.endsWith(".py")) "py" else if (codeStudioPath.endsWith(".js")) "js" else "sh",
                                entryPoint = codeStudioPath
                            )
                            if (res.isSuccess) {
                                Toast.makeText(context, "Tool '${registerToolName}' registered in ToolRegistry!", Toast.LENGTH_LONG).show()
                                showRegisterToolDialog = false
                            }
                        }
                    }
                ) {
                    Text("Register Capability")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegisterToolDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Install .wasti Bundle
    if (showInstallBundleDialog) {
        AlertDialog(
            onDismissRequest = { showInstallBundleDialog = false },
            title = { Text("Install .wasti Capability Bundle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter relative workspace path (e.g. packages/my_tool.wasti) or paste package JSON directly:", fontSize = 12.sp)
                    OutlinedTextField(
                        value = installBundlePathOrJson,
                        onValueChange = { installBundlePathOrJson = it },
                        placeholder = { Text("packages/sysinfo.wasti") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = installBundlePathOrJson.trim()
                        if (input.isNotEmpty()) {
                            val installRes = if (input.startsWith("{")) {
                                val tempFileRes = wreManager.workspaceManager.resolve("tmp/temp_bundle.wasti")
                                val tempFile = tempFileRes.getOrNull()
                                if (tempFile != null) {
                                    tempFile.writeText(input)
                                    wreManager.packageManager.installWastiPackage("tmp/temp_bundle.wasti")
                                } else {
                                    Result.failure(IllegalStateException("Could not resolve tmp path"))
                                }
                            } else {
                                wreManager.packageManager.installWastiPackage(input)
                            }

                            if (installRes.isSuccess) {
                                val pkg = installRes.getOrThrow()
                                Toast.makeText(context, "Installed .wasti package '${pkg.name}' v${pkg.version}!", Toast.LENGTH_LONG).show()
                                refreshWorkspaceFiles()
                                showInstallBundleDialog = false
                            } else {
                                Toast.makeText(context, "Install failed: ${installRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Install Package")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallBundleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog: Test Tool Execution
    if (showTestToolDialog != null) {
        val tool = showTestToolDialog!!
        var testResult by remember { mutableStateOf<String?>(null) }
        var isTesting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showTestToolDialog = null },
            title = { Text("Test Capability: ${tool.definition.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tool.definition.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = testToolArgs,
                        onValueChange = { testToolArgs = it },
                        label = { Text("Arguments / Parameters") },
                        placeholder = { Text("e.g. status or --verbose") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Execution Result:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1E1E1E),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                        ) {
                            Text(
                                text = testResult ?: "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isTesting = true
                            try {
                                val params = mutableMapOf<String, Any>()
                                if (testToolArgs.isNotBlank()) {
                                    params["args"] = testToolArgs
                                    params["input"] = testToolArgs
                                }
                                val out = tool.execute(params)
                                testResult = out
                            } catch (e: Exception) {
                                testResult = "Execution Error: ${e.message}"
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting
                ) {
                    Text(if (isTesting) "Running..." else "Execute")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestToolDialog = null }) {
                    Text("Close")
                }
            }
        )
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

@Composable
fun CodeStudioWorkspacePanel(
    wreManager: WreManager,
    activeVirtualPath: String,
    onPathChange: (String) -> Unit,
    codeContent: String,
    onCodeContentChange: (String) -> Unit,
    consoleOutput: String,
    consoleStderr: String,
    consoleExitCode: Int?,
    consoleVerified: Boolean,
    consoleEvidence: String?,
    isExecuting: Boolean,
    onExecute: () -> Unit,
    onSave: () -> Unit,
    onOpenRegisterTool: () -> Unit,
    onExportWasti: () -> Unit,
    workspaceFileList: List<String>,
    onRefreshFiles: () -> Unit,
    onNewFile: () -> Unit,
    onClearConsole: () -> Unit,
    onAskAIChat: (prompt: String, code: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("code_studio_screen")
    ) {
        // File Selector and Actions Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "/$activeVirtualPath",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = onNewFile,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = "New File", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onRefreshFiles,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (workspaceFileList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        workspaceFileList.forEach { path ->
                            val isSelected = activeVirtualPath == path
                            FilterChip(
                                selected = isSelected,
                                onClick = { onPathChange(path) },
                                label = {
                                    Text(
                                        path.substringAfterLast('/'),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (path.endsWith(".sh")) Icons.Default.Terminal else Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF64748B)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF0284C7),
                                    containerColor = Color(0xFF0F172A)
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Code Editor Input Area
        OutlinedTextField(
            value = codeContent,
            onValueChange = onCodeContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("code_editor_input"),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xFFF1F5F9)
            ),
            placeholder = { Text("# Enter shell, python, or script code...", fontFamily = FontFamily.Monospace, color = Color(0xFF64748B)) },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0A0E17),
                unfocusedContainerColor = Color(0xFF0A0E17),
                focusedBorderColor = Color(0xFF38BDF8),
                unfocusedBorderColor = Color(0xFF334155),
                cursorColor = Color(0xFF38BDF8)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Actions Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onExecute,
                enabled = !isExecuting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                modifier = Modifier.testTag("run_code_button")
            ) {
                if (isExecuting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Running...", fontSize = 12.sp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run Script", fontSize = 12.sp)
                }
            }

            OutlinedButton(
                onClick = onSave,
                modifier = Modifier.testTag("save_file_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onOpenRegisterTool,
                modifier = Modifier.testTag("register_tool_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24))
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Register Tool", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onExportWasti,
                modifier = Modifier.testTag("export_pkg_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399))
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export .wasti", fontSize = 12.sp)
            }
        }

        // Live Execution Console Panel
        if (consoleOutput.isNotBlank() || consoleStderr.isNotBlank() || consoleExitCode != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = if (consoleExitCode == 0) Color(0xFF4ADE80) else Color(0xFFF87171),
                                modifier = Modifier.size(8.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Exit Code: ${consoleExitCode ?: 0}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (consoleVerified) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "✓ Verified Evidence",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4ADE80)
                                )
                            }
                        }

                        IconButton(
                            onClick = onClearConsole,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        if (consoleOutput.isNotBlank()) {
                            item {
                                Text(
                                    text = consoleOutput,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                        if (consoleStderr.isNotBlank()) {
                            item {
                                Text(
                                    text = consoleStderr,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color(0xFFF87171)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WrePackagesWorkspacePanel(
    wreManager: WreManager,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onInstallBundleClick: () -> Unit,
    onTestToolClick: (WastiTool) -> Unit,
    onExportToolClick: (String) -> Unit,
    onUninstallToolClick: (String) -> Unit
) {
    val allTools = remember { ToolRegistry.getAllWastiTools() }
    val filteredTools = allTools.filter { tool ->
        val matchesQuery = tool.definition.name.contains(searchQuery, ignoreCase = true) ||
                tool.definition.description.contains(searchQuery, ignoreCase = true) ||
                tool.definition.category.contains(searchQuery, ignoreCase = true)
        val matchesCategory = selectedCategory == "All" || tool.definition.category.equals(selectedCategory, ignoreCase = true)
        matchesQuery && matchesCategory
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("tool_registry_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Capabilities & Packages",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${allTools.size} autonomous capabilities loaded in Sovereign ToolRegistry",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Button(
                        onClick = onInstallBundleClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.testTag("install_bundle_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Install .wasti", fontSize = 12.sp)
                    }
                }
            }
        }

        // Search Box
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search capabilities, tools, packages...", color = Color(0xFF64748B)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_tools_input"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF0A0E17),
                    unfocusedContainerColor = Color(0xFF0A0E17),
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF334155),
                    cursorColor = Color(0xFF38BDF8)
                )
            )
        }

        // Tool Items
        items(filteredTools) { tool ->
            val isDynamic = tool.definition.id.startsWith("wre_tool_")
            val pkgName = if (isDynamic) tool.definition.id.removePrefix("wre_tool_") else null

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tool_card_${tool.definition.id}"),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isDynamic) Icons.Default.Terminal else Icons.Default.SettingsSuggest,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = tool.definition.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = tool.definition.id,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0F172A)
                        ) {
                            Text(
                                text = tool.definition.category,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = tool.definition.description,
                        fontSize = 12.sp,
                        color = Color(0xFFCBD5E1)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDynamic && pkgName != null) {
                            OutlinedButton(
                                onClick = { onExportToolClick(pkgName) },
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag("export_tool_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399))
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = { onUninstallToolClick(pkgName) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag("uninstall_tool_button")
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Uninstall", fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = { onTestToolClick(tool) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            modifier = Modifier.testTag("test_tool_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Capability", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspaceFilesPanel(
    wreManager: WreManager,
    workspaceFileList: List<String>,
    onRefresh: () -> Unit,
    onNewFile: () -> Unit,
    onOpenFile: (String) -> Unit,
    onRunInTerminal: (String) -> Unit,
    onDeleteFile: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Workspace Files",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${workspaceFileList.size} files in /home/wasti and project trees",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = onNewFile,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New File", fontSize = 12.sp)
                        }

                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8))
                        }
                    }
                }
            }
        }

        if (workspaceFileList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No files found in workspace.", color = Color(0xFF64748B), fontSize = 13.sp)
                }
            }
        }

        items(workspaceFileList) { path ->
            val fileRes = wreManager.workspaceManager.resolve(path)
            val file = fileRes.getOrNull()
            val fileSizeStr = if (file != null && file.exists()) "${file.length()} bytes" else ""

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = when {
                                path.endsWith(".sh") -> Icons.Default.Terminal
                                path.endsWith(".py") -> Icons.Default.Code
                                path.endsWith(".json") -> Icons.Default.DataObject
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = path.substringAfterLast('/'),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                            Text(
                                text = "/$path ($fileSizeStr)",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onOpenFile(path) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF38BDF8))
                        ) {
                            Text("Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        TextButton(
                            onClick = { onRunInTerminal(path) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF4ADE80))
                        ) {
                            Text("Run", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { onDeleteFile(path) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}


