package com.example.data.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import com.example.data.db.IntegrationEntity
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.db.SettingEntity
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.persistence.DraftPersistenceManager
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object WastiDeviceController {

    data class DeviceCommandResult(
        val success: Boolean,
        val userFeedback: String,
        val actionType: String
    )

    // 1. Mobile App Launcher & Intent Dispatcher
    fun openApp(context: Context, target: String): DeviceCommandResult {
        val lower = target.lowercase().trim()
        val pm = context.packageManager

        try {
            // Direct Package Name lookup if target contains dots (e.g. com.whatsapp)
            if (lower.contains(".")) {
                val directIntent = pm.getLaunchIntentForPackage(target)
                if (directIntent != null) {
                    directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(directIntent)
                    return DeviceCommandResult(true, "Successfully launched package '$target', Sir.", "OPEN_APP")
                }
            }

            // Map common app names to known package IDs for fast execution
            val knownPackages = mapOf(
                "whatsapp" to "com.whatsapp",
                "youtube" to "com.google.android.youtube",
                "chrome" to "com.android.chrome",
                "browser" to "com.android.chrome",
                "gmail" to "com.google.android.gm",
                "email" to "com.google.android.gm",
                "maps" to "com.google.android.apps.maps",
                "google maps" to "com.google.android.apps.maps",
                "spotify" to "com.spotify.music",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "telegram" to "org.telegram.messenger",
                "calculator" to "com.google.android.calculator",
                "clock" to "com.google.android.deskclock",
                "camera" to "com.google.android.GoogleCamera",
                "settings" to "com.android.settings"
            )

            val matchedPkg = knownPackages.entries.firstOrNull { lower.contains(it.key) }?.value
            if (matchedPkg != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matchedPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return DeviceCommandResult(true, "Opened $target ($matchedPkg) successfully, Sir.", "OPEN_APP")
                }
            }

            // Custom Intent Handling for System Apps & Fallbacks
            when {
                lower.contains("whatsapp") -> {
                    val intent = pm.getLaunchIntentForPackage("com.whatsapp")
                        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Opened WhatsApp successfully, Sir.", "OPEN_APP")
                }
                lower.contains("camera") -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Camera opened.", "OPEN_CAMERA")
                }
                lower.contains("youtube") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "YouTube opened.", "OPEN_APP")
                }
                lower.contains("email") || lower.contains("gmail") -> {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_EMAIL)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Email application launched.", "OPEN_APP")
                }
                lower.contains("setting") -> {
                    val intent = Intent(Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Android System Settings opened.", "OPEN_SETTINGS")
                }
                lower.contains("gallery") || lower.contains("photo") -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Gallery opened.", "OPEN_APP")
                }
                lower.contains("map") -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=maps")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return DeviceCommandResult(true, "Google Maps opened.", "OPEN_APP")
                }
                else -> {
                    // Search all installed packages using QUERY_ALL_PACKAGES
                    val packages = pm.getInstalledPackages(0)
                    for (pkg in packages) {
                        val appLabel = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: ""
                        if (appLabel.contains(lower) || pkg.packageName.lowercase().contains(lower)) {
                            val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(launchIntent)
                                return DeviceCommandResult(true, "Launched app: ${pkg.applicationInfo?.loadLabel(pm)}", "OPEN_APP")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return DeviceCommandResult(false, "Could not open $target directly: ${e.localizedMessage}", "ERROR")
        }

        return DeviceCommandResult(false, "App '$target' not found on device.", "NOT_FOUND")
    }

    // 2. Direct Messaging & Posting Automation
    fun sendWhatsAppMessage(context: Context, recipient: String, message: String): DeviceCommandResult {
        return try {
            val cleanNumber = recipient.replace(Regex("[^0-9+]"), "")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DeviceCommandResult(true, "WhatsApp chat opened for $recipient with prefilled message: \"$message\"", "WHATSAPP_SEND")
        } catch (e: Exception) {
            DeviceCommandResult(false, "Failed to open WhatsApp: ${e.localizedMessage}", "ERROR")
        }
    }

    fun sendEmail(context: Context, recipient: String, subject: String, body: String): DeviceCommandResult {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DeviceCommandResult(true, "Email composer opened for $recipient with subject '$subject'", "EMAIL_SEND")
        } catch (e: Exception) {
            DeviceCommandResult(false, "Failed to open Email composer: ${e.localizedMessage}", "ERROR")
        }
    }

    fun sendSMS(context: Context, recipient: String, message: String): DeviceCommandResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("sms:$recipient")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DeviceCommandResult(true, "SMS composer opened for $recipient with message: \"$message\"", "SMS_SEND")
        } catch (e: Exception) {
            DeviceCommandResult(false, "Failed to open SMS app: ${e.localizedMessage}", "ERROR")
        }
    }

    fun postSocialMedia(context: Context, platform: String, content: String): DeviceCommandResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Post to $platform via Wasti AI").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            DeviceCommandResult(true, "Dispatched post content to $platform.", "SOCIAL_POST")
        } catch (e: Exception) {
            DeviceCommandResult(false, "Failed to post: ${e.localizedMessage}", "ERROR")
        }
    }

    // 3. Screen Reader & Active UI Node Inspection
    fun readScreenContent(context: Context? = null): String {
        val ctx = context ?: com.example.WastiApplication.instance
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val jsonScrape = service.scrapeActiveScreen()
            if (jsonScrape.isNotBlank() && jsonScrape != "[]") {
                return jsonScrape
            }
            return service.dumpScreenContent()
        }

        val cached = ctx?.let { DraftPersistenceManager.getScrapedScreenData(it) } ?: ""
        if (cached.isNotBlank() && cached != "[]") {
            return "[Cached Screen Nodes]\n$cached"
        }

        return """
            [Wasti Accessibility Service Inactive]
            • Wasti Accessibility Service is not currently enabled in Android Accessibility Settings.
            • To enable real-time screen node reading and tap execution across apps, please enable 'Wasti OS' in Settings -> Accessibility.
            • App Package: ${ctx?.packageName ?: "com.example"}
        """.trimIndent()
    }

    // 4. Tap / Click / Touch & Swipe Simulation (Command Dispatcher via IPC)
    fun simulateTap(context: Context? = null, targetElement: String): DeviceCommandResult {
        val ctx = context ?: com.example.WastiApplication.instance

        // Send IPC broadcast intent to WastiCommandReceiver
        val intent = Intent("com.wasti.os.ACTION_EXECUTE_GESTURE").apply {
            putExtra("actionType", "TAP_TEXT")
            putExtra("targetText", targetElement)
            putExtra("targetElement", targetElement)
            if (ctx != null) setPackage(ctx.packageName)
        }
        ctx?.sendBroadcast(intent)

        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.clickElement(targetElement)
            return if (success) {
                DeviceCommandResult(true, "Successfully executed tap gesture on target '$targetElement' via Wasti IPC Bridge.", "SIMULATE_TAP")
            } else {
                DeviceCommandResult(false, "Wasti Accessibility Engine scanned the screen but target '$targetElement' was not found or gesture dispatch failed.", "SIMULATE_TAP")
            }
        }

        if (ctx != null) {
            Toast.makeText(ctx, "Please enable Wasti Accessibility Service in Settings to tap '$targetElement'", Toast.LENGTH_LONG).show()
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive. Intent dispatched to local IPC bridge.", "SERVICE_INACTIVE")
    }

    fun simulateTapAt(context: Context? = null, x: Float, y: Float): DeviceCommandResult {
        val ctx = context ?: com.example.WastiApplication.instance

        val intent = Intent("com.wasti.os.ACTION_EXECUTE_GESTURE").apply {
            putExtra("actionType", "TAP")
            putExtra("x", x)
            putExtra("y", y)
            if (ctx != null) setPackage(ctx.packageName)
        }
        ctx?.sendBroadcast(intent)

        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performTapAt(x, y)
            return if (success) {
                DeviceCommandResult(true, "Successfully executed tap gesture at coordinates ($x, $y) via Wasti IPC Bridge.", "SIMULATE_TAP")
            } else {
                DeviceCommandResult(false, "Coordinate tap gesture dispatch failed at ($x, $y).", "SIMULATE_TAP")
            }
        }

        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive. Intent dispatched to local IPC bridge.", "SERVICE_INACTIVE")
    }

    fun simulateSwipe(
        context: Context? = null,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300L
    ): DeviceCommandResult {
        val ctx = context ?: com.example.WastiApplication.instance

        val intent = Intent("com.wasti.os.ACTION_EXECUTE_GESTURE").apply {
            putExtra("actionType", "SWIPE")
            putExtra("startX", startX)
            putExtra("startY", startY)
            putExtra("endX", endX)
            putExtra("endY", endY)
            putExtra("duration", durationMs)
            if (ctx != null) setPackage(ctx.packageName)
        }
        ctx?.sendBroadcast(intent)

        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performSwipe(startX, startY, endX, endY, durationMs)
            return if (success) {
                DeviceCommandResult(true, "Successfully executed swipe gesture from ($startX, $startY) to ($endX, $endY).", "SIMULATE_SWIPE")
            } else {
                DeviceCommandResult(false, "Swipe gesture dispatch failed.", "SIMULATE_SWIPE")
            }
        }

        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive. Intent dispatched to local IPC bridge.", "SERVICE_INACTIVE")
    }

    fun typeText(context: Context? = null, text: String, targetElement: String? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.typeText(text, targetElement)
            return if (success) {
                DeviceCommandResult(true, "Successfully typed text into target UI field.", "TYPE_TEXT")
            } else {
                DeviceCommandResult(false, "No active editable input field found on screen.", "TYPE_TEXT")
            }
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performBack(context: Context? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performBack()
            return DeviceCommandResult(success, if (success) "Executed Back button navigation." else "Back navigation failed.", "NAV_BACK")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performHome(context: Context? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performHome()
            return DeviceCommandResult(success, if (success) "Navigated to Android Home screen." else "Home navigation failed.", "NAV_HOME")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performRecents(context: Context? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performRecents()
            return DeviceCommandResult(success, if (success) "Opened Android Recents / App Switcher." else "Recents action failed.", "NAV_RECENTS")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performNotifications(context: Context? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performNotifications()
            return DeviceCommandResult(success, if (success) "Opened Android Notification shade." else "Notification action failed.", "NAV_NOTIFICATIONS")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performQuickSettings(context: Context? = null): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performQuickSettings()
            return DeviceCommandResult(success, if (success) "Opened Android Quick Settings panel." else "Quick Settings action failed.", "NAV_QUICK_SETTINGS")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    fun performScroll(context: Context? = null, direction: String = "DOWN"): DeviceCommandResult {
        val service = WastiAccessibilityService.instance
        if (service != null && WastiAccessibilityService.isServiceActive) {
            val success = service.performScroll(direction)
            return DeviceCommandResult(success, if (success) "Scrolled screen container $direction." else "No scrollable container detected.", "SCROLL")
        }
        return DeviceCommandResult(false, "Wasti Accessibility Service is inactive.", "SERVICE_INACTIVE")
    }

    // 5. Connect Online Voice & AI Provider Models
    suspend fun connectVoiceProvider(
        db: WastiDatabase,
        providerName: String, // e.g. "ElevenLabs", "OpenAI Realtime Voice", "Azure Speech", "Custom REST"
        apiKey: String,
        endpointUrl: String,
        voiceId: String
    ): DeviceCommandResult = withContext(Dispatchers.IO) {
        val cleanName = providerName.ifBlank { "Custom Online Voice" }
        
        db.integrationDao().insertIntegration(
            IntegrationEntity(
                id = UUID.randomUUID().toString(),
                serviceName = cleanName,
                provider = "Online Voice Model",
                isConnected = true,
                authType = "API Key / REST",
                statusText = "Voice ID: ${voiceId.ifBlank { "default" }} • Key Registered"
            )
        )

        val appCtx = com.example.WastiApplication.instance
        if (appCtx != null && apiKey.isNotBlank()) {
            com.example.data.credential.CredentialRegistry.saveCredential("voice_provider_key_${cleanName.lowercase()}", apiKey, appCtx)
        }

        db.settingDao().insertSetting(SettingEntity("active_voice_provider", cleanName))
        db.settingDao().insertSetting(SettingEntity("voice_provider_configured_${cleanName.lowercase()}", "true"))
        db.settingDao().insertSetting(SettingEntity("voice_provider_url_${cleanName.lowercase()}", endpointUrl))
        db.settingDao().insertSetting(SettingEntity("voice_provider_id_${cleanName.lowercase()}", voiceId))

        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "INFO",
                source = "VoiceModelManager",
                message = "Connected online voice model provider: $cleanName",
                details = "Endpoint: $endpointUrl, VoiceID: $voiceId"
            )
        )

        DeviceCommandResult(true, "Connected online voice model provider '$cleanName' successfully! Wasti speech pipeline updated.", "CONNECT_VOICE")
    }

    suspend fun connectAiProvider(
        db: WastiDatabase,
        providerName: String, // e.g. "OpenAI", "Claude", "DeepSeek", "Ollama Local", "Custom AI"
        apiKey: String,
        endpointUrl: String,
        modelName: String
    ): DeviceCommandResult = withContext(Dispatchers.IO) {
        val cleanName = providerName.ifBlank { "Custom AI Model" }

        db.integrationDao().insertIntegration(
            IntegrationEntity(
                id = UUID.randomUUID().toString(),
                serviceName = cleanName,
                provider = "AI LLM Engine",
                isConnected = true,
                authType = "API Key / Bearer",
                statusText = "Model: ${modelName.ifBlank { "gpt-4o" }} • Active"
            )
        )

        val appCtx = com.example.WastiApplication.instance
        if (appCtx != null && apiKey.isNotBlank()) {
            com.example.data.credential.CredentialRegistry.saveCredential("ai_key_${cleanName.lowercase()}", apiKey, appCtx)
        }

        db.settingDao().insertSetting(SettingEntity("active_ai_provider", cleanName))
        db.settingDao().insertSetting(SettingEntity("active_ai_model", modelName))
        db.settingDao().insertSetting(SettingEntity("ai_configured_${cleanName.lowercase()}", "true"))
        db.settingDao().insertSetting(SettingEntity("ai_url_${cleanName.lowercase()}", endpointUrl))

        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "INFO",
                source = "AiModelManager",
                message = "Connected new AI provider: $cleanName ($modelName)",
                details = "Endpoint: $endpointUrl"
            )
        )

        DeviceCommandResult(true, "Connected online AI provider '$cleanName' ($modelName) successfully!", "CONNECT_AI")
    }

    // 6. Dynamic In-App Settings Remote Configurator
    suspend fun updateAppSetting(
        db: WastiDatabase,
        settingKey: String,
        settingValue: String
    ): DeviceCommandResult = withContext(Dispatchers.IO) {
        db.settingDao().insertSetting(SettingEntity(settingKey, settingValue))
        db.memoryDao().insertMemory(
            MemoryEntity(
                id = UUID.randomUUID().toString(),
                key = "App Setting Updated: $settingKey",
                category = "Rule",
                value = "Setting $settingKey set to $settingValue"
            )
        )
        db.systemLogDao().insertLog(
            SystemLogEntity(
                level = "INFO",
                source = "SettingsConfigurator",
                message = "Setting updated via Chat/UI: $settingKey = $settingValue"
            )
        )
        DeviceCommandResult(true, "Updated setting '$settingKey' to '$settingValue', Sir.", "UPDATE_SETTING")
    }
}
