package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.tool.ToolRegistry
import com.example.data.tool.WastiTool
import com.example.data.wre.*
import com.example.ui.components.CodeBlockView
import com.example.ui.viewmodel.WastiViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Stage 9D: Wasti Code Studio + Capability Marketplace + Persistent Workspace
 * 
 * Features:
 * 1. Monospace Code Editor with Virtual Workspace File System Sync
 * 2. Instant WRE Execution and Live Verification Output Console
 * 3. Dynamic Tool Registration directly into Wasti ToolRegistry
 * 4. Capability Marketplace & Package Manager (.wasti bundle export & install)
 * 5. Autonomous AI Patch Synthesizer and Prompt Library integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeStudioScreen(
    viewModel: WastiViewModel? = null,
    wreManager: WreManager,
    activeCodeContext: String = "fun main() {\n    println(\"Wasti OS Code Engine\")\n}",
    onCodeContextChange: (String) -> Unit = {},
    onSendMessageToChat: (prompt: String, codeContext: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Editor, 1: Tool Registry, 2: AI Dev Assistant, 3: Prompts
    var activeVirtualPath by remember { mutableStateOf("home/wasti/scripts/workspace_script.sh") }
    var codeContent by remember(activeCodeContext) { mutableStateOf(activeCodeContext) }
    var consoleOutput by remember { mutableStateOf("") }
    var consoleStderr by remember { mutableStateOf("") }
    var consoleExitCode by remember { mutableStateOf<Int?>(null) }
    var consoleVerified by remember { mutableStateOf(false) }
    var consoleEvidence by remember { mutableStateOf<String?>(null) }
    var isExecuting by remember { mutableStateOf(false) }

    // Dialog states
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

    // List of workspace files in home/wasti, bin, and scripts
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

    val promptTemplates = remember {
        listOf(
            PromptTemplate(
                title = "WRE Native Shell Automation Script",
                category = "WRE Automation",
                promptText = "Create a shell script for WRE that scans directory sizes, verifies disk space, and exports a clean JSON report.",
                sampleCode = "```sh\n#!/bin/sh\necho '{\"status\":\"running\",\"workspace\":\"'\$PWD'\"}'\nls -la\n```"
            ),
            PromptTemplate(
                title = "Jetpack Compose Dynamic Screen",
                category = "Android UI",
                promptText = "Generate a complete Jetpack Compose screen with Material 3 cards, state hoisting, and testTag attributes.",
                sampleCode = "```kotlin\n@Composable\nfun CustomToolScreen() {\n    Card(modifier = Modifier.fillMaxWidth().testTag(\"custom_card\")) {\n        Text(\"Custom Tool\")\n    }\n}\n```"
            ),
            PromptTemplate(
                title = "Wasti Dynamic Tool Definition",
                category = "Tool Registry",
                promptText = "Write a script that processes input text, cleans formatting, and registers as an autonomous capability in ToolRegistry.",
                sampleCode = "```sh\n#!/bin/sh\n# Input arg: $1\necho \"Processed: $1\" | tr '[:lower:]' '[:upper:]'\n```"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("code_studio_screen")
    ) {
        // Studio Navigation Bar
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Code Studio", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                icon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_code_editor")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Capabilities", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                icon = { Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_tool_registry")
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("AI Assistant", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_dev_assistant")
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("Prompts", fontWeight = FontWeight.Bold, fontSize = 13.sp) },
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("tab_prompt_library")
            )
        }

        when (selectedTab) {
            0 -> {
                // ==========================================
                // 1. CODE STUDIO & PERSISTENT WORKSPACE IDE
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // File management toolbar
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "/$activeVirtualPath",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { showNewFileDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.AddCircleOutline, contentDescription = "New File", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            refreshWorkspaceFiles()
                                            Toast.makeText(context, "Workspace refreshed (${workspaceFileList.size} files)", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            // Quick Workspace File Selector Chips
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
                                            onClick = {
                                                activeVirtualPath = path
                                                val fileRes = wreManager.workspaceManager.resolve(path)
                                                val f = fileRes.getOrNull()
                                                if (f != null && f.exists() && f.isFile) {
                                                    codeContent = f.readText()
                                                    onCodeContextChange(codeContent)
                                                }
                                            },
                                            label = {
                                                Text(
                                                    path.substringAfterLast('/'),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    if (path.endsWith(".sh")) Icons.Default.Terminal else Icons.Default.Description,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Code Editor Text Field
                    OutlinedTextField(
                        value = codeContent,
                        onValueChange = {
                            codeContent = it
                            onCodeContextChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("code_editor_input"),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        placeholder = { Text("# Enter shell, python, kotlin, or JSON code here...", fontFamily = FontFamily.Monospace) },
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // RUN Button
                        Button(
                            onClick = {
                                scope.launch {
                                    isExecuting = true
                                    consoleOutput = ""
                                    consoleStderr = ""
                                    consoleExitCode = null
                                    consoleVerified = false

                                    // Save active code to file first
                                    val targetFileRes = wreManager.workspaceManager.resolve(activeVirtualPath)
                                    val targetFile = targetFileRes.getOrNull()
                                    if (targetFile != null) {
                                        targetFile.parentFile?.mkdirs()
                                        targetFile.writeText(codeContent)
                                    }

                                    // Determine execution method
                                    val commandToRun = if (activeVirtualPath.endsWith(".sh") || !activeVirtualPath.contains(".")) {
                                        "sh $activeVirtualPath"
                                    } else {
                                        "cat $activeVirtualPath"
                                    }

                                    val req = ExecutionRequest(
                                        command = commandToRun,
                                        workingDirectory = "home/wasti",
                                        initiatedBy = "CodeStudioScreen"
                                    )
                                    val res = wreManager.execute(req)

                                    consoleOutput = res.stdout
                                    consoleStderr = res.stderr
                                    consoleExitCode = res.exitCode
                                    consoleVerified = res.verified
                                    consoleEvidence = res.verificationEvidence
                                    isExecuting = false

                                    // Persist execution
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
                            enabled = !isExecuting,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("run_code_button")
                        ) {
                            if (isExecuting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Running...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Script", fontSize = 12.sp)
                            }
                        }

                        // SAVE Button
                        OutlinedButton(
                            onClick = {
                                val fileRes = wreManager.workspaceManager.resolve(activeVirtualPath)
                                val file = fileRes.getOrNull()
                                if (file != null) {
                                    file.parentFile?.mkdirs()
                                    file.writeText(codeContent)
                                    refreshWorkspaceFiles()
                                    Toast.makeText(context, "Saved to $activeVirtualPath", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to resolve path", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("save_file_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save", fontSize = 12.sp)
                        }

                        // REGISTER AS TOOL Button
                        OutlinedButton(
                            onClick = {
                                registerToolName = activeVirtualPath.substringAfterLast('/').substringBeforeLast('.')
                                registerToolDesc = "Dynamic WRE tool created from $activeVirtualPath"
                                showRegisterToolDialog = true
                            },
                            modifier = Modifier.testTag("register_tool_button")
                        ) {
                            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Register Tool", fontSize = 12.sp)
                        }

                        // EXPORT .WASTI BUNDLE Button
                        OutlinedButton(
                            onClick = {
                                val pkgName = activeVirtualPath.substringAfterLast('/').substringBeforeLast('.')
                                // Ensure script is registered or saved
                                wreManager.packageManager.installOrUpdateScriptPackage(
                                    name = pkgName,
                                    scriptContent = codeContent,
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
                            modifier = Modifier.testTag("export_pkg_button")
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export .wasti", fontSize = 12.sp)
                        }
                    }

                    // Execution Console Area
                    if (consoleOutput.isNotBlank() || consoleStderr.isNotBlank() || consoleExitCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
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
                                            color = if (consoleExitCode == 0) Color(0xFF4CAF50) else Color(0xFFF44336),
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
                                                color = Color(0xFF81C784)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            consoleOutput = ""
                                            consoleStderr = ""
                                            consoleExitCode = null
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(14.dp))
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
                                                color = Color(0xFFD4D4D4)
                                            )
                                        }
                                    }
                                    if (consoleStderr.isNotBlank()) {
                                        item {
                                            Text(
                                                text = consoleStderr,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = Color(0xFFFF8A80)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // ==========================================
                // 2. CAPABILITY MARKETPLACE & TOOL REGISTRY
                // ==========================================
                val allTools = remember { ToolRegistry.getAllWastiTools() }
                val installedPackages = remember { wreManager.packageManager.listPackages() }

                val filteredTools = allTools.filter { tool ->
                    val matchesQuery = tool.definition.name.contains(toolSearchQuery, ignoreCase = true) ||
                            tool.definition.description.contains(toolSearchQuery, ignoreCase = true) ||
                            tool.definition.category.contains(toolSearchQuery, ignoreCase = true)
                    val matchesCategory = selectedCategoryFilter == "All" || tool.definition.category.equals(selectedCategoryFilter, ignoreCase = true)
                    matchesQuery && matchesCategory
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                        .testTag("tool_registry_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Wasti Capability Marketplace",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = "${allTools.size} live capabilities registered across One-Brain system",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = { showInstallBundleDialog = true },
                                    modifier = Modifier.testTag("install_bundle_button")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Install .wasti", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Search and filter
                    item {
                        OutlinedTextField(
                            value = toolSearchQuery,
                            onValueChange = { toolSearchQuery = it },
                            placeholder = { Text("Search tools & capabilities...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_tools_input"),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    // Tool items
                    items(filteredTools) { tool ->
                        val isDynamic = tool.definition.id.startsWith("wre_tool_")
                        val pkgName = if (isDynamic) tool.definition.id.removePrefix("wre_tool_") else null
                        val pkg = pkgName?.let { wreManager.packageManager.getPackage(it) }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tool_card_${tool.definition.id}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
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
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = tool.definition.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = tool.definition.id,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isDynamic) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = tool.definition.category,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = if (isDynamic) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = tool.definition.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isDynamic && pkgName != null) {
                                        OutlinedButton(
                                            onClick = {
                                                val exp = wreManager.packageManager.exportPackage(pkgName)
                                                if (exp.isSuccess) {
                                                    Toast.makeText(context, "Exported ${pkgName}.wasti", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .padding(end = 6.dp)
                                                .testTag("export_tool_button")
                                        ) {
                                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Export", fontSize = 11.sp)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                wreManager.packageManager.removePackage(pkgName)
                                                Toast.makeText(context, "Uninstalled $pkgName", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
                                        onClick = {
                                            showTestToolDialog = tool
                                            testToolArgs = ""
                                        },
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

            2 -> {
                // ==========================================
                // 3. AI DEV ASSISTANT
                // ==========================================
                DevAssistantScreen(
                    activeCodeContext = codeContent,
                    onCodeContextChange = { newCode ->
                        codeContent = newCode
                        onCodeContextChange(newCode)
                    },
                    onSendMessageToChat = onSendMessageToChat
                )
            }

            3 -> {
                // ==========================================
                // 4. PROMPT & TEMPLATE LIBRARY
                // ==========================================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Text(text = "Engineering & WRE Prompt Templates", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    items(promptTemplates) { tmpl ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = tmpl.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = tmpl.category,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = tmpl.promptText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)

                                Spacer(modifier = Modifier.height(10.dp))
                                CodeBlockView(code = tmpl.sampleCode, language = "kotlin")

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            codeContent = tmpl.sampleCode.removePrefix("```sh\n").removePrefix("```kotlin\n").removeSuffix("\n```")
                                            selectedTab = 0
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text("Open in Studio", fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = { onSendMessageToChat(tmpl.promptText, codeContent) }
                                    ) {
                                        Text("Ask AI Chat", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
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
                                activeVirtualPath = path
                                codeContent = file.readText()
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
                                scriptContent = codeContent,
                                description = registerToolDesc.ifBlank { "Dynamic capability" },
                                runtime = if (activeVirtualPath.endsWith(".py")) "py" else if (activeVirtualPath.endsWith(".js")) "js" else "sh",
                                entryPoint = activeVirtualPath
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
                                // Write to temporary bundle file
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
                        scope.launch {
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
