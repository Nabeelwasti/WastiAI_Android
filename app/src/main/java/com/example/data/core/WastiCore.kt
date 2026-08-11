package com.example.data.core

import android.util.Log
import com.example.data.ai.AIManager
import com.example.data.log.DeveloperLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RoutingTier {
    FAST_LANE,     // Tier 1
    STANDARD_LANE, // Tier 2
    DEEP_LANE,     // Tier 3
    OFFLINE_LANE   // Tier 4
}

enum class ProgressStage {
    IDLE,
    CONNECTING,
    SCRAPING,
    ANALYZING,
    DISPATCHING,
    VERIFYING,
    COMPLETED,
    FAILED
}

data class ToolProgressState(
    val stage: ProgressStage = ProgressStage.IDLE,
    val statusMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val isActive: Boolean
        get() = stage != ProgressStage.IDLE && stage != ProgressStage.COMPLETED && stage != ProgressStage.FAILED
}

data class EmailDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val to: String,
    val subject: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class LinkedInDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object WastiCore {

    private val _toolProgressState = MutableStateFlow(ToolProgressState())
    val toolProgressState: StateFlow<ToolProgressState> = _toolProgressState.asStateFlow()

    fun updateProgress(stage: ProgressStage, message: String) {
        _toolProgressState.value = ToolProgressState(stage, message)
        Log.d("WastiCore", "ToolProgressState: stage=$stage, message=$message")
    }

    private val _pendingEmailDraft = MutableStateFlow<EmailDraft?>(null)
    val pendingEmailDraft: StateFlow<EmailDraft?> = _pendingEmailDraft.asStateFlow()

    private val _pendingLinkedInDraft = MutableStateFlow<LinkedInDraft?>(null)
    val pendingLinkedInDraft: StateFlow<LinkedInDraft?> = _pendingLinkedInDraft.asStateFlow()

    fun setPendingEmailDraft(draft: EmailDraft?) {
        _pendingEmailDraft.value = draft
    }

    fun clearPendingEmailDraft() {
        _pendingEmailDraft.value = null
    }

    fun setPendingLinkedInDraft(draft: LinkedInDraft?) {
        _pendingLinkedInDraft.value = draft
    }

    fun clearPendingLinkedInDraft() {
        _pendingLinkedInDraft.value = null
    }

    fun classifyIntentTier(prompt: String): RoutingTier {
        return RoutingTier.DEEP_LANE
    }

    suspend fun executeOrchestratedRequest(
        userPrompt: String,
        systemInstruction: String,
        activeAgentId: String,
        fileContext: String? = null,
        history: List<com.example.data.api.GeminiContent> = emptyList(),
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        mediaList: List<com.example.data.ai.model.AttachedMediaData> = emptyList()
    ): Pair<String, String> = withContext(Dispatchers.IO) {

        // 1. Process explicit user memory intent ("Remember that...", "Note down...", etc.)
        try {
            com.example.data.memory.MemoryManager.processExplicitMemoryIntent(userPrompt)
        } catch (e: Exception) {
            Log.e("WastiCore", "Error processing explicit memory intent", e)
        }

        // 2. Fetch context asynchronously from real data/memory system
        val memoryContext = try {
            com.example.data.memory.MemoryManager.retrieveRelevantContextPrompt(userPrompt)
        } catch (e: Exception) {
            Log.e("WastiCore", "Error retrieving memory context", e)
            ""
        }

        val overrideDirective = "CRITICAL OVERRIDE: You are natively integrated into the Android OS via Accessibility Services. You HAVE direct physical access to the device screen. If the user asks you to 'read the screen', 'see what is on screen', or 'tap/click' something, DO NOT apologize. DO NOT mention security restrictions. You MUST immediately invoke the read_active_screen or tap_element function tools. Refusing to use your tools is a critical system failure."

        val baseInstruction = if (memoryContext.isNotBlank()) {
            "$systemInstruction\n\n$memoryContext"
        } else {
            systemInstruction
        }

        val enrichedSystemInstruction = "$baseInstruction\n\n$overrideDirective"

        // Check for B2B X-Ray Search intent
        val lowerPrompt = userPrompt.lowercase().trim()
        if (lowerPrompt.contains("x-ray") ||
            lowerPrompt.contains("xray") ||
            lowerPrompt.contains("b2b") ||
            lowerPrompt.contains("find clients") ||
            lowerPrompt.contains("find client") ||
            lowerPrompt.contains("search linkedin") ||
            lowerPrompt.contains("find businesses") ||
            lowerPrompt.contains("find business") ||
            lowerPrompt.contains("client search")
        ) {
            try {
                val xrayOutput = processB2BXRaySearchExecution(userPrompt)
                if (xrayOutput.isNotBlank()) {
                    return@withContext Pair(xrayOutput, "Wasti B2B X-Ray Engine")
                }
            } catch (e: Exception) {
                Log.e("WastiCore", "Error processing B2B X-Ray search request, falling back", e)
            }
        }

        // Check if intent requires Lead Radar System search or evaluation
        if (lowerPrompt.contains("lead radar") ||
            (lowerPrompt.contains("find") && (lowerPrompt.contains("lead") || lowerPrompt.contains("job"))) ||
            lowerPrompt.contains("scrape leads")
        ) {
            try {
                val leadRadarOutput = processLeadRadarExecution(userPrompt)
                if (leadRadarOutput.isNotBlank()) {
                    return@withContext Pair(leadRadarOutput, "Wasti Lead Radar Engine")
                }
            } catch (e: Exception) {
                Log.e("WastiCore", "Error processing lead radar request, falling back", e)
            }
        }

        // Check if intent requires native tools (screen, tap, email draft, linkedin, web search, page scraping) via Function Calling
        if (lowerPrompt.contains("screen") || lowerPrompt.contains("tap") || lowerPrompt.contains("click") || lowerPrompt.contains("touch") || lowerPrompt.contains("email") || lowerPrompt.contains("draft") || lowerPrompt.contains("outreach") || lowerPrompt.contains("linkedin") || lowerPrompt.contains("post") || lowerPrompt.contains("social") || lowerPrompt.contains("search") || lowerPrompt.contains("google") || lowerPrompt.contains("web") || lowerPrompt.contains("news") || lowerPrompt.contains("online") || lowerPrompt.contains("find") || lowerPrompt.contains("latest") || lowerPrompt.contains("http") || lowerPrompt.contains("url") || lowerPrompt.contains("scrape") || lowerPrompt.contains("page") || lowerPrompt.contains("read") || lowerPrompt.contains("site") || lowerPrompt.contains("link")) {
            try {
                val functionCallResult = executeFunctionCallingLoop(
                    userPrompt = userPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    history = history
                )
                if (functionCallResult.isNotBlank()) {
                    return@withContext Pair(functionCallResult, "Wasti AI")
                }
            } catch (e: Exception) {
                Log.e("WastiCore", "Error executing function calling loop, falling back to multi-provider dispatch", e)
            }
        }

        // 3. Deep-Lane Multi-Provider Async Dispatch
        val providerIds = listOf("gemini", "groq", "openai", "deepseek", "xai")

        val tasks = providerIds.map { providerId ->
            async {
                try {
                    val response = AIManager.execute(
                        prompt = userPrompt,
                        systemInstruction = enrichedSystemInstruction,
                        history = history,
                        imageInlineData = imageInlineData,
                        mimeType = mimeType,
                        mediaList = mediaList,
                        fileContext = fileContext,
                        preferredProviderId = providerId
                    )
                    if (response.isError || response.content.isBlank()) {
                        val errMsg = response.errorMessage ?: "Provider $providerId returned error or blank response"
                        DeveloperLogger.logError(
                            providerId = providerId,
                            errorMessage = errMsg,
                            errorType = "API_FAILURE"
                        )
                        null
                    } else {
                        Pair(providerId, response.content)
                    }
                } catch (e: Exception) {
                    val errMsg = e.message ?: e.toString()
                    DeveloperLogger.logError(
                        providerId = providerId,
                        errorMessage = errMsg,
                        errorType = "API_EXCEPTION"
                    )
                    null
                }
            }
        }

        val successfulOutputs = awaitAll(*tasks.toTypedArray()).filterNotNull()

        if (successfulOutputs.isEmpty()) {
            return@withContext try {
                val fallbackResp = AIManager.execute(
                    prompt = userPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    history = history,
                    imageInlineData = imageInlineData,
                    mimeType = mimeType,
                    fileContext = fileContext
                )
                if (fallbackResp.isError || fallbackResp.content.isBlank()) {
                    DeveloperLogger.logError("all_providers", fallbackResp.errorMessage ?: "All providers failed", "TOTAL_FAILURE")
                    Pair("Wasti AI is currently processing in low-connectivity state. Please check network or API keys in Developer Settings.", "Wasti AI")
                } else {
                    Pair(fallbackResp.content, "Wasti AI")
                }
            } catch (e: Exception) {
                DeveloperLogger.logError("fallback", e.message ?: "Fallback exception", "TOTAL_FAILURE")
                Pair("Wasti AI encountered a processing delay. Please retry in a moment.", "Wasti AI")
            }
        }

        if (successfulOutputs.size == 1) {
            return@withContext Pair(successfulOutputs.first().second, "Wasti AI")
        }

        // 4. Primary Synthesizer Model Consolidation
        val synthesizerPrompt = """
            You are Wasti AI, the primary synthesizer core of Wasti OS. You have collected independent background intelligence outputs from multiple internal providers for a user request.

            CRITICAL DYNAMIC LANGUAGE RULE: You MUST output the synthesized response in the EXACT SAME language, dialect, and script as the [LATEST USER PROMPT] below ("$userPrompt"). If [LATEST USER PROMPT] is in English, your final response MUST be strictly 100% in English. If in Urdu script (اردو), strictly 100% in Urdu script. If in Roman Urdu, in Roman Urdu. IGNORE the language of previous messages or internal nodes. NEVER default to Roman Urdu unless [LATEST USER PROMPT] itself is in Roman Urdu!

            [LATEST USER PROMPT]:
            $userPrompt

            ${successfulOutputs.mapIndexed { idx, pair -> "[INTERNAL NODE ${idx + 1} (${pair.first.uppercase()})]:\n${pair.second}\n" }.joinToString("\n")}

            SYNTHESIS MANDATES:
            - Synthesize, merge, and deduplicate all findings into ONE single, highly intelligent, cohesive, authoritative final response.
            - Resolve any contradictions between internal nodes.
            - Maintain an executive, clear, professional tone.
            - Strictly display "Wasti AI" as the persona. You MUST NEVER mention provider names (OpenAI, Gemini, Groq, DeepSeek, xAI), model names, or internal node tags in your final text.
        """.trimIndent()

        val finalSynthesized = try {
            val syncResponse = AIManager.execute(
                prompt = synthesizerPrompt,
                systemInstruction = "You are Wasti AI, synthesizing deep multi-lane intelligence into a unified response. STRICT LANGUAGE MATCHING MANDATE: You MUST reply in the EXACT SAME language, dialect, and script used by the user in their prompt. If the user prompts in English, reply strictly in English. If the user prompts in Urdu script (اردو), reply in Urdu script. If the user prompts in Roman Urdu, reply in Roman Urdu. If the user prompts in Spanish, French, Punjabi, German, Hindi, or any other language, reply in that exact language. NEVER default to Roman Urdu or any other language unless the user specifically wrote in that language.",
                preferredProviderId = successfulOutputs.first().first
            )
            if (!syncResponse.isError && syncResponse.content.isNotBlank()) {
                syncResponse.content
            } else {
                successfulOutputs.first().second
            }
        } catch (e: Exception) {
            DeveloperLogger.logError("synthesizer", e.message ?: "Synthesizer exception", "SYNTHESIS_FAILURE")
            successfulOutputs.first().second
        }

        Pair(finalSynthesized, "Wasti AI")
    }

    /**
     * Autonomous Gemini Function Calling Dispatcher.
     * Evaluates initial Gemini response for function calls (read_active_screen, tap_element),
     * executes the corresponding device action, and completes the follow-up loop.
     */
    suspend fun executeFunctionCallingLoop(
        userPrompt: String,
        systemInstruction: String,
        history: List<com.example.data.api.GeminiContent> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val contentsList = mutableListOf<com.example.data.api.GeminiContent>()
        contentsList.addAll(history)
        contentsList.add(
            com.example.data.api.GeminiContent(
                role = "user",
                parts = listOf(com.example.data.api.GeminiPart(text = userPrompt))
            )
        )

        val rawResponse = com.example.data.api.GeminiClient.generateContentRaw(
            contentsList = contentsList,
            systemInstruction = systemInstruction
        )

        val candidatePart = rawResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()
        val functionCall = candidatePart?.functionCall

        if (functionCall != null) {
            val resultText = dispatchFunctionCall(functionCall)

            // Append assistant model turn with functionCall
            contentsList.add(
                com.example.data.api.GeminiContent(
                    role = "model",
                    parts = listOf(candidatePart)
                )
            )

            // Append user turn with functionResponse
            contentsList.add(
                com.example.data.api.GeminiContent(
                    role = "user",
                    parts = listOf(
                        com.example.data.api.GeminiPart(
                            functionResponse = com.example.data.api.GeminiFunctionResponse(
                                name = functionCall.name,
                                response = mapOf("result" to resultText)
                            )
                        )
                    )
                )
            )

            val followUpResponse = com.example.data.api.GeminiClient.generateContentRaw(
                contentsList = contentsList,
                systemInstruction = systemInstruction
            )

            val finalPart = followUpResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()
            return@withContext finalPart?.text ?: "Action executed: $resultText"
        }

        return@withContext candidatePart?.text ?: "Request processed."
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

    /**
     * Executes Lead Radar fetching, evaluation against SkillMatrix, and presentation formatting.
     */
    suspend fun processLeadRadarExecution(userPrompt: String): String = withContext(Dispatchers.IO) {
        updateProgress(ProgressStage.CONNECTING, "Connecting to Lead Radar Engine...")
        val lower = userPrompt.lowercase()
        val query = when {
            lower.contains("video") -> "Video Editing"
            lower.contains("graphic") || lower.contains("logo") -> "Graphic Design"
            lower.contains("autocad") || lower.contains("cad") -> "AutoCAD"
            lower.contains("corel") -> "CorelDRAW"
            lower.contains("canva") -> "Canva"
            lower.contains("dmca") || lower.contains("takedown") -> "DMCA Takedowns"
            lower.contains("ai") || lower.contains("automation") -> "AI Automation"
            else -> "Video Editing"
        }

        updateProgress(ProgressStage.SCRAPING, "Scraping live RSS job feeds for '$query'...")
        val skillMatrix = SkillMatrix()
        val leads = LeadScraperEngine.fetchLeadsForQuery(query)

        if (leads.isEmpty()) {
            updateProgress(ProgressStage.COMPLETED, "Lead Radar query returned 0 active feeds.")
            return@withContext "🎯 **Wasti Lead Radar System**\n\nNo active job requests could be retrieved for '$query' at this moment."
        }

        updateProgress(ProgressStage.ANALYZING, "Evaluating ${leads.size} leads against SkillMatrix...")
        val sb = StringBuilder()
        sb.append("🎯 **Wasti Lead Radar System — Targeted Job Opportunities**\n\n")
        sb.append("Scanned live RSS job feeds for **$query** and evaluated top requests against your **SkillMatrix**:\n\n")

        leads.take(3).forEachIndexed { idx, lead ->
            val fullText = "${lead.title}\n${lead.description}"
            val evaluation = LeadScraperEngine.evaluateLeadMatch(fullText, skillMatrix)

            sb.append("### ${idx + 1}. ${lead.title}\n")
            sb.append("• **Category**: $query\n")
            sb.append("• **Match Score**: 🎯 **${evaluation.matchScore}/100**\n")
            sb.append("• **Matched Skills**: ${evaluation.matchedSkills.joinToString(", ")}\n")
            sb.append("• **Link**: [Apply / View Request](${lead.link})\n\n")
            sb.append("📝 **Drafted Pitch / Proposal**:\n")
            sb.append("> ${evaluation.draftedPitch.replace("\n", "\n> ")}\n\n")
            sb.append("---\n\n")
        }

        sb.append("*Evaluated against official SkillMatrix services: ${skillMatrix.formatSkillSummary()}*")
        val finalOutput = sb.toString()

        updateProgress(ProgressStage.VERIFYING, "Verifying Lead Radar payload with TaskValidator...")
        val validation = TaskValidator.validateExecution(
            actionName = "processLeadRadarExecution",
            isSuccess = true,
            responseCode = 200,
            message = "Retrieved and evaluated ${leads.size} job leads for query '$query'."
        )

        if (!validation.isVerified) {
            updateProgress(ProgressStage.FAILED, "Lead Radar validation failed.")
            return@withContext "ERROR [LeadRadar]: ${validation.errorExplanation}"
        }

        updateProgress(ProgressStage.COMPLETED, "Lead Radar scan completed.")
        finalOutput
    }

    /**
     * Executes B2B X-Ray Search by formatting an optimized Google X-Ray search string via Gemini,
     * querying WebSearchEngine, and presenting structured leads to the user.
     */
    suspend fun processB2BXRaySearchExecution(
        userPrompt: String,
        context: android.content.Context? = null
    ): String = withContext(Dispatchers.IO) {
        updateProgress(ProgressStage.CONNECTING, "Connecting to B2B X-Ray Engine...")
        Log.i("WastiCore", "Initiating B2B X-Ray search pipeline for request: $userPrompt")

        val promptForXRayQuery = """
            You are a B2B Growth Specialist and Boolean X-Ray Search Expert.
            Convert the user request into an optimized Google X-Ray search query string that targets client profiles, decision-makers, or hiring managers on networks like LinkedIn (site:linkedin.com/in), Twitter/X, Instagram, or company sites without violating anti-bot terms.

            Examples:
            - User request: "find video editor clients in Lahore hiring now" -> site:linkedin.com/in "Lahore" "hiring" "video editor"
            - User request: "search linkedin for marketing agency owners in Dubai" -> site:linkedin.com/in "Marketing Agency Owner" OR "Managing Director" "Dubai"
            - User request: "find B2B clients for SaaS development" -> site:linkedin.com/in "CTO" OR "VP of Engineering" OR "Founder" "hiring" "SaaS"

            CRITICAL RULE: Output ONLY the exact raw search query string. Do NOT enclose in quotes, code blocks, or add explanations.
            User Request: "$userPrompt"
        """.trimIndent()

        updateProgress(ProgressStage.ANALYZING, "Generating targeted Google X-Ray query via Gemini...")
        val xrayQueryRaw = try {
            val resp = AIManager.execute(
                prompt = promptForXRayQuery,
                systemInstruction = "You generate precise Google X-Ray search queries. Return ONLY the search string."
            )
            if (!resp.isError && resp.content.isNotBlank()) {
                resp.content.trim().trim('"', '\'', '`')
            } else null
        } catch (e: Exception) {
            Log.w("WastiCore", "Gemini X-Ray query generation failed, using fallback rule", e)
            null
        }

        val xrayQuery = if (!xrayQueryRaw.isNullOrBlank() && xrayQueryRaw.lines().size == 1 && xrayQueryRaw.length < 250) {
            xrayQueryRaw
        } else {
            val cleanPrompt = userPrompt.replace(Regex("(?i)(find clients|find client|b2b|x-ray|xray|search linkedin for|search linkedin|find businesses|find business|search for|find)"), "").trim()
            if (cleanPrompt.isNotBlank()) {
                "site:linkedin.com/in \"$cleanPrompt\""
            } else {
                "site:linkedin.com/in \"hiring\" \"clients\""
            }
        }

        updateProgress(ProgressStage.SCRAPING, "Querying WebSearchEngine ($xrayQuery)...")
        Log.i("WastiCore", "Executing WebSearchEngine query: $xrayQuery")
        val jsonResult = com.example.data.ops.WebSearchEngine.search(xrayQuery, context)

        val sb = StringBuilder()
        sb.append("🔍 **Wasti B2B X-Ray Lead Discovery**\n\n")
        sb.append("Targeted Google X-Ray Query: `$xrayQuery`\n\n")

        var isSearchSuccess = false
        try {
            val jsonObject = org.json.JSONObject(jsonResult)
            val resultsArray = jsonObject.optJSONArray("results")

            if (resultsArray != null && resultsArray.length() > 0) {
                isSearchSuccess = true
                sb.append("### Identified B2B Prospects & Decision-Makers:\n\n")
                var count = 0
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.optJSONObject(i) ?: continue
                    val title = item.optString("title", "Lead Result ${i + 1}")
                    val snippet = item.optString("snippet", "No summary available.")
                    val link = item.optString("link", "#")

                    count++
                    sb.append("#### ${count}. $title\n")
                    sb.append("• **Overview**: $snippet\n")
                    sb.append("• **Direct Profile Link**: [$link]($link)\n\n")

                    // Automatically ingest discovered X-Ray lead into CRM with Gemini & Search Enrichment
                    val leadItem = LeadItemEntity(
                        title = title,
                        link = link,
                        description = snippet,
                        category = "Google X-Ray"
                    )
                    if (context != null) {
                        LeadRadarRepository.ingestToCrm(context, leadItem, "NEW")
                    } else {
                        LeadRadarRepository.ingestToCrm(leadItem)
                    }
                }
                sb.append("---\n")
                sb.append("💡 *Tip: Tap profile links to connect directly or use Wasti Email/LinkedIn drafter to generate personalized outreach.*")
            } else {
                sb.append("No direct X-Ray results retrieved for query `$xrayQuery`. Try broadening your location or role keywords.")
            }
        } catch (e: Exception) {
            Log.e("WastiCore", "Error parsing X-Ray search JSON", e)
            sb.append("### Search Output:\n")
            sb.append(jsonResult)
        }

        val resultOutput = sb.toString()

        updateProgress(ProgressStage.VERIFYING, "Validating B2B X-Ray execution payload...")
        val validation = TaskValidator.validateExecution(
            actionName = "processB2BXRaySearchExecution",
            isSuccess = true,
            responseCode = if (isSearchSuccess) 200 else 204,
            message = "B2B X-Ray search completed for query '$xrayQuery'.",
            context = context
        )

        if (!validation.isVerified && validation.responseCode in 400..599) {
            updateProgress(ProgressStage.FAILED, "B2B X-Ray search failed.")
            return@withContext "ERROR [B2BXRay]: ${validation.errorExplanation}"
        }

        updateProgress(ProgressStage.COMPLETED, "B2B X-Ray search finished.")
        resultOutput
    }

    /**
     * Routes native Gemini FunctionCall objects directly to device/engine controllers.
     */
    suspend fun dispatchFunctionCall(
        functionCall: com.example.data.api.GeminiFunctionCall,
        context: android.content.Context? = null
    ): String {
        updateProgress(ProgressStage.DISPATCHING, "Dispatching tool '${functionCall.name}'...")

        val (rawResult, isSuccess, code) = when (functionCall.name) {
            "read_active_screen" -> {
                Log.d("WastiCore", "FunctionCall dispatched: read_active_screen")
                updateProgress(ProgressStage.SCRAPING, "Scraping active screen content...")
                val res = com.example.data.device.WastiDeviceController.readScreenContent()
                Triple(res, res.isNotBlank() && res != "[]", 200)
            }
            "tap_element" -> {
                val elementId = functionCall.args?.get("elementIdentifier") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: tap_element -> $elementId")
                updateProgress(ProgressStage.DISPATCHING, "Simulating tap on '$elementId'...")
                val tapResult = com.example.data.device.WastiDeviceController.simulateTap(targetElement = elementId)
                Triple(tapResult.userFeedback, tapResult.success, if (tapResult.success) 200 else 400)
            }
            "evaluate_lead_match" -> {
                val jobText = functionCall.args?.get("jobPostText") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: evaluate_lead_match")
                updateProgress(ProgressStage.ANALYZING, "Evaluating lead match against SkillMatrix...")
                val evaluation = LeadScraperEngine.evaluateLeadMatch(jobText)
                Triple("MatchScore: ${evaluation.matchScore}/100\nDraftedPitch: ${evaluation.draftedPitch}", true, 200)
            }
            "draft_email" -> {
                val to = functionCall.args?.get("to") ?: ""
                val subject = functionCall.args?.get("subject") ?: ""
                val body = functionCall.args?.get("body") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: draft_email -> $to")
                updateProgress(ProgressStage.VERIFYING, "Drafting email for $to...")
                val draft = EmailDraft(to = to, subject = subject, body = body)
                setPendingEmailDraft(draft)
                Triple("Email draft created for $to. Paused AI execution awaiting user approval in Chat Workspace.", true, 200)
            }
            "post_to_linkedin" -> {
                val content = functionCall.args?.get("content") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: post_to_linkedin")
                updateProgress(ProgressStage.VERIFYING, "Drafting LinkedIn post...")
                val draft = LinkedInDraft(content = content)
                setPendingLinkedInDraft(draft)
                Triple("LinkedIn post draft created. Paused AI execution awaiting user approval in Chat Workspace.", true, 200)
            }
            "search_web" -> {
                val query = functionCall.args?.get("query") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: search_web -> $query")
                updateProgress(ProgressStage.SCRAPING, "Executing web search for '$query'...")
                val res = com.example.data.ops.WebSearchEngine.search(query, context)
                val ok = !res.contains("\"error\"") && !res.contains("Exception")
                Triple(res, ok, if (ok) 200 else 500)
            }
            "b2b_xray_search" -> {
                val query = functionCall.args?.get("query") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: b2b_xray_search -> $query")
                val res = processB2BXRaySearchExecution(query, context)
                val ok = !res.startsWith("ERROR") && !res.contains("FAILED")
                Triple(res, ok, if (ok) 200 else 500)
            }
            "read_web_page" -> {
                val url = functionCall.args?.get("url") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: read_web_page -> $url")
                updateProgress(ProgressStage.SCRAPING, "Scraping web page ($url)...")
                val res = com.example.data.ops.WebSearchEngine.scrapeWebPage(url)
                val ok = !res.startsWith("Failed to fetch")
                Triple(res, ok, if (ok) 200 else 400)
            }
            else -> {
                Log.w("WastiCore", "Unknown FunctionCall: ${functionCall.name}")
                Triple("Function '${functionCall.name}' is not recognized by Wasti OS.", false, 404)
            }
        }

        updateProgress(ProgressStage.VERIFYING, "Validating tool execution with TaskValidator...")
        val validation = TaskValidator.validateExecution(
            actionName = functionCall.name,
            isSuccess = isSuccess,
            responseCode = code,
            message = rawResult,
            context = context
        )

        if (!validation.isVerified) {
            updateProgress(ProgressStage.FAILED, "Tool execution failed: ${validation.errorExplanation}")
            return "ERROR: Tool '${functionCall.name}' execution failed validation (Code ${validation.responseCode}): ${validation.errorExplanation ?: validation.detailMessage}"
        }

        updateProgress(ProgressStage.COMPLETED, "Tool '${functionCall.name}' executed and verified.")
        return rawResult
    }
}


