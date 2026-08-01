package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.CommandPaletteDialog
import com.example.ui.components.ExecutiveBrainHeader
import com.example.ui.screens.*
import com.example.ui.theme.WastiTheme
import com.example.ui.viewmodel.WastiViewModel

data class WastiNavDestination(
    val id: String,
    val title: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {

    private val viewModel: WastiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
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

            val activeAgent = agents.find { it.id == activeAgentId }
            val activeAgentName = activeAgent?.name ?: "CEO Agent"

            val navItems = listOf(
                WastiNavDestination("dashboard", "Dashboard", Icons.Default.Dashboard),
                WastiNavDestination("chat", "AI Chat", Icons.Default.Chat),
                WastiNavDestination("agents", "Agents", Icons.Default.Psychology),
                WastiNavDestination("memory", "Memory", Icons.Default.Memory),
                WastiNavDestination("projects", "Projects", Icons.Default.AccountTree),
                WastiNavDestination("code", "Code", Icons.Default.Code),
                WastiNavDestination("integrations", "Connectors", Icons.Default.Extension),
                WastiNavDestination("settings", "Settings", Icons.Default.Settings)
            )

            WastiTheme(darkTheme = darkTheme) {
                CommandPaletteDialog(
                    isOpen = isCommandPaletteOpen,
                    onDismiss = { viewModel.toggleCommandPalette() },
                    onExecuteCommand = { cmd -> viewModel.executeQuickCommand(cmd) }
                )

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("wasti_main_scaffold"),
                    topBar = {
                        ExecutiveBrainHeader(
                            activeAgentName = activeAgentName,
                            isDarkTheme = darkTheme,
                            onToggleTheme = { viewModel.toggleTheme() },
                            onOpenCommandPalette = { viewModel.toggleCommandPalette() }
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .testTag("main_bottom_nav"),
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp
                        ) {
                            navItems.forEach { nav ->
                                val isSelected = activeTab == nav.id
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { viewModel.selectTab(nav.id) },
                                    icon = {
                                        Icon(
                                            imageVector = nav.icon,
                                            contentDescription = nav.title,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = nav.title,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.testTag("nav_item_${nav.id}")
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
                            "chat" -> ChatWorkspaceScreen(
                                conversations = conversations,
                                activeConversationId = activeConversationId,
                                messages = messages,
                                agents = agents,
                                activeAgentId = activeAgentId,
                                isGenerating = isGenerating,
                                onSelectConversation = { viewModel.selectConversation(it) },
                                onSelectAgent = { viewModel.selectAgent(it) },
                                onSendMessage = { viewModel.sendMessage(it) },
                                onCreateNewConversation = { title -> viewModel.createNewConversation(title) }
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
                                onAddMemory = { k, c, v -> viewModel.addMemory(k, c, v) },
                                onDeleteMemory = { id -> viewModel.deleteMemory(id) },
                                onAddKnowledge = { t, c, content, tags -> viewModel.addKnowledge(t, c, content, tags) },
                                onDeleteKnowledge = { id -> viewModel.deleteKnowledge(id) }
                            )
                            "projects" -> ProjectsTasksScreen(
                                projects = projects,
                                allTasks = tasks,
                                agents = agents,
                                onAddProject = { name, desc, priority -> viewModel.addProject(name, desc, priority) },
                                onAddTask = { pId, title, desc, agentId, priority -> viewModel.addTask(pId, title, desc, agentId, priority) },
                                onToggleTaskStatus = { taskId, currentStatus -> viewModel.toggleTaskStatus(taskId, currentStatus) }
                            )
                            "code" -> CodePromptWorkspaceScreen(
                                onSendMessageToChat = { prompt ->
                                    viewModel.selectTab("chat")
                                    viewModel.sendMessage(prompt)
                                }
                            )
                            "integrations" -> IntegrationsLogsScreen(
                                integrations = integrations,
                                logs = logs,
                                onClearLogs = { viewModel.clearLogs() }
                            )
                            "settings" -> SettingsScreen(
                                isDarkTheme = darkTheme,
                                onToggleTheme = { viewModel.toggleTheme() },
                                selectedModel = selectedModel,
                                onSelectModel = { viewModel.setSelectedModel(it) }
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
