package com.example.util

import java.util.Locale

/**
 * Backward-compatible language intelligence layer.
 *
 * The historical name is retained so existing voice/API integrations remain wired, while
 * detection is now language-agnostic. Offline detection uses Unicode scripts plus compact
 * stop-word fingerprints; online models receive the same canonical BCP-47 language tag.
 */
object WastiUrduLanguageEngine {

    enum class LanguageType {
        URDU_SCRIPT,
        PURE_URDU,
        ROMAN_URDU,
        ENGLISH,
        PUNJABI,
        MIRROR_LANGUAGE
    }

    data class LanguageProfile(
        val languageTag: String,
        val languageName: String,
        val confidence: Float,
        val detectionMode: DetectionMode
    )

    enum class DetectionMode { SCRIPT, LEXICAL_OFFLINE, DEFAULT }

    private val lexicalProfiles: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "is", "are", "you", "what", "how", "with", "this", "that", "please"),
        "es" to setOf("el", "la", "los", "las", "que", "de", "para", "como", "está", "hola"),
        "fr" to setOf("le", "la", "les", "des", "que", "pour", "avec", "est", "bonjour", "comment"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "mit", "für", "wie", "hallo"),
        "it" to setOf("il", "la", "gli", "che", "per", "con", "come", "sono", "ciao", "questo"),
        "pt" to setOf("o", "a", "os", "as", "que", "para", "com", "como", "está", "olá"),
        "nl" to setOf("de", "het", "een", "en", "van", "voor", "met", "hoe", "wat", "hallo"),
        "tr" to setOf("ve", "bir", "bu", "için", "ile", "nasıl", "nedir", "değil", "merhaba", "çok"),
        "ru" to setOf("и", "в", "не", "что", "это", "как", "для", "привет", "можно", "есть"),
        "uk" to setOf("і", "в", "не", "що", "це", "як", "для", "привіт", "можна", "є"),
        "pl" to setOf("i", "w", "nie", "że", "to", "jak", "dla", "jest", "cześć", "proszę"),
        "cs" to setOf("a", "v", "že", "to", "jak", "pro", "je", "jsem", "ahoj", "prosím"),
        "ro" to setOf("și", "în", "este", "pentru", "cum", "care", "nu", "bună", "ce", "sunt"),
        "sv" to setOf("och", "det", "att", "är", "för", "med", "hur", "inte", "hej", "som"),
        "da" to setOf("og", "det", "at", "er", "for", "med", "hvordan", "ikke", "hej", "som"),
        "no" to setOf("og", "det", "at", "er", "for", "med", "hvordan", "ikke", "hei", "som"),
        "fi" to setOf("ja", "on", "että", "mitä", "miten", "kanssa", "tämä", "ei", "hei", "kiitos"),
        "hu" to setOf("és", "az", "hogy", "van", "nem", "mit", "hogyan", "ez", "szia", "kérem"),
        "id" to setOf("dan", "yang", "ini", "itu", "untuk", "dengan", "bagaimana", "tidak", "halo", "apa"),
        "ms" to setOf("dan", "yang", "ini", "itu", "untuk", "dengan", "bagaimana", "tidak", "salam", "apa"),
        "vi" to setOf("và", "là", "của", "cho", "với", "như", "không", "xin", "chào", "này"),
        "sw" to setOf("na", "ya", "ni", "kwa", "hii", "hii ni", "jinsi", "habari", "asante", "sana"),
        "ur" to setOf("ہے", "ہیں", "کیا", "آپ", "میں", "کے", "کی", "کا", "اور", "نہیں"),
        "hi" to setOf("है", "हैं", "क्या", "आप", "मैं", "में", "के", "की", "और", "नहीं"),
        "bn" to setOf("এবং", "হয়", "কি", "আপনি", "আমি", "জন্য", "কীভাবে", "না", "হ্যালো", "এই"),
        "pa" to setOf("ਹੈ", "ਹਨ", "ਕੀ", "ਤੁਸੀਂ", "ਮੈਂ", "ਵਿੱਚ", "ਦੇ", "ਦੀ", "ਅਤੇ", "ਨਹੀਂ"),
        "mr" to setOf("आहे", "आहेत", "काय", "तुम्ही", "मी", "मध्ये", "आणि", "नाही", "कसे", "नमस्कार"),
        "gu" to setOf("છે", "છો", "શું", "તમે", "હું", "માં", "અને", "નથી", "કેમ", "નમસ્તે"),
        "ta" to setOf("மற்றும்", "உள்ளது", "என்ன", "நீங்கள்", "நான்", "இல்", "இல்லை", "எப்படி", "வணக்கம்", "இந்த"),
        "te" to setOf("మరియు", "ఉంది", "ఏమిటి", "మీరు", "నేను", "లో", "కాదు", "ఎలా", "నమస్కారం", "ఈ"),
        "th" to setOf("และ", "เป็น", "อะไร", "คุณ", "ฉัน", "ใน", "ไม่", "อย่างไร", "สวัสดี", "นี้"),
        "el" to setOf("και", "είναι", "τι", "εσύ", "εγώ", "για", "δεν", "πώς", "γεια", "αυτό")
    )

    /** Detect a broad language profile without network access. */
    fun detectLanguageProfile(text: String?): LanguageProfile {
        if (text.isNullOrBlank()) return LanguageProfile("en", "English", 0.0f, DetectionMode.DEFAULT)

        val script = detectByScript(text)
        if (script != null) return script

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return LanguageProfile("en", "English", 0.0f, DetectionMode.DEFAULT)

        val scores = lexicalProfiles.mapValues { (_, words) ->
            tokens.count { it in words }
        }.filterValues { it > 0 }

        val winner = scores.maxByOrNull { it.value }
        if (winner != null) {
            val confidence = (winner.value.toFloat() / tokens.size.coerceAtMost(12)).coerceIn(0.35f, 0.98f)
            return LanguageProfile(winner.key, languageName(winner.key), confidence, DetectionMode.LEXICAL_OFFLINE)
        }

        return LanguageProfile("en", "English", 0.20f, DetectionMode.DEFAULT)
    }

    /** Canonical BCP-47 language tag used by cloud and local model routing. */
    fun detectLanguageTag(text: String?): String = detectLanguageProfile(text).languageTag

    /**
     * Preserves the legacy enum contract used by existing Urdu/Punjabi voice paths.
     * New integrations should use detectLanguageTag()/detectLanguageProfile().
     */
    fun detectLanguage(text: String?): LanguageType {
        val profile = detectLanguageProfile(text)
        return when (profile.languageTag) {
            "ur" -> if (text.orEmpty().any { it in '\u0600'..'\u06FF' }) LanguageType.URDU_SCRIPT else LanguageType.ROMAN_URDU
            "pa" -> LanguageType.PUNJABI
            "en" -> LanguageType.ENGLISH
            else -> LanguageType.MIRROR_LANGUAGE
        }
    }

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
        phraseMap.forEach { (phrase, replacement) -> result = result.replace(Regex("(?i)\\b$phrase\\b"), replacement) }
        return result
    }

    fun prepareTextForTts(rawText: String): String = WastiSpeechSanitizer.sanitizeForSpeech(rawText).trim()

    /** Builds a deterministic instruction that preserves the user's detected language and script. */
    fun getLanguagePromptMandate(userInput: String): String {
        val profile = detectLanguageProfile(userInput)
        return "LANGUAGE ROUTING: Detect and preserve the user's language. Detected BCP-47 tag=${profile.languageTag}; language=${profile.languageName}; confidence=${"%.2f".format(Locale.US, profile.confidence)}; detection=${profile.detectionMode}. Reply in the user's language and script by default. Do not translate unless requested. If detection confidence is low, mirror the input conservatively and never silently switch to Urdu, Roman Urdu, or English."
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}]+"))
            .filter { it.length >= 2 }
            .toSet()

    private fun detectByScript(text: String): LanguageProfile? {
        fun has(range: CharRange) = text.any { it in range }
        return when {
            has('\u3040'..'\u30ff') -> LanguageProfile("ja", "Japanese", 0.98f, DetectionMode.SCRIPT)
            has('\uac00'..'\ud7af') -> LanguageProfile("ko", "Korean", 0.98f, DetectionMode.SCRIPT)
            has('\u4e00'..'\u9fff') -> LanguageProfile("zh", "Chinese", 0.96f, DetectionMode.SCRIPT)
            has('\u0900'..'\u097f') -> LanguageProfile("hi", "Hindi", 0.90f, DetectionMode.SCRIPT)
            has('\u0980'..'\u09ff') -> LanguageProfile("bn", "Bengali", 0.96f, DetectionMode.SCRIPT)
            has('\u0a00'..'\u0a7f') -> LanguageProfile("pa", "Punjabi", 0.96f, DetectionMode.SCRIPT)
            has('\u0a80'..'\u0aff') -> LanguageProfile("gu", "Gujarati", 0.96f, DetectionMode.SCRIPT)
            has('\u0b00'..'\u0b7f') -> LanguageProfile("or", "Odia", 0.96f, DetectionMode.SCRIPT)
            has('\u0b80'..'\u0bff') -> LanguageProfile("ta", "Tamil", 0.96f, DetectionMode.SCRIPT)
            has('\u0c00'..'\u0c7f') -> LanguageProfile("te", "Telugu", 0.96f, DetectionMode.SCRIPT)
            has('\u0c80'..'\u0cff') -> LanguageProfile("kn", "Kannada", 0.96f, DetectionMode.SCRIPT)
            has('\u0d00'..'\u0d7f') -> LanguageProfile("ml", "Malayalam", 0.96f, DetectionMode.SCRIPT)
            has('\u0e00'..'\u0e7f') -> LanguageProfile("th", "Thai", 0.96f, DetectionMode.SCRIPT)
            has('\u10a0'..'\u10ff') -> LanguageProfile("ka", "Georgian", 0.96f, DetectionMode.SCRIPT)
            has('\u0370'..'\u03ff') -> LanguageProfile("el", "Greek", 0.96f, DetectionMode.SCRIPT)
            has('\u0400'..'\u04ff') -> LanguageProfile("ru", "Russian", 0.90f, DetectionMode.SCRIPT)
            has('\u0590'..'\u05ff') -> LanguageProfile("he", "Hebrew", 0.96f, DetectionMode.SCRIPT)
            has('\u0600'..'\u06ff') -> {
                val urduMarkers = setOf('ٹ', 'ڈ', 'ڑ', 'ں', 'ے', 'ھ')
                if (text.any { it in urduMarkers }) LanguageProfile("ur", "Urdu", 0.97f, DetectionMode.SCRIPT)
                else LanguageProfile("ar", "Arabic", 0.90f, DetectionMode.SCRIPT)
            }
            else -> null
        }
    }

    private fun languageName(tag: String): String = when (tag) {
        "en" -> "English"; "es" -> "Spanish"; "fr" -> "French"; "de" -> "German"; "it" -> "Italian"; "pt" -> "Portuguese"; "nl" -> "Dutch"; "tr" -> "Turkish"; "ru" -> "Russian"; "uk" -> "Ukrainian"; "pl" -> "Polish"; "cs" -> "Czech"; "ro" -> "Romanian"; "sv" -> "Swedish"; "da" -> "Danish"; "no" -> "Norwegian"; "fi" -> "Finnish"; "hu" -> "Hungarian"; "id" -> "Indonesian"; "ms" -> "Malay"; "vi" -> "Vietnamese"; "sw" -> "Swahili"; "ur" -> "Urdu"; "hi" -> "Hindi"; "bn" -> "Bengali"; "pa" -> "Punjabi"; "mr" -> "Marathi"; "gu" -> "Gujarati"; "ta" -> "Tamil"; "te" -> "Telugu"; "th" -> "Thai"; "el" -> "Greek"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh" -> "Chinese"; "he" -> "Hebrew"; "ar" -> "Arabic"; "ka" -> "Georgian"; "or" -> "Odia"; "kn" -> "Kannada"; "ml" -> "Malayalam"; else -> Locale.forLanguageTag(tag).displayLanguage
    }
}
