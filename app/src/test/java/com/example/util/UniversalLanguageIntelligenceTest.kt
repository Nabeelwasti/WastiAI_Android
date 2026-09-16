package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalLanguageIntelligenceTest {
    @Test fun detectsMajorScriptsOffline() {
        assertEquals("en", WastiUrduLanguageEngine.detectLanguageTag("How can you help me today?"))
        assertEquals("ur", WastiUrduLanguageEngine.detectLanguageTag("آپ کیسے ہیں؟"))
        assertEquals("ar", WastiUrduLanguageEngine.detectLanguageTag("مرحبا كيف حالك؟"))
        assertEquals("hi", WastiUrduLanguageEngine.detectLanguageTag("आप कैसे हैं?"))
        assertEquals("bn", WastiUrduLanguageEngine.detectLanguageTag("আপনি কেমন আছেন?"))
        assertEquals("pa", WastiUrduLanguageEngine.detectLanguageTag("ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"))
        assertEquals("ta", WastiUrduLanguageEngine.detectLanguageTag("நீங்கள் எப்படி இருக்கிறீர்கள்?"))
        assertEquals("te", WastiUrduLanguageEngine.detectLanguageTag("మీరు ఎలా ఉన్నారు?"))
        assertEquals("th", WastiUrduLanguageEngine.detectLanguageTag("คุณเป็นอย่างไรบ้าง?"))
        assertEquals("ja", WastiUrduLanguageEngine.detectLanguageTag("こんにちは、元気ですか？"))
        assertEquals("ko", WastiUrduLanguageEngine.detectLanguageTag("안녕하세요?"))
        assertEquals("zh", WastiUrduLanguageEngine.detectLanguageTag("你好，你怎么样？"))
        assertEquals("ru", WastiUrduLanguageEngine.detectLanguageTag("Как вы сегодня?"))
        assertEquals("el", WastiUrduLanguageEngine.detectLanguageTag("Πώς είσαι σήμερα;"))
        assertEquals("he", WastiUrduLanguageEngine.detectLanguageTag("שלום, מה שלומך?"))
    }

    @Test fun detectsMajorLatinLanguagesOffline() {
        assertEquals("es", WastiUrduLanguageEngine.detectLanguageTag("Hola, ¿cómo estás hoy?"))
        assertEquals("fr", WastiUrduLanguageEngine.detectLanguageTag("Bonjour, comment allez-vous aujourd'hui?"))
        assertEquals("de", WastiUrduLanguageEngine.detectLanguageTag("Hallo, wie geht es dir heute?"))
        assertEquals("it", WastiUrduLanguageEngine.detectLanguageTag("Ciao, come stai oggi?"))
        assertEquals("pt", WastiUrduLanguageEngine.detectLanguageTag("Olá, como você está hoje?"))
        assertEquals("tr", WastiUrduLanguageEngine.detectLanguageTag("Merhaba, bugün nasılsın?"))
        assertEquals("id", WastiUrduLanguageEngine.detectLanguageTag("Halo, bagaimana kabarmu hari ini?"))
        assertEquals("vi", WastiUrduLanguageEngine.detectLanguageTag("Xin chào, bạn khỏe không?"))
        assertEquals("sw", WastiUrduLanguageEngine.detectLanguageTag("Habari, ukoje leo? Asante."))
    }

    @Test fun preservesLegacyRomanUrduAndMandate() {
        assertEquals(WastiUrduLanguageEngine.LanguageType.ROMAN_URDU, WastiUrduLanguageEngine.detectLanguage("aap kaise hain"))
        val mandate = WastiUrduLanguageEngine.getLanguagePromptMandate("Bonjour, comment allez-vous?")
        assertTrue(mandate.contains("tag=fr"))
        assertTrue(mandate.contains("Do not translate unless requested"))
    }
}
