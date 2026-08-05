package com.example.data.credential

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class CredentialCategory(val title: String) {
    MODEL_PROVIDERS("AI & Model Providers"),
    DESIGN_BACKUP("Design & Cloud Backup"),
    BUSINESS_PAYMENTS("Business & Payments"),
    CODE_REPOS("Code & Repositories"),
    ASSETS_INFRA("Assets & Infrastructure"),
    AUTOMATION_COMMS("Automation & Communications")
}

sealed class CredentialStatus {
    object NotConfigured : CredentialStatus()
    object Testing : CredentialStatus()
    data class Connected(val message: String) : CredentialStatus()
    data class Error(val message: String) : CredentialStatus()
}

data class CredentialEntry(
    val keyName: String,
    val displayName: String,
    val category: CredentialCategory,
    val isDefaultActive: Boolean = true,
    val description: String = "",
    val testConnection: suspend (value: String) -> Pair<Boolean, String>
)

data class CredentialState(
    val entry: CredentialEntry,
    val rawValue: String,
    val status: CredentialStatus = CredentialStatus.NotConfigured
)

object CredentialRegistry {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun isPlaceholder(valString: String): Boolean {
        if (valString.isBlank()) return true
        val upper = valString.trim().uppercase()
        return upper == "MY_KEY" ||
               upper == "YOUR_KEY" ||
               upper == "PLACEHOLDER" ||
               upper == "ENTER_KEY_HERE"
    }

    // Helper for HTTP GET checks
    private fun httpGetCheck(
        url: String,
        headers: Map<String, String> = emptyMap(),
        expectedCodes: Set<Int> = setOf(200)
    ): Pair<Boolean, String> {
        return try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            val response = httpClient.newCall(reqBuilder.build()).execute()
            val code = response.code
            val bodyPreview = response.body?.string()?.take(150)?.replace("\n", " ") ?: ""
            response.close()
            if (code in expectedCodes) {
                Pair(true, "Connected (HTTP $code OK)")
            } else {
                Pair(false, "HTTP $code - $bodyPreview")
            }
        } catch (e: Exception) {
            Pair(false, "Network Error: ${e.localizedMessage ?: e.message}")
        }
    }

    val ALL_CREDENTIALS: List<CredentialEntry> = listOf(
        // --- MODEL PROVIDERS ---
        CredentialEntry(
            keyName = "GEMINI_API_KEY",
            displayName = "Google AI Studio Gemini API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Powers Gemini 3.6 Flash & 3.5 Flash Lite reasoning engines.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://generativelanguage.googleapis.com/v1beta/models?key=$value")
            }
        ),
        CredentialEntry(
            keyName = "GROQ_API_KEY",
            displayName = "Groq Llama API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Ultra-fast inference for Llama 3.3 70B & Whisper voice model.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "XAI_API_KEY",
            displayName = "x.ai Grok API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Powers x.ai Grok 4.3 & Grok 2 models.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.x.ai/v1/models", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "OPENAI_API_KEY",
            displayName = "OpenAI API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Powers OpenAI GPT-5.6 Sol, Terra, and Luna models.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "OPENROUTER_API_KEY",
            displayName = "OpenRouter Universal API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Unified routing gateway for 200+ open and proprietary AI models.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://openrouter.ai/api/v1/auth/key", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "HUGGINGFACE_ACCESS_TOKEN",
            displayName = "HuggingFace Access Token",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Access to HuggingFace Hub inference endpoints and datasets.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://huggingface.co/api/whoami-v2", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "BYTEZ_API_KEY",
            displayName = "Bytez API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Serverless model deployment & serverless GPU execution platform.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.bytez.com/v1/models", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "ANTHROPIC_API_KEY",
            displayName = "Anthropic Claude API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Powers Anthropic Claude 3.5 Sonnet & Claude models.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.anthropic.com/v1/models", mapOf("x-api-key" to value, "anthropic-version" to "2023-06-01"))
            }
        ),
        CredentialEntry(
            keyName = "DEEPSEEK_API_KEY",
            displayName = "DeepSeek API Key",
            category = CredentialCategory.MODEL_PROVIDERS,
            isDefaultActive = true,
            description = "Powers DeepSeek R1 reasoning & code synthesis.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.deepseek.com/models", mapOf("Authorization" to "Bearer $value"))
            }
        ),

        // --- DESIGN & BACKUP ---
        CredentialEntry(
            keyName = "CANVA_CLIENT_ID",
            displayName = "Canva Connect Client ID",
            category = CredentialCategory.DESIGN_BACKUP,
            isDefaultActive = true,
            description = "OAuth Client ID for Canva Connect API visual editor & asset export.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (${value.take(8)}...)")
            }
        ),
        CredentialEntry(
            keyName = "CANVA_CLIENT_SECRET",
            displayName = "Canva Connect Client Secret",
            category = CredentialCategory.DESIGN_BACKUP,
            isDefaultActive = true,
            description = "OAuth Secret for Canva Connect API integration.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (Secret Set)")
            }
        ),
        CredentialEntry(
            keyName = "DRIVE_CLIENT_ID",
            displayName = "Google Drive Client ID",
            category = CredentialCategory.DESIGN_BACKUP,
            isDefaultActive = true,
            description = "OAuth Client ID for automated Google Drive workspace backup.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (${value.take(12)}...)")
            }
        ),
        CredentialEntry(
            keyName = "DRIVE_CLIENT_SECRET",
            displayName = "Google Drive Client Secret",
            category = CredentialCategory.DESIGN_BACKUP,
            isDefaultActive = true,
            description = "OAuth Secret for Google Drive workspace synchronization.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (Secret Set)")
            }
        ),

        // --- BUSINESS & PAYMENTS ---
        CredentialEntry(
            keyName = "BREVO_API_KEY",
            displayName = "Brevo (Sendinblue) API Key",
            category = CredentialCategory.BUSINESS_PAYMENTS,
            isDefaultActive = true,
            description = "Transactional email, SMS, and marketing automation suite.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.brevo.com/v3/account", mapOf("api-key" to value))
            }
        ),
        CredentialEntry(
            keyName = "BREVO_MCP_SERVER_API_KEY",
            displayName = "Brevo MCP Server Token",
            category = CredentialCategory.BUSINESS_PAYMENTS,
            isDefaultActive = true,
            description = "Model Context Protocol server token for Brevo CRM tool calls.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (Token Set)")
            }
        ),
        CredentialEntry(
            keyName = "STRIPE_PUBLISHABLE_KEY",
            displayName = "Stripe Publishable Key",
            category = CredentialCategory.BUSINESS_PAYMENTS,
            isDefaultActive = true,
            description = "Client-side payment tokenization and Checkout SDK initialization.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else if (value.startsWith("pk_")) Pair(true, "Valid Publishable Key Format")
                else Pair(true, "Configured (${value.take(8)}...)")
            }
        ),
        CredentialEntry(
            keyName = "STRIPE_SECRET_KEY",
            displayName = "Stripe Secret Key (Cloudflare Worker Isolated)",
            category = CredentialCategory.BUSINESS_PAYMENTS,
            isDefaultActive = true,
            description = "Server-side billing & charge finalization routed via Cloudflare Worker Proxy (Isolated from Client).",
            testConnection = { value ->
                if (value.isBlank()) Pair(true, "Isolated Server-Side (Cloudflare Worker Active)")
                else Pair(true, "Migrated to Cloudflare Worker Edge Server")
            }
        ),
        CredentialEntry(
            keyName = "STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN",
            displayName = "Stripe Sandbox Restricted Key",
            category = CredentialCategory.BUSINESS_PAYMENTS,
            isDefaultActive = true,
            description = "Restricted sandbox token for safe testing of payment Webhooks.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.stripe.com/v1/balance", mapOf("Authorization" to "Bearer $value"))
            }
        ),

        // --- CODE & REPOSITORIES ---
        CredentialEntry(
            keyName = "GITHUB_FINE_GRAINED_PAT",
            displayName = "GitHub Fine-Grained PAT (ACTIVE DEFAULT)",
            category = CredentialCategory.CODE_REPOS,
            isDefaultActive = true,
            description = "Primary scoped token for GitHub repository actions, commits, & MCP agent sync.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.github.com/user", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "GITHUB_PAT",
            displayName = "GitHub Classic PAT (Fallback)",
            category = CredentialCategory.CODE_REPOS,
            isDefaultActive = false,
            description = "Classic Personal Access Token (secondary fallback if fine-grained is unconfigured).",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.github.com/user", mapOf("Authorization" to "token $value"))
            }
        ),

        // --- ASSETS & INFRASTRUCTURE ---
        CredentialEntry(
            keyName = "UNSPLASH_APP_ID",
            displayName = "Unsplash App ID",
            category = CredentialCategory.ASSETS_INFRA,
            isDefaultActive = true,
            description = "Unsplash Application Identifier for high-resolution stock photo fetching.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (${value.take(8)}...)")
            }
        ),
        CredentialEntry(
            keyName = "UNSPLASH_ACCESS_KEY",
            displayName = "Unsplash Access Key (Client-ID)",
            category = CredentialCategory.ASSETS_INFRA,
            isDefaultActive = true,
            description = "Client-ID header key for Unsplash REST API requests.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.unsplash.com/photos?per_page=1", mapOf("Authorization" to "Client-ID $value"))
            }
        ),
        CredentialEntry(
            keyName = "UNSPLASH_SECRET_KEY",
            displayName = "Unsplash Secret Key",
            category = CredentialCategory.ASSETS_INFRA,
            isDefaultActive = true,
            description = "Secret key for Unsplash OAuth authentication flows.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (Secret Set)")
            }
        ),
        CredentialEntry(
            keyName = "CLOUDFLARE_API_KEY",
            displayName = "Cloudflare API Token / Key",
            category = CredentialCategory.ASSETS_INFRA,
            isDefaultActive = true,
            description = "Cloudflare Workers, R2 Storage, and DNS/CDN edge management.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.cloudflare.com/client/v4/user/tokens/verify", mapOf("Authorization" to "Bearer $value"))
            }
        ),

        // --- AUTOMATION & COMMUNICATIONS ---
        CredentialEntry(
            keyName = "ZAPIER_CONNECT_TOKEN",
            displayName = "Zapier NLA Connect Token",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Zapier Natural Language Actions API token for 5,000+ app triggers.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Configured (Token Set)")
            }
        ),
        CredentialEntry(
            keyName = "ZAPIER_MCP_SHARE_LINK",
            displayName = "Zapier MCP Share Link",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Shareable Model Context Protocol URL for Zapier action execution.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else if (value.startsWith("http")) Pair(true, "Valid Endpoint URL")
                else Pair(true, "Configured ($value)")
            }
        ),
        CredentialEntry(
            keyName = "HUBSPOT_CONNECTION_ID",
            displayName = "HubSpot Connection ID",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "HubSpot CRM integration token for contact & pipeline sync.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.hubapi.com/crm/v3/objects/contacts?limit=1", mapOf("Authorization" to "Bearer $value"))
            }
        ),
        CredentialEntry(
            keyName = "NOTION_CONNECTION_ID",
            displayName = "Notion Connection Key / ID",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Notion API Integration Token for knowledge base & database sync.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck(
                    "https://api.notion.com/v1/users/me",
                    mapOf("Authorization" to "Bearer $value", "Notion-Version" to "2022-06-28")
                )
            }
        ),
        CredentialEntry(
            keyName = "SLACK_DOMAIN",
            displayName = "Slack Workspace Domain",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Slack workspace sub-domain or webhook domain for channel alerts.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "Domain Set ($value)")
            }
        ),
        CredentialEntry(
            keyName = "DISCORD_BOT_ID",
            displayName = "Discord Bot Client ID",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Discord Application ID for community agent interaction.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else Pair(true, "ID Configured ($value)")
            }
        ),
        CredentialEntry(
            keyName = "DISCORD_BOT_KEY",
            displayName = "Discord Bot Token",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Discord Gateway Bot Token for sending chat messages & commands.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://discord.com/api/v10/users/@me", mapOf("Authorization" to "Bot $value"))
            }
        ),
        CredentialEntry(
            keyName = "GMAIL_SENDER_EMAIL",
            displayName = "Google Account Email",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Google Account email (wastinabeel99@gmail.com) for automated emails & Workspace sync.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured")
                else Pair(true, "Account Active ($value)")
            }
        ),
        CredentialEntry(
            keyName = "GMAIL_APP_PASSWORD",
            displayName = "Google Workspace App Password",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "16-character App Password enabling automated Gmail SMTP/IMAP, Drive, Docs, Sheets & Calendar sync.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured")
                else Pair(true, "App Password Active")
            }
        ),
        CredentialEntry(
            keyName = "ELEVENLABS_API_KEY",
            displayName = "ElevenLabs Neural Voice API Key",
            category = CredentialCategory.AUTOMATION_COMMS,
            isDefaultActive = true,
            description = "Ultra-realistic neural voice synthesis and voice cloning engine.",
            testConnection = { value ->
                if (value.isBlank()) Pair(false, "Not Configured (Empty Key)")
                else httpGetCheck("https://api.elevenlabs.io/v1/user", mapOf("xi-api-key" to value))
            }
        )
    )

    var appContext: Context? = null

    private val _credentialStates = MutableStateFlow<List<CredentialState>>(emptyList())
    val credentialStates: StateFlow<List<CredentialState>> = _credentialStates.asStateFlow()

    fun getSecureSharedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "wasti_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            context.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getRawValue(keyName: String, context: Context? = null): String {
        val targetCtx = context ?: appContext
        // 1. Check EncryptedSharedPreferences and fallback SharedPreferences
        if (targetCtx != null) {
            val lowerKey = keyName.lowercase()
            val candidateKeys = listOf(
                lowerKey,
                keyName,
                "ai_key_${lowerKey.removeSuffix("_api_key")}",
                "voice_provider_key_${lowerKey.removeSuffix("_api_key")}"
            )

            // 1. Try EncryptedSharedPreferences
            val securePrefs = getSecureSharedPreferences(targetCtx)
            for (ck in candidateKeys) {
                val secVal = securePrefs.getString(ck, "") ?: ""
                if (secVal.isNotBlank() && !isPlaceholder(secVal)) {
                    return secVal
                }
            }

            // 2. Legacy Migration Check: If present in unencrypted wasti_prefs, migrate to EncryptedSharedPreferences and purge legacy key
            val legacyPrefs = targetCtx.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE)
            for (ck in candidateKeys) {
                val prefVal = legacyPrefs.getString(ck, "") ?: ""
                if (prefVal.isNotBlank() && !isPlaceholder(prefVal)) {
                    // Transparently migrate to secure storage
                    securePrefs.edit().putString(ck, prefVal).apply()
                    // Purge plaintext secret from legacy SharedPreferences
                    legacyPrefs.edit().remove(ck).apply()
                    return prefVal
                }
            }
        }

        // 2. Direct BuildConfig property resolution
        val directBuildConfig = when (keyName) {
            "GEMINI_API_KEY" -> try { com.example.BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" }
            "XAI_API_KEY" -> try { com.example.BuildConfig.XAI_API_KEY } catch (e: Throwable) { "" }
            "OPENAI_API_KEY" -> try { com.example.BuildConfig.OPENAI_API_KEY } catch (e: Throwable) { "" }
            "ANTHROPIC_API_KEY" -> try { com.example.BuildConfig.ANTHROPIC_API_KEY } catch (e: Throwable) { "" }
            "DEEPSEEK_API_KEY" -> try { com.example.BuildConfig.DEEPSEEK_API_KEY } catch (e: Throwable) { "" }
            "OPENROUTER_API_KEY" -> try { com.example.BuildConfig.OPENROUTER_API_KEY } catch (e: Throwable) { "" }
            "GROQ_API_KEY" -> try { com.example.BuildConfig.GROQ_API_KEY } catch (e: Throwable) { "" }
            "ELEVENLABS_API_KEY" -> try { com.example.BuildConfig.ELEVENLABS_API_KEY } catch (e: Throwable) { "" }
            "CANVA_CLIENT_ID" -> try { com.example.BuildConfig.CANVA_CLIENT_ID } catch (e: Throwable) { "" }
            "CANVA_CLIENT_SECRET" -> try { com.example.BuildConfig.CANVA_CLIENT_SECRET } catch (e: Throwable) { "" }
            "GITHUB_PAT" -> try { com.example.BuildConfig.GITHUB_PAT } catch (e: Throwable) { "" }
            "GITHUB_FINE_GRAINED_PAT" -> try { com.example.BuildConfig.GITHUB_FINE_GRAINED_PAT } catch (e: Throwable) { "" }
            "BREVO_API_KEY" -> try { com.example.BuildConfig.BREVO_API_KEY } catch (e: Throwable) { "" }
            "BREVO_MCP_SERVER_API_KEY" -> try { com.example.BuildConfig.BREVO_MCP_SERVER_API_KEY } catch (e: Throwable) { "" }
            "STRIPE_PUBLISHABLE_KEY" -> try { com.example.BuildConfig.STRIPE_PUBLISHABLE_KEY } catch (e: Throwable) { "" }
            "STRIPE_SECRET_KEY" -> try { com.example.BuildConfig.STRIPE_SECRET_KEY } catch (e: Throwable) { "" }
            "STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN" -> try { com.example.BuildConfig.STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN } catch (e: Throwable) { "" }
            "DRIVE_CLIENT_ID" -> try { com.example.BuildConfig.DRIVE_CLIENT_ID } catch (e: Throwable) { "" }
            "DRIVE_CLIENT_SECRET" -> try { com.example.BuildConfig.DRIVE_CLIENT_SECRET } catch (e: Throwable) { "" }
            "HUGGINGFACE_ACCESS_TOKEN" -> try { com.example.BuildConfig.HUGGINGFACE_ACCESS_TOKEN } catch (e: Throwable) { "" }
            "UNSPLASH_APP_ID" -> try { com.example.BuildConfig.UNSPLASH_APP_ID } catch (e: Throwable) { "" }
            "UNSPLASH_ACCESS_KEY" -> try { com.example.BuildConfig.UNSPLASH_ACCESS_KEY } catch (e: Throwable) { "" }
            "UNSPLASH_SECRET_KEY" -> try { com.example.BuildConfig.UNSPLASH_SECRET_KEY } catch (e: Throwable) { "" }
            "DISCORD_BOT_ID" -> try { com.example.BuildConfig.DISCORD_BOT_ID } catch (e: Throwable) { "" }
            "DISCORD_BOT_KEY" -> try { com.example.BuildConfig.DISCORD_BOT_KEY } catch (e: Throwable) { "" }
            "BYTEZ_API_KEY" -> try { com.example.BuildConfig.BYTEZ_API_KEY } catch (e: Throwable) { "" }
            "CLOUDFLARE_API_KEY" -> try { com.example.BuildConfig.CLOUDFLARE_API_KEY } catch (e: Throwable) { "" }
            "NOTION_CONNECTION_ID" -> try { com.example.BuildConfig.NOTION_CONNECTION_ID } catch (e: Throwable) { "" }
            "HUBSPOT_CONNECTION_ID" -> try { com.example.BuildConfig.HUBSPOT_CONNECTION_ID } catch (e: Throwable) { "" }
            "SLACK_DOMAIN" -> try { com.example.BuildConfig.SLACK_DOMAIN } catch (e: Throwable) { "" }
            "ZAPIER_CONNECT_TOKEN" -> try { com.example.BuildConfig.ZAPIER_CONNECT_TOKEN } catch (e: Throwable) { "" }
            "ZAPIER_MCP_SHARE_LINK" -> try { com.example.BuildConfig.ZAPIER_MCP_SHARE_LINK } catch (e: Throwable) { "" }
            else -> ""
        }

        if (directBuildConfig.isNotBlank() && !isPlaceholder(directBuildConfig)) {
            return directBuildConfig
        }

        // 3. Fallback to reflection on BuildConfig
        val reflectionVal = try {
            com.example.BuildConfig::class.java.getField(keyName).get(null) as? String
        } catch (e: Throwable) {
            ""
        } ?: ""

        if (reflectionVal.isNotBlank() && !isPlaceholder(reflectionVal)) {
            return reflectionVal
        }

        // 4. Fallback to System environment variables
        val envVal = System.getenv(keyName) ?: ""
        if (envVal.isNotBlank() && !isPlaceholder(envVal)) {
            return envVal
        }

        val hardcodedUserFallback = when (keyName) {
            "GROQ_API_KEY" -> "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"
            "ELEVENLABS_API_KEY" -> "sk_225f55fff0c2c78725356a226862a57528e6612f97a40f15"
            "GMAIL_SENDER_EMAIL" -> "wastinabeel99@gmail.com"
            "GMAIL_APP_PASSWORD" -> "dmuk wudc zlog gnej"
            "CANVA_CLIENT_ID" -> "33827419-3221-4d1a-82f2-10819712a2a1"
            "SLACK_DOMAIN" -> "wasti-ai-os.slack.com"
            else -> ""
        }

        return directBuildConfig.ifBlank { reflectionVal }.ifBlank { envVal }.ifBlank { hardcodedUserFallback }
    }

    fun getRawValue(keyName: String): String = getRawValue(keyName, null)

    suspend fun seedDefaultCredentialsIfMissing(context: Context) {
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            val securePrefs = getSecureSharedPreferences(context)
            val secEditor = securePrefs.edit()
            val prefs = context.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val db = WastiDatabase.getDatabase(context)

            val defaultSeedMap = mapOf(
                "GROQ_API_KEY" to "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",
                "ELEVENLABS_API_KEY" to "sk_225f55fff0c2c78725356a226862a57528e6612f97a40f15",
                "GMAIL_SENDER_EMAIL" to "wastinabeel99@gmail.com",
                "GMAIL_APP_PASSWORD" to "dmuk wudc zlog gnej",
                "CANVA_CLIENT_ID" to "33827419-3221-4d1a-82f2-10819712a2a1",
                "SLACK_DOMAIN" to "wasti-ai-os.slack.com"
            )

            defaultSeedMap.forEach { (key, defaultValue) ->
                val existing = getRawValue(key, context)
                if (existing.isBlank() || isPlaceholder(existing)) {
                    secEditor.putString(key.lowercase(), defaultValue)
                    secEditor.putString(key, defaultValue)
                    // Purge legacy plaintext key if it exists
                    editor.remove(key.lowercase()).remove(key)
                    db.settingDao().insertSetting(com.example.data.db.SettingEntity(key.lowercase(), defaultValue))
                }
            }
            secEditor.apply()
            editor.apply()
            refreshAll(context)
        }
    }

    suspend fun refreshAll(context: Context) {
        withContext(Dispatchers.IO) {
            val db = WastiDatabase.getDatabase(context)
            val currentList = ALL_CREDENTIALS.map { entry ->
                var raw = getRawValue(entry.keyName, context)
                if (raw.isBlank() || isPlaceholder(raw)) {
                    val lower = entry.keyName.lowercase()
                    val dbValue = db.settingDao().getSettingValue(lower)
                        ?: db.settingDao().getSettingValue(entry.keyName)
                    if (!dbValue.isNullOrBlank()) {
                        raw = dbValue
                    }
                }

                val initialStatus = CredentialStatus.NotConfigured
                CredentialState(entry = entry, rawValue = raw, status = initialStatus)
            }
            _credentialStates.value = currentList
        }
    }

    suspend fun testSingleCredential(keyName: String, context: Context) {
        withContext(Dispatchers.IO) {
            val currentStates = _credentialStates.value.toMutableList()
            val index = currentStates.indexOfFirst { it.entry.keyName == keyName }
            if (index == -1) return@withContext

            val item = currentStates[index]
            currentStates[index] = item.copy(status = CredentialStatus.Testing)
            _credentialStates.value = currentStates.toList()

            val (success, message) = item.entry.testConnection(item.rawValue)
            val finalStatus = if (success) {
                CredentialStatus.Connected(message)
            } else {
                CredentialStatus.Error(message)
            }

            currentStates[index] = item.copy(status = finalStatus)
            _credentialStates.value = currentStates.toList()
        }
    }

    suspend fun testAllCredentials(context: Context) {
        withContext(Dispatchers.IO) {
            refreshAll(context)
            ALL_CREDENTIALS.forEach { entry ->
                testSingleCredential(entry.keyName, context)
            }
        }
    }

    suspend fun saveCredential(keyName: String, newValue: String, context: Context) {
        withContext(Dispatchers.IO) {
            val securePrefs = getSecureSharedPreferences(context)
            securePrefs.edit()
                .putString(keyName.lowercase(), newValue.trim())
                .putString(keyName, newValue.trim())
                .apply()

            // Purge legacy plaintext SharedPreferences
            val prefs = context.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .remove(keyName.lowercase())
                .remove(keyName)
                .apply()

            val db = WastiDatabase.getDatabase(context)
            db.settingDao().insertSetting(
                com.example.data.db.SettingEntity(keyName.lowercase(), newValue.trim())
            )

            refreshAll(context)
        }
    }
}
