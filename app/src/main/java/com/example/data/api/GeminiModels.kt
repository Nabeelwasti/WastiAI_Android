package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @field:Json(name = "mimeType") val mimeType: String = "image/jpeg",
    @field:Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @field:Json(name = "text") val text: String? = null,
    @field:Json(name = "inlineData") val inlineData: GeminiInlineData? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @field:Json(name = "role") val role: String? = null,
    @field:Json(name = "parts") val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @field:Json(name = "temperature") val temperature: Float? = 0.7f,
    @field:Json(name = "topP") val topP: Float? = 0.95f,
    @field:Json(name = "topK") val topK: Int? = 40,
    @field:Json(name = "maxOutputTokens") val maxOutputTokens: Int? = 4096
)

@JsonClass(generateAdapter = true)
data class GeminiTool(
    @field:Json(name = "googleSearch") val googleSearch: Map<String, String>? = emptyMap()
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @field:Json(name = "contents") val contents: List<GeminiContent>,
    @field:Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null,
    @field:Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    @field:Json(name = "tools") val tools: List<GeminiTool>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @field:Json(name = "content") val content: GeminiContent? = null,
    @field:Json(name = "finishReason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @field:Json(name = "candidates") val candidates: List<GeminiCandidate>? = null
)
