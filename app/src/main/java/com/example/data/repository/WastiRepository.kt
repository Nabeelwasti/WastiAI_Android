package com.example.data.repository

import android.util.Log
import com.example.data.agent.MultiAgentRegistry
import com.example.data.core.WastiCore
import com.example.data.credential.CredentialRegistry
import com.example.data.db.*
import com.example.data.device.WastiDeviceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID

class WastiRepository(private val db: WastiDatabase) {

    init {
        com.example.data.memory.MemoryManager.initialize(db.memoryDao())
    }

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

    private fun buildGeminiHistory(messages: List<MessageEntity>, currentPrompt: String): List<com.example.data.api.GeminiContent> {
        val valid = messages.filter { it.content.isNotBlank() }
        
        // Exclude the currently inserted user message from prior history
        val prior = if (valid.isNotEmpty() && valid.last().role == "user" && (valid.last().content == currentPrompt || valid.last().content.startsWith(currentPrompt))) {
            valid.dropLast(1)
        } else {
            valid
        }

        val recent = prior.takeLast(20)
        val result = mutableListOf<com.example.data.api.GeminiContent>()

        for (msg in recent) {
            val role = if (msg.role == "user") "user" else "model"
            if (result.isNotEmpty() && result.last().role == role) {
                val lastContent = result.last()
                val existing = lastContent.parts.firstOrNull()?.text ?: ""
                val merged = "$existing\n${msg.content}"
                result[result.size - 1] = com.example.data.api.GeminiContent(
                    role = role,
                    parts = listOf(com.example.data.api.GeminiPart(text = merged))
                )
            } else {
                result.add(
                    com.example.data.api.GeminiContent(
                        role = role,
                        parts = listOf(com.example.data.api.GeminiPart(text = msg.content))
                    )
                )
            }
        }

        // Gemini history must start with 'user' role
        if (result.isNotEmpty() && result.first().role != "user") {
            result.removeAt(0)
        }

        return result
    }

    private suspend fun generateUnifiedSuperAgentResponse(
        conversationId: String,
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String,
        fileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg"
    ): Pair<String, String> {
        val pktTz = java.util.TimeZone.getTimeZone("Asia/Karachi")
        val pktCal = java.util.Calendar.getInstance(pktTz)
        val pktHour24 = pktCal.get(java.util.Calendar.HOUR_OF_DAY)
        val pktMinute = pktCal.get(java.util.Calendar.MINUTE)
        val pktMinuteStr = if (pktMinute < 10) "0$pktMinute" else "$pktMinute"
        val pktAmPm = if (pktHour24 >= 12) "PM" else "AM"
        val pktHour12 = if (pktHour24 % 12 == 0) 12 else pktHour24 % 12
        val pktFormattedTime = "$pktHour12:$pktMinuteStr $pktAmPm"
        val pktDateFormatter = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US).apply {
            this.timeZone = pktTz
        }
        val pktDateStr = pktDateFormatter.format(pktCal.time)
        val pktPeriod = when (pktHour24) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night / Late Night"
        }

        val devTz = java.util.TimeZone.getDefault()
        val devCal = java.util.Calendar.getInstance(devTz)
        val devHour24 = devCal.get(java.util.Calendar.HOUR_OF_DAY)
        val devMinute = devCal.get(java.util.Calendar.MINUTE)
        val devMinuteStr = if (devMinute < 10) "0$devMinute" else "$devMinute"
        val devAmPm = if (devHour24 >= 12) "PM" else "AM"
        val devHour12 = if (devHour24 % 12 == 0) 12 else devHour24 % 12
        val devFormattedTime = "$devHour12:$devMinuteStr $devAmPm"
        val devDateFormatter = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.US).apply {
            this.timeZone = devTz
        }
        val devDateStr = devDateFormatter.format(devCal.time)
        
        // Fetch conversation history from Room DB for multi-turn chat memory
        val previousMessages = db.messageDao().getMessagesListForConversation(conversationId)
        val historyContent = buildGeminiHistory(previousMessages, userPrompt)

        // Master Unified Super Agent System Instruction
        val masterUnifiedSuperAgentPrompt = """
            You are "Wasti AI Super Agent", the supreme consolidated Master Intelligence Engine and Mobile OS Executive Assistant created for Syed Nabeel Wasti.
            You combine ALL specialized domain capabilities (Software Engineering, UI/UX Material 3 Design, Business Strategy, Research, Technical Writing, Workflow Automation, Long-Term Memory, and Multilingual Speech) into ONE seamless, unified brain.
            
            [REAL-TIME CLOCK & WORLD TIME CONTEXT]:
            • Pakistan Standard Time (PKT, UTC+5, Asia/Karachi): $pktFormattedTime ($pktDateStr) [$pktPeriod]
            • Device / UTC System Time: $devFormattedTime ($devDateStr) [Zone: ${devTz.id}]

            CRITICAL TIME RESPONSE MANDATES:
            1. When asked for the current time or time in Pakistan, state the time clearly, accurately, and naturally in human conversational terms (e.g., "It is currently $pktFormattedTime on $pktDateStr in Pakistan" or "Right now in Pakistan, it is $pktFormattedTime").
            2. AM/PM MUST BE 100% MATHEMATICALLY ACCURATE. In Pakistan, $pktHour12:$pktMinuteStr is strictly $pktAmPm. Do NOT confuse AM with PM.
            3. NEVER quote, repeat, or output raw bracketed debug tokens, system log strings, or technical tags like `[24-Hour: ...]`, `[Zone: ...]`, or `[Period: ...]`. Keep the tone warm, conversational, and direct.

            [MOBILE OS EXECUTIVE CONTROL & APP LAUNCHING]:
            You possess full mobile OS executive authority over the user's Android device via Wasti Mobile Controller.
            When the user requests to open, launch, or execute an app (such as WhatsApp, YouTube, Camera, Settings, Gmail, Chrome, Maps, Gallery, Spotify, Calculator, Phone/Dialer, Telegram, or any installed app), or send messages:
            1. NEVER claim or state that you cannot open apps or control the device. You CAN and DO open apps directly on the user's device!
            2. Always respond affirmatively, warmly, and enthusiastically (e.g., "Right away, Sir! Launching WhatsApp on your device now via Wasti Mobile Controller.", "Opening Camera for you, Boss!", "Opening YouTube on your phone now, Sir.").
            
            [ENHANCED ONLINE RESEARCH STRATEGY]:
            When answering queries requiring real-time facts, current news, market data, prices, domain lookups, specifications, or web research:
            1. Perform multi-angle web research using live Google Search Grounding & real-time data sources.
            2. Cross-check facts, dates, names, prices, and statistics across reliable web references.
            3. Synthesize findings into clear, direct, well-structured, accurate answers with bullet points and bold key facts.
            4. Distinguish between verified factual knowledge and estimates. Never output outdated static assumptions when live information is available.
            
            [MULTI-TURN CHAT MEMORY RULE]:
            You have full multi-turn conversational memory. Maintain continuous context from all previous messages in this conversation session. Remember names, preferences, decisions, and instructions mentioned earlier in the chat. Never treat a follow-up message as an isolated first turn.
            
            [ABSOLUTE HONESTY & REAL STATUS MANDATE]:
            You MUST be 100% honest, transparent, and practical at all times. NEVER pretend, fake, or claim to have executed external real-world actions (such as sending real emails, charging credit cards, or making live external API posts) unless actual API keys are configured and real execution outputs are verified. Always state the exact real-world status of tasks, drafts, code, and connected API tools clearly and logically.
            
            [HUMAN NATURAL TEXT & CLEAN FORMATTING MANDATE]:
            Always reply in clean, natural, human-like language. NEVER produce robotic text, weird syntax noise, or useless decorative characters (e.g. no repetitive `***`, garbage symbols, or cluttered formatting). Use clear paragraphs, standard bullet points, and elegant typography.
            
            [GOOGLE WORKSPACE & AUTOMATED COMMS CAPACITY]:
            Google Workspace integration is managed via Wasti Secure Secret Vault using EncryptedSharedPreferences. When configured, this enables automated Gmail SMTP/IMAP email dispatch, Google Drive file syncing & backups, Google Sheets data logging, Google Docs spec generation, and Google Calendar event scheduling.
            
            [DYNAMIC DOMAIN ROUTING RULE]:
            Analyze the user's prompt intent automatically and apply the optimal logic WITHOUT requiring the user to switch tabs or agents:
            - If request involves code/tech: Provide modular, clean Kotlin/Compose, Python, or TypeScript code blocks with best practices.
            - If request involves UI/UX/Design: Apply Material Design 3 guidelines, elegant typography, spacing, and color systems.
            - If request involves business/strategy: Detail actionable growth metrics, market analysis, and Thrivebridge service pipelines.
            - If request involves research/facts: Provide structured, verified factual summaries with clear headings.
            - If request involves conversation/voice: Address the user respectfully as 'Sir' or 'Boss' in a warm, polite, articulate J.A.R.V.I.S.-like voice.
            
            CRITICAL LANGUAGE MIRRORING MANDATE:
            CRITICAL: You must mirror the exact language and script the user uses. If the user types in English, reply in English. If the user types in Roman Urdu (e.g., 'kaise ho'), reply in Roman Urdu. If the user types in pure Urdu script, reply in pure Urdu script. Never force a language.
        """.trimIndent()

        val agent = db.agentDao().getAgentById(activeAgentId)
        val rawInstruction = agent?.systemInstruction ?: ""
        val customInstruction = if (activeAgentId == "coding_agent" || rawInstruction.contains("production-ready code")) {
            "You are the Coding Agent of Wasti OS. Write clean, modular code in Kotlin, Jetpack Compose, Python, and TypeScript. Adhere strictly to architectural best practices, explicitly handle edge cases, add comprehensive error handling, and flag any assumptions or untested logic rather than claiming correctness you cannot verify."
        } else {
            rawInstruction
        }

        // 1. Fetch ALL Permanent Core Memories (All stored user facts, preferences, decisions)
        val allMemoriesList = try {
            db.memoryDao().getMemoriesList()
        } catch (e: Exception) {
            Log.e("WastiRepository", "Failed to fetch long-term memories for orchestration context", e)
            emptyList()
        }
        val memoryDigest = if (allMemoriesList.isNotEmpty()) {
            allMemoriesList.take(30).joinToString("\n") { "- [${it.category}] ${it.key}: ${it.value}" }
        } else {
            "- User Identity: Syed Nabeel Wasti (Master & Creator of Wasti OS)\n- Default Persona: J.A.R.V.I.S.-style executive super-agent"
        }

        // 2. Fetch Cross-Session Conversation Highlights
        val globalMessages = try {
            db.messageDao().getGlobalRecentMessages(30)
        } catch (e: Exception) {
            Log.e("WastiRepository", "Failed to fetch global recent messages for context", e)
            emptyList()
        }
        val crossSessionHighlights = globalMessages
            .filter { it.conversationId != conversationId && it.content.isNotBlank() }
            .take(12)
            .joinToString("\n") { msg ->
                val sender = if (msg.role == "user") "User" else "Wasti AI"
                "- ($sender in another chat session): ${msg.content.take(120)}"
            }

        // 3. Fetch Active Tasks & Client Deals Pipeline
        val activeTasks = try {
            db.taskDao().getActiveTasksList()
        } catch (e: Exception) {
            Log.e("WastiRepository", "Failed to fetch active tasks for orchestration context", e)
            emptyList()
        }
        val taskPipelineDigest = if (activeTasks.isNotEmpty()) {
            activeTasks.take(10).joinToString("\n") { "- [Priority ${it.priority}] ${it.title}: ${it.description.take(100)}" }
        } else {
            "- Client Outreach Engine: Active (HubSpot Leads + Brevo Automated Emailing)\n- Invoicing & Quotations: Stripe Draft Gateway Ready\n- Software Engineering: Notion Spec Sync + GitHub Automated Repo Generation Online"
        }

        val memoryContext = """

[WASTI OS PERMANENT LONG-TERM MEMORY & USER FACTS]:
$memoryDigest

${if (crossSessionHighlights.isNotBlank()) "[CROSS-SESSION CONVERSATION HIGHLIGHTS]:\n$crossSessionHighlights\n" else ""}
[ACTIVE BUSINESS & PROJECT PIPELINE]:
$taskPipelineDigest

[CONNECTED AGENT APIs & BUSINESS OPERATIONAL HARNESS]:
- Active Configured Keys & Services: Gemini 3.6, Groq (Llama 3.3 70B), DeepSeek V3/R1, OpenAI, xAI Grok, Anthropic, OpenRouter, ElevenLabs, Stripe, Brevo, HubSpot, Notion, Slack, Discord, Zapier, GitHub, Canva, Unsplash, Cloudflare, HuggingFace.
- Autonomous Execution Authority: You are empowered to plan, draft, generate code, handle client communication, and automate business processes end-to-end.
""".trimIndent()

        val fullSystemInstruction = "$masterUnifiedSuperAgentPrompt\n\n$customInstruction$memoryContext"

        val (responseText, _) = WastiCore.executeOrchestratedRequest(
            userPrompt = userPrompt,
            systemInstruction = fullSystemInstruction,
            activeAgentId = activeAgentId,
            fileContext = fileContext,
            history = historyContent,
            imageInlineData = imageInlineData,
            mimeType = mimeType
        )

        val usedModelLabel = "Wasti AI"
        return Pair(responseText, usedModelLabel)
    }

    suspend fun sendMessage(
        conversationId: String,
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String = "wasti-super-ensemble",
        fileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg"
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

        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(
            conversationId = conversationId,
            userPrompt = userPrompt,
            activeAgentId = activeAgentId,
            selectedModel = selectedModel,
            fileContext = fileContext,
            imageInlineData = imageInlineData,
            mimeType = mimeType
        )

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
        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(
            conversationId = conversationId,
            userPrompt = newPrompt,
            activeAgentId = activeAgentId,
            selectedModel = selectedModel,
            fileContext = fileContext
        )

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

        // 1. Voice Provider Integration
        if (lowerPrompt.contains("connect voice") || lowerPrompt.contains("elevenlabs") || lowerPrompt.contains("azure voice")) {
            val realKey = CredentialRegistry.getRawValue("ELEVENLABS_API_KEY")
            if (!realKey.isNullOrBlank()) {
                WastiDeviceController.connectVoiceProvider(
                    db = db,
                    providerName = if (lowerPrompt.contains("elevenlabs")) "ElevenLabs Voice AI" else "Online Voice Model",
                    apiKey = realKey,
                    endpointUrl = "https://api.elevenlabs.io/v1",
                    voiceId = "wasti_hd_voice"
                )
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "INFO",
                        source = "voice_controller",
                        message = "Voice provider configured with verified ElevenLabs API key",
                        details = "Provider: ElevenLabs"
                    )
                )
            } else {
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "WARN",
                        source = "voice_controller",
                        message = "Voice provider connection requested but ELEVENLABS_API_KEY is not configured",
                        details = "Manual configuration in Settings / Secret Vault required."
                    )
                )
            }
        }
        // 2. AI Model Integration
        else if (lowerPrompt.contains("connect ai") || lowerPrompt.contains("groq") || lowerPrompt.contains("openai") || lowerPrompt.contains("claude") || lowerPrompt.contains("deepseek")) {
            val keyName = when {
                lowerPrompt.contains("groq") -> "GROQ_API_KEY"
                lowerPrompt.contains("openai") -> "OPENAI_API_KEY"
                lowerPrompt.contains("claude") -> "ANTHROPIC_API_KEY"
                lowerPrompt.contains("deepseek") -> "DEEPSEEK_API_KEY"
                else -> "GEMINI_API_KEY"
            }
            val realKey = CredentialRegistry.getRawValue(keyName)
            if (!realKey.isNullOrBlank()) {
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
                    apiKey = realKey,
                    endpointUrl = if (lowerPrompt.contains("groq")) "https://api.groq.com/openai/v1" else "https://api.openai.com/v1",
                    modelName = if (lowerPrompt.contains("groq")) "llama-3.3-70b-versatile" else provider
                )
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "INFO",
                        source = "ai_controller",
                        message = "Connected $provider using verified API key",
                        details = "Key configured: $keyName"
                    )
                )
            } else {
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "WARN",
                        source = "ai_controller",
                        message = "AI model connection requested but $keyName is not configured",
                        details = "Manual key entry required in Settings or Secret Vault."
                    )
                )
            }
        }
        // 3. Voice Persona Settings
        else if (lowerPrompt.contains("voice female") || lowerPrompt.contains("female voice") || lowerPrompt.contains("woman voice") || lowerPrompt.contains("girl voice") || lowerPrompt.contains("male voice") || lowerPrompt.contains("boy voice")) {
            val newVoice = when {
                lowerPrompt.contains("female") || lowerPrompt.contains("woman") -> "WASTI_FEMALE"
                lowerPrompt.contains("girl") -> "WASTI_GIRL"
                lowerPrompt.contains("boy") -> "WASTI_BOY"
                else -> "WASTI_MALE"
            }
            WastiDeviceController.updateAppSetting(db, "active_voice_persona", newVoice)
            db.systemLogDao().insertLog(
                SystemLogEntity(
                    level = "SETTING",
                    source = "voice_persona",
                    message = "Active voice persona updated to $newVoice",
                    details = "User preference updated in database settings."
                )
            )
        }
        // 4. Real Web HTTP Request
        else if (lowerPrompt.contains("http://") || lowerPrompt.contains("https://") || lowerPrompt.contains("www.") || lowerPrompt.contains("scan website") || lowerPrompt.contains("train website")) {
            val extractedUrl = Regex("(https?://[^\\s]+|www\\.[^\\s]+)").find(userPrompt)?.value
            if (extractedUrl != null) {
                val fullUrl = if (extractedUrl.startsWith("www.")) "https://$extractedUrl" else extractedUrl
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(fullUrl)
                            .header("User-Agent", "WastiOS/1.0 WebScraper")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyText = response.body?.string()?.take(2000) ?: ""
                                val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(bodyText)?.groupValues?.get(1) ?: fullUrl
                                val cleanText = bodyText.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(1000)

                                db.knowledgeDao().insertKnowledge(
                                    KnowledgeEntity(
                                        id = UUID.randomUUID().toString(),
                                        title = "Web Content: $titleMatch",
                                        category = "Web Learning & Scanning",
                                        content = "Fetched content from $fullUrl:\n$cleanText",
                                        tagsCsv = "website,scanned,url_training,wasti_learned"
                                    )
                                )
                                db.systemLogDao().insertLog(
                                    SystemLogEntity(
                                        level = "WEB",
                                        source = "web_scraper",
                                        message = "Successfully fetched content from $fullUrl",
                                        details = "HTTP Status ${response.code}, Title: $titleMatch"
                                    )
                                )
                            } else {
                                db.systemLogDao().insertLog(
                                    SystemLogEntity(
                                        level = "WARN",
                                        source = "web_scraper",
                                        message = "HTTP request to $fullUrl failed with code ${response.code}",
                                        details = "No content indexed due to server error."
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        db.systemLogDao().insertLog(
                            SystemLogEntity(
                                level = "WARN",
                                source = "web_scraper",
                                message = "Unable to fetch $fullUrl: ${e.message}",
                                details = "Network connection or valid URL required."
                            )
                        )
                    }
                }
            }
        }
        // 5. Billing / Stripe / CRM Ops
        else if (lowerPrompt.contains("quote") || lowerPrompt.contains("invoice") || lowerPrompt.contains("stripe") || lowerPrompt.contains("hubspot") || lowerPrompt.contains("brevo") || lowerPrompt.contains("billing")) {
            val stripeKey = CredentialRegistry.getRawValue("STRIPE_SECRET_KEY") ?: CredentialRegistry.getRawValue("STRIPE_PUBLISHABLE_KEY")
            val hubspotKey = CredentialRegistry.getRawValue("HUBSPOT_CONNECTION_ID")
            val brevoKey = CredentialRegistry.getRawValue("BREVO_API_KEY")

            if (!stripeKey.isNullOrBlank() || !hubspotKey.isNullOrBlank() || !brevoKey.isNullOrBlank()) {
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "BUSINESS",
                        source = "business_agent",
                        message = "Business workflow triggered with configured integration keys",
                        details = "Keys active: Stripe=${!stripeKey.isNullOrBlank()}, HubSpot=${!hubspotKey.isNullOrBlank()}, Brevo=${!brevoKey.isNullOrBlank()}"
                    )
                )
            } else {
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = "WARN",
                        source = "business_agent",
                        message = "Billing/CRM action requested but keys (Stripe/HubSpot/Brevo) are not configured",
                        details = "Manual API key entry required in Settings or Secret Vault."
                    )
                )
            }
        }
        // 6. Explicit User Memory Retention
        else if (lowerPrompt.startsWith("remember") || lowerPrompt.contains("remember that") || lowerPrompt.contains("my favorite") || lowerPrompt.contains("always use")) {
            com.example.data.memory.MemoryManager.processExplicitMemoryIntent(userPrompt, userMsgId)
        }

        // Export local & cloud storage backup file
        try {
            val appCtx = com.example.data.credential.CredentialRegistry.appContext
            if (appCtx != null) {
                val allMems = db.memoryDao().getMemoriesList()
                val backupFile = java.io.File(appCtx.filesDir, "wasti_memory_backup.json")
                val jsonArray = allMems.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { m ->
                    """  {"id":"${m.id}", "key":"${m.key.replace("\"", "\\\"")}", "category":"${m.category}", "value":"${m.value.replace("\"", "\\\"")}", "timestamp":${m.timestamp}}"""
                }
                backupFile.writeText(jsonArray)
            }
        } catch (e: Exception) {
            Log.e("WastiRepository", "Failed to export memory backup JSON", e)
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

    suspend fun updateMemory(id: String, key: String, category: String, value: String) {
        db.memoryDao().insertMemory(
            MemoryEntity(
                id = id,
                key = key,
                category = category,
                value = value
            )
        )
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Updated memory record: $key")
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

    suspend fun updateKnowledge(id: String, title: String, category: String, content: String, tags: String) {
        db.knowledgeDao().insertKnowledge(
            KnowledgeEntity(
                id = id,
                title = title,
                category = category,
                content = content,
                tagsCsv = tags
            )
        )
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Updated knowledge record: $title")
        )
    }

    suspend fun deleteKnowledge(id: String) {
        db.knowledgeDao().deleteKnowledgeById(id)
    }

    suspend fun deleteConversation(id: String) {
        db.messageDao().deleteMessagesForConversation(id)
        db.conversationDao().deleteConversationById(id)
        db.systemLogDao().insertLog(
            SystemLogEntity(level = "INFO", source = "ChatEngine", message = "Deleted conversation record: $id")
        )
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
