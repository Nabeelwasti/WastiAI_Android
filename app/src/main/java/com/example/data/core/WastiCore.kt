package com.example.data.core

import com.example.data.ai.AIManager
import com.example.data.ai.model.ProviderCapability
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

enum class RoutingTier {
    FAST_LANE,     // Tier 1: Simple / short queries -> fastest single model
    STANDARD_LANE, // Tier 2: Normal requests -> agent primary with failover chain
    DEEP_LANE,     // Tier 3: Complex / high-stakes -> parallel models merged by reviewer
    OFFLINE_LANE   // Tier 4: No internet -> local synthesis engine
}

object WastiCore {

    fun classifyIntentTier(prompt: String): RoutingTier {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // Explicit Deep Lane triggers
        val deepKeywords = listOf(
            "deep research", "architecture decision", "multi-step strategy",
            "system design", "financial modeling", "security audit", "refactor system",
            "comprehensive breakdown", "strategic roadmap", "unit economics"
        )
        if (deepKeywords.any { lower.contains(it) } || (trimmed.length > 500 && lower.contains("analyze"))) {
            return RoutingTier.DEEP_LANE
        }

        // Real-Time, Search, Clock, and Device Control queries -> STANDARD_LANE
        val realTimeKeywords = listOf(
            "time", "date", "clock", "pakistan", "news", "weather", "search",
            "current", "latest", "today", "price", "who is", "what is", "where is",
            "open", "launch", "whatsapp", "camera", "youtube"
        )
        if (realTimeKeywords.any { lower.contains(it) }) {
            return RoutingTier.STANDARD_LANE
        }

        // Fast Lane triggers
        val fastKeywords = listOf("hi", "hello", "salam", "hey", "thanks", "kaise ho", "kya haal hai")
        if (trimmed.length < 35 || fastKeywords.any { lower == it || lower.startsWith("$it ") }) {
            return RoutingTier.FAST_LANE
        }

        return RoutingTier.STANDARD_LANE
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

        val memoryContext = com.example.data.memory.MemoryManager.retrieveRelevantContextPrompt(userPrompt)
        val enrichedSystemInstruction = if (memoryContext.isNotBlank()) {
            "$systemInstruction\n\n$memoryContext"
        } else {
            systemInstruction
        }

        val tier = classifyIntentTier(userPrompt)

        when (tier) {
            RoutingTier.FAST_LANE -> {
                // Delegate to AIManager with preference for fast providers (Groq or Gemini)
                val response = AIManager.execute(
                    prompt = userPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    history = history,
                    imageInlineData = imageInlineData,
                    mimeType = mimeType,
                    fileContext = fileContext,
                    preferredProviderId = "groq"
                )
                Pair(response.content, "Wasti AI Engine (${response.providerName})")
            }

            RoutingTier.STANDARD_LANE -> {
                val isCodingTask = activeAgentId == "coding_agent" ||
                        userPrompt.lowercase().contains("code") ||
                        userPrompt.lowercase().contains("kotlin") ||
                        userPrompt.lowercase().contains("function")

                val preferredProvider = if (isCodingTask && imageInlineData.isNullOrBlank()) "deepseek" else "gemini"

                val response = AIManager.execute(
                    prompt = userPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    history = history,
                    imageInlineData = imageInlineData,
                    mimeType = mimeType,
                    fileContext = fileContext,
                    preferredProviderId = preferredProvider
                )
                Pair(response.content, "Wasti AI Engine (${response.providerName})")
            }

            RoutingTier.DEEP_LANE -> {
                // Tier 3: Parallel Execution via AIManager Coroutines
                val task1 = async {
                    try {
                        AIManager.execute(
                            prompt = userPrompt,
                            systemInstruction = systemInstruction,
                            fileContext = fileContext,
                            preferredProviderId = "gemini"
                        ).content
                    } catch (e: Exception) { "" }
                }
                val task2 = async {
                    try {
                        AIManager.execute(
                            prompt = userPrompt,
                            systemInstruction = systemInstruction,
                            fileContext = fileContext,
                            preferredProviderId = "groq"
                        ).content
                    } catch (e: Exception) { "" }
                }
                val task3 = async {
                    try {
                        AIManager.execute(
                            prompt = userPrompt,
                            systemInstruction = systemInstruction,
                            fileContext = fileContext,
                            preferredProviderId = "deepseek"
                        ).content
                    } catch (e: Exception) { "" }
                }

                val outputs = awaitAll(task1, task2, task3).filter { it.isNotBlank() }

                if (outputs.isEmpty()) {
                    val fallback = AIManager.execute(
                        prompt = userPrompt,
                        systemInstruction = systemInstruction,
                        fileContext = fileContext
                    )
                    return@withContext Pair(fallback.content, "Wasti AI Engine (Deep Analysis)")
                }

                if (outputs.size == 1) {
                    return@withContext Pair(outputs.first(), "Wasti AI Engine (Deep Analysis)")
                }

                val reviewerPrompt = """
                You are the Wasti AI Reviewer Engine. You have received multiple independent deep-analysis outputs from internal intelligence nodes for a high-stakes request.
                
                [USER PROMPT]:
                $userPrompt
                
                [INTERNAL NODE OUTPUT 1]:
                ${outputs.getOrElse(0) { "N/A" }}
                
                [INTERNAL NODE OUTPUT 2]:
                ${outputs.getOrElse(1) { "N/A" }}
                
                [INTERNAL NODE OUTPUT 3]:
                ${outputs.getOrElse(2) { "N/A" }}
                
                INSTRUCTIONS:
                - Synthesize, merge, and deduplicate all findings into ONE cohesive, authoritative, beautifully structured final response.
                - Resolve any contradictions between internal nodes.
                - Do NOT mention model names or internal node names. Attribute all findings as Wasti AI.
                """.trimIndent()

                val finalSynthesized = try {
                    AIManager.execute(
                        prompt = reviewerPrompt,
                        systemInstruction = "You are Wasti AI, synthesizing deep intelligence into a unified authoritative response.",
                        preferredProviderId = "gemini"
                    ).content
                } catch (e: Exception) {
                    outputs.first()
                }

                Pair(finalSynthesized, "Wasti AI Engine (Deep Analysis)")
            }

            RoutingTier.OFFLINE_LANE -> {
                val fallback = AIManager.execute(
                    prompt = userPrompt,
                    systemInstruction = systemInstruction,
                    preferredProviderId = "offline"
                )
                Pair(fallback.content, "Wasti AI Engine (Local Core)")
            }
        }
    }
}

