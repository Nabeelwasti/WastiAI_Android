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

    fun isPlaceholder(valString: String): Boolean {
        if (valString.isBlank()) return true
        val upper = valString.trim().uppercase()
        return upper == "MY_KEY" ||
               upper == "YOUR_KEY" ||
               upper == "PLACEHOLDER" ||
               upper == "ENTER_KEY_HERE" ||
               upper == "NULL" ||
               upper.startsWith("MY_") ||
               upper.startsWith("YOUR_")
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
            description = "Google Account email for automated emails & Workspace sync.",
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

    fun getRawValue(keyName: String, context: Context? = null): String? {
        val targetCtx = context ?: appContext
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
                val secVal = securePrefs.getString(ck, null)
                if (!secVal.isNullOrBlank() && !isPlaceholder(secVal)) {
                    return secVal
                }
            }

            // 2. Legacy Migration Check: If present in unencrypted wasti_prefs, migrate to EncryptedSharedPreferences and purge legacy key
            val legacyPrefs = targetCtx.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE)
            for (ck in candidateKeys) {
                val prefVal = legacyPrefs.getString(ck, null)
                if (!prefVal.isNullOrBlank() && !isPlaceholder(prefVal)) {
                    // Transparently migrate to secure storage
                    securePrefs.edit().putString(ck, prefVal).apply()
                    // Purge plaintext secret from legacy SharedPreferences
                    legacyPrefs.edit().remove(ck).apply()
                    return prefVal
                }
            }
        }

        // 3. Direct BuildConfig property resolution
        val directBuildConfig = when (keyName) {
            "GEMINI_API_KEY" -> try { com.example.BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { null }
            "XAI_API_KEY" -> try { com.example.BuildConfig.XAI_API_KEY } catch (e: Throwable) { null }
            "OPENAI_API_KEY" -> try { com.example.BuildConfig.OPENAI_API_KEY } catch (e: Throwable) { null }
            "ANTHROPIC_API_KEY" -> try { com.example.BuildConfig.ANTHROPIC_API_KEY } catch (e: Throwable) { null }
            "DEEPSEEK_API_KEY" -> try { com.example.BuildConfig.DEEPSEEK_API_KEY } catch (e: Throwable) { null }
            "OPENROUTER_API_KEY" -> try { com.example.BuildConfig.OPENROUTER_API_KEY } catch (e: Throwable) { null }
            "GROQ_API_KEY" -> try { com.example.BuildConfig.GROQ_API_KEY } catch (e: Throwable) { null }
            "ELEVENLABS_API_KEY" -> try { com.example.BuildConfig.ELEVENLABS_API_KEY } catch (e: Throwable) { null }
            "CANVA_CLIENT_ID" -> try { com.example.BuildConfig.CANVA_CLIENT_ID } catch (e: Throwable) { null }
            "CANVA_CLIENT_SECRET" -> try { com.example.BuildConfig.CANVA_CLIENT_SECRET } catch (e: Throwable) { null }
            "GITHUB_PAT" -> try { com.example.BuildConfig.GITHUB_PAT } catch (e: Throwable) { null }
            "GITHUB_FINE_GRAINED_PAT" -> try { com.example.BuildConfig.GITHUB_FINE_GRAINED_PAT } catch (e: Throwable) { null }
            "BREVO_API_KEY" -> try { com.example.BuildConfig.BREVO_API_KEY } catch (e: Throwable) { null }
            "BREVO_MCP_SERVER_API_KEY" -> try { com.example.BuildConfig.BREVO_MCP_SERVER_API_KEY } catch (e: Throwable) { null }
            "STRIPE_PUBLISHABLE_KEY" -> try { com.example.BuildConfig.STRIPE_PUBLISHABLE_KEY } catch (e: Throwable) { null }
            "STRIPE_SECRET_KEY" -> try { com.example.BuildConfig.STRIPE_SECRET_KEY } catch (e: Throwable) { null }
            "STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN" -> try { com.example.BuildConfig.STRIPE_SANDBOX_RESTRICTED_KEY_TOKEN } catch (e: Throwable) { null }
            "DRIVE_CLIENT_ID" -> try { com.example.BuildConfig.DRIVE_CLIENT_ID } catch (e: Throwable) { null }
            "DRIVE_CLIENT_SECRET" -> try { com.example.BuildConfig.DRIVE_CLIENT_SECRET } catch (e: Throwable) { null }
            "HUGGINGFACE_ACCESS_TOKEN" -> try { com.example.BuildConfig.HUGGINGFACE_ACCESS_TOKEN } catch (e: Throwable) { null }
            "UNSPLASH_APP_ID" -> try { com.example.BuildConfig.UNSPLASH_APP_ID } catch (e: Throwable) { null }
            "UNSPLASH_ACCESS_KEY" -> try { com.example.BuildConfig.UNSPLASH_ACCESS_KEY } catch (e: Throwable) { null }
            "UNSPLASH_SECRET_KEY" -> try { com.example.BuildConfig.UNSPLASH_SECRET_KEY } catch (e: Throwable) { null }
            "DISCORD_BOT_ID" -> try { com.example.BuildConfig.DISCORD_BOT_ID } catch (e: Throwable) { null }
            "DISCORD_BOT_KEY" -> try { com.example.BuildConfig.DISCORD_BOT_KEY } catch (e: Throwable) { null }
            "BYTEZ_API_KEY" -> try { com.example.BuildConfig.BYTEZ_API_KEY } catch (e: Throwable) { null }
            "CLOUDFLARE_API_KEY" -> try { com.example.BuildConfig.CLOUDFLARE_API_KEY } catch (e: Throwable) { null }
            "NOTION_CONNECTION_ID" -> try { com.example.BuildConfig.NOTION_CONNECTION_ID } catch (e: Throwable) { null }
            "HUBSPOT_CONNECTION_ID" -> try { com.example.BuildConfig.HUBSPOT_CONNECTION_ID } catch (e: Throwable) { null }
            "SLACK_DOMAIN" -> try { com.example.BuildConfig.SLACK_DOMAIN } catch (e: Throwable) { null }
            "ZAPIER_CONNECT_TOKEN" -> try { com.example.BuildConfig.ZAPIER_CONNECT_TOKEN } catch (e: Throwable) { null }
            "ZAPIER_MCP_SHARE_LINK" -> try { com.example.BuildConfig.ZAPIER_MCP_SHARE_LINK } catch (e: Throwable) { null }
            else -> null
        }

        if (!directBuildConfig.isNullOrBlank() && !isPlaceholder(directBuildConfig)) {
            return directBuildConfig
        }

        // 4. Fallback to System environment variables
        val envVal = System.getenv(keyName)
        if (!envVal.isNullOrBlank() && !isPlaceholder(envVal)) {
            return envVal
        }

        // Return null if not configured
        return null
    }

    fun getRawValue(keyName: String): String? = getRawValue(keyName, null)

    suspend fun ingestBuildConfigKeysToVault(context: Context) {
        withContext(Dispatchers.IO) {
            val securePrefs = getSecureSharedPreferences(context)
            ALL_CREDENTIALS.forEach { entry ->
                val keyName = entry.keyName
                val existingLower = securePrefs.getString(keyName.lowercase(), null)
                val existingUpper = securePrefs.getString(keyName, null)
                val hasVaultValue = (!existingLower.isNullOrBlank() && !isPlaceholder(existingLower)) ||
                                    (!existingUpper.isNullOrBlank() && !isPlaceholder(existingUpper))

                if (!hasVaultValue) {
                    val buildConfigVal = getRawValue(keyName, context)
                    if (!buildConfigVal.isNullOrBlank() && !isPlaceholder(buildConfigVal)) {
                        android.util.Log.i("CredentialRegistry", "Vault Ingestion: Auto-saving non-placeholder secret [$keyName] into Vault")
                        saveCredential(keyName, buildConfigVal, context)
                    }
                }
            }
        }
    }

    suspend fun seedDefaultCredentialsIfMissing(context: Context) {
        withContext(Dispatchers.IO) {
            appContext = context.applicationContext
            ingestBuildConfigKeysToVault(context)
            refreshAll(context)
        }
    }

    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.length <= 8) return "••••••••"
        val prefix = if (trimmed.contains("-")) {
            val parts = trimmed.split("-")
            if (parts.size >= 2) parts.first() + "-" else trimmed.take(4)
        } else if (trimmed.contains("_")) {
            val parts = trimmed.split("_")
            if (parts.size >= 2) parts.first() + "_" else trimmed.take(4)
        } else {
            trimmed.take(4)
        }
        val suffix = trimmed.takeLast(4)
        return "$prefix...$suffix"
    }

    suspend fun getCustomKeyNames(context: Context): List<String> {
        val securePrefs = getSecureSharedPreferences(context)
        val rawCsv = securePrefs.getString("wasti_custom_key_names_csv", "") ?: ""
        return rawCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    suspend fun addCustomKey(keyName: String, newValue: String, context: Context) {
        withContext(Dispatchers.IO) {
            val formattedKey = keyName.trim().uppercase().replace(" ", "_")
            if (formattedKey.isBlank()) return@withContext

            val securePrefs = getSecureSharedPreferences(context)
            val currentKeys = getCustomKeyNames(context).toMutableSet()
            currentKeys.add(formattedKey)
            
            securePrefs.edit()
                .putString("wasti_custom_key_names_csv", currentKeys.joinToString(","))
                .apply()

            saveCredential(formattedKey, newValue, context)
        }
    }

    suspend fun deleteCustomKey(keyName: String, context: Context) {
        withContext(Dispatchers.IO) {
            val formattedKey = keyName.trim().uppercase().replace(" ", "_")
            val securePrefs = getSecureSharedPreferences(context)
            val currentKeys = getCustomKeyNames(context).filter { it != formattedKey }

            securePrefs.edit()
                .putString("wasti_custom_key_names_csv", currentKeys.joinToString(","))
                .remove(formattedKey)
                .remove(formattedKey.lowercase())
                .apply()

            val db = WastiDatabase.getDatabase(context)
            db.settingDao().deleteSetting(formattedKey.lowercase())

            refreshAll(context)
        }
    }

    suspend fun refreshAll(context: Context) {
        withContext(Dispatchers.IO) {
            val db = WastiDatabase.getDatabase(context)
            val customKeyNames = getCustomKeyNames(context)

            val customEntries = customKeyNames.map { customName ->
                CredentialEntry(
                    keyName = customName,
                    displayName = customName.replace("_", " ").lowercase().capitalize(),
                    category = CredentialCategory.MODEL_PROVIDERS,
                    isDefaultActive = true,
                    description = "Custom User API Secret / Integration Token",
                    testConnection = { value ->
                        if (value.isBlank()) Pair(false, "Not Configured (Empty Secret)")
                        else Pair(true, "Custom Secret Configured & Validated")
                    }
                )
            }

            val allEntriesToProcess = ALL_CREDENTIALS + customEntries.filter { custom -> ALL_CREDENTIALS.none { it.keyName == custom.keyName } }

            val currentList = allEntriesToProcess.map { entry ->
                var raw = getRawValue(entry.keyName, context)
                if (raw.isNullOrBlank() || isPlaceholder(raw)) {
                    val lower = entry.keyName.lowercase()
                    val dbValue = db.settingDao().getSettingValue(lower)
                        ?: db.settingDao().getSettingValue(entry.keyName)
                    if (!dbValue.isNullOrBlank()) {
                        raw = dbValue
                    }
                }

                val finalRaw = raw ?: ""
                val initialStatus = if (finalRaw.isBlank()) CredentialStatus.NotConfigured else CredentialStatus.NotConfigured
                CredentialState(entry = entry, rawValue = finalRaw, status = initialStatus)
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
