package com.example.service

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ai.AIManager
import com.example.data.device.WastiDeviceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SuggestionState(
    val isLoading: Boolean = false,
    val screenContextSnippet: String = "",
    val suggestions: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Task 41D: Floating AI Suggestion Overlay Engine
 * Scrapes the active screen content via WastiAccessibilityService / WastiDeviceController,
 * passes current screen context to Gemini, and generates 3 quick, actionable suggestions.
 */
object WastiSuggestionOverlay {

    private const val TAG = "WastiSuggestionOverlay"

    private val _suggestionState = MutableStateFlow(SuggestionState())
    val suggestionState: StateFlow<SuggestionState> = _suggestionState.asStateFlow()

    suspend fun analyzeScreenAndGenerateSuggestions(context: Context): List<String> = withContext(Dispatchers.IO) {
        _suggestionState.value = SuggestionState(isLoading = true)

        val rawScreenJson = try {
            val scraped = WastiAccessibilityService.instance?.scrapeActiveScreen()
            if (!scraped.isNullOrBlank() && scraped != "[]") {
                scraped
            } else {
                WastiDeviceController.readScreenContent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scraping active screen for suggestion overlay", e)
            WastiDeviceController.readScreenContent()
        }

        val snippet = if (rawScreenJson.length > 1500) rawScreenJson.take(1500) + "..." else rawScreenJson

        val prompt = """
            You are Wasti OS Contextual Intelligence Assistant.
            Analyze the following text scraped from the user's active Android screen:

            SCREEN TEXT DUMP:
            $snippet

            Task: Generate exactly 3 concise, highly actionable business or productivity suggestions based on what is currently visible on screen.

            Examples:
            1. Draft a quick reply proposal to this lead message.
            2. Extract key metrics and create a CRM task entry.
            3. Search B2B X-Ray leads matching this job title.

            Rules:
            - Return ONLY 3 bullet points starting with '• '.
            - Keep each suggestion under 15 words.
            - Focus on real actions Wasti OS can perform for the user.
        """.trimIndent()

        try {
            val aiResponse = AIManager.execute(
                prompt = prompt,
                systemInstruction = "You generate 3 short actionable suggestions based on screen context. Output ONLY 3 bullet points starting with '• '."
            )

            if (!aiResponse.isError && aiResponse.content.isNotBlank()) {
                val lines = aiResponse.content.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("•") || it.startsWith("-") || it.startsWith("1.") || it.startsWith("2.") || it.startsWith("3.") }
                    .map { it.removePrefix("•").removePrefix("-").removePrefix("1.").removePrefix("2.").removePrefix("3.").trim() }
                    .take(3)

                val suggestionsList = if (lines.isNotEmpty()) lines else listOf(
                    "Draft a personalized outreach response to screen text",
                    "Export visible lead details to CRM & Lead Radar",
                    "Execute web search on key topics detected on screen"
                )

                _suggestionState.value = SuggestionState(
                    isLoading = false,
                    screenContextSnippet = snippet,
                    suggestions = suggestionsList
                )
                suggestionsList
            } else {
                val fallback = listOf(
                    "Draft a personalized outreach response to screen text",
                    "Export visible lead details to CRM & Lead Radar",
                    "Execute web search on key topics detected on screen"
                )
                _suggestionState.value = SuggestionState(
                    isLoading = false,
                    screenContextSnippet = snippet,
                    suggestions = fallback,
                    errorMessage = aiResponse.errorMessage
                )
                fallback
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception generating AI screen suggestions", e)
            val fallback = listOf(
                "Draft a personalized outreach response to screen text",
                "Export visible lead details to CRM & Lead Radar",
                "Execute web search on key topics detected on screen"
            )
            _suggestionState.value = SuggestionState(
                isLoading = false,
                screenContextSnippet = snippet,
                suggestions = fallback,
                errorMessage = e.message
            )
            fallback
        }
    }
}

/**
 * Semi-transparent Floating AI Suggestion Overlay UI component.
 */
@Composable
fun FloatingSuggestionOverlayBar(
    onExecuteSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val suggestionState by WastiSuggestionOverlay.suggestionState.collectAsStateWithLifecycle()

    var isExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (!isExpanded) {
            FloatingActionButton(
                onClick = {
                    isExpanded = true
                    coroutineScope.launch {
                        WastiSuggestionOverlay.analyzeScreenAndGenerateSuggestions(context)
                    }
                },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = CircleShape,
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "AI Screen Suggestions")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("AI Suggestions", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "✨ Screen Context Suggestions",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        WastiSuggestionOverlay.analyzeScreenAndGenerateSuggestions(context)
                                    }
                                },
                                enabled = !suggestionState.isLoading
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh Screen Suggestions", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { isExpanded = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Suggestions Overlay")
                            }
                        }
                    }

                    if (suggestionState.isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Scrape & analyzing active screen...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val suggestions = suggestionState.suggestions
                        if (suggestions.isEmpty()) {
                            Text(
                                text = "Tap refresh to scan active screen.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            suggestions.forEachIndexed { idx, suggestion ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onExecuteSuggestion(suggestion)
                                            isExpanded = false
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    tonalElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = "${idx + 1}.",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = suggestion,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Execute Suggestion",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
