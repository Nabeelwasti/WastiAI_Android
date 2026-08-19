package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.core.AppStartupManager
import com.example.data.core.AppStartupState
import com.example.ui.components.CommandPaletteDialog
import com.example.ui.components.ExecutiveBrainHeader
import com.example.ui.components.WastiStartupSplashScreen
import com.example.ui.screens.*
import com.example.data.wre.WreManager
import com.example.ui.theme.WastiTheme
import com.example.ui.viewmodel.WastiViewModel

data class WastiNavDestination(
    val id: String,
    val title: String,
    val icon: ImageVector
)

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val viewModel: WastiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.data.credential.CredentialRegistry.appContext = applicationContext
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        setContent {
            val focusManager = LocalFocusManager.current
            var triggerVoiceModalSignal by remember { mutableIntStateOf(0) }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ -> }

            LaunchedEffect(Unit) {
                try {
                    // Smooth 400ms splash display before automatically rendering workspace
                    kotlinx.coroutines.delay(400)
                    if (AppStartupManager.startupState.value !is AppStartupState.Ready) {
                        AppStartupManager.setReady()
                    }
                } catch (e: Exception) {
                    AppStartupManager.setReady()
                }
            }

            LaunchedEffect(Unit) {
                com.example.data.action.WastiAppActionBus.actions.collect { action ->
                    when (action) {
                        is com.example.data.action.WastiAppAction.NavigateTo -> {
                            viewModel.setActiveTab(action.destinationId)
                        }
                        is com.example.data.action.WastiAppAction.OpenProject -> {
                            viewModel.setActiveTab("projects")
                        }
                        is com.example.data.action.WastiAppAction.SearchMemory -> {
                            viewModel.setActiveTab("memory")
                        }
                        is com.example.data.action.WastiAppAction.ExecuteTerminalCommand -> {
                            viewModel.setActiveTab("terminal")
                        }
                        is com.example.data.action.WastiAppAction.TriggerVoiceModal -> {
                            triggerVoiceModalSignal++
                        }
                        is com.example.data.action.WastiAppAction.SwitchTheme -> {
                            viewModel.setDarkTheme(action.darkTheme)
                        }
                        is com.example.data.action.WastiAppAction.OpenDevAssistant -> {
                            viewModel.setActiveTab("code")
                        }
                        else -> { /* Handled by background runtime */ }
                    }
                }
            }

            val startupState by AppStartupManager.startupState.collectAsStateWithLifecycle()
            val darkTheme by viewModel.darkThemeEnabled.collectAsStateWithLifecycle()
            val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
            val activeConversationId by viewModel.activeConversationId.collectAsStateWithLifecycle()
            val activeAgentId by viewModel.activeAgentId.collectAsStateWithLifecycle()
            val isCommandPaletteOpen by viewModel.isCommandPaletteOpen.collectAsStateWithLifecycle()
            val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
            val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()

            val conversations by viewModel.conversations.collectAsStateWithLifecycle()
            val messages by viewModel.currentMessages.collectAsStateWithLifecycle()
            val memories by viewModel.memories.collectAsStateWithLifecycle()
            val knowledge by viewModel.knowledge.collectAsStateWithLifecycle()
            val agents by viewModel.agents.collectAsStateWithLifecycle()
            val projects by viewModel.projects.collectAsStateWithLifecycle()
            val tasks by viewModel.allTasks.collectAsStateWithLifecycle()
            val integrations by viewModel.integrations.collectAsStateWithLifecycle()
            val logs by viewModel.logs.collectAsStateWithLifecycle()
            val activeCodeContext by viewModel.activeCodeContext.collectAsStateWithLifecycle()

            val activeAgentName = "Wasti AI"

            val navItems = listOf(
                WastiNavDestination("dashboard", "Executive", Icons.Default.Dashboard),
                WastiNavDestination("chat", "AI Chat", Icons.AutoMirrored.Filled.Chat),
                WastiNavDestination("operations", "Telemetry", Icons.Default.Analytics),
                WastiNavDestination("agents", "Wasti AI", Icons.Default.Psychology),
                WastiNavDestination("memory", "Memory", Icons.Default.Memory),
                WastiNavDestination("projects", "Projects", Icons.Default.AccountTree),
                WastiNavDestination("terminal", "Terminal", Icons.Default.Terminal),
                WastiNavDestination("code", "Code", Icons.Default.Code),
                WastiNavDestination("integrations", "Connectors", Icons.Default.Extension),
                WastiNavDestination("account_hub", "Account Hub", Icons.Default.VpnKey),
                WastiNavDestination("settings", "Settings", Icons.Default.Settings)
            )

            WastiTheme(darkTheme = darkTheme) {
                if (startupState !is AppStartupState.Ready) {
                    WastiStartupSplashScreen(
                        startupState = startupState,
                        onRetry = {
                            (application as? WastiApplication)?.initializeSubsystems()
                        }
                    )
                } else {
                    CommandPaletteDialog(
                        isOpen = isCommandPaletteOpen,
                        onDismiss = { viewModel.toggleCommandPalette() },
                        onExecuteCommand = { cmd -> viewModel.executeQuickCommand(cmd) }
                    )

                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("wasti_main_scaffold"),
                        topBar = {
                            ExecutiveBrainHeader(
                                activeAgentName = activeAgentName,
                                isDarkTheme = darkTheme,
                                onToggleTheme = { viewModel.toggleTheme() },
                                onOpenCommandPalette = { viewModel.toggleCommandPalette() },
                                onOpenVoiceCall = {
                                    focusManager.clearFocus()
                                    viewModel.selectTab("chat")
                                    triggerVoiceModalSignal++
                                }
                            )
                        },
                        bottomBar = {
                            ScrollableTabRow(
                                selectedTabIndex = navItems.indexOfFirst { it.id == activeTab }.coerceAtLeast(0),
                                edgePadding = 8.dp,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary,
                                divider = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .testTag("main_bottom_nav")
                            ) {
                                navItems.forEach { nav ->
                                    val isSelected = activeTab == nav.id
                                    Tab(
                                        selected = isSelected,
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.selectTab(nav.id)
                                        },
                                        modifier = Modifier.testTag("nav_item_${nav.id}"),
                                        text = {
                                            Text(
                                                text = nav.title,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = nav.icon,
                                                contentDescription = nav.title,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (activeTab) {
                            "dashboard" -> DashboardScreen(
                                conversations = conversations,
                                memories = memories,
                                agents = agents,
                                projects = projects,
                                tasks = tasks,
                                logs = logs,
                                onNavigateTab = { viewModel.selectTab(it) },
                                onOpenConversation = {
                                    viewModel.selectConversation(it)
                                    viewModel.selectTab("chat")
                                }
                            )
                            "operations" -> OperationsDashboardScreen()
                            "chat" -> ChatWorkspaceScreen(
                                conversations = conversations,
                                activeConversationId = activeConversationId,
                                messages = messages,
                                agents = agents,
                                activeAgentId = activeAgentId,
                                selectedModel = selectedModel,
                                isGenerating = isGenerating,
                                onSelectConversation = { viewModel.selectConversation(it) },
                                onSelectAgent = { viewModel.selectAgent(it) },
                                onSelectModel = { viewModel.setSelectedModel(it) },
                                onClearChatHistory = { viewModel.clearChatHistory() },
                                onSendMessage = { prompt, imageInlineData, mimeType, attachedMediaUris, mediaList ->
                                    viewModel.sendMessage(
                                        prompt = prompt,
                                        imageInlineData = imageInlineData,
                                        mimeType = mimeType,
                                        attachedMediaUris = attachedMediaUris,
                                        mediaList = mediaList
                                    )
                                },
                                onEditAndResendMessage = { mId, newContent -> viewModel.editMessageAndResend(mId, newContent) },
                                onCreateNewConversation = { title -> viewModel.createNewConversation(title) },
                                onCancelGeneration = { viewModel.cancelActiveGeneration() },
                                triggerVoiceCallSignal = triggerVoiceModalSignal
                            )
                            "agents" -> AgentManagerScreen(
                                agents = agents,
                                onAddAgent = { name, roleTitle, agentType, instruction, caps ->
                                    viewModel.addAgent(name, roleTitle, agentType, instruction, caps)
                                },
                                onSelectAgentForChat = { agentId ->
                                    viewModel.selectAgent(agentId)
                                    viewModel.selectTab("chat")
                                }
                            )
                            "memory" -> MemoryKnowledgeScreen(
                                memories = memories,
                                knowledge = knowledge,
                                conversations = conversations,
                                onAddMemory = { k, c, v -> viewModel.addMemory(k, c, v) },
                                onUpdateMemory = { id, k, c, v -> viewModel.updateMemory(id, k, c, v) },
                                onDeleteMemory = { id -> viewModel.deleteMemory(id) },
                                onAddKnowledge = { t, c, content, tags -> viewModel.addKnowledge(t, c, content, tags) },
                                onUpdateKnowledge = { id, t, c, content, tags -> viewModel.updateKnowledge(id, t, c, content, tags) },
                                onDeleteKnowledge = { id -> viewModel.deleteKnowledge(id) },
                                onSelectConversation = { convId ->
                                    viewModel.selectConversation(convId)
                                    viewModel.selectTab("chat")
                                },
                                onDeleteConversation = { convId -> viewModel.deleteConversation(convId) },
                                onNavigateToChatWithPrompt = { promptText ->
                                    viewModel.selectTab("chat")
                                    viewModel.sendMessage(promptText)
                                }
                            )
                            "projects" -> ProjectsTasksScreen(
                                projects = projects,
                                allTasks = tasks,
                                agents = agents,
                                onAddProject = { name, desc, priority -> viewModel.addProject(name, desc, priority) },
                                onAddTask = { pId, title, desc, agentId, priority -> viewModel.addTask(pId, title, desc, agentId, priority) },
                                onToggleTaskStatus = { taskId, currentStatus -> viewModel.toggleTaskStatus(taskId, currentStatus) }
                            )
                            "terminal" -> TerminalWorkspaceScreen(
                                wreManager = viewModel.wreManager,
                                onNavigateBack = { viewModel.selectTab("dashboard") }
                            )
                            "code" -> CodePromptWorkspaceScreen(
                                activeCodeContext = activeCodeContext,
                                onCodeContextChange = { viewModel.setActiveCodeContext(it) },
                                onSendMessageToChat = { prompt, codeCtx ->
                                    viewModel.selectAgent("coding_agent")
                                    viewModel.setActiveCodeContext(codeCtx)
                                    viewModel.selectTab("chat")
                                    viewModel.sendMessage(prompt, codeCtx)
                                }
                            )
                            "integrations" -> IntegrationsLogsScreen(
                                integrations = integrations,
                                logs = logs,
                                onClearLogs = { viewModel.clearLogs() }
                            )
                            "welcome" -> WelcomeAuthScreen(
                                onLaunchWorkspace = { viewModel.selectTab("dashboard") },
                                onOpenAccountHub = { viewModel.selectTab("account_hub") }
                            )
                            "account_hub" -> AccountHubScreen(
                                onNavigateBack = { viewModel.selectTab("settings") }
                            )
                            "wakeword_settings" -> com.example.ui.screens.WakeWordSettingsScreen(
                                onNavigateBack = { viewModel.selectTab("settings") }
                            )
                            "settings" -> SettingsScreen(
                                isDarkTheme = darkTheme,
                                onToggleTheme = { viewModel.toggleTheme() },
                                selectedModel = selectedModel,
                                onSelectModel = { viewModel.setSelectedModel(it) },
                                onOpenWakeWordSettings = { viewModel.selectTab("wakeword_settings") }
                            )
                            "dev_assistant" -> com.example.ui.screens.DevAssistantScreen(
                                activeCodeContext = activeCodeContext,
                                onCodeContextChange = { viewModel.setActiveCodeContext(it) },
                                onSendMessageToChat = { prompt, codeCtx ->
                                    viewModel.selectAgent("coding_agent")
                                    viewModel.setActiveCodeContext(codeCtx)
                                    viewModel.selectTab("chat")
                                    viewModel.sendMessage(prompt, codeCtx)
                                }
                            )
                            else -> DashboardScreen(
                                conversations = conversations,
                                memories = memories,
                                agents = agents,
                                projects = projects,
                                tasks = tasks,
                                logs = logs,
                                onNavigateTab = { viewModel.selectTab(it) },
                                onOpenConversation = {
                                    viewModel.selectConversation(it)
                                    viewModel.selectTab("chat")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
}
