package com.example.data.core

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LeadItem(
    val title: String,
    val link: String,
    val description: String,
    val pubDate: String = "",
    val category: String = ""
)

data class LeadEvaluationResult(
    val matchScore: Int,
    val draftedPitch: String,
    val matchedSkills: List<String>
)

object LeadScraperEngine {

    private const val TAG = "LeadScraperEngine"

    /**
     * Fetches and parses an XML RSS feed using standard HttpURLConnection and XmlPullParser.
     */
    suspend fun fetchRssFeed(feedUrl: String): List<LeadItem> = withContext(Dispatchers.IO) {
        val leads = mutableListOf<LeadItem>()
        var connection: HttpURLConnection? = null
        try {
            val url = URL(feedUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WastiLeadRadar/1.0")
                instanceFollowRedirects = true
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(inputStream, "UTF-8")

                var eventType = parser.eventType
                var currentTitle = ""
                var currentLink = ""
                var currentDescription = ""
                var currentPubDate = ""
                var insideItem = false

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) {
                                insideItem = true
                                currentTitle = ""
                                currentLink = ""
                                currentDescription = ""
                                currentPubDate = ""
                            } else if (insideItem) {
                                when (tagName.lowercase()) {
                                    "title" -> currentTitle = safeNextText(parser)
                                    "link" -> {
                                        val href = parser.getAttributeValue(null, "href")
                                        val text = safeNextText(parser)
                                        currentLink = if (!href.isNullOrBlank()) href else text
                                    }
                                    "description", "summary", "content" -> currentDescription = cleanHtml(safeNextText(parser))
                                    "pubdate", "published", "updated" -> currentPubDate = safeNextText(parser)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if ((tagName.equals("item", ignoreCase = true) || tagName.equals("entry", ignoreCase = true)) && insideItem) {
                                insideItem = false
                                if (currentTitle.isNotBlank() || currentDescription.isNotBlank()) {
                                    leads.add(
                                        LeadItem(
                                            title = currentTitle,
                                            link = currentLink,
                                            description = currentDescription,
                                            pubDate = currentPubDate
                                        )
                                    )
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } else {
                Log.w(TAG, "RSS Feed HTTP response code: ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/parsing RSS feed from $feedUrl", e)
        } finally {
            connection?.disconnect()
        }

        if (leads.isEmpty()) {
            return@withContext emptyList<LeadItem>()
        }

        return@withContext leads
    }

    /**
     * Constructs RSS query URL (e.g. Upwork RSS feed) and fetches lead items.
     * Uses UPWORK_RSS_CUSTOM_URL if configured in CredentialRegistry.
     * If RSS fetch returns 0 items, falls back to WebSearchEngine deep web search.
     */
    suspend fun fetchLeadsForQuery(query: String, context: Context? = null): List<LeadItemEntity> {
        val customRssUrl = com.example.data.credential.CredentialRegistry.getRawValue("UPWORK_RSS_CUSTOM_URL", context)
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query.replace(" ", "+")
        }
        
        val rssUrl = if (!customRssUrl.isNullOrBlank() && customRssUrl.startsWith("http")) {
            if (customRssUrl.contains("?")) "$customRssUrl&q=$encodedQuery" else "$customRssUrl?q=$encodedQuery"
        } else {
            "https://www.upwork.com/ab/feed/jobs/rss?q=$encodedQuery"
        }
        val rawItems = fetchRssFeed(rssUrl).toMutableList()

        if (rawItems.isEmpty()) {
            Log.i(TAG, "RSS fetch returned 0 items. Intercepting flow and searching live web for leads...")
            try {
                val searchQuery = if (query.isNotBlank()) "latest freelance jobs for $query" else "latest freelance jobs for graphic design web development ai automation"
                val searchResultJson = com.example.data.ops.WebSearchEngine.search(searchQuery, context)
                val webLeads = parseSearchResultsToLeadItems(searchResultJson)
                rawItems.addAll(webLeads)
            } catch (e: Exception) {
                Log.w(TAG, "Deep web search fallback completed: ${e.message}")
            }
        }

        return rawItems.map { raw ->
            LeadItemEntity(
                title = raw.title,
                link = raw.link,
                description = raw.description,
                pubDate = raw.pubDate,
                category = raw.category.ifBlank { query }
            )
        }
    }

    private fun parseSearchResultsToLeadItems(jsonString: String): List<LeadItem> {
        val leadItems = mutableListOf<LeadItem>()
        try {
            val json = JSONObject(jsonString)
            val resultsArray = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val itemObj = resultsArray.getJSONObject(i)
                val title = itemObj.optString("title", "Freelance Job Opportunity")
                val snippet = itemObj.optString("snippet", "")
                val link = itemObj.optString("link", "")
                if (title.isNotBlank() || snippet.isNotBlank()) {
                    leadItems.add(
                        LeadItem(
                            title = title,
                            link = link,
                            description = snippet,
                            pubDate = "Live Web",
                            category = "Deep Web Sourced"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing web search results into LeadItems", e)
        }
        return leadItems
    }

    /**
     * Evaluates a job post text against the user's SkillMatrix services,
     * returning a MatchScore (0-100) and a DraftedPitch.
     */
    fun evaluateLeadMatch(
        jobPostText: String,
        skillMatrix: SkillMatrix = SkillMatrix()
    ): LeadEvaluationResult {
        val textLower = jobPostText.lowercase()

        val matchedSkills = skillMatrix.services.filter { skill ->
            val keywords = when {
                skill.contains("Graphic", ignoreCase = true) || skill.contains("Branding", ignoreCase = true) || skill.contains("Canva", ignoreCase = true) || skill.contains("Corel", ignoreCase = true) ->
                    listOf("graphic", "design", "logo", "brand", "visual", "photoshop", "illustrator", "banner", "poster", "signboard", "menu", "stationery", "canva", "coreldraw", "vector")
                skill.contains("Advanced Visuals", ignoreCase = true) || skill.contains("Motion", ignoreCase = true) || skill.contains("Video", ignoreCase = true) || skill.contains("AutoCAD", ignoreCase = true) || skill.contains("CAD", ignoreCase = true) ->
                    listOf("2d", "3d", "motion", "video", "photo", "edit", "editor", "portrait", "architectural", "rendering", "modeling", "reel", "after effects", "premiere", "blender", "clip", "montage", "autocad", "cad", "dwg")
                skill.contains("Screen Printing", ignoreCase = true) || skill.contains("Production", ignoreCase = true) || skill.contains("Print", ignoreCase = true) ->
                    listOf("screen print", "printing", "production", "pre-press", "apparel", "merchandise", "artwork", "t-shirt")
                skill.contains("Web", ignoreCase = true) || skill.contains("App", ignoreCase = true) || skill.contains("Software", ignoreCase = true) ->
                    listOf("web", "website", "app", "portal", "attendance", "software", "development", "frontend", "backend", "react", "wordpress", "mobile", "full stack", "fullstack")
                skill.contains("AI", ignoreCase = true) || skill.contains("Automation", ignoreCase = true) ->
                    listOf("ai", "automation", "assistant", "detection", "bot", "workflow", "gpt", "llm", "agent", "python", "zapier", "n8n")
                skill.contains("Digital Presence", ignoreCase = true) || skill.contains("SEO", ignoreCase = true) || skill.contains("Marketing", ignoreCase = true) ->
                    listOf("seo", "social media", "marketing", "consulting", "ranking", "traffic", "ads", "google ads", "meta")
                skill.contains("Copywriting", ignoreCase = true) || skill.contains("Content", ignoreCase = true) || skill.contains("Writing", ignoreCase = true) || skill.contains("Lyrics", ignoreCase = true) ->
                    listOf("copywriting", "content", "ebook", "e-book", "poetry", "lyrics", "writing", "article", "newsletter", "script")
                skill.contains("Corporate Outreach", ignoreCase = true) || skill.contains("B2B", ignoreCase = true) ->
                    listOf("outreach", "cold email", "b2b", "procurement", "campaign", "lead generation", "sales")
                skill.contains("DMCA", ignoreCase = true) || skill.contains("Protection", ignoreCase = true) || skill.contains("Takedown", ignoreCase = true) ->
                    listOf("dmca", "copyright", "takedown", "infringement", "stolen content", "protection", "removal", "piracy")
                skill.contains("Instructional", ignoreCase = true) || skill.contains("File", ignoreCase = true) || skill.contains("Academic", ignoreCase = true) ->
                    listOf("instructional", "academic", "file management", "curriculum", "training", "course")
                else -> listOf(skill.lowercase())
            }
            keywords.any { textLower.contains(it) }
        }

        val baseScore = when {
            matchedSkills.size >= 3 -> 96
            matchedSkills.size == 2 -> 88
            matchedSkills.size == 1 -> 78
            textLower.contains("client") || textLower.contains("project") || textLower.contains("need") -> 70
            else -> 65
        }

        val primaryService = matchedSkills.firstOrNull() ?: skillMatrix.services.first()
        val skillsStr = if (matchedSkills.isNotEmpty()) matchedSkills.joinToString(", ") else skillMatrix.formatSkillSummary()

        val isUrdu = jobPostText.any { it in '\u0600'..'\u06FF' }
        val pitch = if (isUrdu) {
            """
                محترم کلائنٹ،

                میں نے آپ کی ضرورت ("${jobPostText.take(90)}...") دیکھی۔ میں ${skillMatrix.agencyName} کے پلیٹ فارم سے آپ کے اس کام کو بہترین اور منافع بخش حقیقت میں بدلنے کے لیے مکمل طور پر تیار ہوں۔

                میری اہم خدمات:
                • $skillsStr
                • تیز رفتار، معیاری اور خودکار ڈیلیوری
                • کام اور بزنس آئیڈیا پر مفت مشورہ (Free Consultation)

                رابطہ کریں:
                👤 ${skillMatrix.ownerName}
                🏢 ${skillMatrix.agencyName}
                📞 کال / واٹس ایپ: ${skillMatrix.ownerPhone} (${skillMatrix.ownerPhoneInternational})
                📧 ای میل: ${skillMatrix.ownerEmail}
            """.trimIndent()
        } else {
            """
                Respected Hiring Client,

                I came across your job request ("${jobPostText.take(90)}...") and am exceptionally equipped to deliver this with top-tier professional precision.

                Through ${skillMatrix.agencyName}, I bring specialized expertise in $skillsStr, rapid delivery timelines, and guaranteed polish.

                Why Choose ${skillMatrix.agencyName}:
                • Direct Mastery: ${skillMatrix.services.take(4).joinToString(", ")}.
                • Complete End-to-End Execution & Automated Workflows.
                • 100% Free Project Scoping & Consultation.

                I am ready to start immediately. Let's discuss your project goals!

                Sincerely,
                ${skillMatrix.ownerName}
                ${skillMatrix.agencyName}
                📞 Call/WhatsApp: ${skillMatrix.ownerPhoneInternational} (${skillMatrix.ownerPhone})
                📧 Email: ${skillMatrix.ownerEmail}
            """.trimIndent()
        }

        return LeadEvaluationResult(
            matchScore = baseScore,
            draftedPitch = pitch,
            matchedSkills = if (matchedSkills.isNotEmpty()) matchedSkills else listOf(primaryService)
        )
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return try {
            parser.nextText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanHtml(htmlText: String): String {
        return htmlText
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
