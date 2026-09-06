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
import com.example.data.ai.model.ToolCallDefinition
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

        // Register all local open-source brain providers (100% Local / Free / Offline / Zero-API-Key)
        com.example.data.ai.engine.UnifiedBrain.getAllLocalProviders().forEach { localProvider ->
            capabilityRegistry.registerProvider(localProvider)
            healthMonitor.initializeProvider(localProvider.id, localProvider.name)
        }

        // Register core tools into ToolCallingEngine for LLM tool invocation
        registerCoreToolsInCallingEngine()
    }

    suspend fun execute(
        prompt: String,
        systemInstruction: String = "CRITICAL DYNAMIC MULTI-TURN LANGUAGE MANDATE: You MUST reply in the EXACT SAME language, dialect, and script used in the LATEST user prompt. The user may dynamically change languages from message to message in the same chat. IGNORE the language used in previous conversation history or past assistant turns.",
        history: List<GeminiContent> = emptyList(),
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        mediaList: List<com.example.data.ai.model.AttachedMediaData> = emptyList(),
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
            mediaList = mediaList,
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

    private fun registerCoreToolsInCallingEngine() {
        for (tool in com.example.data.tool.ToolRegistry.getAllTools()) {
            toolCallingEngine.registerTool(
                ToolCallDefinition(
                    name = tool.id,
                    description = tool.description,
                    parametersJsonSchema = tool.parametersJsonSchema
                )
            ) { paramsJson ->
                val paramsMap = try {
                    val obj = org.json.JSONObject(paramsJson)
                    val map = mutableMapOf<String, Any>()
                    obj.keys().forEach { k -> map[k] = obj.get(k) }
                    map
                } catch (_: Exception) {
                    emptyMap<String, Any>()
                }
                com.example.data.tool.ToolRegistry.executeTool(tool.id, paramsMap)
            }
        }

        // Core workspace file and execution tools routed through UnifiedExecutionFabric
        toolCallingEngine.registerTool(
            ToolCallDefinition(
                name = "read_file",
                description = "Reads utf-8 text file content safely within the workspace boundary.",
                parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            )
        ) { paramsJson ->
            val path = try { org.json.JSONObject(paramsJson).optString("path", "") } catch (_: Exception) { "" }
            val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
                capabilityId = "read_file",
                parameters = mapOf("path" to path)
            )
            com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req).output
        }

        toolCallingEngine.registerTool(
            ToolCallDefinition(
                name = "write_file",
                description = "Writes utf-8 text content to a file safely within the workspace boundary.",
                parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""
            )
        ) { paramsJson ->
            val (path, content) = try {
                val obj = org.json.JSONObject(paramsJson)
                Pair(obj.optString("path", ""), obj.optString("content", ""))
            } catch (_: Exception) {
                Pair("", "")
            }
            val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
                capabilityId = "write_file",
                parameters = mapOf("path" to path, "content" to content)
            )
            com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req).output
        }

        toolCallingEngine.registerTool(
            ToolCallDefinition(
                name = "list_files",
                description = "Lists files and subdirectories in a workspace path safely.",
                parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":[]}"""
            )
        ) { paramsJson ->
            val path = try { org.json.JSONObject(paramsJson).optString("path", ".") } catch (_: Exception) { "." }
            val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
                capabilityId = "list_files",
                parameters = mapOf("path" to path)
            )
            com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req).output
        }

        toolCallingEngine.registerTool(
            ToolCallDefinition(
                name = "execute_code",
                description = "Executes structured code/binaries through sandboxed execution within workspace boundaries.",
                parametersJsonSchema = """{"type":"object","properties":{"executable":{"type":"string"},"arguments":{"type":"array","items":{"type":"string"}},"workingDirectory":{"type":"string"},"language":{"type":"string"}},"required":["executable"]}"""
            )
        ) { paramsJson ->
            val paramsMap = try {
                val obj = org.json.JSONObject(paramsJson)
                val map = mutableMapOf<String, Any>()
                obj.keys().forEach { k -> map[k] = obj.get(k) }
                map
            } catch (_: Exception) {
                emptyMap<String, Any>()
            }
            val req = com.example.data.agent.runtime.UnifiedExecutionRequest(
                capabilityId = "execute_code",
                parameters = paramsMap
            )
            com.example.data.agent.runtime.UnifiedExecutionFabric.instance.execute(req).output
        }
    }
}
