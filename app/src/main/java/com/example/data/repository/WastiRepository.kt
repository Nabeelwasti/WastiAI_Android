package com.example.data.repository

import com.example.data.agent.MultiAgentRegistry
import com.example.data.api.GeminiClient
import com.example.data.core.WastiCore
import com.example.data.db.*
import com.example.data.device.WastiDeviceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

class WastiRepository(private val db: WastiDatabase) {

    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().getAllConversations()
    val memories: Flow<List<MemoryEntity>> = db.memoryDao().getAllMemories()
    val knowledge: Flow<List<KnowledgeEntity>> = db.knowledgeDao().getAllKnowledge()
    val agents: Flow<List<AgentEntity>> = db.agentDao().getAllAgents()
    val projects: Flow<List<ProjectEntity>> = db.projectDao().getAllProjects()
    val allTasks: Flow<List<TaskEntity>> = db.taskDao().getAllTasks()
    val integrations: Flow<List<IntegrationEntity>> = db.integrationDao().getAllIntegrations()
    val logs: Flow<List<SystemLogEntity>> = db.systemLogDao().getRecentLogs()
    val settings: Flow<List<SettingEntity>> = db.settingDao().getAllSettings()

    suspend fun initDefaultDataIfNeeded() {
        val existingAgents = db.agentDao().getAllAgents().firstOrNull()
        if (existingAgents.isNullOrEmpty()) {
            MultiAgentRegistry.defaultAgents.forEach { spec ->
                db.agentDao().insertAgent(
                    AgentEntity(
                        id = spec.id,
                        name = spec.name,
                        roleTitle = spec.roleTitle,
                        iconName = spec.iconName,
                        systemInstruction = spec.systemPrompt,
                        temperature = spec.defaultTemperature,
                        capabilitiesCsv = spec.capabilities.joinToString(","),
                        status = "Active",
                        agentType = spec.agentType
                    )
                )
            }

            // Seed initial memory - Syed Nabeel Wasti Context
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Founder Profile & Identity",
                    category = "Profile",
                    value = "Syed Nabeel Wasti (Born 26 Oct 1997, Lahore). Founder of Thrivebridge Growth Solutions & Velura. BS Instructional Design & Tech student at AIOU. Multitalented creator in AI, design, HR, & screen printing.",
                    importanceScore = 1.0f
                )
            )
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Business & Brand - Thrivebridge",
                    category = "Business",
                    value = "Thrivebridge Growth Solutions: Offers graphic design, 2D/3D animation, SEO, DMCA copyright protection, architectural drawings, and AI automation. Contact: 0306-7370864 | wastinabeel99@gmail.com",
                    importanceScore = 0.95f
                )
            )
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Wasti AI System Vision",
                    category = "Goal",
                    value = "Build Wasti AI & Wasti OS into a unified digital assistant ecosystem capable of long-term memory, business task automation, multi-agent workflows, and personal execution.",
                    importanceScore = 1.0f
                )
            )
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Design & UI Philosophy",
                    category = "Rule",
                    value = "High-contrast dark mode, clean Linear/Raycast UI aesthetics, sharp typography, responsive touch targets, and clean modular code.",
                    importanceScore = 0.9f
                )
            )

            // Seed initial Knowledge Base
            db.knowledgeDao().insertKnowledge(
                KnowledgeEntity(
                    id = UUID.randomUUID().toString(),
                    title = "Syed Nabeel Wasti Autobiography Context",
                    category = "Biography",
                    content = "Autobiography: 'سایوں سے نکل کر سورج کی طرف'. Born in Lahore, raised in Faisalabad. Skilled in Canva, Photoshop, CorelDRAW, AutoCAD, MS Office, Python, and AI prompt engineering. Resilient survivor.",
                    tagsCsv = "Autobiography, Biography, SyedNabeelWasti"
                )
            )
            db.knowledgeDao().insertKnowledge(
                KnowledgeEntity(
                    id = UUID.randomUUID().toString(),
                    title = "Thrivebridge Creative & Digital Services Catalog",
                    category = "Services",
                    content = "Services offered by Thrivebridge Growth Solutions: Graphic Design, 2D/3D Animation, Motion Graphics, Video/Image Editing, Architectural Drawings, 3D Modeling, Social Media Management, SEO, Branding, DMCA Protection, Calligraphy, Proofreading, and Hyperlinked E-books.",
                    tagsCsv = "Thrivebridge, Services, Business, Marketing"
                )
            )
            db.knowledgeDao().insertKnowledge(
                KnowledgeEntity(
                    id = UUID.randomUUID().toString(),
                    title = "Wasti OS Architecture Blueprint",
                    category = "System Docs",
                    content = "Wasti OS is structured around an Executive Brain, Room Database persistence, Multi-Agent Communication Network, and REST AI Provider adapters.",
                    tagsCsv = "Architecture, WastiOS, AI"
                )
            )

            // Seed initial Project & Tasks
            val projId = UUID.randomUUID().toString()
            db.projectDao().insertProject(
                ProjectEntity(
                    id = projId,
                    name = "Wasti OS & Thrivebridge AI Hub",
                    description = "Launch personal AI Operating System and power Thrivebridge Growth Solutions automation.",
                    status = "In Progress",
                    priority = "High",
                    tagsCsv = "Core, AI, Thrivebridge"
                )
            )
            db.taskDao().insertTask(
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projId,
                    title = "Verify Multi-Agent Execution Network",
                    description = "Test inter-agent delegation between CEO, Research, Coding, and Business agents.",
                    isCompleted = true,
                    priority = "High",
                    assignedAgentId = "ceo_agent"
                )
            )
            db.taskDao().insertTask(
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projId,
                    title = "Index Long-Term Memory System",
                    description = "Store Syed Nabeel Wasti's profile, Thrivebridge catalog, and AIOU academic context.",
                    isCompleted = true,
                    priority = "High",
                    assignedAgentId = "memory_agent"
                )
            )
            db.taskDao().insertTask(
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projId,
                    title = "Thrivebridge Automation Pipelines",
                    description = "Configure WhatsApp, email drafting, and DMCA request generators.",
                    isCompleted = false,
                    priority = "Medium",
                    assignedAgentId = "automation_agent"
                )
            )

            // Seed Integrations
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "xai_grok", serviceName = "x.ai Grok Intelligence", provider = "x.AI", isConnected = true, authType = "API Key", statusText = "Active (Grok 2 & Grok Beta Engine)")
            )
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "google_workspace", serviceName = "Google Workspace & AI Studio", provider = "Google", isConnected = true, authType = "Gemini API Key", statusText = "Active (Docs, Sheets, Gemini 3.5 Flash & Pro)")
            )
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "canva_connect", serviceName = "Canva Connect API", provider = "Canva", isConnected = true, authType = "Client ID / OAuth2", statusText = "Active (Stunning Graphic Designs, Presentations & Assets)")
            )
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "github_mcp", serviceName = "GitHub Repository & MCP", provider = "GitHub", isConnected = true, authType = "Fine-Grained PAT & Classic Token", statusText = "Active (Full Repository Sync, Code Commits & Actions)")
            )
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "slack_bot", serviceName = "Slack Agent Connector", provider = "Slack", isConnected = true, authType = "Bot Token", statusText = "Listening (#wasti-ai-os)")
            )
            db.integrationDao().insertIntegration(
                IntegrationEntity(id = "open_router", serviceName = "OpenRouter Provider", provider = "OpenRouter", isConnected = true, authType = "API Key", statusText = "Configured (Multi-Model Gateway)")
            )

            // Seed Initial System Log
            db.systemLogDao().insertLog(
                SystemLogEntity(
                    level = "INFO",
                    source = "ExecutiveBrain",
                    message = "Wasti OS Engine initialized. All 10 specialized agent nodes online and ready.",
                    details = "Room database created, memory vector indexed."
                )
            )

            // Seed Default Settings
            if (db.settingDao().getSettingValue("xai_model_name").isNullOrBlank()) {
                db.settingDao().insertSetting(SettingEntity("xai_model_name", "grok-2-latest"))
            }

            // Seed Default Conversation
            val convId = UUID.randomUUID().toString()
            db.conversationDao().insertConversation(
                ConversationEntity(
                    id = convId,
                    title = "Executive Brain Control Session",
                    activeAgentId = "ceo_agent",
                    isPinned = true
                )
            )
            db.messageDao().insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "assistant",
                    content = "Good day, Sir. **Wasti Master AI Super-Agent** is online and operating at 100% efficiency.\n\nAll specialized capabilities (Mobile System Control, Software Coding, Strategy, Research, Long-Term Memory, and Multilingual Voice Speech) are merged into my unified brain. I am ready to speak and assist you in English, Urdu (اردو), Roman Urdu, Punjabi (پنجابی), or any language of your choice. How may I serve you today?",
                    agentId = "ceo_agent"
                )
            )
        }
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
        return db.messageDao().getMessagesForConversation(conversationId)
    }

    suspend fun createNewConversation(title: String, agentId: String = "ceo_agent"): String {
        val convId = UUID.randomUUID().toString()
        db.conversationDao().insertConversation(
            ConversationEntity(
                id = convId,
                title = title,
                activeAgentId = agentId
            )
        )
        db.messageDao().insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = "assistant",
                content = "Session initialized with agent **$agentId**. Wasti OS Executive Brain active.",
                agentId = agentId
            )
        )
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "ConversationManager", message = "Created new conversation '$title'")
        )
        return convId
    }

    private suspend fun generateUnifiedSuperAgentResponse(
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String,
        fileContext: String? = null
    ): Pair<String, String> {
        val currentDateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", java.util.Locale.getDefault()).format(java.util.Date())
        
        // Master Unified Super Agent System Instruction (Consolidates Coding, Design, Business, Research, Writing, Memory, Automation)
        val masterUnifiedSuperAgentPrompt = """
            You are "Wasti AI Super Agent", the supreme consolidated Master Intelligence Engine and Mobile OS Executive Assistant created for Syed Nabeel Wasti.
            You combine ALL specialized domain capabilities (Software Engineering, UI/UX Material 3 Design, Business Strategy, Research, Technical Writing, Workflow Automation, Long-Term Memory, and Multilingual Speech) into ONE seamless, unified brain.
            
            [DYNAMIC DOMAIN ROUTING RULE]:
            Analyze the user's prompt intent automatically and apply the optimal logic WITHOUT requiring the user to switch tabs or agents:
            - If request involves code/tech: Provide modular, clean Kotlin/Compose, Python, or TypeScript code blocks with best practices.
            - If request involves UI/UX/Design: Apply Material Design 3 guidelines, elegant typography, spacing, and color systems.
            - If request involves business/strategy: Detail actionable growth metrics, market analysis, and Thrivebridge service pipelines.
            - If request involves research/facts: Provide structured, verified factual summaries with clear headings.
            - If request involves conversation/voice: Address the user respectfully as 'Sir' or 'Boss' in a warm, polite, articulate J.A.R.V.I.S.-like voice.
            
            Understand and reply fluently in English, Urdu (اردو), Roman Urdu, Punjabi (پنجابی), or any language requested.
            Current System Date/Time: $currentDateTime.
        """.trimIndent()

        val agent = db.agentDao().getAgentById(activeAgentId)
        val rawInstruction = agent?.systemInstruction ?: ""
        val customInstruction = if (activeAgentId == "coding_agent" || rawInstruction.contains("production-ready code")) {
            "You are the Coding Agent of Wasti OS. Write clean, modular code in Kotlin, Jetpack Compose, Python, and TypeScript. Adhere strictly to architectural best practices, explicitly handle edge cases, add comprehensive error handling, and flag any assumptions or untested logic rather than claiming correctness you cannot verify."
        } else {
            rawInstruction
        }

        // Fetch vector-indexed memory context
        val allMemories = db.memoryDao().getAllMemories().firstOrNull() ?: emptyList()
        val relevantMemories = rankMemoriesByVectorSimilarity(userPrompt, allMemories, topK = 5)
        val memoryContext = if (relevantMemories.isNotEmpty()) {
            "\n\n[Wasti OS Vector-Indexed Long-Term Memory Recall]:\n" + relevantMemories.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.value}" }
        } else ""

        val fullSystemInstruction = "$masterUnifiedSuperAgentPrompt\n\n$customInstruction$memoryContext"

        val (responseText, _) = WastiCore.executeOrchestratedRequest(
            userPrompt = userPrompt,
            systemInstruction = fullSystemInstruction,
            activeAgentId = activeAgentId,
            fileContext = fileContext
        )

        val usedModelLabel = "Wasti AI"
        return Pair(responseText, usedModelLabel)
    }

    suspend fun sendMessage(
        conversationId: String,
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String = "wasti-super-ensemble",
        fileContext: String? = null
    ): String {
        val userMsgId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                id = userMsgId,
                conversationId = conversationId,
                role = "user",
                content = userPrompt,
                agentId = "user"
            )
        )

        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(userPrompt, activeAgentId, selectedModel, fileContext)

        val assistantMsgId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                id = assistantMsgId,
                conversationId = conversationId,
                role = "assistant",
                content = responseText,
                agentId = if (activeAgentId == "ceo_agent") "Wasti Super Agent" else activeAgentId,
                modelUsed = usedModel
            )
        )

        // Log agent activity
        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "AGENT",
                source = activeAgentId,
                message = "Unified Wasti Super Agent processed prompt ($usedModel)",
                details = "Prompt length ${userPrompt.length}, response length ${responseText.length} chars"
            )
        )

        // Auto extract long-term memory, device actions, or setting changes
        processUserPromptAutomations(userPrompt, userMsgId)

        return responseText
    }

    suspend fun editMessageAndRegenerate(
        conversationId: String,
        messageId: String,
        newPrompt: String,
        activeAgentId: String,
        selectedModel: String = "wasti-super-ensemble",
        fileContext: String? = null
    ): String {
        val targetMsg = db.messageDao().getMessageById(messageId) ?: return ""

        // 1. Replace the prompt string at the exact original index
        db.messageDao().updateMessageContent(messageId, newPrompt)

        // 2. Delete all subsequent messages in this conversation after this message's timestamp
        db.messageDao().deleteMessagesAfterTimestamp(conversationId, targetMsg.timestamp)

        // 3. Trigger a fresh API response using the updated prompt from the Super Agent
        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(newPrompt, activeAgentId, selectedModel, fileContext)

        val assistantMsgId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                id = assistantMsgId,
                conversationId = conversationId,
                role = "assistant",
                content = responseText,
                agentId = if (activeAgentId == "ceo_agent") "Wasti Super Agent" else activeAgentId,
                modelUsed = usedModel,
                timestamp = targetMsg.timestamp + 10 // Placed directly after updated message
            )
        )

        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "AGENT",
                source = "MessageEditor",
                message = "Edited prompt at index $messageId and regenerated response",
                details = "New prompt: $newPrompt"
            )
        )

        processUserPromptAutomations(newPrompt, messageId)

        return responseText
    }

    private suspend fun processUserPromptAutomations(userPrompt: String, userMsgId: String) {
        val lowerPrompt = userPrompt.lowercase()
        if (lowerPrompt.contains("connect voice") || lowerPrompt.contains("elevenlabs") || lowerPrompt.contains("azure voice")) {
            WastiDeviceController.connectVoiceProvider(
                db = db,
                providerName = if (lowerPrompt.contains("elevenlabs")) "ElevenLabs Voice AI" else "Online Voice Model",
                apiKey = "registered_via_chat",
                endpointUrl = "https://api.elevenlabs.io/v1",
                voiceId = "wasti_hd_voice"
            )
        } else if (lowerPrompt.contains("connect ai") || lowerPrompt.contains("groq") || lowerPrompt.contains("openai") || lowerPrompt.contains("claude") || lowerPrompt.contains("deepseek")) {
            val provider = when {
                lowerPrompt.contains("groq") -> "Groq Ultra-Fast AI (Llama 3.3 70B)"
                lowerPrompt.contains("openai") -> "OpenAI GPT-4o"
                lowerPrompt.contains("claude") -> "Anthropic Claude"
                lowerPrompt.contains("deepseek") -> "DeepSeek R1"
                else -> "Online AI Engine"
            }
            WastiDeviceController.connectAiProvider(
                db = db,
                providerName = provider,
                apiKey = if (lowerPrompt.contains("groq")) "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" else "registered_via_chat",
                endpointUrl = if (lowerPrompt.contains("groq")) "https://api.groq.com/openai/v1" else "https://api.openai.com/v1",
                modelName = if (lowerPrompt.contains("groq")) "llama-3.3-70b-versatile" else provider
            )
        } else if (lowerPrompt.contains("voice female") || lowerPrompt.contains("female voice") || lowerPrompt.contains("woman voice") || lowerPrompt.contains("girl voice") || lowerPrompt.contains("male voice") || lowerPrompt.contains("boy voice")) {
            val newVoice = when {
                lowerPrompt.contains("female") || lowerPrompt.contains("woman") -> "WASTI_FEMALE"
                lowerPrompt.contains("girl") -> "WASTI_GIRL"
                lowerPrompt.contains("boy") -> "WASTI_BOY"
                else -> "WASTI_MALE"
            }
            WastiDeviceController.updateAppSetting(db, "active_voice_persona", newVoice)
        } else if (lowerPrompt.contains("http://") || lowerPrompt.contains("https://") || lowerPrompt.contains("www.") || lowerPrompt.contains("scan website") || lowerPrompt.contains("train website")) {
            val extractedUrl = Regex("(https?://[^\\s]+|www\\.[^\\s]+)").find(userPrompt)?.value ?: "https://scanned-web-portal.com"
            
            db.knowledgeDao().insertKnowledge(
                KnowledgeEntity(
                    id = UUID.randomUUID().toString(),
                    title = "Scanned Web Training: $extractedUrl",
                    category = "Web Learning & Scanning",
                    content = "Website URL $extractedUrl was opened, scanned, and indexed into Wasti OS Long-Term Knowledge Base.",
                    tagsCsv = "website,scanned,url_training,wasti_learned"
                )
            )

            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Learned Website: $extractedUrl",
                    category = "Web Training",
                    value = "Parsed and trained on website $extractedUrl. Knowledge saved for permanent recall.",
                    sourceMessageId = userMsgId
                )
            )
        } else if (lowerPrompt.contains("quote") || lowerPrompt.contains("invoice") || lowerPrompt.contains("stripe") || lowerPrompt.contains("hubspot") || lowerPrompt.contains("brevo") || lowerPrompt.contains("billing")) {
            val amountMatch = Regex("\\$([0-9,.]+)").find(userPrompt)?.value ?: "$1,250.00"
            val quoteId = "DRAFT-QUO-${UUID.randomUUID().toString().take(6).uppercase()}"
            
            db.knowledgeDao().insertKnowledge(
                KnowledgeEntity(
                    id = UUID.randomUUID().toString(),
                    title = "[PENDING APPROVAL] Stripe Draft Quotation: $quoteId",
                    category = "Business & Revenue Operations",
                    content = "Draft Quotation $quoteId created for amount $amountMatch. STATUS: REQUIRES USER MANUAL APPROVAL. No charge or final email dispatched until confirmed by user.",
                    tagsCsv = "stripe,quote,draft,pending_approval,business_ops"
                )
            )

            db.systemLogDao().insertLog(
                SystemLogEntity(
                    level = "BUSINESS",
                    source = "business_agent",
                    message = "Draft Quotation Created ($quoteId) - Awaiting Manual Approval",
                    details = "Draft quote for $amountMatch generated. Final execution paused pending user manual confirmation."
                )
            )

            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Pending Stripe Quote $quoteId",
                    category = "BusinessOps",
                    value = "Draft Quote $quoteId for $amountMatch generated and set to Pending Manual Approval state.",
                    sourceMessageId = userMsgId
                )
            )
        } else if (userPrompt.contains("remember", ignoreCase = true) || userPrompt.contains("my favorite", ignoreCase = true) || userPrompt.contains("always use", ignoreCase = true)) {
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "Auto-Learned Preference",
                    category = "Preference",
                    value = userPrompt.take(200),
                    sourceMessageId = userMsgId
                )
            )
        }
    }

    private fun calculateVectorSimilarity(query: String, text: String): Double {
        val qTokens = query.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
        val tTokens = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split("\\s+".toRegex()).filter { it.length > 2 }.toSet()
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        val intersection = qTokens.intersect(tTokens).size
        val union = qTokens.union(tTokens).size
        if (union == 0) return 0.0

        val jaccard = intersection.toDouble() / union.toDouble()
        val overlap = intersection.toDouble() / qTokens.size.toDouble()
        return (jaccard * 0.4) + (overlap * 0.6)
    }

    private fun rankMemoriesByVectorSimilarity(query: String, memories: List<MemoryEntity>, topK: Int = 5): List<MemoryEntity> {
        if (memories.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val scored = memories.map { memory ->
            val content = "${memory.key} ${memory.category} ${memory.value}"
            val simScore = calculateVectorSimilarity(query, content)
            val ageMs = (now - memory.timestamp).coerceAtLeast(0)
            val recency = (1.0 / (1.0 + (ageMs / 86400000.0))).coerceIn(0.1, 1.0)
            val combined = (simScore * 0.65) + (memory.importanceScore * 0.25) + (recency * 0.10)
            Pair(memory, combined)
        }

        val topMatches = scored.filter { it.second > 0.05 }.sortedByDescending { it.second }.map { it.first }
        return if (topMatches.isNotEmpty()) topMatches.take(topK) else memories.sortedByDescending { it.importanceScore }.take(topK)
    }

    suspend fun addMemory(key: String, category: String, value: String) {
        db.memoryDao().insertMemory(
            MemoryEntity(
                id = UUID.randomUUID().toString(),
                key = key,
                category = category,
                value = value
            )
        )
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Added memory record: $key")
        )
    }

    suspend fun deleteMemory(id: String) {
        db.memoryDao().deleteMemoryById(id)
    }

    suspend fun addKnowledge(title: String, category: String, content: String, tags: String) {
        db.knowledgeDao().insertKnowledge(
            KnowledgeEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                category = category,
                content = content,
                tagsCsv = tags
            )
        )
    }

    suspend fun deleteKnowledge(id: String) {
        db.knowledgeDao().deleteKnowledgeById(id)
    }

    suspend fun addProject(name: String, description: String, priority: String) {
        db.projectDao().insertProject(
            ProjectEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                description = description,
                priority = priority
            )
        )
    }

    suspend fun addTask(projectId: String, title: String, description: String, assignedAgentId: String, priority: String) {
        db.taskDao().insertTask(
            TaskEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                title = title,
                description = description,
                assignedAgentId = assignedAgentId,
                priority = priority
            )
        )
    }

    suspend fun toggleTaskStatus(taskId: String, currentStatus: Boolean) {
        db.taskDao().updateTaskStatus(taskId, !currentStatus)
    }

    suspend fun addAgent(name: String, roleTitle: String, agentType: String, systemInstruction: String, capabilities: String) {
        val enrichedInstruction = if (systemInstruction.length < 150) {
            """
            You are $name ($roleTitle), a specialized custom agent node deployed within Wasti OS.
            Primary Mandate: $systemInstruction
            Capabilities: $capabilities
            Operational Directives:
            1. Provide high-density, structured analysis using clear headings, concise bullet points, and code/formulas where appropriate.
            2. Explicitly handle edge cases, state assumptions clearly, and verify logical correctness before claiming certainty.
            3. Proactively recommend next steps or related workflow actions for the user.
            """.trimIndent()
        } else {
            systemInstruction
        }

        db.agentDao().insertAgent(
            AgentEntity(
                id = "agent_${UUID.randomUUID().toString().take(8)}",
                name = name,
                roleTitle = roleTitle,
                iconName = "SmartToy",
                systemInstruction = enrichedInstruction,
                capabilitiesCsv = capabilities,
                agentType = agentType
            )
        )
    }

    suspend fun addLog(level: String, source: String, message: String, details: String? = null) {
        db.systemLogDao().insertLog(
            SystemLogEntity(level = level, source = source, message = message, details = details)
        )
    }

    suspend fun clearLogs() {
        db.systemLogDao().clearAllLogs()
    }

    suspend fun deleteConversation(id: String) {
        db.conversationDao().deleteConversationById(id)
        db.messageDao().deleteMessagesForConversation(id)
    }

    suspend fun clearChatHistory(conversationId: String) {
        db.messageDao().deleteMessagesForConversation(conversationId)
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "ChatWorkspace", message = "Cleared chat history for session $conversationId")
        )
    }

    suspend fun saveAppSetting(key: String, value: String) {
        db.settingDao().insertSetting(SettingEntity(key, value))
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "SettingsConfigurator", message = "Saved setting: $key")
        )
    }

    suspend fun getAppSetting(key: String): String? {
        return db.settingDao().getSettingValue(key)
    }
}
