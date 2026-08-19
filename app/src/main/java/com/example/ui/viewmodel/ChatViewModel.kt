package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.agent.runtime.AgentEvent
import com.example.data.ai.model.AttachedMediaData
import com.example.data.db.ConversationEntity
import com.example.data.db.MessageEntity
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Specialized ViewModel for Chat and Conversation orchestration.
 * Directly listens to AgentEventBus for live thinking and execution status.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WastiServiceLocator.repository
    private val eventBus = WastiServiceLocator.agentEventBus
    private val prefs = application.getSharedPreferences("wasti_prefs", android.content.Context.MODE_PRIVATE)

    val activeConversationId = MutableStateFlow<String?>(null)
    val activeAgentId = MutableStateFlow("ceo_agent")
    val isGenerating = MutableStateFlow(false)
    val selectedModel = MutableStateFlow(
        prefs.getString("selected_model", "groq-llama-3.3-70b") ?: "groq-llama-3.3-70b"
    )

    val activeCodeContext = MutableStateFlow("fun main() {\n    println(\"Wasti OS Code Engine\")\n}")
    val activeFileName = MutableStateFlow("WorkspaceCode.kt")

    private val _activeGenerationCount = MutableStateFlow(0)
    val activeGenerationCount: StateFlow<Int> = _activeGenerationCount.asStateFlow()

    private val _lastOperationError = MutableStateFlow<String?>(null)
    val lastOperationError: StateFlow<String?> = _lastOperationError.asStateFlow()

    // Live agent event stream from the AgentEventBus
    private val _liveAgentEvents = MutableStateFlow<List<AgentEvent>>(emptyList())
    val liveAgentEvents: StateFlow<List<AgentEvent>> = _liveAgentEvents.asStateFlow()

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

    private val activeGenerationJobs = LinkedHashMap<String, Job>()
    private val conversationCreationMutex = Mutex()
    private var latestExternalGenerationId: String? = null

    init {
        viewModelScope.launch {
            try {
                repository.initDefaultDataIfNeeded()
                val firstConv = repository.conversations.firstOrNull()?.firstOrNull()
                if (firstConv != null) {
                    activeConversationId.value = firstConv.id
                    activeAgentId.value = firstConv.activeAgentId
                }
            } catch (e: Exception) {
                _lastOperationError.value = "Init error: ${e.message}"
            }
        }

        // Subscribe to AgentEventBus to mirror thinking and execution in chat
        viewModelScope.launch {
            eventBus.events.collect { event ->
                val current = _liveAgentEvents.value.toMutableList()
                current.add(event)
                if (current.size > 100) current.removeAt(0)
                _liveAgentEvents.value = current
            }
        }
    }

    fun selectConversation(id: String) {
        activeConversationId.value = id
    }

    fun selectAgent(id: String) {
        activeAgentId.value = id
    }

    fun setSelectedModel(modelKey: String) {
        val normalized = modelKey.trim()
        if (normalized.isNotBlank()) {
            selectedModel.value = normalized
            prefs.edit().putString("selected_model", normalized).apply()
        }
    }

    fun setActiveCodeContext(code: String, fileName: String = "WorkspaceCode.kt") {
        activeCodeContext.value = code
        activeFileName.value = fileName
    }

    fun clearOperationError() {
        _lastOperationError.value = null
    }

    fun createNewConversation(title: String, agentId: String = activeAgentId.value) {
        val normalizedTitle = title.trim().ifBlank { "New conversation" }
        viewModelScope.launch {
            try {
                val id = repository.createNewConversation(normalizedTitle, agentId)
                activeConversationId.value = id
                activeAgentId.value = agentId
            } catch (e: Exception) {
                _lastOperationError.value = "Create conversation failed: ${e.message}"
            }
        }
    }

    fun sendMessage(
        prompt: String,
        explicitFileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        attachedMediaUris: String = "",
        mediaList: List<AttachedMediaData> = emptyList()
    ) {
        if (prompt.isBlank() && imageInlineData.isNullOrBlank() && mediaList.isEmpty()) return

        val agentId = activeAgentId.value
        val model = selectedModel.value
        val fileContext = explicitFileContext?.takeIf { it.isNotBlank() }
            ?: (if (activeAgentId.value == "coding_agent") activeCodeContext.value.ifBlank { null } else null)

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
        val fileContext = if (activeAgentId.value == "coding_agent") activeCodeContext.value.ifBlank { null } else null

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

    fun cancelActiveGeneration() {
        val jobsToCancel = activeGenerationJobs.values.toList()
        activeGenerationJobs.clear()
        latestExternalGenerationId = null
        updateGenerationState()
        jobsToCancel.forEach { it.cancel() }
        com.example.data.ai.AIManager.cancelActiveGeneration()
        com.example.data.core.WastiCore.cancelActiveGeneration()
    }

    fun clearChatHistory() {
        val convId = activeConversationId.value ?: return
        viewModelScope.launch {
            try {
                repository.clearChatHistory(convId)
            } catch (e: Exception) {
                _lastOperationError.value = "Clear chat history failed: ${e.message}"
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteConversation(id)
                if (activeConversationId.value == id) {
                    val remaining = repository.conversations.firstOrNull()?.firstOrNull()
                    activeConversationId.value = remaining?.id
                }
            } catch (e: Exception) {
                _lastOperationError.value = "Delete conversation failed: ${e.message}"
            }
        }
    }

    private suspend fun ensureActiveConversation(title: String, agentId: String): String {
        activeConversationId.value?.let { return it }

        return conversationCreationMutex.withLock {
            activeConversationId.value ?: repository.createNewConversation(title, agentId).also {
                activeConversationId.value = it
                activeAgentId.value = agentId
            }
        }
    }

    private fun launchGeneration(operation: String, work: suspend () -> Unit): String {
        val generationId = UUID.randomUUID().toString()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _lastOperationError.value = "$operation failed: ${error.message}"
            } finally {
                activeGenerationJobs.remove(generationId)
                updateGenerationState()
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
}
