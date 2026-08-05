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

        parts.add("[USER REQUEST]:\n$userPrompt")

        return parts.joinToString("\n\n")
    }
}
