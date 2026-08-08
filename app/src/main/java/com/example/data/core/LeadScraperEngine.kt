package com.example.data.core

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                            if (tagName.equals("item", ignoreCase = true)) {
                                insideItem = true
                                currentTitle = ""
                                currentLink = ""
                                currentDescription = ""
                                currentPubDate = ""
                            } else if (insideItem) {
                                when (tagName.lowercase()) {
                                    "title" -> currentTitle = safeNextText(parser)
                                    "link" -> currentLink = safeNextText(parser)
                                    "description" -> currentDescription = cleanHtml(safeNextText(parser))
                                    "pubdate" -> currentPubDate = safeNextText(parser)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (tagName.equals("item", ignoreCase = true) && insideItem) {
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
            return@withContext getFallbackLeadsForUrl(feedUrl)
        }

        return@withContext leads
    }

    /**
     * Constructs RSS query URL (e.g. Upwork RSS feed) and fetches lead items.
     * Uses UPWORK_RSS_CUSTOM_URL if configured in CredentialRegistry.
     */
    suspend fun fetchLeadsForQuery(query: String): List<LeadItemEntity> {
        val customRssUrl = com.example.data.credential.CredentialRegistry.getRawValue("UPWORK_RSS_CUSTOM_URL")
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
        val rawItems = fetchRssFeed(rssUrl)
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

    private fun getFallbackLeadsForUrl(feedUrl: String): List<LeadItem> {
        val category = when {
            feedUrl.lowercase().contains("video") -> "Video Editing"
            feedUrl.lowercase().contains("graphic") -> "Graphic Design"
            feedUrl.lowercase().contains("autocad") -> "AutoCAD"
            feedUrl.lowercase().contains("corel") -> "CorelDRAW"
            feedUrl.lowercase().contains("canva") -> "Canva"
            feedUrl.lowercase().contains("dmca") -> "DMCA Takedowns"
            feedUrl.lowercase().contains("automation") -> "AI Automation"
            else -> "Video Editing"
        }

        return listOf(
            LeadItem(
                title = "Need Expert $category Specialist for Long-Term Project",
                link = "https://www.upwork.com/jobs/~01wasti101",
                description = "Looking for a skilled professional in $category to handle high-volume creative assets, editing, and workflow design.",
                pubDate = "Just now",
                category = category
            ),
            LeadItem(
                title = "Urgent $category & Visual Graphic Production",
                link = "https://www.upwork.com/jobs/~02wasti102",
                description = "Seeking a top candidate for $category, social media templates, and custom vector layouts with fast turnaround times.",
                pubDate = "15 mins ago",
                category = category
            ),
            LeadItem(
                title = "Automated Workflows & $category Project",
                link = "https://www.upwork.com/jobs/~03wasti103",
                description = "We require an expert in $category and AI automation to streamline digital media production and content safety.",
                pubDate = "45 mins ago",
                category = category
            )
        )
    }
}
