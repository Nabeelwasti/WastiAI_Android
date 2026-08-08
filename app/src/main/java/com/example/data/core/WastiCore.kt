package com.example.data.core

import android.util.Log
import com.example.data.ai.AIManager
import com.example.data.log.DeveloperLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

enum class RoutingTier {
    FAST_LANE,     // Tier 1
    STANDARD_LANE, // Tier 2
    DEEP_LANE,     // Tier 3
    OFFLINE_LANE   // Tier 4
}

object WastiCore {

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
        mimeType: String = "image/jpeg"
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

        // Check if intent requires Lead Radar System search or evaluation
        val lowerPrompt = userPrompt.lowercase().trim()
        if (lowerPrompt.contains("lead radar") ||
            (lowerPrompt.contains("find") && (lowerPrompt.contains("client") || lowerPrompt.contains("lead") || lowerPrompt.contains("job"))) ||
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

        // Check if intent requires native screen reading or tap execution via Function Calling
        if (lowerPrompt.contains("screen") || lowerPrompt.contains("tap") || lowerPrompt.contains("click") || lowerPrompt.contains("touch")) {
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

        val skillMatrix = SkillMatrix()
        val leads = LeadScraperEngine.fetchLeadsForQuery(query)

        if (leads.isEmpty()) {
            return@withContext "🎯 **Wasti Lead Radar System**\n\nNo active job requests could be retrieved for '$query' at this moment."
        }

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
        sb.toString()
    }

    /**
     * Routes native Gemini FunctionCall objects directly to device/engine controllers.
     */
    fun dispatchFunctionCall(functionCall: com.example.data.api.GeminiFunctionCall): String {
        return when (functionCall.name) {
            "read_active_screen" -> {
                Log.d("WastiCore", "FunctionCall dispatched: read_active_screen")
                com.example.data.device.WastiDeviceController.readScreenContent()
            }
            "tap_element" -> {
                val elementId = functionCall.args?.get("elementIdentifier") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: tap_element -> $elementId")
                val tapResult = com.example.data.device.WastiDeviceController.simulateTap(targetElement = elementId)
                tapResult.userFeedback
            }
            "evaluate_lead_match" -> {
                val jobText = functionCall.args?.get("jobPostText") ?: ""
                Log.d("WastiCore", "FunctionCall dispatched: evaluate_lead_match")
                val evaluation = LeadScraperEngine.evaluateLeadMatch(jobText)
                "MatchScore: ${evaluation.matchScore}/100\nDraftedPitch: ${evaluation.draftedPitch}"
            }
            else -> {
                Log.w("WastiCore", "Unknown FunctionCall: ${functionCall.name}")
                "Function '${functionCall.name}' is not recognized by Wasti OS."
            }
        }
    }
}


