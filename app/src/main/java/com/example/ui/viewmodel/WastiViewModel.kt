package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.*
import com.example.data.repository.WastiRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WastiViewModel(application: Application) : AndroidViewModel(application) {

    private val db = WastiDatabase.getDatabase(application)
    val repository = WastiRepository(db)

    val activeTab = MutableStateFlow("dashboard")
    val activeConversationId = MutableStateFlow<String?>(null)
    val activeAgentId = MutableStateFlow("ceo_agent")
    val isCommandPaletteOpen = MutableStateFlow(false)
    val isGenerating = MutableStateFlow(false)
    val darkThemeEnabled = MutableStateFlow(true)

    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentMessages: StateFlow<List<MessageEntity>> = activeConversationId
        .flatMapLatest { convId ->
            if (convId != null) {
                repository.getMessagesForConversation(convId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = repository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledge: StateFlow<List<KnowledgeEntity>> = repository.knowledge
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agents: StateFlow<List<AgentEntity>> = repository.agents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val projects: StateFlow<List<ProjectEntity>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTasks: StateFlow<List<TaskEntity>> = repository.allTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val integrations: StateFlow<List<IntegrationEntity>> = repository.integrations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<SystemLogEntity>> = repository.logs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.initDefaultDataIfNeeded()
            // Set initial active conversation
            val firstConv = repository.conversations.firstOrNull()?.firstOrNull()
            if (firstConv != null) {
                activeConversationId.value = firstConv.id
                activeAgentId.value = firstConv.activeAgentId
            }
        }
    }

    fun selectTab(tab: String) {
        activeTab.value = tab
    }

    fun selectConversation(id: String) {
        activeConversationId.value = id
    }

    fun selectAgent(id: String) {
        activeAgentId.value = id
    }

    fun toggleCommandPalette() {
        isCommandPaletteOpen.value = !isCommandPaletteOpen.value
    }

    fun toggleTheme() {
        darkThemeEnabled.value = !darkThemeEnabled.value
    }

    fun createNewConversation(title: String, agentId: String = activeAgentId.value) {
        viewModelScope.launch {
            val id = repository.createNewConversation(title, agentId)
            activeConversationId.value = id
            activeAgentId.value = agentId
            activeTab.value = "chat"
        }
    }

    fun sendMessage(prompt: String) {
        val convId = activeConversationId.value ?: return
        if (prompt.isBlank()) return

        viewModelScope.launch {
            isGenerating.value = true
            try {
                repository.sendMessage(convId, prompt, activeAgentId.value)
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun addMemory(key: String, category: String, value: String) {
        viewModelScope.launch {
            repository.addMemory(key, category, value)
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            repository.deleteMemory(id)
        }
    }

    fun addKnowledge(title: String, category: String, content: String, tags: String) {
        viewModelScope.launch {
            repository.addKnowledge(title, category, content, tags)
        }
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch {
            repository.deleteKnowledge(id)
        }
    }

    fun addProject(name: String, description: String, priority: String) {
        viewModelScope.launch {
            repository.addProject(name, description, priority)
        }
    }

    fun addTask(projectId: String, title: String, description: String, assignedAgentId: String, priority: String) {
        viewModelScope.launch {
            repository.addTask(projectId, title, description, assignedAgentId, priority)
        }
    }

    fun toggleTaskStatus(taskId: String, currentStatus: Boolean) {
        viewModelScope.launch {
            repository.toggleTaskStatus(taskId, currentStatus)
        }
    }

    fun addAgent(name: String, roleTitle: String, agentType: String, systemInstruction: String, capabilities: String) {
        viewModelScope.launch {
            repository.addAgent(name, roleTitle, agentType, systemInstruction, capabilities)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun executeQuickCommand(command: String) {
        isCommandPaletteOpen.value = false
        val trimmed = command.trim()
        when {
            trimmed.lowercase().startsWith("open chat") || trimmed.lowercase().startsWith("chat") -> {
                activeTab.value = "chat"
            }
            trimmed.lowercase().startsWith("open memory") || trimmed.lowercase().startsWith("memory") -> {
                activeTab.value = "memory"
            }
            trimmed.lowercase().startsWith("open agents") || trimmed.lowercase().startsWith("agents") -> {
                activeTab.value = "agents"
            }
            trimmed.lowercase().startsWith("open projects") || trimmed.lowercase().startsWith("projects") -> {
                activeTab.value = "projects"
            }
            trimmed.lowercase().startsWith("open code") || trimmed.lowercase().startsWith("code") -> {
                activeTab.value = "code"
            }
            trimmed.lowercase().startsWith("open integrations") || trimmed.lowercase().startsWith("integrations") -> {
                activeTab.value = "integrations"
            }
            trimmed.lowercase().startsWith("open settings") || trimmed.lowercase().startsWith("settings") -> {
                activeTab.value = "settings"
            }
            else -> {
                // Treat as prompt
                activeTab.value = "chat"
                sendMessage(trimmed)
            }
        }
    }
}
