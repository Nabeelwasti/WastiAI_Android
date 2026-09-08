package com.example.data.ai.engine

import com.example.data.api.GeminiContent

/**
 * [Wasti AI OS - Sovereign Conversation Context Coordinator]
 *
 * Provides ultra-expanded, intelligent multi-turn context coordination without
 * artificial token caps or premature truncation. Designed to leverage full 1M+ token
 * context windows across Gemini 3.6 Flash, DeepSeek V3, Groq Llama 3.3 70B, GPT-4o,
 * and Claude 3.5 Sonnet.
 */
class ConversationCoordinator(
    private val maxHistoryChars: Int = 1_000_000
) {

    fun formatHistoryTranscript(history: List<GeminiContent>): String {
        if (history.isEmpty()) return ""

        // Process all available multi-turn history without arbitrary down-sampling
        val rawItems = history
        val formattedLines = mutableListOf<String>()
        var accumulatedChars = 0

        // Iterate backwards from newest to oldest to preserve chronological integrity
        for (i in rawItems.indices.reversed()) {
            val item = rawItems[i]
            val role = if (item.role == "user") "User" else "Wasti AI"
            val rawText = item.parts.firstOrNull()?.text ?: ""
            if (rawText.isBlank()) continue

            val line = "[$role]: $rawText"
            if (accumulatedChars + line.length > maxHistoryChars) {
                formattedLines.add(0, "[...Earlier conversation turns archived in long-term memory...]")
                break
            }
            formattedLines.add(0, line)
            accumulatedChars += line.length
        }

        val lines = formattedLines.joinToString("\n")
        return "\n\n[CONVERSATION HISTORY - FULL FIDELITY]:\n$lines\n[END CONVERSATION HISTORY]\n"
    }

    fun enrichPromptWithContext(
        userPrompt: String,
        fileContext: String? = null,
        workspaceContext: String? = null
    ): String {
        val parts = mutableListOf<String>()

        if (!workspaceContext.isNullOrBlank()) {
            parts.add("[ACTIVE WORKSPACE ENVIRONMENT]:\n$workspaceContext")
        }

        if (!fileContext.isNullOrBlank()) {
            parts.add("[ACTIVE FILE CONTEXT - FULL FIDELITY]:\n```\n$fileContext\n```")
        }

        parts.add("""
            CRITICAL MULTI-TURN DYNAMIC LANGUAGE MANDATE:
            You MUST reply in the EXACT SAME language, dialect, and script used in the [LATEST USER PROMPT] below.
            - The user may dynamically switch languages from message to message in this chat session.
            - IGNORE the language used in previous conversation history or past assistant turns.
            - If [LATEST USER PROMPT] is in English -> Reply strictly 100% in English!
            - If [LATEST USER PROMPT] is in Urdu script (اردو) -> Reply strictly 100% in Urdu script!
            - If [LATEST USER PROMPT] is in Roman Urdu -> Reply in Roman Urdu!
            - If [LATEST USER PROMPT] is in Spanish, French, German, Arabic, Punjabi, Hindi, or any other language -> Reply strictly in that exact language!
            - DO NOT reply in Roman Urdu unless the [LATEST USER PROMPT] itself is written in Roman Urdu!

            [LATEST USER PROMPT]:
            $userPrompt
        """.trimIndent())

        return parts.joinToString("\n\n")
    }
}

