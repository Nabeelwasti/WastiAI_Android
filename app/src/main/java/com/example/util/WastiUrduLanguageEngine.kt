package com.example.util

import java.util.Locale

object WastiUrduLanguageEngine {

    enum class LanguageType {
        PURE_URDU,
        ROMAN_URDU,
        ENGLISH,
        PUNJABI
    }

    private val romanUrduKeywords = setOf(
        "kya", "hai", "hain", "kaise", "kaisa", "kaisi", "ho", "main", "mein", "aap", "tum",
        "mera", "meri", "mere", "shukriya", "batao", "bhejo", "karo", "mujh", "se", "naam", "aaj",
        "chal", "kaam", "baat", "salam", "urdu", "suno", "wasti", "bohat", "bahut", "achha",
        "acha", "baatein", "hoga", "raha", "rahi", "rahe", "bolo", "kaha", "karna", "karne",
        "rha", "rhi", "rhe", "nabeel", "kiya", "kuch", "shukria", "theek", "thik", "hoon",
        "hun", "mujhe", "tujhe", "hamara", "humara", "kahan", "kahin", "kab", "kyun", "kyu",
        "chahiye", "sakte", "sakti", "sakta", "pata", "bhi", "par", "pe", "ko", "ne", "ki", "ke", "ka"
    )

    fun detectLanguage(text: String?): LanguageType {
        if (text.isNullOrBlank()) return LanguageType.ENGLISH

        // Pure Urdu Script (\u0600..\u06FF)
        if (text.any { it in '\u0600'..'\u06FF' }) {
            return LanguageType.PURE_URDU
        }

        // Punjabi phrases
        val lower = text.lowercase().trim()
        if (lower.contains("ki haal") || lower.contains("tuhada") || lower.contains("changa") || lower.contains("kithe")) {
            return LanguageType.PUNJABI
        }

        // Tokenized Roman Urdu detection
        val words = lower.split(Regex("[\\s,\\.?!'\"]+"))
        val romanMatchCount = words.count { romanUrduKeywords.contains(it) }

        if (romanMatchCount >= 2 || (words.size <= 4 && romanMatchCount >= 1)) {
            return LanguageType.ROMAN_URDU
        }

        return LanguageType.ENGLISH
    }

    /**
     * Bidirectional Background Pure Urdu Converter:
     * Converts Roman Urdu words into Pure Urdu Script (Nastaliq) phonetics for TTS rendering.
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
            "allah hafiz" to "اللہ حافظ",
            "main bilkul theek" to "میں بالکل ٹھیک",
            "aap batayein" to "آپ بتائیے",
            "kya madad" to "کیا مدد",
            "wasti ai" to "واسطی اے آئی"
        )

        val wordMap = mapOf(
            "kya" to "کیا",
            "hai" to "ہے",
            "hain" to "ہیں",
            "kaise" to "کیسے",
            "kaisa" to "کیسا",
            "kaisi" to "کیسی",
            "ho" to "ہو",
            "main" to "میں",
            "mein" to "میں",
            "hoon" to "ہوں",
            "hun" to "ہوں",
            "aap" to "آپ",
            "tum" to "تم",
            "mera" to "میرا",
            "meri" to "میری",
            "mere" to "میرے",
            "naam" to "نام",
            "batao" to "بتاؤ",
            "bataiye" to "بتائیے",
            "bhejo" to "بھیجو",
            "karo" to "کرو",
            "karna" to "کرنا",
            "mujh" to "مجھ",
            "mujhe" to "مجھے",
            "se" to "سے",
            "aaj" to "آج",
            "kaam" to "کام",
            "baat" to "بات",
            "urdu" to "اردو",
            "wasti" to "واسطی",
            "bohat" to "بہت",
            "bahut" to "بہت",
            "achha" to "اچھا",
            "acha" to "اچھا",
            "theek" to "ٹھیک",
            "thik" to "ٹھیک",
            "sir" to "سر",
            "boss" to "باس",
            "ji" to "جی",
            "haan" to "ہاں",
            "nahi" to "نہیں",
            "nahin" to "نہیں",
            "aur" to "اور",
            "par" to "پر",
            "pe" to "پر",
            "ko" to "کو",
            "ne" to "نے",
            "ki" to "کی",
            "ke" to "کے",
            "ka" to "کا",
            "tak" to "تک",
            "ab" to "اب",
            "kab" to "کب",
            "kahan" to "کہاں",
            "kyun" to "کیوں",
            "kyu" to "کیوں",
            "samjh" to "سمجھ",
            "suno" to "سنو",
            "whatsapp" to "واٹس ایپ",
            "open" to "اوپن",
            "message" to "میسج",
            "call" to "کال"
        )

        var result = romanUrduText
        phraseMap.forEach { (phrase, replacement) ->
            result = result.replace(Regex("(?i)\\b$phrase\\b"), replacement)
        }
        wordMap.forEach { (word, replacement) ->
            result = result.replace(Regex("(?i)\\b$word\\b"), replacement)
        }

        return result
    }

    /**
     * Prepares text for TTS reading:
     * Sanitizes Markdown and converts Roman Urdu phonetically to Pure Urdu Script
     * so Android's ur-PK TTS speaks it in natural, pure Urdu voice!
     */
    fun prepareTextForTts(rawText: String): String {
        val clean = WastiSpeechSanitizer.sanitizeForSpeech(rawText)
        if (clean.isBlank()) return ""

        val lang = detectLanguage(clean)
        return if (lang == LanguageType.ROMAN_URDU) {
            romanUrduToPureUrduScript(clean)
        } else {
            clean
        }
    }

    /**
     * Generates exact Language Prompt Mandate for Gemini API requests.
     */
    fun getLanguagePromptMandate(userInput: String): String {
        val lang = detectLanguage(userInput)
        return when (lang) {
            LanguageType.ROMAN_URDU, LanguageType.PURE_URDU -> """
                MANDATE - LANGUAGE COMPLIANCE:
                The user asked in URDU or ROMAN URDU (e.g., 'kya haal hai', 'کیسے ہو', 'mera naam Nabeel hai').
                You MUST reply strictly in PURE ORIGINAL PAKISTANI URDU SCRIPT (اردو) in standard Nastaliq style.
                Example: 'میں بالکل ٹھیک ہوں، جناب! آپ بتائیے میں آپ کی کیا خدمت کر سکتا ہوں؟'
                Do NOT output Roman Urdu letters or English text. Write pure, natural, correct humanized Pakistani Urdu script.
                Address the user politely as جناب or سر or باس.
            """.trimIndent()

            LanguageType.PUNJABI -> """
                MANDATE - LANGUAGE COMPLIANCE:
                The user asked in PUNJABI (پنجابی).
                Reply strictly in PURE URDU SCRIPT (اردو) or PUNJABI SCRIPT with a warm, respectful tone.
                Address the user as جناب or سر.
            """.trimIndent()

            LanguageType.ENGLISH -> """
                MANDATE - LANGUAGE COMPLIANCE:
                The user asked in ENGLISH.
                Reply strictly in ENGLISH in a professional, warm tone. Address the user as Sir or Boss.
            """.trimIndent()
        }
    }
}
