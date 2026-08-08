package com.example.util

import java.util.Locale

object WastiUrduLanguageEngine {

    enum class LanguageType {
        URDU_SCRIPT,
        PURE_URDU,
        ROMAN_URDU,
        ENGLISH,
        PUNJABI,
        MIRROR_LANGUAGE
    }

    /**
     * Re-engineered detectLanguage using Unicode character validation.
     * Checks for the Arabic/Urdu Unicode block (\u0600..\u06FF).
     * Returns URDU_SCRIPT if Urdu/Arabic script is detected.
     * Default fallback is MIRROR_LANGUAGE.
     */
    fun detectLanguage(text: String?): LanguageType {
        if (text.isNullOrBlank()) return LanguageType.MIRROR_LANGUAGE

        // Character validation checking for Urdu/Arabic Unicode script block (\u0600..\u06FF)
        if (text.any { it in '\u0600'..'\u06FF' }) {
            return LanguageType.URDU_SCRIPT
        }

        return LanguageType.MIRROR_LANGUAGE
    }

    /**
     * Converts Roman Urdu words into Pure Urdu Script (Nastaliq) phonetics for TTS rendering
     * if explicitly required.
     */
    fun romanUrduToPureUrduScript(romanUrduText: String): String {
        if (romanUrduText.isBlank()) return ""

        val phraseMap = listOf(
            "assalam-o-alaikum" to "السلام علیکم",
            "assalam o alaikum" to "السلام علیکم",
            "assalamoalaikum" to "السلام علیکم",
            "salam wahi" to "سلام واسطی",
            "salam" to "سلام",
            "kya haal hai" to "کیا حال ہے",
            "kaise ho" to "کیسے ہو",
            "kaise hain" to "کیسے ہیں",
            "mera naam" to "میرا نام",
            "shukriya" to "شکریہ",
            "shukria" to "شکریہ",
            "khuda hafiz" to "خدا حافظ",
            "allah hafiz" to "اللہ حافظ"
        )

        var result = romanUrduText
        phraseMap.forEach { (phrase, replacement) ->
            result = result.replace(Regex("(?i)\\b$phrase\\b"), replacement)
        }

        return result
    }

    /**
     * Prepares text for TTS reading without forcing Roman Urdu conversion unless explicitly requested.
     */
    fun prepareTextForTts(rawText: String): String {
        val clean = WastiSpeechSanitizer.sanitizeForSpeech(rawText)
        if (clean.isBlank()) return ""
        return clean
    }

    /**
     * Generates exact Language Prompt Mandate for API requests.
     */
    fun getLanguagePromptMandate(userInput: String): String {
        return "CRITICAL: Mirror the exact language of the user prompt."
    }
}
