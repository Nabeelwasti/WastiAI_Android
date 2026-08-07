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

        val enrichedSystemInstruction = if (memoryContext.isNotBlank()) {
            "$systemInstruction\n\n$memoryContext"
        } else {
            systemInstruction
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

            [USER PROMPT]:
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
                systemInstruction = "You are Wasti AI, synthesizing deep multi-lane intelligence into a unified response.",
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
}


