package com.example.util

/**
 * Utility for sanitizing Markdown, special characters, code blocks,
 * and formatting tags before sending text to the TextToSpeech engine.
 */
object WastiSpeechSanitizer {

    /**
     * Cleans input text into fluent, human-readable prose for TTS.
     */
    fun sanitizeForSpeech(input: String?): String {
        if (input.isNullOrBlank()) return ""

        return input
            // Replace multi-line code blocks with summary
            .replace(Regex("```[\\s\\S]*?```"), " Code block generated. ")
            // Remove inline backtick code snippets
            .replace(Regex("`[^`]+`"), " ")
            // Convert markdown links [Text](URL) -> Text
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            // Remove URLs
            .replace(Regex("https?://\\S+"), " link ")
            // Strip HTML/XML tags
            .replace(Regex("<[^>]*>"), " ")
            // Strip markdown formatting symbols (*, #, _, ~, `, |, >, -, +, =, [, ], {, }, ^, %, $, @, \)
            .replace(Regex("[#*`_~\\[\\]\\\\()<>{}=+|!$@%^&~-]"), " ")
            // Replace emojis or unusual unicode symbols if needed
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]"), "")
            // Normalize spaces and newlines
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
