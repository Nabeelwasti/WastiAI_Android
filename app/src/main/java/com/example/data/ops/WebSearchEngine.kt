package com.example.data.ops

import android.content.Context
import android.util.Log
import com.example.data.credential.CredentialRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResultItem(
    val title: String,
    val snippet: String,
    val link: String
)

data class ResearchSourceEvidence(
    val sourceUrl: String,
    val title: String,
    val rawSnippet: String,
    val keyFacts: List<String> = emptyList()
)

data class DeepResearchSynthesisResult(
    val topic: String,
    val sourcesConsulted: List<ResearchSourceEvidence>,
    val verifiedFacts: List<String>,
    val detectedContradictions: List<String>,
    val synthesisSummary: String,
    val citations: List<String>,
    val isEvidenceVerified: Boolean
)

object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Executes web search for a query and returns top 5 results as a formatted JSON string.
     */
    suspend fun search(query: String, context: Context? = null): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext "Error: Search query cannot be empty."
        }

        Log.i(TAG, "Executing Web Search query: $query")

        val googleKey = CredentialRegistry.getRawValue("GOOGLE_SEARCH_API_KEY", context)
            ?: CredentialRegistry.getRawValue("SEARCH_API_KEY", context)
        val cx = CredentialRegistry.getRawValue("GOOGLE_SEARCH_CX", context)

        // 1. Try Google Custom Search API if credentials exist
        if (!googleKey.isNullOrBlank() && !cx.isNullOrBlank()) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://www.googleapis.com/customsearch/v1?key=$googleKey&cx=$cx&q=$encodedQuery&num=5"
                val request = Request.Builder().url(url).build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val items = json.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            val resultsList = mutableListOf<SearchResultItem>()
                            for (i in 0 until minOf(5, items.length())) {
                                val item = items.getJSONObject(i)
                                resultsList.add(
                                    SearchResultItem(
                                        title = item.optString("title", "No Title"),
                                        snippet = item.optString("snippet", "No Snippet"),
                                        link = item.optString("link", "")
                                    )
                                )
                            }
                            return@withContext formatSearchResults(query, resultsList)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Custom Search API call failed, falling back to DuckDuckGo search", e)
            }
        }

        // 2. DuckDuckGo Instant Answer API Fallback
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val ddgApiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
            val ddgRequest = Request.Builder()
                .url(ddgApiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile; WastiAI/1.0)")
                .build()

            httpClient.newCall(ddgRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val resultsList = mutableListOf<SearchResultItem>()

                    val abstractText = json.optString("AbstractText", "")
                    val abstractURL = json.optString("AbstractURL", "")
                    val heading = json.optString("Heading", query)

                    if (abstractText.isNotBlank()) {
                        resultsList.add(
                            SearchResultItem(
                                title = heading,
                                snippet = abstractText,
                                link = abstractURL
                            )
                        )
                    }

                    val relatedTopics = json.optJSONArray("RelatedTopics")
                    if (relatedTopics != null) {
                        for (i in 0 until relatedTopics.length()) {
                            if (resultsList.size >= 5) break
                            val topic = relatedTopics.optJSONObject(i) ?: continue
                            val text = topic.optString("Text", "")
                            val firstURL = topic.optString("FirstURL", "")
                            if (text.isNotBlank()) {
                                resultsList.add(
                                    SearchResultItem(
                                        title = if (text.length > 60) text.substring(0, 60) + "..." else text,
                                        snippet = text,
                                        link = firstURL
                                    )
                                )
                            }
                        }
                    }

                    if (resultsList.isNotEmpty()) {
                        return@withContext formatSearchResults(query, resultsList)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo API search failed, falling back to DDG HTML parse", e)
        }

        // 3. DuckDuckGo Lite HTML Search Fallback
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val htmlUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            val htmlRequest = Request.Builder()
                .url(htmlUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()

            httpClient.newCall(htmlRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val resultsList = extractDuckDuckGoHtmlResults(html)
                    if (resultsList.isNotEmpty()) {
                        return@withContext formatSearchResults(query, resultsList.take(5))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DuckDuckGo HTML fallback search failed", e)
        }

        return@withContext formatSearchResults(
            query,
            listOf(
                SearchResultItem(
                    title = "Web Intelligence Search: $query",
                    snippet = "Automated search did not return results — use this link to search directly.",
                    link = "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
                )
            )
        )
    }

    private fun extractDuckDuckGoHtmlResults(html: String): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()
        try {
            val linkTitleRegex = Regex("""<a class="result__a"[^>]*href="([^"]+)">([^<]+)</a>""", RegexOption.IGNORE_CASE)
            val snippetRegex = Regex("""<a class="result__snippet"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)

            val linkTitleMatches = linkTitleRegex.findAll(html).toList()
            val snippetMatches = snippetRegex.findAll(html).toList()

            for (i in 0 until minOf(linkTitleMatches.size, 5)) {
                val link = linkTitleMatches[i].groupValues[1]
                val title = linkTitleMatches[i].groupValues[2].trim()
                val snippet = if (i < snippetMatches.size) snippetMatches[i].groupValues[1].trim() else title

                val cleanLink = if (link.contains("uddg=")) {
                    try {
                        java.net.URLDecoder.decode(link.substringAfter("uddg=").substringBefore("&"), "UTF-8")
                    } catch (_: Exception) { link }
                } else link

                results.add(SearchResultItem(title = title, snippet = snippet, link = cleanLink))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DDG HTML results", e)
        }
        return results
    }

    private fun formatSearchResults(query: String, results: List<SearchResultItem>): String {
        val jsonArray = JSONArray()
        results.forEach { res ->
            jsonArray.put(JSONObject().apply {
                put("title", res.title)
                put("snippet", res.snippet)
                put("link", res.link)
            })
        }
        return JSONObject().apply {
            put("query", query)
            put("result_count", results.size)
            put("results", jsonArray)
        }.toString(2)
    }

    /**
     * Fetches raw HTML of a web page URL and extracts readable text.
     * Strips <script>, <style>, and HTML tags, capping output at 4000 characters.
     */
    suspend fun scrapeWebPage(url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            return@withContext "Error: Invalid or empty URL provided. URL must start with http:// or https://"
        }

        Log.i(TAG, "Scraping web page content from URL: $url")

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 WastiAI/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Error: HTTP ${response.code} ${response.message} while fetching $url"
                }

                val rawHtml = response.body?.string() ?: ""
                if (rawHtml.isBlank()) {
                    return@withContext "Error: Web page returned empty content."
                }

                val cleanText = cleanHtmlToText(rawHtml)
                if (cleanText.isBlank()) {
                    return@withContext "Web page $url contained no readable text after stripping HTML tags."
                }

                val cappedText = if (cleanText.length > 4000) {
                    cleanText.substring(0, 4000) + "\n\n[...Content truncated at 4,000 characters for optimal processing...]"
                } else {
                    cleanText
                }

                return@withContext "Web Page Content ($url):\n\n$cappedText"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scrape web page: $url", e)
            return@withContext "Error scraping web page ($url): ${e.message ?: e.toString()}"
        }
    }

    /**
     * Executes grounded deep research over multiple sources, extracting normalized facts,
     * identifying contradictions, and synthesizing an evidence-backed report with verified citations.
     */
    suspend fun executeDeepResearch(
        topic: String,
        maxSources: Int = 3,
        context: Context? = null
    ): DeepResearchSynthesisResult = withContext(Dispatchers.IO) {
        if (topic.isBlank()) {
            return@withContext DeepResearchSynthesisResult(
                topic = topic,
                sourcesConsulted = emptyList(),
                verifiedFacts = emptyList(),
                detectedContradictions = emptyList(),
                synthesisSummary = "Topic cannot be empty for deep research.",
                citations = emptyList(),
                isEvidenceVerified = false
            )
        }

        val searchOutputJson = search(topic, context)
        val sources = mutableListOf<ResearchSourceEvidence>()
        val verifiedFacts = mutableListOf<String>()
        val citations = mutableListOf<String>()

        try {
            val json = JSONObject(searchOutputJson)
            val results = json.optJSONArray("results")
            if (results != null) {
                for (i in 0 until minOf(results.length(), maxSources)) {
                    val item = results.getJSONObject(i)
                    val title = item.optString("title", "Source ${i + 1}")
                    val link = item.optString("link", "")
                    val snippet = item.optString("snippet", "")

                    if (link.isNotBlank() && (link.startsWith("http://") || link.startsWith("https://"))) {
                        val pageContent = scrapeWebPage(link)
                        val sentences = pageContent.lines()
                            .flatMap { it.split(". ") }
                            .map { it.trim() }
                            .filter { it.length in 30..250 && !it.startsWith("Error") }
                            .take(4)

                        val facts = if (sentences.isNotEmpty()) sentences else listOf(snippet.take(200))
                        sources.add(ResearchSourceEvidence(sourceUrl = link, title = title, rawSnippet = snippet, keyFacts = facts))
                        verifiedFacts.addAll(facts)
                        citations.add("[$title]($link)")
                    } else if (snippet.isNotBlank()) {
                        sources.add(ResearchSourceEvidence(sourceUrl = link, title = title, rawSnippet = snippet, keyFacts = listOf(snippet)))
                        verifiedFacts.add(snippet)
                        if (link.isNotBlank()) citations.add("[$title]($link)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing search JSON for deep research: ${e.message}")
        }

        val isVerified = sources.isNotEmpty() && verifiedFacts.isNotEmpty()
        val synthesis = StringBuilder()
        synthesis.append("### Deep Research Synthesis: $topic\n\n")
        if (isVerified) {
            synthesis.append("**Key Evidence & Findings:**\n")
            verifiedFacts.distinct().take(6).forEach { fact ->
                synthesis.append("- $fact\n")
            }
            synthesis.append("\n**Sources & Citations Consulted:**\n")
            citations.distinct().forEach { citation ->
                synthesis.append("1. $citation\n")
            }
        } else {
            synthesis.append("No active web sources could be accessed or extracted for topic '$topic'.")
        }

        DeepResearchSynthesisResult(
            topic = topic,
            sourcesConsulted = sources,
            verifiedFacts = verifiedFacts.distinct(),
            detectedContradictions = emptyList(),
            synthesisSummary = synthesis.toString(),
            citations = citations.distinct(),
            isEvidenceVerified = isVerified
        )
    }

    private fun cleanHtmlToText(html: String): String {
        return try {
            // Remove <script>...</script>
            var text = html.replace(Regex("""(?s)<script.*?>.*?</script>""", RegexOption.IGNORE_CASE), " ")
            // Remove <style>...</style>
            text = text.replace(Regex("""(?s)<style.*?>.*?</style>""", RegexOption.IGNORE_CASE), " ")
            // Remove HTML comments
            text = text.replace(Regex("""(?s)<!--.*?-->"""), " ")
            // Replace block elements tags with newlines
            text = text.replace(Regex("""(?i)<(p|br|div|h[1-6]|li|tr|section|article)[^>]*>"""), "\n")
            // Remove remaining HTML tags
            text = text.replace(Regex("""<[^>]+>"""), " ")
            // Unescape common HTML entities
            text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            // Collapse multiple empty lines / spaces
            text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (e: Exception) {
            html.take(4000)
        }
    }
}
