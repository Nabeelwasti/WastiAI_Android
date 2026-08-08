package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GeminiInlineData(
    @field:Json(name = "mimeType") val mimeType: String = "image/jpeg",
    @field:Json(name = "data") val data: String
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionCall(
    @field:Json(name = "name") val name: String,
    @field:Json(name = "args") val args: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionResponse(
    @field:Json(name = "name") val name: String,
    @field:Json(name = "response") val response: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @field:Json(name = "text") val text: String? = null,
    @field:Json(name = "inlineData") val inlineData: GeminiInlineData? = null,
    @field:Json(name = "functionCall") val functionCall: GeminiFunctionCall? = null,
    @field:Json(name = "functionResponse") val functionResponse: GeminiFunctionResponse? = null
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
data class GeminiProperty(
    @field:Json(name = "type") val type: String = "STRING",
    @field:Json(name = "description") val description: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionParameters(
    @field:Json(name = "type") val type: String = "OBJECT",
    @field:Json(name = "properties") val properties: Map<String, GeminiProperty>? = null,
    @field:Json(name = "required") val required: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiFunctionDeclaration(
    @field:Json(name = "name") val name: String,
    @field:Json(name = "description") val description: String,
    @field:Json(name = "parameters") val parameters: GeminiFunctionParameters? = null
)

@JsonClass(generateAdapter = true)
data class GeminiTool(
    @field:Json(name = "googleSearch") val googleSearch: Map<String, String>? = null,
    @field:Json(name = "functionDeclarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
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
