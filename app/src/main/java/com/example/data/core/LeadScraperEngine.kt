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
                val searchQuery = "latest freelance jobs for graphic design video editing"
                val searchResultJson = com.example.data.ops.WebSearchEngine.search(searchQuery, context)
                val webLeads = parseSearchResultsToLeadItems(searchResultJson)
                rawItems.addAll(webLeads)
            } catch (e: Exception) {
                Log.e(TAG, "Deep web search fallback failed", e)
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
            val keywords = when (skill) {
                "Graphic Design" -> listOf("graphic", "design", "logo", "brand", "visual", "photoshop", "illustrator", "banner", "poster")
                "Video Editing" -> listOf("video", "edit", "editor", "reel", "youtube", "premiere", "after effects", "clip", "montage")
                "AutoCAD" -> listOf("autocad", "cad", "2d", "3d", "architectural", "drafting", "floor plan", "dwg")
                "CorelDRAW" -> listOf("coreldraw", "corel", "vector", "print", "cdr")
                "Canva" -> listOf("canva", "social media", "template", "post", "infographic")
                "DMCA Takedowns" -> listOf("dmca", "takedown", "copyright", "infringement", "protection", "removal", "piracy")
                "AI Automation" -> listOf("ai", "automation", "python", "gpt", "workflow", "bot", "script", "llm", "agent")
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

        val pitch = """
            Respected Hiring Client,

            I came across your job request ("${jobPostText.take(90)}...") and I am exceptionally equipped to deliver this with top-tier professional precision.

            As an experienced specialist in $skillsStr, I bring end-to-end expertise in $primaryService, fast delivery timelines, and polished execution.

            Why Choose My Services:
            • Direct Mastery: ${skillMatrix.services.take(4).joinToString(", ")}.
            • Complete End-to-End Workflow & Rapid Turnarounds.
            • 100% Client Satisfaction & Revisions Guarantee.

            I am ready to start immediately. Let's discuss your project requirements in detail!

            Sincerely,
            Wasti AI Lead Radar Specialist
        """.trimIndent()

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
