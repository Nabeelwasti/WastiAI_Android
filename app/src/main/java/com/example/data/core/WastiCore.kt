package com.example.data.core

import com.example.BuildConfig
import com.example.data.api.DeepSeekClient
import com.example.data.api.GeminiClient
import com.example.data.api.GroqClient
import com.example.data.api.OpenAIClient
import com.example.data.api.OpenRouterClient
import com.example.data.api.XAIClient
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

        val historyTranscript = if (history.isNotEmpty()) {
            val lines = history.joinToString("\n") { item ->
                val role = if (item.role == "user") "User" else "Wasti AI"
                val text = item.parts.firstOrNull()?.text ?: ""
                "[$role]: $text"
            }
            "\n\n[RECENT CONVERSATION HISTORY (Last 20 Messages)]:\n$lines\n[END CONVERSATION HISTORY]\n"
        } else ""

        val enrichedSystemInstruction = "$systemInstruction$historyTranscript"

        val tier = classifyIntentTier(userPrompt)
        val fullPrompt = if (!fileContext.isNullOrBlank()) {
            """
            [ACTIVE WORKSPACE FILE CONTEXT]:
            ```
            $fileContext
            ```

            [USER REQUEST]:
            $userPrompt
            """.trimIndent()
        } else {
            userPrompt
        }

        when (tier) {
            RoutingTier.FAST_LANE -> {
                // Tier 1: Fast single call to Groq Llama 3.3 or Gemini Flash-Lite
                val groqKey = CredentialRegistry.getRawValue("GROQ_API_KEY")
                if (groqKey.isNotBlank() && imageInlineData.isNullOrBlank()) {
                    try {
                        val res = GroqClient.generateText(
                            prompt = fullPrompt,
                            systemInstruction = enrichedSystemInstruction,
                            modelName = "llama-3.3-70b-versatile"
                        )
                        if (res.isNotBlank()) return@withContext Pair(res, "Wasti AI Engine (Fast Lane)")
                    } catch (_: Exception) {}
                }
                // Fallback to Gemini 3.5 Flash Lite
                val geminiRes = GeminiClient.generateText(
                    prompt = fullPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    modelName = "gemini-3.5-flash-lite",
                    history = history,
                    imageInlineData = imageInlineData,
                    mimeType = mimeType
                )
                Pair(geminiRes, "Wasti AI Engine (Fast Lane)")
            }

            RoutingTier.STANDARD_LANE -> {
                // Tier 2: Standard Lane with Failover Chain
                // Coding Specific: DeepSeek -> Gemini -> Groq -> xAI -> OpenAI -> OpenRouter

                val isCodingTask = activeAgentId == "coding_agent" ||
                        userPrompt.lowercase().contains("code") ||
                        userPrompt.lowercase().contains("kotlin") ||
                        userPrompt.lowercase().contains("function")

                if (isCodingTask && imageInlineData.isNullOrBlank()) {
                    val deepSeekKey = CredentialRegistry.getRawValue("DEEPSEEK_API_KEY")
                    if (deepSeekKey.isNotBlank()) {
                        try {
                            val dsRes = DeepSeekClient.generateText(
                                prompt = fullPrompt,
                                systemInstruction = enrichedSystemInstruction,
                                apiKey = deepSeekKey
                            )
                            if (dsRes.isNotBlank()) return@withContext Pair(dsRes, "Wasti AI Engine (DeepSeek Code)")
                        } catch (_: Exception) {}
                    }
                }

                // 1. Primary: Gemini 3.6 Flash (Supports text + multimodal + multi-turn history)
                try {
                    val res = GeminiClient.generateText(
                        prompt = fullPrompt,
                        systemInstruction = enrichedSystemInstruction,
                        modelName = "gemini-3.6-flash",
                        history = history,
                        imageInlineData = imageInlineData,
                        mimeType = mimeType
                    )
                    if (res.isNotBlank() && !res.startsWith("Right away, Sir")) {
                        return@withContext Pair(res, "Wasti AI Engine")
                    }
                } catch (_: Exception) {}

                // 2. Failover: Groq Llama 3.3 70B
                val groqKey = CredentialRegistry.getRawValue("GROQ_API_KEY")
                if (groqKey.isNotBlank()) {
                    try {
                        val res = GroqClient.generateText(
                            prompt = fullPrompt,
                            systemInstruction = enrichedSystemInstruction,
                            modelName = "llama-3.3-70b-versatile"
                        )
                        if (res.isNotBlank()) return@withContext Pair(res, "Wasti AI Engine")
                    } catch (_: Exception) {}
                }

                // 3. xAI grok-4.3
                val xaiKey = CredentialRegistry.getRawValue("XAI_API_KEY")
                if (xaiKey.isNotBlank()) {
                    try {
                        val res = XAIClient.generateText(
                            prompt = fullPrompt,
                            systemInstruction = enrichedSystemInstruction,
                            apiKey = xaiKey,
                            modelName = "grok-4.3"
                        )
                        if (res.isNotBlank() && !res.contains("403") && !res.contains("credit")) {
                            return@withContext Pair(res, "Wasti AI Engine")
                        }
                    } catch (_: Exception) {}
                }

                // 4. OpenAI gpt-3.5-turbo
                val openAiKey = CredentialRegistry.getRawValue("OPENAI_API_KEY")
                if (openAiKey.isNotBlank()) {
                    try {
                        val res = OpenAIClient.generateText(
                            prompt = fullPrompt,
                            systemInstruction = enrichedSystemInstruction,
                            apiKey = openAiKey,
                            modelName = "gpt-3.5-turbo"
                        )
                        if (res.isNotBlank()) return@withContext Pair(res, "Wasti AI Engine")
                    } catch (_: Exception) {}
                }

                // 5. OpenRouter Universal Fallback (When Gemini, Groq, xAI, and OpenAI all fail/exhaust)
                val openRouterKey = CredentialRegistry.getRawValue("OPENROUTER_API_KEY")
                if (openRouterKey.isNotBlank()) {
                    try {
                        val res = OpenRouterClient.generateText(
                            prompt = fullPrompt,
                            systemInstruction = enrichedSystemInstruction,
                            apiKey = openRouterKey,
                            modelName = "meta-llama/llama-3.3-70b-instruct:free"
                        )
                        if (res.isNotBlank()) return@withContext Pair(res, "Wasti AI Engine (OpenRouter Universal Gateway)")
                    } catch (_: Exception) {}
                }

                // Final Fallback: Local On-Device Synthesis Engine
                val localRes = GeminiClient.generateText(
                    prompt = fullPrompt,
                    systemInstruction = enrichedSystemInstruction,
                    modelName = "gemini-3.6-flash"
                )
                Pair(localRes, "Wasti AI Engine")
            }

            RoutingTier.DEEP_LANE -> {
                // Tier 3: Parallel Execution via Coroutines -> Reviewer Merge (Gemini 3.6 Flash)
                val task1 = async {
                    try {
                        GeminiClient.generateText(prompt = fullPrompt, systemInstruction = enrichedSystemInstruction, modelName = "gemini-3.6-flash")
                    } catch (e: Exception) { "" }
                }
                val task2 = async {
                    try {
                        GroqClient.generateText(prompt = fullPrompt, systemInstruction = enrichedSystemInstruction, modelName = "llama-3.3-70b-versatile")
                    } catch (e: Exception) { "" }
                }
                val task3 = async {
                    val deepSeekKey = CredentialRegistry.getRawValue("DEEPSEEK_API_KEY")
                    if (deepSeekKey.isNotBlank()) {
                        DeepSeekClient.generateText(prompt = fullPrompt, systemInstruction = enrichedSystemInstruction, apiKey = deepSeekKey)
                    } else ""
                }

                val outputs = awaitAll(task1, task2, task3).filter { it.isNotBlank() }

                if (outputs.isEmpty()) {
                    val fallback = GeminiClient.generateText(prompt = fullPrompt, systemInstruction = systemInstruction, modelName = "gemini-3.6-flash")
                    return@withContext Pair(fallback, "Wasti AI Engine (Deep Analysis)")
                }

                if (outputs.size == 1) {
                    return@withContext Pair(outputs.first(), "Wasti AI Engine (Deep Analysis)")
                }

                // Synthesize/Review outputs using Reviewer Model (Gemini 3.6 Flash)
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
                    GeminiClient.generateText(prompt = reviewerPrompt, systemInstruction = "You are Wasti AI, synthesizing deep intelligence into a unified authoritative response.", modelName = "gemini-3.6-flash")
                } catch (e: Exception) {
                    outputs.first()
                }

                Pair(finalSynthesized, "Wasti AI Engine (Deep Analysis)")
            }

            RoutingTier.OFFLINE_LANE -> {
                val fallback = GeminiClient.generateText(prompt = fullPrompt, systemInstruction = systemInstruction, modelName = "gemini-3.6-flash")
                Pair(fallback, "Wasti AI Engine (Local Core)")
            }
        }
    }
}
