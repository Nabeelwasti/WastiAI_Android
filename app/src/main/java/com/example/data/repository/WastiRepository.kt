package com.example.data.repository

import android.util.Log
import com.example.data.agent.MultiAgentRegistry
import com.example.data.api.GeminiContent
import com.example.data.api.GeminiPart
import com.example.data.core.WastiCore
import com.example.data.credential.CredentialRegistry
import com.example.data.db.*
import com.example.data.device.WastiDeviceController
import com.example.data.memory.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Central repository for Wasti OS's chat, memory, knowledge, project, and settings data.
 *
 * This class owns the Room database connection and is the single entry point the UI layer
 * (via WastiViewModel) uses to read and write everything the assistant knows and remembers.
 * The actual AI orchestration (which provider to call, function-calling loop, etc.) lives in
 * [WastiCore] — this class is responsible for assembling the *context* that orchestration
 * needs (conversation history, long-term memory, active tasks) and persisting the result.
 *
 * @param db The shared Room database instance.
 */
class WastiRepository(private val db: WastiDatabase) {

    init {
        MemoryManager.initialize(db.memoryDao())
    }

    // ---------------------------------------------------------------------------------------
    // Reactive data streams — the UI layer collects these directly.
    // ---------------------------------------------------------------------------------------

    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().getAllConversations()
    val memories: Flow<List<MemoryEntity>> = db.memoryDao().getAllMemories()
    val knowledge: Flow<List<KnowledgeEntity>> = db.knowledgeDao().getAllKnowledge()
    val agents: Flow<List<AgentEntity>> = db.agentDao().getAllAgents()
    val projects: Flow<List<ProjectEntity>> = db.projectDao().getAllProjects()
    val allTasks: Flow<List<TaskEntity>> = db.taskDao().getAllTasks()
    val integrations: Flow<List<IntegrationEntity>> = db.integrationDao().getAllIntegrations()
    val logs: Flow<List<SystemLogEntity>> = db.systemLogDao().getRecentLogs()
    val settings: Flow<List<SettingEntity>> = db.settingDao().getAllSettings()
    val terminalSessions: Flow<List<TerminalSessionEntity>> = db.terminalSessionDao().getHistoryForSession("default")

    suspend fun recordTerminalSession(
        command: String,
        output: String = "",
        stderr: String = "",
        workingDirectory: String = "home/wasti",
        status: String = "SUCCESS",
        exitCode: Int = 0,
        durationMs: Long = 0L,
        verified: Boolean = false,
        verificationEvidence: String? = null,
        sessionId: String = "default"
    ) = withContext(Dispatchers.IO) {
        db.terminalSessionDao().insertSessionEntry(
            TerminalSessionEntity(
                sessionId = sessionId,
                command = command,
                output = output,
                stderr = stderr,
                workingDirectory = workingDirectory,
                status = status,
                exitCode = exitCode,
                durationMs = durationMs,
                verified = verified,
                verificationEvidence = verificationEvidence,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearTerminalHistory(sessionId: String = "default") = withContext(Dispatchers.IO) {
        db.terminalSessionDao().deleteHistoryForSession(sessionId)
    }


    companion object {
        private const val TAG = "WastiRepository"

        /** Default model identifier used when the caller doesn't specify one. */
        const val DEFAULT_MODEL = "wasti-super-ensemble"

        /** How many prior turns of the *current* conversation to send as history to the model. */
        private const val MAX_HISTORY_TURNS = 20

        /** How many long-term memory entries to inject into the system prompt at most. */
        private const val MAX_MEMORY_ENTRIES = 30

        /** How many messages from *other* conversations to surface as cross-session context. */
        private const val MAX_CROSS_SESSION_HIGHLIGHTS = 12

        /** How many active tasks to summarize into the prompt's business-pipeline section. */
        private const val MAX_ACTIVE_TASKS = 10

        /** Minimum length an agent-supplied system instruction must have to be used as-is. */
        private const val MIN_CUSTOM_INSTRUCTION_LENGTH = 150
    }

    // =========================================================================================
    // First-run seed data
    // =========================================================================================

    /**
     * Populates the database with default agents, seed memories, starter knowledge entries,
     * an initial project/task pipeline, and a welcome message — but only if no agents exist
     * yet. Safe to call on every app launch; it's a no-op after the first successful run.
     */
    suspend fun initDefaultDataIfNeeded() {
        val existingAgents = db.agentDao().getAllAgents().firstOrNull()
        if (!existingAgents.isNullOrEmpty()) return

        seedDefaultAgents()
        seedFounderMemories()
        seedStarterKnowledge()
        seedInitialProjectAndTasks()
        seedIntegrationStatusEntries()
        seedInitialSystemLog()
        seedDefaultSettings()
        seedWelcomeConversation()
    }

    private suspend fun seedDefaultAgents() {
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
    }

    private suspend fun seedFounderMemories() {
        val seedMemories = listOf(
            Triple(
                "Founder Profile & Identity",
                "Profile",
                "Syed Nabeel Wasti (Born 26 Oct 1997, Lahore). Founder of Thrivebridge Growth Solutions & Velura. BS Instructional Design & Tech student at AIOU. Multitalented creator in AI, design, HR, & screen printing."
            ),
            Triple(
                "Business & Brand - Thrivebridge",
                "Business",
                "Thrivebridge Growth Solutions: Offers graphic design, 2D/3D animation, SEO, DMCA copyright protection, architectural drawings, and AI automation. Contact: 0306-7370864 | wastinabeel99@gmail.com"
            ),
            Triple(
                "Wasti AI System Vision",
                "Goal",
                "Build Wasti AI & Wasti OS into a unified digital assistant ecosystem capable of long-term memory, business task automation, multi-agent workflows, and personal execution."
            ),
            Triple(
                "Design & UI Philosophy",
                "Rule",
                "High-contrast dark mode, clean Linear/Raycast UI aesthetics, sharp typography, responsive touch targets, and clean modular code."
            )
        )

        seedMemories.forEach { (key, category, value) ->
            db.memoryDao().insertMemory(
                MemoryEntity(
                    id = UUID.randomUUID().toString(),
                    key = key,
                    category = category,
                    value = value,
                    importanceScore = if (category == "Rule") 0.9f else 0.95f
                )
            )
        }
    }

    private suspend fun seedStarterKnowledge() {
        val entries = listOf(
            Triple(
                "Syed Nabeel Wasti Autobiography Context",
                "Biography",
                "Autobiography: 'سایوں سے نکل کر سورج کی طرف'. Born in Lahore, raised in Faisalabad. Skilled in Canva, Photoshop, CorelDRAW, AutoCAD, MS Office, Python, and AI prompt engineering. Resilient survivor."
            ) to "Autobiography, Biography, SyedNabeelWasti",
            Triple(
                "Thrivebridge Creative & Digital Services Catalog",
                "Services",
                "Services offered by Thrivebridge Growth Solutions: Graphic Design, 2D/3D Animation, Motion Graphics, Video/Image Editing, Architectural Drawings, 3D Modeling, Social Media Management, SEO, Branding, DMCA Protection, Calligraphy, Proofreading, and Hyperlinked E-books."
            ) to "Thrivebridge, Services, Business, Marketing",
            Triple(
                "Wasti OS Architecture Blueprint",
                "System Docs",
                "Wasti OS is structured around an Executive Brain, Room Database persistence, Multi-Agent Communication Network, and REST AI Provider adapters."
            ) to "Architecture, WastiOS, AI"
        )

        entries.forEach { (titleCategoryContent, tags) ->
            val (title, category, content) = titleCategoryContent
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
    }

    private suspend fun seedInitialProjectAndTasks() {
        val projectId = UUID.randomUUID().toString()
        db.projectDao().insertProject(
            ProjectEntity(
                id = projectId,
                name = "Wasti OS & Thrivebridge AI Hub",
                description = "Launch personal AI Operating System and power Thrivebridge Growth Solutions automation.",
                status = "In Progress",
                priority = "High",
                tagsCsv = "Core, AI, Thrivebridge"
            )
        )

        val seedTasks = listOf(
            Triple("Verify Multi-Agent Execution Network", "Test inter-agent delegation between CEO, Research, Coding, and Business agents.", "ceo_agent") to true,
            Triple("Index Long-Term Memory System", "Store Syed Nabeel Wasti's profile, Thrivebridge catalog, and AIOU academic context.", "memory_agent") to true,
            Triple("Thrivebridge Automation Pipelines", "Configure WhatsApp, email drafting, and DMCA request generators.", "automation_agent") to false
        )

        seedTasks.forEach { (titleDescAgent, completed) ->
            val (title, description, agentId) = titleDescAgent
            db.taskDao().insertTask(
                TaskEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = title,
                    description = description,
                    isCompleted = completed,
                    priority = if (completed) "High" else "Medium",
                    assignedAgentId = agentId
                )
            )
        }
    }

    private suspend fun seedIntegrationStatusEntries() {
        val integrationSeeds = listOf(
            IntegrationEntity(id = "xai_grok", serviceName = "x.ai Grok Intelligence", provider = "x.AI", isConnected = true, authType = "API Key", statusText = "Active (Grok 2 & Grok Beta Engine)"),
            IntegrationEntity(id = "google_workspace", serviceName = "Google Workspace & AI Studio", provider = "Google", isConnected = true, authType = "Gemini API Key", statusText = "Active (Docs, Sheets, Gemini 3.5 Flash & Pro)"),
            IntegrationEntity(id = "canva_connect", serviceName = "Canva Connect API", provider = "Canva", isConnected = true, authType = "Client ID / OAuth2", statusText = "Active (Stunning Graphic Designs, Presentations & Assets)"),
            IntegrationEntity(id = "github_mcp", serviceName = "GitHub Repository & MCP", provider = "GitHub", isConnected = true, authType = "Fine-Grained PAT & Classic Token", statusText = "Active (Full Repository Sync, Code Commits & Actions)"),
            IntegrationEntity(id = "slack_bot", serviceName = "Slack Agent Connector", provider = "Slack", isConnected = true, authType = "Bot Token", statusText = "Listening (#wasti-ai-os)"),
            IntegrationEntity(id = "open_router", serviceName = "OpenRouter Provider", provider = "OpenRouter", isConnected = true, authType = "API Key", statusText = "Configured (Multi-Model Gateway)")
        )
        integrationSeeds.forEach { db.integrationDao().insertIntegration(it) }
    }

    private suspend fun seedInitialSystemLog() {
        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "INFO",
                source = "ExecutiveBrain",
                message = "Wasti OS Engine initialized. All 10 specialized agent nodes online and ready.",
                details = "Room database created, memory vector indexed."
            )
        )
    }

    private suspend fun seedDefaultSettings() {
        if (db.settingDao().getSettingValue("xai_model_name").isNullOrBlank()) {
            db.settingDao().insertSetting(SettingEntity("xai_model_name", "grok-2-latest"))
        }
    }

    private suspend fun seedWelcomeConversation() {
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

    // =========================================================================================
    // Conversations
    // =========================================================================================

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> {
        return db.messageDao().getMessagesForConversation(conversationId)
    }

    suspend fun createNewConversation(title: String, agentId: String = "ceo_agent"): String {
        val convId = UUID.randomUUID().toString()
        db.conversationDao().insertConversation(
            ConversationEntity(id = convId, title = title, activeAgentId = agentId)
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

    /**
     * Converts stored [MessageEntity] rows into the role/parts structure Gemini's API expects,
     * merging consecutive same-role turns (Gemini rejects back-to-back same-role entries) and
     * excluding [currentPrompt] itself, since that's sent separately as the live turn.
     */
    private fun buildGeminiHistory(messages: List<MessageEntity>, currentPrompt: String): List<GeminiContent> {
        val nonBlank = messages.filter { it.content.isNotBlank() }

        val priorTurns = if (nonBlank.isNotEmpty() &&
            nonBlank.last().role == "user" &&
            (nonBlank.last().content == currentPrompt || nonBlank.last().content.startsWith(currentPrompt))
        ) {
            nonBlank.dropLast(1)
        } else {
            nonBlank
        }

        val recentTurns = priorTurns.takeLast(MAX_HISTORY_TURNS)
        val history = mutableListOf<GeminiContent>()

        for (msg in recentTurns) {
            val role = if (msg.role == "user") "user" else "model"
            val previous = history.lastOrNull()

            if (previous != null && previous.role == role) {
                val existingText = previous.parts.firstOrNull()?.text ?: ""
                history[history.size - 1] = GeminiContent(
                    role = role,
                    parts = listOf(GeminiPart(text = "$existingText\n${msg.content}"))
                )
            } else {
                history.add(GeminiContent(role = role, parts = listOf(GeminiPart(text = msg.content))))
            }
        }

        // Gemini requires the conversation to start with a 'user' turn.
        if (history.isNotEmpty() && history.first().role != "user") {
            history.removeAt(0)
        }

        return history
    }

    /** A snapshot of the current time in a given timezone, pre-formatted for prompt injection. */
    private data class TimeSnapshot(
        val formatted12Hour: String,
        val dateString: String,
        val periodOfDay: String,
        val zoneLabel: String
    )

    private fun snapshotTime(timeZone: TimeZone): TimeSnapshot {
        val calendar = Calendar.getInstance(timeZone)
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val minuteStr = minute.toString().padStart(2, '0')
        val amPm = if (hour24 >= 12) "PM" else "AM"
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12

        val dateFormatter = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { this.timeZone = timeZone }
        val period = when (hour24) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Night / Late Night"
        }

        return TimeSnapshot(
            formatted12Hour = "$hour12:$minuteStr $amPm",
            dateString = dateFormatter.format(calendar.time),
            periodOfDay = period,
            zoneLabel = timeZone.id
        )
    }

    /**
     * Builds the full system prompt and calls [WastiCore] to generate a response. This is the
     * single place where conversational context (time, memory, tasks, history) gets assembled
     * before handing off to the actual AI orchestration layer.
     */
    private suspend fun generateUnifiedSuperAgentResponse(
        conversationId: String,
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String,
        fileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        mediaList: List<com.example.data.ai.model.AttachedMediaData> = emptyList()
    ): Pair<String, String> {
        val pkt = snapshotTime(TimeZone.getTimeZone("Asia/Karachi"))
        val device = snapshotTime(TimeZone.getDefault())

        val previousMessages = db.messageDao().getMessagesListForConversation(conversationId)
        val historyContent = buildGeminiHistory(previousMessages, userPrompt)

        val masterUnifiedSuperAgentPrompt = """
            You are "Wasti AI Super Agent", the supreme consolidated Master Intelligence Engine and Mobile OS Executive Assistant created for Syed Nabeel Wasti.
            You combine ALL specialized domain capabilities (Software Engineering, UI/UX Material 3 Design, Business Strategy, Research, Technical Writing, Workflow Automation, Long-Term Memory, and Multilingual Speech) into ONE seamless, unified brain.

            [REAL-TIME CLOCK & WORLD TIME CONTEXT]:
            • Pakistan Standard Time (PKT, UTC+5, Asia/Karachi): ${pkt.formatted12Hour} (${pkt.dateString}) [${pkt.periodOfDay}]
            • Device / UTC System Time: ${device.formatted12Hour} (${device.dateString}) [Zone: ${device.zoneLabel}]

            CRITICAL TIME RESPONSE MANDATES:
            1. When asked for the current time or time in Pakistan, state the time clearly, accurately, and naturally in human conversational terms (e.g., "It is currently ${pkt.formatted12Hour} on ${pkt.dateString} in Pakistan" or "Right now in Pakistan, it is ${pkt.formatted12Hour}").
            2. AM/PM MUST BE 100% MATHEMATICALLY ACCURATE. Do NOT confuse AM with PM.
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

            CRITICAL DYNAMIC MULTI-TURN LANGUAGE RULE:
            The user MAY change languages dynamically from prompt to prompt within the exact same chat session.
            You MUST reply in the EXACT SAME language, dialect, and script used in the LATEST user prompt.
            - IGNORE the language or script used in previous conversation history or past assistant turns.
            - If the latest user prompt is in English -> reply strictly 100% in English!
            - If the latest user prompt is in Urdu script (اردو) -> reply strictly 100% in Urdu script!
            - If the latest user prompt is in Roman Urdu -> reply in Roman Urdu!
            - If the latest user prompt is in Spanish, French, German, Arabic, Punjabi, Hindi, or any other language -> reply strictly in that exact language!
            - NEVER default to Roman Urdu unless the latest user prompt itself is written in Roman Urdu!
        """.trimIndent()

        val agent = db.agentDao().getAgentById(activeAgentId)
        val rawInstruction = agent?.systemInstruction ?: ""
        val customInstruction = if (activeAgentId == "coding_agent" || rawInstruction.contains("production-ready code")) {
            "You are the Coding Agent of Wasti OS. Write clean, modular code in Kotlin, Jetpack Compose, Python, and TypeScript. Adhere strictly to architectural best practices, explicitly handle edge cases, add comprehensive error handling, and flag any assumptions or untested logic rather than claiming certainty you cannot verify."
        } else {
            rawInstruction
        }

        val memoryContext = buildMemoryContextBlock(conversationId)
        val fullSystemInstruction = "$masterUnifiedSuperAgentPrompt\n\n$customInstruction$memoryContext"

        val (responseText, _) = WastiCore.executeOrchestratedRequest(
            userPrompt = userPrompt,
            systemInstruction = fullSystemInstruction,
            activeAgentId = activeAgentId,
            fileContext = fileContext,
            history = historyContent,
            imageInlineData = imageInlineData,
            mimeType = mimeType,
            mediaList = mediaList
        )

        return Pair(responseText, "Wasti AI")
    }

    /**
     * Assembles the long-term memory, cross-session highlights, and active-task-pipeline
     * section of the system prompt.
     */
    private suspend fun buildMemoryContextBlock(conversationId: String): String {
        val memoryDigest = try {
            db.memoryDao().getMemoriesList()
                .take(MAX_MEMORY_ENTRIES)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- [${it.category}] ${it.key}: ${it.value}" }
                ?: "- User Identity: Syed Nabeel Wasti (Master & Creator of Wasti OS)\n- Default Persona: J.A.R.V.I.S.-style executive super-agent"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch long-term memories for orchestration context", e)
            "- User Identity: Syed Nabeel Wasti (Master & Creator of Wasti OS)\n- Default Persona: J.A.R.V.I.S.-style executive super-agent"
        }

        val crossSessionHighlights = try {
            db.messageDao().getGlobalRecentMessages(MAX_CROSS_SESSION_HIGHLIGHTS * 3)
                .filter { it.conversationId != conversationId && it.content.isNotBlank() }
                .take(MAX_CROSS_SESSION_HIGHLIGHTS)
                .joinToString("\n") { msg ->
                    val sender = if (msg.role == "user") "User" else "Wasti AI"
                    "- ($sender in another chat session): ${msg.content.take(120)}"
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch global recent messages for context", e)
            ""
        }

        val taskPipelineDigest = try {
            db.taskDao().getActiveTasksList()
                .take(MAX_ACTIVE_TASKS)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- [Priority ${it.priority}] ${it.title}: ${it.description.take(100)}" }
                ?: "- Client Outreach Engine: Active (HubSpot Leads + Brevo Automated Emailing)\n- Invoicing & Quotations: Stripe Draft Gateway Ready\n- Software Engineering: Notion Spec Sync + GitHub Automated Repo Generation Online"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch active tasks for orchestration context", e)
            "- Client Outreach Engine: Active (HubSpot Leads + Brevo Automated Emailing)\n- Invoicing & Quotations: Stripe Draft Gateway Ready\n- Software Engineering: Notion Spec Sync + GitHub Automated Repo Generation Online"
        }

        val crossSessionBlock = if (crossSessionHighlights.isNotBlank()) {
            "[CROSS-SESSION CONVERSATION HIGHLIGHTS]:\n$crossSessionHighlights\n"
        } else {
            ""
        }

        return """

[WASTI OS PERMANENT LONG-TERM MEMORY & USER FACTS]:
$memoryDigest

$crossSessionBlock[ACTIVE BUSINESS & PROJECT PIPELINE]:
$taskPipelineDigest

[CONNECTED AGENT APIs & BUSINESS OPERATIONAL HARNESS]:
- Active Configured Keys & Services: Gemini 3.6, Groq (Llama 3.3 70B), DeepSeek V3/R1, OpenAI, xAI Grok, Anthropic, OpenRouter, ElevenLabs, Stripe, Brevo, HubSpot, Notion, Slack, Discord, Zapier, GitHub, Canva, Unsplash, Cloudflare, HuggingFace.
- Autonomous Execution Authority: You are empowered to plan, draft, generate code, handle client communication, and automate business processes end-to-end.
""".trimIndent()
    }

    /**
     * Sends a user message: persists it, generates a response via [generateUnifiedSuperAgentResponse],
     * persists that too, logs the exchange, and runs any automation triggers the prompt implies.
     */
    suspend fun sendMessage(
        conversationId: String,
        userPrompt: String,
        activeAgentId: String,
        selectedModel: String = DEFAULT_MODEL,
        fileContext: String? = null,
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        attachedMediaUris: String = "",
        mediaList: List<com.example.data.ai.model.AttachedMediaData> = emptyList()
    ): String {
        val userMsgId = UUID.randomUUID().toString()
        db.messageDao().insertMessage(
            MessageEntity(
                id = userMsgId,
                conversationId = conversationId,
                role = "user",
                content = userPrompt,
                agentId = "user",
                attachedMediaUris = attachedMediaUris
            )
        )

        // Store attachments in Media Vault catalog
        if (attachedMediaUris.isNotBlank()) {
            val uris = attachedMediaUris.split(",").map { it.trim() }.filter { it.isNotBlank() }
            uris.forEachIndexed { idx, uriStr ->
                val mediaItem = mediaList.getOrNull(idx)
                val mType = mediaItem?.mimeType ?: mimeType
                db.mediaVaultDao().insertMedia(
                    MediaVaultEntity(
                        conversationId = conversationId,
                        messageId = userMsgId,
                        uri = uriStr,
                        mimeType = mType
                    )
                )
            }
        }

        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(
            conversationId = conversationId,
            userPrompt = userPrompt,
            activeAgentId = activeAgentId,
            selectedModel = selectedModel,
            fileContext = fileContext,
            imageInlineData = imageInlineData,
            mimeType = mimeType,
            mediaList = mediaList
        )

        db.messageDao().insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = responseText,
                agentId = if (activeAgentId == "ceo_agent") "Wasti Super Agent" else activeAgentId,
                modelUsed = usedModel
            )
        )

        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "AGENT",
                source = activeAgentId,
                message = "Unified Wasti Super Agent processed prompt ($usedModel)",
                details = "Prompt length ${userPrompt.length}, response length ${responseText.length} chars"
            )
        )

        processUserPromptAutomations(userPrompt, userMsgId)

        return responseText
    }

    /**
     * Edits a previously-sent user message in place, discards everything that came after it
     * in the conversation, and regenerates the assistant's response from that point.
     */
    suspend fun editMessageAndRegenerate(
        conversationId: String,
        messageId: String,
        newPrompt: String,
        activeAgentId: String,
        selectedModel: String = DEFAULT_MODEL,
        fileContext: String? = null
    ): String {
        val targetMsg = db.messageDao().getMessageById(messageId) ?: return ""

        db.messageDao().updateMessageContent(messageId, newPrompt)
        db.messageDao().deleteMessagesAfterTimestamp(conversationId, targetMsg.timestamp)

        val (responseText, usedModel) = generateUnifiedSuperAgentResponse(
            conversationId = conversationId,
            userPrompt = newPrompt,
            activeAgentId = activeAgentId,
            selectedModel = selectedModel,
            fileContext = fileContext
        )

        db.messageDao().insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = responseText,
                agentId = if (activeAgentId == "ceo_agent") "Wasti Super Agent" else activeAgentId,
                modelUsed = usedModel,
                timestamp = targetMsg.timestamp + 10
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

    // =========================================================================================
    // Lightweight prompt-triggered automations
    // =========================================================================================

    private suspend fun processUserPromptAutomations(userPrompt: String, userMsgId: String) {
        val lowerPrompt = userPrompt.lowercase()

        when {
            isVoiceProviderRequest(lowerPrompt) -> handleVoiceProviderConnection(lowerPrompt)
            isAiProviderRequest(lowerPrompt) -> handleAiProviderConnection(lowerPrompt)
            isVoicePersonaRequest(lowerPrompt) -> handleVoicePersonaChange(lowerPrompt)
            isWebFetchRequest(lowerPrompt) -> handleWebFetchRequest(userPrompt)
            isBillingRequest(lowerPrompt) -> handleBillingCrmCheck()
            isExplicitMemoryRequest(lowerPrompt) -> MemoryManager.processExplicitMemoryIntent(userPrompt, userMsgId)
        }

        // Export local & cloud storage backup file
        try {
            val appCtx = CredentialRegistry.appContext
            if (appCtx != null) {
                val allMems = db.memoryDao().getMemoriesList()
                val backupFile = File(appCtx.filesDir, "wasti_memory_backup.json")
                val jsonArray = allMems.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { m ->
                    """  {"id":"${m.id}", "key":"${m.key.replace("\"", "\\\"")}", "category":"${m.category}", "value":"${m.value.replace("\"", "\\\"")}", "timestamp":${m.timestamp}}"""
                }
                backupFile.writeText(jsonArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export memory backup JSON", e)
        }
    }

    private fun isVoiceProviderRequest(p: String) =
        p.contains("connect voice") || p.contains("elevenlabs") || p.contains("azure voice")

    private fun isAiProviderRequest(p: String) =
        p.contains("connect ai") || p.contains("groq") || p.contains("openai") || p.contains("claude") || p.contains("deepseek")

    private fun isVoicePersonaRequest(p: String) =
        p.contains("voice female") || p.contains("female voice") || p.contains("woman voice") ||
            p.contains("girl voice") || p.contains("male voice") || p.contains("boy voice")

    private fun isWebFetchRequest(p: String) =
        p.contains("http://") || p.contains("https://") || p.contains("www.") ||
            p.contains("scan website") || p.contains("train website")

    private fun isBillingRequest(p: String) =
        p.contains("quote") || p.contains("invoice") || p.contains("stripe") ||
            p.contains("hubspot") || p.contains("brevo") || p.contains("billing")

    private fun isExplicitMemoryRequest(p: String) =
        p.startsWith("remember") || p.contains("remember that") || p.contains("my favorite") || p.contains("always use")

    private suspend fun logAutomation(level: String, source: String, message: String, details: String) {
        db.systemLogDao().insertLog(SystemLogEntity(level = level, source = source, message = message, details = details))
    }

    private suspend fun handleVoiceProviderConnection(lowerPrompt: String) {
        val realKey = CredentialRegistry.getRawValue("ELEVENLABS_API_KEY")
        if (realKey.isNullOrBlank()) {
            logAutomation(
                "WARN", "voice_controller",
                "Voice provider connection requested but ELEVENLABS_API_KEY is not configured",
                "Manual configuration in Settings / Secret Vault required."
            )
            return
        }

        val providerName = if (lowerPrompt.contains("elevenlabs")) "ElevenLabs Voice AI" else "Online Voice Model"
        WastiDeviceController.connectVoiceProvider(
            db = db,
            providerName = providerName,
            apiKey = realKey,
            endpointUrl = "https://api.elevenlabs.io/v1",
            voiceId = "wasti_hd_voice"
        )
        logAutomation("INFO", "voice_controller", "Voice provider configured with verified ElevenLabs API key", "Provider: ElevenLabs")
    }

    private suspend fun handleAiProviderConnection(lowerPrompt: String) {
        val keyName = when {
            lowerPrompt.contains("groq") -> "GROQ_API_KEY"
            lowerPrompt.contains("openai") -> "OPENAI_API_KEY"
            lowerPrompt.contains("claude") -> "ANTHROPIC_API_KEY"
            lowerPrompt.contains("deepseek") -> "DEEPSEEK_API_KEY"
            else -> "GEMINI_API_KEY"
        }

        val realKey = CredentialRegistry.getRawValue(keyName)
        if (realKey.isNullOrBlank()) {
            logAutomation(
                "WARN", "ai_controller",
                "AI model connection requested but $keyName is not configured",
                "Manual key entry required in Settings or Secret Vault."
            )
            return
        }

        val providerName = when {
            lowerPrompt.contains("groq") -> "Groq Ultra-Fast AI (Llama 3.3 70B)"
            lowerPrompt.contains("openai") -> "OpenAI GPT-4o"
            lowerPrompt.contains("claude") -> "Anthropic Claude"
            lowerPrompt.contains("deepseek") -> "DeepSeek R1"
            else -> "Online AI Engine"
        }

        WastiDeviceController.connectAiProvider(
            db = db,
            providerName = providerName,
            apiKey = realKey,
            endpointUrl = if (lowerPrompt.contains("groq")) "https://api.groq.com/openai/v1" else "https://api.openai.com/v1",
            modelName = if (lowerPrompt.contains("groq")) "llama-3.3-70b-versatile" else providerName
        )
        logAutomation("INFO", "ai_controller", "Connected $providerName using verified API key", "Key configured: $keyName")
    }

    private suspend fun handleVoicePersonaChange(lowerPrompt: String) {
        val newVoice = when {
            lowerPrompt.contains("female") || lowerPrompt.contains("woman") -> "WASTI_FEMALE"
            lowerPrompt.contains("girl") -> "WASTI_GIRL"
            lowerPrompt.contains("boy") -> "WASTI_BOY"
            else -> "WASTI_MALE"
        }
        WastiDeviceController.updateAppSetting(db, "active_voice_persona", newVoice)
        logAutomation("SETTING", "voice_persona", "Active voice persona updated to $newVoice", "User preference updated in database settings.")
    }

    private suspend fun handleWebFetchRequest(userPrompt: String) {
        val extractedUrl = Regex("(https?://[^\\s]+|www\\.[^\\s]+)").find(userPrompt)?.value ?: return
        val fullUrl = if (extractedUrl.startsWith("www.")) "https://$extractedUrl" else extractedUrl

        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "WastiOS/1.0 WebScraper")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logAutomation(
                            "WARN", "web_scraper",
                            "HTTP request to $fullUrl failed with code ${response.code}",
                            "No content indexed due to server error."
                        )
                        return@use
                    }

                    val bodyText = response.body?.string()?.take(2000) ?: ""
                    val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                        .find(bodyText)?.groupValues?.get(1) ?: fullUrl
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
                    logAutomation(
                        "WEB", "web_scraper",
                        "Successfully fetched content from $fullUrl",
                        "HTTP Status ${response.code}, Title: $titleMatch"
                    )
                }
            } catch (e: Exception) {
                logAutomation("WARN", "web_scraper", "Unable to fetch $fullUrl: ${e.message}", "Network connection or valid URL required.")
            }
        }
    }

    private suspend fun handleBillingCrmCheck() {
        val stripeKey = CredentialRegistry.getRawValue("STRIPE_SECRET_KEY") ?: CredentialRegistry.getRawValue("STRIPE_PUBLISHABLE_KEY")
        val hubspotKey = CredentialRegistry.getRawValue("HUBSPOT_CONNECTION_ID")
        val brevoKey = CredentialRegistry.getRawValue("BREVO_API_KEY")

        val anyConfigured = !stripeKey.isNullOrBlank() || !hubspotKey.isNullOrBlank() || !brevoKey.isNullOrBlank()

        if (anyConfigured) {
            logAutomation(
                "BUSINESS", "business_agent",
                "Business workflow triggered with configured integration keys",
                "Keys active: Stripe=${!stripeKey.isNullOrBlank()}, HubSpot=${!hubspotKey.isNullOrBlank()}, Brevo=${!brevoKey.isNullOrBlank()}"
            )
        } else {
            logAutomation(
                "WARN", "business_agent",
                "Billing/CRM action requested but keys (Stripe/HubSpot/Brevo) are not configured",
                "Manual API key entry required in Settings or Secret Vault."
            )
        }
    }

    // =========================================================================================
    // Local memory relevance ranking
    // =========================================================================================

    private fun tokenize(text: String): Set<String> =
        text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").split("\\s+".toRegex()).filter { it.length > 2 }.toSet()

    private fun calculateVectorSimilarity(query: String, text: String): Double {
        val queryTokens = tokenize(query)
        val textTokens = tokenize(text)
        if (queryTokens.isEmpty() || textTokens.isEmpty()) return 0.0

        val intersection = queryTokens.intersect(textTokens).size
        val union = queryTokens.union(textTokens).size
        if (union == 0) return 0.0

        val jaccard = intersection.toDouble() / union.toDouble()
        val overlap = intersection.toDouble() / queryTokens.size.toDouble()
        return (jaccard * 0.4) + (overlap * 0.6)
    }

    private fun rankMemoriesByVectorSimilarity(query: String, memoriesList: List<MemoryEntity>, topK: Int = 5): List<MemoryEntity> {
        if (memoriesList.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()

        val scored = memoriesList.map { memory ->
            val content = "${memory.key} ${memory.category} ${memory.value}"
            val similarity = calculateVectorSimilarity(query, content)
            val ageMs = (now - memory.timestamp).coerceAtLeast(0)
            val recency = (1.0 / (1.0 + (ageMs / 86_400_000.0))).coerceIn(0.1, 1.0)
            val combined = (similarity * 0.65) + (memory.importanceScore * 0.25) + (recency * 0.10)
            memory to combined
        }

        val topMatches = scored.filter { it.second > 0.05 }.sortedByDescending { it.second }.map { it.first }
        return topMatches.ifEmpty { memoriesList.sortedByDescending { it.importanceScore }.take(topK) }.take(topK)
    }

    // =========================================================================================
    // Memory & knowledge CRUD
    // =========================================================================================

    suspend fun addMemory(key: String, category: String, value: String) {
        db.memoryDao().insertMemory(MemoryEntity(id = UUID.randomUUID().toString(), key = key, category = category, value = value))
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Added memory record: $key"))
    }

    suspend fun updateMemory(id: String, key: String, category: String, value: String) {
        db.memoryDao().insertMemory(MemoryEntity(id = id, key = key, category = category, value = value))
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Updated memory record: $key"))
    }

    suspend fun deleteMemory(id: String) {
        db.memoryDao().deleteMemoryById(id)
    }

    suspend fun addKnowledge(title: String, category: String, content: String, tags: String) {
        db.knowledgeDao().insertKnowledge(KnowledgeEntity(id = UUID.randomUUID().toString(), title = title, category = category, content = content, tagsCsv = tags))
    }

    suspend fun updateKnowledge(id: String, title: String, category: String, content: String, tags: String) {
        db.knowledgeDao().insertKnowledge(KnowledgeEntity(id = id, title = title, category = category, content = content, tagsCsv = tags))
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "MemoryEngine", message = "Updated knowledge record: $title"))
    }

    suspend fun deleteKnowledge(id: String) {
        db.knowledgeDao().deleteKnowledgeById(id)
    }

    suspend fun deleteConversation(id: String) {
        db.messageDao().deleteMessagesForConversation(id)
        db.conversationDao().deleteConversationById(id)
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "ChatEngine", message = "Deleted conversation record: $id"))
    }

    // =========================================================================================
    // Projects, tasks, agents, logs, settings
    // =========================================================================================

    suspend fun addProject(name: String, description: String, priority: String) {
        db.projectDao().insertProject(ProjectEntity(id = UUID.randomUUID().toString(), name = name, description = description, priority = priority))
    }

    suspend fun addTask(projectId: String, title: String, description: String, assignedAgentId: String, priority: String) {
        db.taskDao().insertTask(
            TaskEntity(id = UUID.randomUUID().toString(), projectId = projectId, title = title, description = description, assignedAgentId = assignedAgentId, priority = priority)
        )
    }

    suspend fun toggleTaskStatus(taskId: String, currentStatus: Boolean) {
        db.taskDao().updateTaskStatus(taskId, !currentStatus)
    }

    suspend fun addAgent(name: String, roleTitle: String, agentType: String, systemInstruction: String, capabilities: String) {
        val enrichedInstruction = if (systemInstruction.length < MIN_CUSTOM_INSTRUCTION_LENGTH) {
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
        db.systemLogDao().insertLog(SystemLogEntity(level = level, source = source, message = message, details = details))
    }

    suspend fun clearLogs() {
        db.systemLogDao().clearAllLogs()
    }

    suspend fun clearChatHistory(conversationId: String) {
        db.messageDao().deleteMessagesForConversation(conversationId)
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "ChatWorkspace", message = "Cleared chat history for session $conversationId"))
    }

    suspend fun saveAppSetting(key: String, value: String) {
        db.settingDao().insertSetting(SettingEntity(key, value))
        db.systemLogDao().insertLog(SystemLogEntity(level = "INFO", source = "SettingsConfigurator", message = "Saved setting: $key"))
    }

    suspend fun getAppSetting(key: String): String? {
        return db.settingDao().getSettingValue(key)
    }
}
