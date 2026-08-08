package com.example.data.ai.engine

import com.example.data.api.GeminiContent

class ConversationCoordinator {

    fun formatHistoryTranscript(history: List<GeminiContent>): String {
        if (history.isEmpty()) return ""
        val lines = history.takeLast(20).joinToString("\n") { item ->
            val role = if (item.role == "user") "User" else "Wasti AI"
            val text = item.parts.firstOrNull()?.text ?: ""
            "[$role]: $text"
        }
        return "\n\n[RECENT CONVERSATION HISTORY (Last 20 Messages)]:\n$lines\n[END CONVERSATION HISTORY]\n"
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
            parts.add("[ACTIVE FILE CONTEXT]:\n```\n$fileContext\n```")
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
