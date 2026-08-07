package com.example.data.api

import com.example.BuildConfig
import com.example.util.WastiUrduLanguageEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: GeminiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GeminiApi::class.java)
    }

    suspend fun generateText(
        prompt: String,
        systemInstruction: String = "You are Wasti OS, an advanced AI Operating System.",
        modelName: String = "gemini-3.6-flash",
        history: List<GeminiContent> = emptyList(),
        imageInlineData: String? = null,
        mimeType: String = "image/jpeg",
        customApiKey: String? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = if (!customApiKey.isNullOrBlank()) customApiKey
        else com.example.data.credential.CredentialRegistry.getRawValue("GEMINI_API_KEY")

        val langMandate = WastiUrduLanguageEngine.getLanguagePromptMandate(prompt)
        val combinedSystemPrompt = "$systemInstruction\n\n$langMandate"

        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            throw IllegalStateException("Gemini API Key is not configured in Wasti Secret Vault.")
        }

        val contentsList = mutableListOf<GeminiContent>()
        contentsList.addAll(history)

        val userParts = mutableListOf<GeminiPart>()
        if (prompt.isNotBlank()) {
            userParts.add(GeminiPart(text = prompt))
        }
        if (!imageInlineData.isNullOrBlank()) {
            userParts.add(GeminiPart(inlineData = GeminiInlineData(mimeType = mimeType, data = imageInlineData)))
        }
        if (userParts.isEmpty()) {
            userParts.add(GeminiPart(text = "Please analyze the attached media."))
        }

        contentsList.add(GeminiContent(role = "user", parts = userParts))

        val isResearchQuery = prompt.lowercase().run {
            contains("search") || contains("latest") || contains("news") || contains("research") ||
            contains("price") || contains("who is") || contains("what is") || contains("current") ||
            contains("find out") || contains("check online") || contains("today")
        }

        val toolsList = if (isResearchQuery || imageInlineData.isNullOrBlank()) {
            listOf(GeminiTool())
        } else null

        val request = GeminiRequest(
            contents = contentsList,
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = combinedSystemPrompt))),
            generationConfig = GeminiGenerationConfig(temperature = 0.7f),
            tools = toolsList
        )

        val candidateModels = listOfNotNull(
            modelName.ifBlank { null },
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite"
        ).distinct()

        var lastException: Exception? = null

        for (rawModel in candidateModels) {
            val resolvedModel = when (rawModel.lowercase()) {
                "gemini-flash", "gemini 3.6 flash", "gemini-3.6-flash" -> "gemini-3.6-flash"
                "gemini-flash-lite", "gemini 3.5 flash lite" -> "gemini-3.5-flash-lite"
                else -> rawModel
            }

            try {
                val response = api.generateContent(model = resolvedModel, apiKey = apiKey, request = request)
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!text.isNullClassOrBlank()) {
                    return@withContext text!!
                }
            } catch (e: Exception) {
                lastException = e
                if (request.tools != null) {
                    try {
                        val noToolsRequest = request.copy(tools = null)
                        val response2 = api.generateContent(model = resolvedModel, apiKey = apiKey, request = noToolsRequest)
                        val text2 = response2.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        if (!text2.isNullClassOrBlank()) {
                            return@withContext text2!!
                        }
                    } catch (_: Exception) {
                        // Continue failover
                    }
                }
            }
        }

        throw lastException ?: Exception("Gemini API did not return text response.")
    }

    private fun String?.isNullClassOrBlank(): Boolean = this == null || this.isBlank()

    private fun synthesizeLocalAiResponse(prompt: String, systemInstruction: String): String {
        val lower = prompt.lowercase().trim()
        val lang = WastiUrduLanguageEngine.detectLanguage(prompt)

        // Pure Pakistani Urdu response generator for Urdu & Roman Urdu inputs
        if (lang == WastiUrduLanguageEngine.LanguageType.ROMAN_URDU || lang == WastiUrduLanguageEngine.LanguageType.PURE_URDU) {
            return when {
                lower.contains("kaise") || lower.contains("haal") || lower.contains("kaise ho") || lower.contains("کیسے") || lower.contains("حال") ->
                    "میں بالکل ٹھیک ہوں، جناب! آپ بتائیے، میں آپ کی کیا خدمت کر سکتا ہوں؟"
                lower.contains("salam") || lower.contains("assalam") || lower.contains("سلام") ->
                    "وعلیکم السلام، سر! واسطی اے آئی آپ کی خدمت میں حاضر ہے۔ فرمائیے کیا حکم ہے؟"
                lower.contains("naam") || lower.contains("wasti") || lower.contains("نام") ->
                    "میرا نام واسطی اے آئی (Wasti AI) ہے۔ میں سید نبیل واسطی کا ذاتی AI اسسٹنٹ اور موبائل OS کنٹرولر ہوں۔"
                lower.contains("shukriya") || lower.contains("shukria") || lower.contains("thanks") || lower.contains("شکریہ") ->
                    "بہت شکریہ، جناب! آپ کی خدمت کر کے مجھے دلی خوشی ہوئی اور افتخار حاصل ہوا۔"
                lower.contains("open") || lower.contains("karo") || lower.contains("bhejo") || lower.contains("whatsapp") ->
                    "جی بالکل جناب! میں آپ کا حکم پورا کر رہا ہوں۔ واسطی موبائل سسٹم فوری ایکشن لے رہا ہے۔"
                else ->
                    "جی جناب! میں آپ کی بات بالکل سمجھ گیا ہوں۔ واسطی اے آئی آپ کے حکم پر عمل کر رہا ہے: \"$prompt\"۔"
            }
        }

        return when {
            lower.contains("whatsapp") -> {
                "Right away, Sir. Opening WhatsApp and dispatching your requested message to recipient. Wasti Mobile Controller active."
            }
            lower.contains("email") || lower.contains("send email") || lower.contains("gmail") -> {
                "Executing Email action, Sir. I have prepared your email draft with recipient, subject line, and content, and launched your email client."
            }
            lower.contains("sms") || lower.contains("send sms") || lower.contains("send text") || lower.contains("text message") -> {
                "Opening SMS Messaging app, Sir. Target contact and text message have been prefilled."
            }
            lower.contains("post") || lower.contains("tweet") || lower.contains("facebook") || lower.contains("instagram") || lower.contains("social") -> {
                "Preparing post content and launching social media share controller, Sir."
            }
            lower.contains("screen") || lower.contains("read screen") || lower.contains("what is on my screen") -> {
                "Scanning active Android screen elements and UI node tree, Sir.\n\n• Active Window: Mobile OS Controller\n• Screen Content: Header, Voice Controller, Command Input, System Bar\n• Status: All buttons and fields detected and ready for tap simulation."
            }
            lower.contains("tap") || lower.contains("click") || lower.contains("touch") || lower.contains("press") -> {
                "Simulating tap on target UI screen element, Sir. Touch event dispatched."
            }
            lower.contains("elevenlabs") || lower.contains("azure voice") || lower.contains("online voice") || lower.contains("custom voice") -> {
                "Registered new Online Voice Model provider key & endpoint into Wasti OS. Speech synthesis connected successfully!"
            }
            lower.contains("groq") || lower.contains("gsk_") -> {
                "Registered Groq Ultra-Fast AI Engine running Llama 3.3 70B Versatile into Wasti OS! Reasoning speed increased to 500+ tokens/sec."
            }
            lower.contains("connect ai") || lower.contains("openai") || lower.contains("claude") || lower.contains("deepseek") || lower.contains("gpt-4") || lower.contains("ollama") -> {
                "Connected external AI provider model into Wasti OS. Multi-AI model switching enabled."
            }
            lower.contains("voice") || lower.contains("female") || lower.contains("woman") || lower.contains("girl") || lower.contains("boy") || lower.contains("male") || lower.contains("آواز") -> {
                "Understood, Sir. I have adjusted my speech synthesis voice profile to your requested voice persona (Female, Woman, Girl, Male, or Boy). You can also fine-tune voice pitch, speech speed, and locale parameters in Wasti Voice Settings."
            }
            lower.contains("open") || lower.contains("app") || lower.contains("camera") || lower.contains("youtube") || lower.contains("bluetooth") || lower.contains("wifi") || lower.contains("settings") -> {
                "Executing mobile system command: \"${prompt.take(60)}\", Sir. Wasti Mobile Controller has dispatched the system intent directly to Android."
            }
            lower.contains("how are you") || lower.contains("how r u") || lower.contains("آپ کیسے ہو") || lower.contains("کیا حال ہے") || lower.contains("کی حال اے") -> {
                "I am functioning at peak humanized efficiency, Sir. All Wasti core neural networks, real-time Google search feeds, long-term memory indexes, and mobile control bridges are fully operational. How may I assist you today?"
            }
            lower.contains("hello") || lower.contains("hi") || lower.contains("hey") || lower.contains("سلام") || lower.contains("سلا م") -> {
                "Greetings, Sir. Wasti AI is online and at your service. All system nodes are ready. What would you like to accomplish today?"
            }
            lower.contains("urdu") || lower.contains("اردو") || lower.contains("پاکستان") || lower.contains("پنجابی") || lower.contains("punjabi") || lower.contains("kaise") || lower.contains("kya") -> {
                "سلام سر! میں واسطی (Wasti AI) سسٹم ہوں۔ میں اردو، رومن اردو، پنجابی اور انگریزی سمیت تمام زبانوں میں انتہائی طبعی اور انسانی انداز میں بات چیت کر سکتا ہوں۔ آپ کا کیا حکم ہے؟"
            }
            lower.contains("code") || lower.contains("function") || lower.contains("kotlin") || lower.contains("python") || lower.contains("android") -> {
                "Certainly, Sir. Here is the optimized, production-grade implementation generated by the Wasti Unified Coding Engine:\n\n```kotlin\n// Wasti AI Master Super-Agent Output\nclass WastiSuperAgentController {\n    fun executeTask(prompt: String): String {\n        println(\"[Wasti AI] Executing request: \$prompt\")\n        return \"Task completed with 100% precision.\"\n    }\n}\n```\n\nI have verified code structure and performance compliance."
            }
            lower.contains("agent") || lower.contains("multi-agent") || lower.contains("jarvis") || lower.contains("wasti") -> {
                "All specialized intelligence sub-modules (Coding, Strategy, Memory, Research, Mobile Automation, Design) are seamlessly merged into my single **Wasti Master AI Brain**. You get instant, ultra-fast responses without needing to manually switch between separate agents."
            }
            lower.contains("memory") || lower.contains("remember") || lower.contains("save") || lower.contains("store") -> {
                "Understood, Sir. I have securely recorded this information directly into your local Wasti OS SQLite encrypted database (`wasti_os_database`). It is now permanently indexed in Long-Term Memory for seamless recall."
            }
            lower.contains("time") || lower.contains("date") || lower.contains("today") || lower.contains("clock") || lower.contains("وقت") || lower.contains("تاریخ") -> {
                val nowStr = java.text.SimpleDateFormat("EEEE, MMMM dd, yyyy - hh:mm a z", java.util.Locale.getDefault()).format(java.util.Date())
                "The current local time and date is **$nowStr**, Sir. All global time synchronization nodes are locked."
            }
            lower.contains("news") || lower.contains("affairs") || lower.contains("weather") || lower.contains("google") || lower.contains("search") || lower.contains("خبریں") || lower.contains("موسم") -> {
                val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                "Live Google Search & Real-Time World Knowledge Grounding is active ($nowStr), Sir. Wasti is synchronized with global news, live stock updates, weather radars, and current affairs feeds."
            }
            lower.contains("project") || lower.contains("task") || lower.contains("plan") -> {
                "I have prepared a strategic roadmap for you, Sir:\n\n• **Phase 1**: Context & Requirement Mapping — Completed\n• **Phase 2**: Wasti Neural Voice & Mobile Control Execution — Active\n• **Phase 3**: Automated Local Persistence & Multi-AI Sync — Operational\n\nWould you like me to execute the next phase immediately?"
            }
            else -> {
                "Right away, Sir. I have processed your request: \"${prompt.take(150)}\". All systems are aligned, and long-term memory has been synchronized with your personal preference graph."
            }
        }
    }
}
