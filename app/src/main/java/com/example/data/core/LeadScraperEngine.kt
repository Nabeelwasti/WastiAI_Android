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
    val category: String = "",
    val email: String = "",
    val phone: String = "",
    val companyName: String = "",
    val linkedInUrl: String = "",
    val socialProfiles: List<String> = emptyList()
)

data class LeadEvaluationResult(
    val matchScore: Int,
    val draftedPitch: String,
    val matchedSkills: List<String>,
    val clientEmail: String = "",
    val clientPhone: String = "",
    val clientCompany: String = "",
    val clientLinkedIn: String = ""
)

object LeadScraperEngine {

    private const val TAG = "LeadScraperEngine"

    private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", RegexOption.IGNORE_CASE)
    private val PHONE_REGEX = Regex("(?:\\+?\\d{1,4}[\\s.-]?)?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,5}")
    private val WA_LINK_REGEX = Regex("https?://(?:wa\\.me|api\\.whatsapp\\.com/send\\?phone=)(\\+?\\d+)", RegexOption.IGNORE_CASE)
    private val LINKEDIN_REGEX = Regex("https?://(?:[a-zA-Z0-9]+\\.)?linkedin\\.com/(?:company|in)/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
    private val INSTAGRAM_REGEX = Regex("https?://(?:www\\.)?instagram\\.com/[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE)
    private val TWITTER_REGEX = Regex("https?://(?:www\\.)?(?:twitter\\.com|x\\.com)/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE)
    private val FACEBOOK_REGEX = Regex("https?://(?:www\\.)?facebook\\.com/[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE)
    private val GITHUB_REGEX = Regex("https?://(?:www\\.)?github\\.com/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
    private val TELEGRAM_REGEX = Regex("https?://(?:www\\.)?t\\.me/[a-zA-Z0-9_]+", RegexOption.IGNORE_CASE)
    private val DISCORD_REGEX = Regex("https?://(?:www\\.)?(?:discord\\.gg|discord\\.com/invite)/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)
    private val YOUTUBE_REGEX = Regex("https?://(?:www\\.)?youtube\\.com/(?:@[a-zA-Z0-9_-]+|c/[a-zA-Z0-9_-]+|channel/[a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE)
    private val TIKTOK_REGEX = Regex("https?://(?:www\\.)?tiktok\\.com/@[a-zA-Z0-9_.]+", RegexOption.IGNORE_CASE)
    private val REDDIT_REGEX = Regex("https?://(?:www\\.)?reddit\\.com/(?:r|u|user)/[a-zA-Z0-9_-]+", RegexOption.IGNORE_CASE)

    /**
     * Deep Multi-Source Business Lead Hunter & Web Scraper.
     * Searches multi-engine queries (Google / DDG / Live Web / LinkedIn X-Ray / RSS),
     * scrapes discovered target landing pages, extracts real contact intelligence,
     * and performs AI evaluation & pitch crafting.
     */
    suspend fun fetchLeadsForQuery(query: String, context: Context? = null): List<LeadItemEntity> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim().ifBlank { "Creative & Technical Solutions" }
        Log.i(TAG, "Starting comprehensive lead discovery & deep scraping for query: $cleanQuery")

        val discoveredLeads = mutableListOf<LeadItem>()

        // Lane 1: Custom/Upwork RSS Feed (if applicable)
        if (cleanQuery.contains("freelance", ignoreCase = true) || cleanQuery.contains("job", ignoreCase = true) || cleanQuery.contains("upwork", ignoreCase = true)) {
            try {
                val customRssUrl = com.example.data.credential.CredentialRegistry.getRawValue("UPWORK_RSS_CUSTOM_URL", context)
                val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
                val rssUrl = if (!customRssUrl.isNullOrBlank() && customRssUrl.startsWith("http")) {
                    if (customRssUrl.contains("?")) "$customRssUrl&q=$encodedQuery" else "$customRssUrl?q=$encodedQuery"
                } else {
                    "https://www.upwork.com/ab/feed/jobs/rss?q=$encodedQuery"
                }
                val rssLeads = fetchRssFeed(rssUrl)
                discoveredLeads.addAll(rssLeads)
            } catch (e: Exception) {
                Log.w(TAG, "RSS fetch completed with notice: ${e.message}")
            }
        }

        // Lane 2: Live Multi-Search Engine Queries (Targeted Business, Hiring, Contact & Social)
        val searchQueries = listOf(
            "$cleanQuery hiring contact email phone",
            "$cleanQuery official website services contact us",
            "site:linkedin.com/company/ $cleanQuery",
            "$cleanQuery business directory phone email website"
        )

        for (searchQ in searchQueries) {
            if (discoveredLeads.size >= 8) break
            try {
                val searchResultJson = com.example.data.ops.WebSearchEngine.search(searchQ, context)
                val items = parseSearchResultsToLeadItems(searchResultJson, cleanQuery)
                for (item in items) {
                    if (discoveredLeads.none { it.link.equals(item.link, ignoreCase = true) || it.title.equals(item.title, ignoreCase = true) }) {
                        discoveredLeads.add(item)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search query '$searchQ' execution: ${e.message}")
            }
        }

        // Lane 3: Deep Page Scraping & Real Contact Intelligence Extraction for discovered targets
        val enrichedLeadEntities = mutableListOf<LeadItemEntity>()
        val skillMatrix = SkillMatrix()

        for (lead in discoveredLeads.take(10)) {
            var extractedEmail = lead.email
            var extractedPhone = lead.phone
            var extractedCompany = lead.companyName
            var extractedLinkedIn = lead.linkedInUrl
            val socialProfiles = lead.socialProfiles.toMutableList()
            var pageSnippet = lead.description

            // Scrape target landing page if a valid HTTP URL exists
            if (lead.link.startsWith("http://") || lead.link.startsWith("https://")) {
                try {
                    val scrapedPage = com.example.data.ops.WebSearchEngine.scrapeWebPage(lead.link)
                    if (!scrapedPage.startsWith("Error")) {
                        // Extract emails from page
                        if (extractedEmail.isBlank() || extractedEmail == "Pending Discovery") {
                            val emailMatches = EMAIL_REGEX.findAll(scrapedPage)
                                .map { it.value.trim() }
                                .filter { !it.endsWith(".png") && !it.endsWith(".jpg") && !it.endsWith(".jpeg") && !it.contains("example.com") && !it.contains("domain.com") && !it.contains("sentry.io") }
                                .toList()
                            if (emailMatches.isNotEmpty()) {
                                extractedEmail = emailMatches.first()
                            }
                        }

                        // Extract phones and WhatsApp links
                        if (extractedPhone.isBlank() || extractedPhone == "Pending Discovery") {
                            val waMatch = WA_LINK_REGEX.find(scrapedPage)
                            if (waMatch != null) {
                                extractedPhone = waMatch.groupValues[1]
                            } else {
                                val phoneMatches = PHONE_REGEX.findAll(scrapedPage)
                                    .map { it.value.trim() }
                                    .filter { it.length in 8..20 && it.count { c -> c.isDigit() } >= 7 }
                                    .toList()
                                if (phoneMatches.isNotEmpty()) {
                                    extractedPhone = phoneMatches.first()
                                }
                            }
                        }

                        // Extract LinkedIn
                        if (extractedLinkedIn.isBlank()) {
                            val liMatch = LINKEDIN_REGEX.find(scrapedPage)
                            if (liMatch != null) {
                                extractedLinkedIn = liMatch.value.trim()
                            }
                        }

                        // Extract other social channels
                        INSTAGRAM_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        TWITTER_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        FACEBOOK_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        GITHUB_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        TELEGRAM_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        DISCORD_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        YOUTUBE_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        TIKTOK_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }
                        REDDIT_REGEX.findAll(scrapedPage).take(2).forEach { socialProfiles.add(it.value) }

                        // Extract company name if pending
                        if (extractedCompany.isBlank() || extractedCompany == "Pending Discovery") {
                            extractedCompany = LeadRadarRepository.extractCompanyName(lead.title, scrapedPage.take(300))
                        }

                        val socialSummary = if (socialProfiles.isNotEmpty()) "\nSocial Channels: " + socialProfiles.distinct().joinToString(" | ") else ""
                        if (scrapedPage.length > pageSnippet.length) {
                            pageSnippet = (lead.description + "\n\n" + scrapedPage.take(600) + socialSummary).trim()
                        } else if (socialSummary.isNotBlank()) {
                            pageSnippet = (pageSnippet + socialSummary).trim()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Deep page scraping for ${lead.link} encountered: ${e.message}")
                }
            }

            // Fallback heuristics from title & description if not extracted from page
            if (extractedEmail.isBlank() || extractedEmail == "Pending Discovery") {
                val emailFromDesc = LeadRadarRepository.extractEmail(lead.description)
                if (emailFromDesc != "Pending Discovery") extractedEmail = emailFromDesc
            }
            if (extractedPhone.isBlank() || extractedPhone == "Pending Discovery") {
                val phoneFromDesc = LeadRadarRepository.extractPhone(lead.description)
                if (phoneFromDesc != "Pending Discovery") extractedPhone = phoneFromDesc
            }
            if (extractedLinkedIn.isBlank()) {
                val liFromDesc = LeadRadarRepository.extractLinkedInUrl(lead.description)
                if (liFromDesc.isNotBlank()) extractedLinkedIn = liFromDesc
            }
            if (extractedCompany.isBlank() || extractedCompany == "Pending Discovery") {
                extractedCompany = LeadRadarRepository.extractCompanyName(lead.title, lead.description)
            }

            // AI-Powered Evaluation & Personalized Business Pitch Drafting
            val eval = evaluateLeadMatch(
                jobPostText = "${lead.title}\n$pageSnippet\nCompany: $extractedCompany",
                skillMatrix = skillMatrix,
                extractedEmail = extractedEmail,
                extractedPhone = extractedPhone,
                extractedCompany = extractedCompany,
                extractedLinkedIn = extractedLinkedIn,
                targetCategory = cleanQuery
            )

            // Construct enriched LeadItemEntity
            val entity = LeadItemEntity(
                id = java.util.UUID.randomUUID().toString(),
                title = lead.title,
                link = lead.link,
                description = pageSnippet,
                pubDate = lead.pubDate.ifBlank { "Live Web" },
                category = lead.category.ifBlank { cleanQuery },
                matchScore = eval.matchScore,
                matchedSkills = eval.matchedSkills,
                draftedPitch = eval.draftedPitch,
                status = LeadStatus.DISCOVERED,
                clientEmail = eval.clientEmail.ifBlank { extractedEmail },
                timestamp = System.currentTimeMillis()
            )

            enrichedLeadEntities.add(entity)
        }

        return@withContext enrichedLeadEntities
    }

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

        return@withContext leads
    }

    fun parseSearchResultsToLeadItems(jsonString: String, defaultCategory: String): List<LeadItem> {
        val leadItems = mutableListOf<LeadItem>()
        try {
            val json = JSONObject(jsonString)
            val resultsArray = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val itemObj = resultsArray.getJSONObject(i)
                val title = itemObj.optString("title", "Business Opportunity")
                val snippet = itemObj.optString("snippet", "")
                val link = itemObj.optString("link", "")
                if (title.isNotBlank() || snippet.isNotBlank()) {
                    val extractedEmail = LeadRadarRepository.extractEmail("$title $snippet")
                    val extractedPhone = LeadRadarRepository.extractPhone("$title $snippet")
                    val extractedCompany = LeadRadarRepository.extractCompanyName(title, snippet)
                    val extractedLinkedIn = LeadRadarRepository.extractLinkedInUrl("$title $snippet $link")

                    leadItems.add(
                        LeadItem(
                            title = title,
                            link = link,
                            description = snippet,
                            pubDate = "Live Web",
                            category = defaultCategory,
                            email = if (extractedEmail != "Pending Discovery") extractedEmail else "",
                            phone = if (extractedPhone != "Pending Discovery") extractedPhone else "",
                            companyName = if (extractedCompany != "Pending Discovery") extractedCompany else "",
                            linkedInUrl = extractedLinkedIn
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
     * Evaluates a job post or business profile against the agency's SkillMatrix services,
     * returning MatchScore, deeply tailored Pitch, and extracted contact fields.
     */
    fun evaluateLeadMatch(
        jobPostText: String,
        skillMatrix: SkillMatrix = SkillMatrix(),
        extractedEmail: String = "",
        extractedPhone: String = "",
        extractedCompany: String = "",
        extractedLinkedIn: String = "",
        targetCategory: String = ""
    ): LeadEvaluationResult {
        val textLower = jobPostText.lowercase()

        val matchedSkills = skillMatrix.services.filter { skill ->
            val keywords = when {
                skill.contains("Graphic", ignoreCase = true) || skill.contains("Branding", ignoreCase = true) || skill.contains("Canva", ignoreCase = true) || skill.contains("Corel", ignoreCase = true) ->
                    listOf("graphic", "design", "logo", "brand", "visual", "photoshop", "illustrator", "banner", "poster", "signboard", "menu", "stationery", "canva", "coreldraw", "vector", "flyer", "packaging")
                skill.contains("Advanced Visuals", ignoreCase = true) || skill.contains("Motion", ignoreCase = true) || skill.contains("Video", ignoreCase = true) || skill.contains("AutoCAD", ignoreCase = true) || skill.contains("CAD", ignoreCase = true) ->
                    listOf("2d", "3d", "motion", "video", "photo", "edit", "editor", "portrait", "architectural", "rendering", "modeling", "reel", "after effects", "premiere", "blender", "clip", "montage", "autocad", "cad", "dwg", "animation", "vfx")
                skill.contains("Screen Printing", ignoreCase = true) || skill.contains("Production", ignoreCase = true) || skill.contains("Print", ignoreCase = true) ->
                    listOf("screen print", "printing", "production", "pre-press", "apparel", "merchandise", "artwork", "t-shirt", "color separation", "dtf", "sublimation")
                skill.contains("Web", ignoreCase = true) || skill.contains("App", ignoreCase = true) || skill.contains("Software", ignoreCase = true) ->
                    listOf("web", "website", "app", "portal", "attendance", "software", "development", "frontend", "backend", "react", "wordpress", "mobile", "full stack", "fullstack", "node", "android", "ios", "api", "saas")
                skill.contains("AI", ignoreCase = true) || skill.contains("Automation", ignoreCase = true) ->
                    listOf("ai", "automation", "assistant", "detection", "bot", "workflow", "gpt", "llm", "agent", "python", "zapier", "n8n", "machine learning", "computer vision", "crawler", "scraper")
                skill.contains("Digital Presence", ignoreCase = true) || skill.contains("SEO", ignoreCase = true) || skill.contains("Marketing", ignoreCase = true) ->
                    listOf("seo", "social media", "marketing", "consulting", "ranking", "traffic", "ads", "google ads", "meta", "facebook ads", "instagram ads", "growth")
                skill.contains("Copywriting", ignoreCase = true) || skill.contains("Content", ignoreCase = true) || skill.contains("Writing", ignoreCase = true) || skill.contains("Lyrics", ignoreCase = true) ->
                    listOf("copywriting", "content", "ebook", "e-book", "poetry", "lyrics", "writing", "article", "newsletter", "script", "blog", "technical writing")
                skill.contains("Corporate Outreach", ignoreCase = true) || skill.contains("B2B", ignoreCase = true) ->
                    listOf("outreach", "cold email", "b2b", "procurement", "campaign", "lead generation", "sales", "crm", "enterprise")
                skill.contains("DMCA", ignoreCase = true) || skill.contains("Protection", ignoreCase = true) || skill.contains("Takedown", ignoreCase = true) ->
                    listOf("dmca", "copyright", "takedown", "infringement", "stolen content", "protection", "removal", "piracy", "intellectual property")
                skill.contains("Instructional", ignoreCase = true) || skill.contains("File", ignoreCase = true) || skill.contains("Academic", ignoreCase = true) ->
                    listOf("instructional", "academic", "file management", "curriculum", "training", "course", "lms", "e-learning")
                else -> listOf(skill.lowercase())
            }
            keywords.any { textLower.contains(it) }
        }

        val baseScore = when {
            matchedSkills.size >= 3 -> 98
            matchedSkills.size == 2 -> 92
            matchedSkills.size == 1 -> 85
            textLower.contains("client") || textLower.contains("business") || textLower.contains("service") || textLower.contains("hire") -> 80
            else -> 75
        }

        val primaryService = matchedSkills.firstOrNull() ?: targetCategory.ifBlank { skillMatrix.services.first() }
        val skillsStr = if (matchedSkills.isNotEmpty()) matchedSkills.joinToString(", ") else primaryService

        val clientDisplayName = if (extractedCompany.isNotBlank() && extractedCompany != "Pending Discovery") {
            extractedCompany
        } else if (extractedEmail.isNotBlank() && extractedEmail.contains("@")) {
            extractedEmail.substringBefore("@").replace(".", " ").capitalizeWords()
        } else {
            "Leadership Team"
        }

        val isUrdu = jobPostText.any { it in '\u0600'..'\u06FF' }
        val pitch = if (isUrdu) {
            """
                محترم $clientDisplayName،

                ہم نے آپ کے بزنس / پروجیکٹ کی تفصیلات ("${jobPostText.take(100).replace("\n", " ")}...") کا بغور جائزہ لیا ہے۔

                ہم ${skillMatrix.agencyName} کے پلیٹ فارم سے آپ کے بزنس کے لیے جدید اور اعلیٰ معیار کی خدمات فراہم کرنے کے لیے مکمل طور پر تیار ہیں:

                ہماری اہم خدمات:
                • $skillsStr
                • جدید ڈیزائن، ویڈیو ایڈیٹنگ، اور ویب / AI آٹومیشن کے حل
                • تیز رفتار، محفوظ اور منافع بخش ڈیلیوری
                • مکمل مفت پروجیکٹ اسکوپنگ اور مشورہ (100% Free Consultation & Scoping)

                رابطہ کی تفصیلات:
                👤 ${skillMatrix.ownerName}
                🏢 ${skillMatrix.agencyName}
                📞 کال / واٹس ایپ: ${skillMatrix.ownerPhoneInternational} (${skillMatrix.ownerPhone})
                📧 ای میل: ${skillMatrix.ownerEmail}
            """.trimIndent()
        } else {
            """
                Dear $clientDisplayName Team,

                I reviewed your business focus and project requirements regarding "${jobPostText.take(110).replace("\n", " ")}...".

                Through ${skillMatrix.agencyName}, I specialize in delivering turnkey solutions tailored specifically to your objectives, including:
                • Targeted Expertise: $skillsStr
                • End-to-End Execution: From design & media production to robust web, AI automation, and digital scaling.
                • Dedicated Turnaround & High Precision.
                • 100% Free Project Scoping & Discovery Consultation.

                I would be delighted to schedule a brief consultation to discuss your specific goals and provide a comprehensive proposal.

                Best regards,
                ${skillMatrix.ownerName}
                ${skillMatrix.agencyName}
                📞 Call/WhatsApp: ${skillMatrix.ownerPhoneInternational} (${skillMatrix.ownerPhone})
                📧 Email: ${skillMatrix.ownerEmail}
            """.trimIndent()
        }

        return LeadEvaluationResult(
            matchScore = baseScore,
            draftedPitch = pitch,
            matchedSkills = if (matchedSkills.isNotEmpty()) matchedSkills else listOf(primaryService),
            clientEmail = extractedEmail,
            clientPhone = extractedPhone,
            clientCompany = extractedCompany,
            clientLinkedIn = extractedLinkedIn
        )
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
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

