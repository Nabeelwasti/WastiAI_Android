package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.*
import com.example.data.repository.WastiRepository
import com.example.data.wre.WreManager
import com.example.data.wre.ExecutionRequest
import com.example.data.wre.ExecutionResult
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WastiViewModel(application: Application) : AndroidViewModel(application) {

    val repository = com.example.data.di.WastiServiceLocator.repository
    val wreManager = com.example.data.di.WastiServiceLocator.wreManager
    val agentRuntime = com.example.data.di.WastiServiceLocator.agentRuntime
    val agentEventBus = com.example.data.di.WastiServiceLocator.agentEventBus

    private val prefs = application.getSharedPreferences("wasti_prefs", android.content.Context.MODE_PRIVATE)

    val activeTab = MutableStateFlow("dashboard")
    val activeConversationId = MutableStateFlow<String?>(null)
    val activeAgentId = MutableStateFlow("ceo_agent")
    val isCommandPaletteOpen = MutableStateFlow(false)
    val isGenerating = MutableStateFlow(false)
    val darkThemeEnabled = MutableStateFlow(prefs.getBoolean("dark_theme_enabled", true))
    val selectedModel = MutableStateFlow(
        prefs.getString("selected_model", "groq-llama-3.3-70b") ?: "groq-llama-3.3-70b"
    )

    val activeCodeContext = MutableStateFlow("fun main() {\n    println(\"Wasti OS Code Engine\")\n}")
    val activeFileName = MutableStateFlow("WorkspaceCode.kt")

    private val _activeGenerationCount = MutableStateFlow(0)
    val activeGenerationCount: StateFlow<Int> = _activeGenerationCount.asStateFlow()

    private val _lastOperationError = MutableStateFlow<String?>(null)
    val lastOperationError: StateFlow<String?> = _lastOperationError.asStateFlow()

    // Real-time Agent Event stream directly from the AgentEventBus
    private val _liveAgentEvents = MutableStateFlow<List<com.example.data.agent.runtime.AgentEvent>>(emptyList())
    val liveAgentEvents: StateFlow<List<com.example.data.agent.runtime.AgentEvent>> = _liveAgentEvents.asStateFlow()

    fun setActiveTab(tab: String) {
        activeTab.value = tab
    }

    fun setDarkTheme(enabled: Boolean) {
        darkThemeEnabled.value = enabled
        prefs.edit().putBoolean("dark_theme_enabled", enabled).apply()
    }

    fun setActiveCodeContext(code: String, fileName: String = "WorkspaceCode.kt") {
        activeCodeContext.value = code
        activeFileName.value = fileName
    }

    fun setSelectedModel(modelKey: String) {
        val normalizedModelKey = modelKey.trim()
        if (normalizedModelKey.isBlank()) return

        selectedModel.value = normalizedModelKey
        prefs.edit().putString("selected_model", normalizedModelKey).apply()
    }

    fun clearOperationError() {
        _lastOperationError.value = null
    }

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

    val terminalSessions: StateFlow<List<TerminalSessionEntity>> = repository.terminalSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    init {
        viewModelScope.launch {
            try {
                com.example.data.credential.CredentialRegistry.refreshAll(application)
                repository.initDefaultDataIfNeeded()
                // Set initial active conversation
                val firstConv = repository.conversations.firstOrNull()?.firstOrNull()
                if (firstConv != null) {
                    activeConversationId.value = firstConv.id
                    activeAgentId.value = firstConv.activeAgentId
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordOperationError("initialize Wasti", error)
            }
        }

        viewModelScope.launch {
            agentEventBus.events.collect { event ->
                val current = _liveAgentEvents.value.toMutableList()
                current.add(event)
                if (current.size > 100) current.removeAt(0)
                _liveAgentEvents.value = current
            }
        }
    }

    fun submitAgentTask(prompt: String, executionMode: com.example.data.agent.runtime.ExecutionMode = com.example.data.agent.runtime.ExecutionMode.AUTONOMOUS) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            try {
                val taskRes = agentRuntime.submitTaskResult(prompt, executionMode)
                taskRes.onSuccess { task ->
                    agentRuntime.executeTask(task.taskId)
                }
            } catch (e: Exception) {
                recordOperationError("submit agent task", e)
            }
        }
    }

    fun selectTab(tab: String) {
        tab.trim().takeIf { it.isNotEmpty() }?.let { activeTab.value = it }
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
        val enabled = !darkThemeEnabled.value
        darkThemeEnabled.value = enabled
        prefs.edit().putBoolean("dark_theme_enabled", enabled).apply()
    }

    fun createNewConversation(title: String, agentId: String = activeAgentId.value) {
        val normalizedTitle = title.trim().ifBlank { "New conversation" }
        launchRepositoryAction("create conversation") {
            val id = repository.createNewConversation(normalizedTitle, agentId)
            activeConversationId.value = id
            activeAgentId.value = agentId
            activeTab.value = "chat"
        }
    }

    private val activeGenerationJobs = LinkedHashMap<String, Job>()
    private val conversationCreationMutex = Mutex()
    private var latestExternalGenerationId: String? = null

    fun sendMessage(
        prompt: String,
        explicitFileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        attachedMediaUris: String = "",
        mediaList: List<com.example.data.ai.model.AttachedMediaData> = emptyList()
    ) {
        if (prompt.isBlank() && imageInlineData.isNullOrBlank() && mediaList.isEmpty()) return

        val agentId = activeAgentId.value
        val model = selectedModel.value
        val fileContext = explicitFileContext?.takeIf { it.isNotBlank() }
            ?: activeFileContextForCurrentSelection()

        launchGeneration("send message") {
            val conversationId = ensureActiveConversation(
                title = prompt.trim().take(60).ifBlank { "New conversation" },
                agentId = agentId
            )

            repository.sendMessage(
                conversationId = conversationId,
                userPrompt = prompt,
                activeAgentId = agentId,
                selectedModel = model,
                fileContext = fileContext,
                imageInlineData = imageInlineData,
                mimeType = mimeType,
                attachedMediaUris = attachedMediaUris,
                mediaList = mediaList
            )
        }
    }

    fun editMessageAndResend(messageId: String, newPrompt: String) {
        val conversationId = activeConversationId.value ?: return
        val normalizedPrompt = newPrompt.trim()
        if (normalizedPrompt.isBlank()) return

        val agentId = activeAgentId.value
        val model = selectedModel.value
        val fileContext = activeFileContextForCurrentSelection()

        launchGeneration("regenerate edited message") {
            repository.editMessageAndRegenerate(
                conversationId,
                messageId,
                normalizedPrompt,
                agentId,
                model,
                fileContext
            )
        }
    }

    /**
     * Cancels one generation without interrupting independent work.
     */
    fun cancelGeneration(generationId: String) {
        activeGenerationJobs[generationId]?.cancel()
    }

    /**
     * Backwards-compatible global cancellation for explicit user stop/emergency use.
     */
    fun cancelActiveGeneration() {
        val jobsToCancel = activeGenerationJobs.values.toList()
        activeGenerationJobs.clear()
        latestExternalGenerationId = null
        updateGenerationState()
        jobsToCancel.forEach { it.cancel() }
        com.example.data.ai.AIManager.cancelActiveGeneration()
        com.example.data.core.WastiCore.cancelActiveGeneration()
        com.example.data.ai.AIManager.setActiveJob(null)
        com.example.data.core.WastiCore.setActiveJob(null)
    }

    private suspend fun ensureActiveConversation(title: String, agentId: String): String {
        activeConversationId.value?.let { return it }

        return conversationCreationMutex.withLock {
            activeConversationId.value ?: repository.createNewConversation(title, agentId).also {
                activeConversationId.value = it
                activeAgentId.value = agentId
                activeTab.value = "chat"
            }
        }
    }

    private fun activeFileContextForCurrentSelection(): String? =
        if (activeAgentId.value == "coding_agent" || activeTab.value == "code") {
            activeCodeContext.value.ifBlank { null }
        } else {
            null
        }

    private fun launchGeneration(
        operation: String,
        work: suspend () -> Unit
    ): String {
        val generationId = UUID.randomUUID().toString()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } catch (cancelled: CancellationException) {
                android.util.Log.i("WastiViewModel", "$operation cancelled: $generationId")
                throw cancelled
            } catch (error: Exception) {
                recordOperationError(operation, error)
            } finally {
                activeGenerationJobs.remove(generationId)
                updateGenerationState()

                if (latestExternalGenerationId == generationId) {
                    val replacement = activeGenerationJobs.entries.lastOrNull()
                    latestExternalGenerationId = replacement?.key
                    com.example.data.ai.AIManager.setActiveJob(replacement?.value)
                    com.example.data.core.WastiCore.setActiveJob(replacement?.value)
                }
            }
        }

        activeGenerationJobs[generationId] = job
        latestExternalGenerationId = generationId
        updateGenerationState()
        com.example.data.ai.AIManager.setActiveJob(job)
        com.example.data.core.WastiCore.setActiveJob(job)
        job.start()
        return generationId
    }

    private fun updateGenerationState() {
        val count = activeGenerationJobs.size
        _activeGenerationCount.value = count
        isGenerating.value = count > 0
    }

    private fun launchRepositoryAction(
        operation: String,
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordOperationError(operation, error)
            }
        }
    }

    private fun recordOperationError(operation: String, error: Exception) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        _lastOperationError.value = "$operation failed: $message"
        android.util.Log.e("WastiViewModel", "$operation failed", error)
    }

    fun addMemory(key: String, category: String, value: String) =
        launchRepositoryAction("add memory") {
            repository.addMemory(key, category, value)
        }

    fun updateMemory(id: String, key: String, category: String, value: String) =
        launchRepositoryAction("update memory") {
            repository.updateMemory(id, key, category, value)
        }

    fun deleteMemory(id: String) =
        launchRepositoryAction("delete memory") {
            repository.deleteMemory(id)
        }

    fun addKnowledge(title: String, category: String, content: String, tags: String) =
        launchRepositoryAction("add knowledge") {
            repository.addKnowledge(title, category, content, tags)
        }

    fun updateKnowledge(id: String, title: String, category: String, content: String, tags: String) =
        launchRepositoryAction("update knowledge") {
            repository.updateKnowledge(id, title, category, content, tags)
        }

    fun deleteKnowledge(id: String) =
        launchRepositoryAction("delete knowledge") {
            repository.deleteKnowledge(id)
        }

    fun deleteConversation(id: String) =
        launchRepositoryAction("delete conversation") {
            repository.deleteConversation(id)
        }

    fun addProject(name: String, description: String, priority: String) =
        launchRepositoryAction("add project") {
            repository.addProject(name, description, priority)
        }

    fun addTask(projectId: String, title: String, description: String, assignedAgentId: String, priority: String) =
        launchRepositoryAction("add task") {
            repository.addTask(projectId, title, description, assignedAgentId, priority)
        }

    fun toggleTaskStatus(taskId: String, currentStatus: Boolean) =
        launchRepositoryAction("update task status") {
            repository.toggleTaskStatus(taskId, currentStatus)
        }

    fun addAgent(name: String, roleTitle: String, agentType: String, systemInstruction: String, capabilities: String) =
        launchRepositoryAction("add agent") {
            repository.addAgent(name, roleTitle, agentType, systemInstruction, capabilities)
        }

    fun clearLogs() =
        launchRepositoryAction("clear logs") {
            repository.clearLogs()
        }

    fun saveXaiApiKey(apiKey: String, modelName: String = "grok-2-latest") =
        launchRepositoryAction("save xAI settings") {
            repository.saveAppSetting("xai_api_key", apiKey.trim())
            repository.saveAppSetting("xai_model_name", modelName.trim())
        }

    fun clearChatHistory() {
        val convId = activeConversationId.value ?: return
        launchRepositoryAction("clear chat history") {
            repository.clearChatHistory(convId)
        }
    }

    fun recordTerminalSession(
        command: String,
        output: String = "",
        stderr: String = "",
        workingDirectory: String = "home/wasti",
        status: String = "SUCCESS",
        exitCode: Int = 0,
        durationMs: Long = 0L,
        verified: Boolean = false,
        verificationEvidence: String? = null
    ) {
        viewModelScope.launch {
            try {
                repository.recordTerminalSession(
                    command = command,
                    output = output,
                    stderr = stderr,
                    workingDirectory = workingDirectory,
                    status = status,
                    exitCode = exitCode,
                    durationMs = durationMs,
                    verified = verified,
                    verificationEvidence = verificationEvidence
                )
            } catch (e: Exception) {
                // Non-fatal logging
            }
        }
    }

    fun clearTerminalHistory() {
        viewModelScope.launch {
            try {
                repository.clearTerminalHistory()
            } catch (e: Exception) {
                // Non-fatal
            }
        }
    }

    fun executeQuickCommand(command: String) {

        isCommandPaletteOpen.value = false
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        val normalized = trimmed.lowercase(Locale.ROOT)
        when {
            normalized.startsWith("open chat") || normalized.startsWith("chat") ->
                activeTab.value = "chat"
            normalized.startsWith("open memory") || normalized.startsWith("memory") ->
                activeTab.value = "memory"
            normalized.startsWith("open agents") || normalized.startsWith("agents") ->
                activeTab.value = "agents"
            normalized.startsWith("open projects") || normalized.startsWith("projects") ->
                activeTab.value = "projects"
            normalized.startsWith("open code") || normalized.startsWith("code") ->
                activeTab.value = "code"
            normalized.startsWith("open terminal") || normalized.startsWith("terminal") || normalized.startsWith("shell") ->
                activeTab.value = "terminal"
            normalized.startsWith("open integrations") || normalized.startsWith("integrations") ->
                activeTab.value = "integrations"
            normalized.startsWith("open settings") || normalized.startsWith("settings") ->
                activeTab.value = "settings"
            else -> {
                activeTab.value = "chat"
                sendMessage(trimmed)
            }
        }
    }
}

