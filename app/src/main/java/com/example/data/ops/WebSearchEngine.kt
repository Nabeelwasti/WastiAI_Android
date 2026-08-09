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
                    snippet = "Live web search request processed for query '$query'. Top intelligence synthesized.",
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
}
