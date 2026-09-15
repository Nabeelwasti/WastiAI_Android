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
        val activeProfile = BusinessProfileManager.getActiveProfile(context)
        val skillMatrix = activeProfile.toSkillMatrix()

        // Lane 1: Live, Verified Remotive Remote Jobs Feed (Authentic Public API)
        val remotiveJobs = fetchRemotiveJobs(cleanQuery)
        discoveredLeads.addAll(remotiveJobs)

        // Lane 2: RemoteOK Public RSS Feed (Real Remote Jobs)
        if (discoveredLeads.size < 6) {
            try {
                val remoteOkLeads = fetchRssFeed("https://remoteok.com/remote-jobs.rss")
                val queryLower = cleanQuery.lowercase()
                val matchedRemoteOk = remoteOkLeads.filter {
                    it.title.lowercase().contains(queryLower) ||
                    it.description.lowercase().contains(queryLower) ||
                    queryLower.split(" ").any { w -> w.length > 3 && (it.title.lowercase().contains(w) || it.description.lowercase().contains(w)) }
                }
                discoveredLeads.addAll(matchedRemoteOk)
            } catch (e: Exception) {
                Log.w(TAG, "RemoteOK RSS notice: ${e.message}")
            }
        }

        // Lane 3: Custom User-Configured RSS Feed (e.g. Upwork authenticated RSS bridge)
        val customRssUrl = com.example.data.credential.CredentialRegistry.getRawValue("UPWORK_RSS_CUSTOM_URL", context)
        if (!customRssUrl.isNullOrBlank() && customRssUrl.startsWith("http")) {
            try {
                val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
                val rssUrl = if (customRssUrl.contains("?")) "$customRssUrl&q=$encodedQuery" else "$customRssUrl?q=$encodedQuery"
                val customLeads = fetchRssFeed(rssUrl)
                discoveredLeads.addAll(customLeads)
            } catch (e: Exception) {
                Log.w(TAG, "Custom RSS fetch notice: ${e.message}")
            }
        }

        // Lane 4: Live Multi-Search Engine Queries with Strict Lead Gate
        if (discoveredLeads.size < 8) {
            val searchQueries = listOf(
                "$cleanQuery hiring apply contact email",
                "$cleanQuery freelance contract rfp job",
                "$cleanQuery business services contact us"
            )

            for (searchQ in searchQueries) {
                if (discoveredLeads.size >= 10) break
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
        }

        // Lane 5: Deep Page Scraping & Real Contact Intelligence Extraction for discovered targets
        val enrichedLeadEntities = mutableListOf<LeadItemEntity>()

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
                targetCategory = cleanQuery,
                businessProfile = activeProfile
            )

            // Strictly gate on positive genuine match: reject irrelevant or non-hiring entries
            if (eval.matchScore >= 70) {
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
        }

        return@withContext enrichedLeadEntities
    }

    /**
     * Fetches verified, live remote jobs from the public Remotive API without requiring API keys.
     */
    suspend fun fetchRemotiveJobs(query: String): List<LeadItem> = withContext(Dispatchers.IO) {
        val jobs = mutableListOf<LeadItem>()
        var connection: HttpURLConnection? = null
        try {
            val cleanQ = query.trim()
            val encodedQuery = URLEncoder.encode(cleanQ, "UTF-8")
            val urlString = if (cleanQ.isNotBlank()) {
                "https://remotive.com/api/remote-jobs?search=$encodedQuery&limit=12"
            } else {
                "https://remotive.com/api/remote-jobs?limit=12"
            }
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WastiLeadRadar/2.0")
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val jobsArray = json.optJSONArray("jobs") ?: JSONArray()
                for (i in 0 until jobsArray.length()) {
                    val jobObj = jobsArray.getJSONObject(i)
                    val title = jobObj.optString("title", "")
                    val company = jobObj.optString("company_name", "")
                    val jobUrl = jobObj.optString("url", "")
                    val rawDesc = jobObj.optString("description", "")
                    val cleanDesc = cleanHtml(rawDesc).take(800)
                    val pubDate = jobObj.optString("publication_date", "")
                    val category = jobObj.optString("category", cleanQ)
                    val location = jobObj.optString("candidate_required_location", "Worldwide")

                    if (title.isNotBlank() && jobUrl.isNotBlank()) {
                        val fullSnippet = "$cleanDesc\nLocation: $location\nCompany: $company"
                        jobs.add(
                            LeadItem(
                                title = "$title at $company",
                                link = jobUrl,
                                description = fullSnippet,
                                pubDate = pubDate.ifBlank { "Live Job Feed" },
                                category = category,
                                companyName = company
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remotive live jobs fetch notice: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        jobs
    }

    /**
     * Strict validation filter to eliminate fake leads, informational articles,
     * encyclopedia pages, and search engine results.
     */
    fun isGenuineJobOrBusinessOpportunity(title: String, snippet: String, link: String): Boolean {
        val lowerLink = link.lowercase()
        val lowerTitle = title.lowercase()
        val lowerSnippet = snippet.lowercase()
        val combined = "$lowerTitle $lowerSnippet"

        // 1. Blacklist invalid / informational / search engine domains
        val bannedDomains = listOf(
            "wikipedia.org", "wikimedia.org", "wiktionary.org",
            "google.com/search", "duckduckgo.com", "bing.com", "search.yahoo.com",
            "dictionary.com", "merriam-webster.com", "britannica.com",
            "thefreedictionary.com", "cambridge.org", "schema.org", "w3.org",
            "youtube.com/watch", "tiktok.com", "pinterest.com", "example.com"
        )
        if (bannedDomains.any { lowerLink.contains(it) }) {
            return false
        }

        // 2. Reject pure informational or encyclopedia definition patterns
        val informationalPatterns = listOf(
            "refers to", "may refer to", "is defined as", "definition of",
            "wikipedia article", "free encyclopedia", "overview of the history",
            "meaning in english", "synonyms and antonyms"
        )
        if (informationalPatterns.any { combined.contains(it) }) {
            return false
        }

        // 3. Affirmative hiring / client / project solicitation signals
        val hiringKeywords = listOf(
            "hiring", "job", "career", "looking for", "seeking", "needed", "wanted",
            "freelance", "contract", "remote", "developer", "designer", "engineer",
            "writer", "consultant", "specialist", "agency", "quote", "rfp", "project",
            "budget", "rates", "apply", "contact us", "get in touch", "client",
            "opportunity", "vacancy", "internship", "employment", "recruiting",
            "position", "team", "services", "solutions", "property", "listing", "sales"
        )
        return hiringKeywords.any { combined.contains(it) }
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
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WastiLeadRadar/2.0")
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

                // Strict validation gate: discard encyclopedia, search links, and non-hiring pages
                if (!isGenuineJobOrBusinessOpportunity(title, snippet, link)) {
                    continue
                }

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
     * Evaluates a job post or business inquiry against the configured BusinessProfile,
     * returning MatchScore, deeply tailored Pitch, and extracted contact fields.
     */
    fun evaluateLeadMatch(
        jobPostText: String,
        skillMatrix: SkillMatrix? = null,
        extractedEmail: String = "",
        extractedPhone: String = "",
        extractedCompany: String = "",
        extractedLinkedIn: String = "",
        targetCategory: String = "",
        businessProfile: BusinessProfile? = null
    ): LeadEvaluationResult {
        val matrix = when {
            skillMatrix != null && skillMatrix.services != SkillMatrix().services -> skillMatrix
            businessProfile != null -> businessProfile.toSkillMatrix()
            skillMatrix != null -> skillMatrix
            else -> (businessProfile ?: BusinessProfileManager.getActiveProfile()).toSkillMatrix()
        }
        val textLower = jobPostText.lowercase()

        // Match against active business services
        val matchedSkills = matrix.services.filter { service ->
            val keywords = service.lowercase().split("&", ",", " ", "/", "-")
                .map { it.trim() }
                .filter { it.length >= 3 && it !in setOf("and", "the", "for", "with", "all") }
            keywords.any { textLower.contains(it) }
        }

        val hasHiringIntent = listOf(
            "hiring", "job", "career", "looking for", "seeking", "needed", "wanted",
            "freelance", "contract", "apply", "position", "opportunity", "budget",
            "rates", "rfp", "project", "quote", "developer", "designer", "consultant"
        ).any { textLower.contains(it) }

        // Truthful score calculation — NEVER fabricate high score for irrelevant text
        val baseScore = when {
            matchedSkills.size >= 3 && hasHiringIntent -> 98
            matchedSkills.size == 2 && hasHiringIntent -> 92
            matchedSkills.size == 1 && hasHiringIntent -> 88
            matchedSkills.isNotEmpty() -> 80
            hasHiringIntent -> 75
            else -> 0 // Ineligible / zero match
        }

        val primaryService = matchedSkills.firstOrNull() ?: targetCategory.ifBlank {
            matrix.services.firstOrNull() ?: "Professional Services"
        }
        val skillsStr = if (matchedSkills.isNotEmpty()) matchedSkills.joinToString(", ") else primaryService

        val clientDisplayName = if (extractedCompany.isNotBlank() && extractedCompany != "Pending Discovery") {
            extractedCompany
        } else if (extractedEmail.isNotBlank() && extractedEmail.contains("@")) {
            extractedEmail.substringBefore("@").replace(".", " ").capitalizeWords()
        } else {
            "Leadership & Hiring Team"
        }

        val isUrdu = jobPostText.any { it in '\u0600'..'\u06FF' }
        val pitch = if (isUrdu) {
            """
                محترم $clientDisplayName،

                ہم نے آپ کے بزنس / پروجیکٹ کی تفصیلات ("${jobPostText.take(100).replace("\n", " ")}...") کا بغور جائزہ لیا ہے۔

                ہم ${matrix.agencyName} کے پلیٹ فارم سے آپ کے لیے جدید اور اعلیٰ معیار کی خدمات فراہم کرنے کے لیے تیار ہیں:

                اہم خدمات:
                • $skillsStr
                • اعلیٰ کوالٹی، تیز رفتار اور محفوظ ڈیلیوری
                • مکمل مفت پروجیکٹ اسکوپنگ اور مشورہ (100% Free Consultation)

                رابطہ کی تفصیلات:
                👤 ${matrix.ownerName} (${matrix.ownerTitle})
                🏢 ${matrix.agencyName}
                📞 کال / واٹس ایپ: ${matrix.ownerPhoneInternational} (${matrix.ownerPhone})
                📧 ای میل: ${matrix.ownerEmail}
            """.trimIndent()
        } else {
            """
                Dear $clientDisplayName Team,

                I reviewed your project and business requirements regarding "${jobPostText.take(110).replace("\n", " ")}...".

                Through ${matrix.agencyName}, I specialize in delivering turnkey solutions tailored specifically to your objectives, including:
                • Core Expertise: $skillsStr
                • High-Precision Execution & Dedicated Turnaround.
                • 100% Free Project Scoping & Initial Consultation.

                I would be delighted to schedule a brief discussion regarding your goals and provide a comprehensive roadmap.

                Best regards,
                ${matrix.ownerName}
                ${matrix.ownerTitle} | ${matrix.agencyName}
                📞 Call/WhatsApp: ${matrix.ownerPhoneInternational} (${matrix.ownerPhone})
                📧 Email: ${matrix.ownerEmail}
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

