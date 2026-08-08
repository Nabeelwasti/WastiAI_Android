package com.example.data.ai

import com.example.data.ai.engine.CapabilityRegistry
import com.example.data.ai.engine.ConversationCoordinator
import com.example.data.ai.engine.CostTracker
import com.example.data.ai.engine.HealthMonitor
import com.example.data.ai.engine.ProviderRouter
import com.example.data.ai.engine.RetryManager
import com.example.data.ai.engine.StreamingEngine
import com.example.data.ai.engine.TokenUsageTracker
import com.example.data.ai.engine.ToolCallingEngine
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderHealth
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.ai.model.UsageStats
import com.example.data.ai.provider.DeepSeekProvider
import com.example.data.ai.provider.GeminiProvider
import com.example.data.ai.provider.GroqProvider
import com.example.data.ai.provider.OfflineProvider
import com.example.data.ai.provider.OpenAIProvider
import com.example.data.ai.provider.OpenRouterProvider
import com.example.data.ai.provider.XAIProvider
import com.example.data.api.GeminiContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

object AIManager {

    val capabilityRegistry = CapabilityRegistry()
    val healthMonitor = HealthMonitor()
    val tokenUsageTracker = TokenUsageTracker()
    val costTracker = CostTracker(tokenUsageTracker)
    val retryManager = RetryManager()
    val streamingEngine = StreamingEngine()
    val toolCallingEngine = ToolCallingEngine()
    val conversationCoordinator = ConversationCoordinator()

    val providerRouter = ProviderRouter(
        capabilityRegistry = capabilityRegistry,
        healthMonitor = healthMonitor,
        tokenTracker = tokenUsageTracker,
        costTracker = costTracker,
        retryManager = retryManager
    )

    init {
        // Register default providers
        val gemini = GeminiProvider()
        val groq = GroqProvider()
        val openAi = OpenAIProvider()
        val xai = XAIProvider()
        val deepSeek = DeepSeekProvider()
        val openRouter = OpenRouterProvider()
        val offline = OfflineProvider()

        capabilityRegistry.registerProvider(gemini)
        capabilityRegistry.registerProvider(groq)
        capabilityRegistry.registerProvider(openAi)
        capabilityRegistry.registerProvider(xai)
        capabilityRegistry.registerProvider(deepSeek)
        capabilityRegistry.registerProvider(openRouter)
        capabilityRegistry.registerProvider(offline)

        // Initialize health records
        healthMonitor.initializeProvider(gemini.id, gemini.name)
        healthMonitor.initializeProvider(groq.id, groq.name)
        healthMonitor.initializeProvider(openAi.id, openAi.name)
        healthMonitor.initializeProvider(xai.id, xai.name)
        healthMonitor.initializeProvider(deepSeek.id, deepSeek.name)
        healthMonitor.initializeProvider(openRouter.id, openRouter.name)
        healthMonitor.initializeProvider(offline.id, offline.name)
    }

    suspend fun execute(
        prompt: String,
        systemInstruction: String = "CRITICAL DYNAMIC MULTI-TURN LANGUAGE MANDATE: You MUST reply in the EXACT SAME language, dialect, and script used in the LATEST user prompt. The user may dynamically change languages from message to message in the same chat. IGNORE the language used in previous conversation history or past assistant turns.",
        history: List<GeminiContent> = emptyList(),
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        fileContext: String? = null,
        preferredProviderId: String? = null,
        requiredCapabilities: Set<ProviderCapability> = setOf(ProviderCapability.TEXT_GENERATION)
    ): ProviderResponse {

        val formattedHistory = conversationCoordinator.formatHistoryTranscript(history)
        val dynamicLanguageMandate = "\n\nCRITICAL DYNAMIC MULTI-TURN LANGUAGE MANDATE:\nYou MUST reply in the EXACT SAME language, script, and dialect as the LATEST USER PROMPT. The user may switch languages dynamically from message to message in the same chat. IGNORE the language used in previous conversation history or past assistant turns. If the latest prompt is in English -> Reply strictly 100% in English! If in Urdu script (اردو) -> Reply strictly 100% in Urdu script! If in Roman Urdu -> Reply in Roman Urdu! If in Spanish, French, German, Arabic, Punjabi, Hindi, or any other language -> Reply strictly 100% in that exact language! NEVER default to Roman Urdu or any other language unless the latest user prompt itself is in that language!"
        val enrichedSystemPrompt = "$systemInstruction$formattedHistory$dynamicLanguageMandate"
        val enrichedPrompt = conversationCoordinator.enrichPromptWithContext(
            userPrompt = prompt,
            fileContext = fileContext
        )

        val req = ProviderRequest(
            prompt = enrichedPrompt,
            systemInstruction = enrichedSystemPrompt,
            history = history,
            imageInlineData = imageInlineData,
            mimeType = mimeType,
            requiredCapabilities = requiredCapabilities
        )

        return providerRouter.routeAndExecute(req, preferredProviderId)
    }

    suspend fun stream(
        prompt: String,
        systemInstruction: String = "You are Wasti OS, an advanced AI Operating System.",
        preferredProviderId: String? = "gemini"
    ): Flow<String> {
        val provider = capabilityRegistry.getProvider(preferredProviderId ?: "gemini")
            ?: capabilityRegistry.getProvider("offline")!!
        val req = ProviderRequest(
            prompt = prompt,
            systemInstruction = systemInstruction
        )
        return streamingEngine.streamFromProvider(provider, req)
    }

    @Volatile
    private var activeJob: kotlinx.coroutines.Job? = null

    fun setActiveJob(job: kotlinx.coroutines.Job?) {
        activeJob = job
    }

    fun cancelActiveGeneration() {
        activeJob?.cancel()
        activeJob = null
    }

    fun getHealthState(): StateFlow<Map<String, ProviderHealth>> = healthMonitor.healthFlow

    fun getUsageState(): StateFlow<Map<String, UsageStats>> = tokenUsageTracker.usageFlow

    fun getDailyCostState(): StateFlow<Double> = costTracker.dailyCostFlow
}
