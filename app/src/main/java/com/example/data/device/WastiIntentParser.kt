package com.example.data.device

import android.content.Context
import android.util.Log

object WastiIntentParser {

    data class IntentParseResult(
        val hasIntent: Boolean,
        val actionResult: WastiDeviceController.DeviceCommandResult?,
        val targetApp: String? = null
    )

    fun parseAndExecute(context: Context, text: String): IntentParseResult {
        if (text.isBlank()) return IntentParseResult(false, null)

        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // 1. Explicit Action Block Tag Parsing (e.g. [ACTION: OPEN_APP app="whatsapp"] or [OPEN_APP: whatsapp])
        val actionTagRegex = Regex("\\[(?:ACTION:\\s*)?(?:OPEN_APP|LAUNCH_APP|RUN_APP)[:=]?\\s*\"?([^\"]+)\"?\\]", RegexOption.IGNORE_CASE)
        val tagMatch = actionTagRegex.find(trimmed)
        if (tagMatch != null) {
            val appTarget = tagMatch.groupValues[1].trim()
            val result = WastiDeviceController.openApp(context, appTarget)
            return IntentParseResult(true, result, appTarget)
        }

        // 2. Direct Messaging / Communication Commands
        if (lower.contains("send whatsapp message to") || lower.contains("send whatsapp to")) {
            val whatsappRegex = Regex("send whatsapp (?:message )?to\\s+([a-zA-Z0-9+\\s]+)(?:\\s+saying|\\s+with text|\\s+message)?\\s*(.*)", RegexOption.IGNORE_CASE)
            val match = whatsappRegex.find(trimmed)
            if (match != null) {
                val recipient = match.groupValues[1].trim()
                val msg = match.groupValues[2].trim().ifEmpty { "Hello from Wasti AI Command Center!" }
                val result = WastiDeviceController.sendWhatsAppMessage(context, recipient, msg)
                return IntentParseResult(true, result, "WhatsApp")
            }
        }

        // 3. Natural Language App Launching Intents
        val launchPhrases = listOf(
            "open whatsapp", "launch whatsapp", "start whatsapp",
            "open youtube", "launch youtube", "start youtube",
            "open camera", "launch camera", "take a photo", "open camera app",
            "open settings", "launch settings", "open system settings",
            "open chrome", "launch chrome", "open browser", "launch browser",
            "open gmail", "launch gmail", "open email", "launch email",
            "open maps", "launch maps", "open google maps",
            "open spotify", "launch spotify",
            "open calculator", "launch calculator",
            "open instagram", "launch instagram",
            "open facebook", "launch facebook",
            "open twitter", "launch twitter", "open x app",
            "open telegram", "launch telegram",
            "open phone", "open dialer", "make a call",
            "open gallery", "open photos"
        )

        for (phrase in launchPhrases) {
            if (lower.contains(phrase)) {
                val targetApp = when {
                    phrase.contains("whatsapp") -> "whatsapp"
                    phrase.contains("youtube") -> "youtube"
                    phrase.contains("camera") || phrase.contains("photo") -> "camera"
                    phrase.contains("setting") -> "settings"
                    phrase.contains("chrome") || phrase.contains("browser") -> "chrome"
                    phrase.contains("gmail") || phrase.contains("email") -> "gmail"
                    phrase.contains("map") -> "maps"
                    phrase.contains("spotify") -> "spotify"
                    phrase.contains("calculator") -> "calculator"
                    phrase.contains("instagram") -> "instagram"
                    phrase.contains("facebook") -> "facebook"
                    phrase.contains("twitter") || phrase.contains("x app") -> "twitter"
                    phrase.contains("telegram") -> "telegram"
                    phrase.contains("phone") || phrase.contains("dialer") -> "phone"
                    phrase.contains("gallery") || phrase.contains("photo") -> "gallery"
                    else -> phrase.replace("open ", "").replace("launch ", "").replace("start ", "").trim()
                }
                val result = WastiDeviceController.openApp(context, targetApp)
                return IntentParseResult(true, result, targetApp)
            }
        }

        // 4. Generic App Open Command Pattern: "open [app_name]" or "launch [app_name]"
        val genericOpenRegex = Regex("^(?:please\\s+)?(?:open|launch|start|run|exec|execute)\\s+(?:the\\s+)?([a-zA-Z0-9\\s._-]+?)(?:\\s+app|\\s+application)?$", RegexOption.IGNORE_CASE)
        val genericMatch = genericOpenRegex.find(trimmed)
        if (genericMatch != null) {
            val appTarget = genericMatch.groupValues[1].trim()
            if (appTarget.isNotBlank() && appTarget.length > 1 && !appTarget.contains("session") && !appTarget.contains("workspace")) {
                val result = WastiDeviceController.openApp(context, appTarget)
                return IntentParseResult(true, result, appTarget)
            }
        }

        return IntentParseResult(false, null)
    }
}
