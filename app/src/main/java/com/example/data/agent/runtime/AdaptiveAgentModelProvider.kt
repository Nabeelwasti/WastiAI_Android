package com.example.data.agent.runtime

import com.example.data.ai.AIManager
import com.example.data.ai.model.ProviderCapability
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage 4: Adaptive Agent Model Provider.
 * Connects AIManager (Gemini, Groq, DeepSeek, etc.) for live LLM planning,
 * error diagnosis, and self-correction proposals.
 * Dynamically and seamlessly falls back to RuleBasedAgentModelProvider when offline,
 * when credentials are missing, or when an AI call fails.
 */
class AdaptiveAgentModelProvider(
    private val fallback: RuleBasedAgentModelProvider = RuleBasedAgentModelProvider()
) : AgentModelProvider {

    override suspend fun generatePlan(goal: String, availableCapabilities: List<String>): ModelPlanResponse {
        val availableProviders = AIManager.capabilityRegistry.getAvailableProviders()
        val onlineProvider = availableProviders.firstOrNull { it.id != "offline" && it.isAvailable() }
        if (onlineProvider == null) {
            return fallback.generatePlan(goal, availableCapabilities)
        }

        return try {
            val systemPrompt = """
                You are Wasti OS Agent Planner.
                Available tools/capabilities: ${availableCapabilities.joinToString(", ")}.
                Generate a machine-readable JSON plan for the user's goal.
                Your response must strictly be valid JSON with this exact structure:
                {
                  "rawReasoning": "brief explanation",
                  "steps": [
                    {
                      "stepId": 1,
                      "toolName": "read_file",
                      "arguments": {"path": "filename"},
                      "description": "purpose of step"
                    }
                  ]
                }
                Do not include markdown code block syntax around the JSON if possible, but if included, ensure valid JSON inside.
            """.trimIndent()

            val response = AIManager.execute(
                prompt = "Goal: $goal",
                systemInstruction = systemPrompt,
                preferredProviderId = onlineProvider.id,
                requiredCapabilities = setOf(ProviderCapability.TEXT_GENERATION)
            )

            if (response.isError || response.content.isBlank()) {
                return fallback.generatePlan(goal, availableCapabilities)
            }

            parsePlanResponse(response.content, goal) ?: fallback.generatePlan(goal, availableCapabilities)
        } catch (_: Throwable) {
            fallback.generatePlan(goal, availableCapabilities)
        }
    }

    override suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse {
        val availableProviders = AIManager.capabilityRegistry.getAvailableProviders()
        val onlineProvider = availableProviders.firstOrNull { it.id != "offline" && it.isAvailable() }
        if (onlineProvider == null) {
            return fallback.analyzeError(errorOutput, context)
        }

        return try {
            val systemPrompt = """
                You are Wasti OS Error Diagnostics Engine.
                Analyze the provided execution error and context.
                Respond with valid JSON:
                {
                  "category": "RUNTIME",
                  "summary": "one line summary",
                  "evidence": "quoted error evidence",
                  "probableCause": "why it failed",
                  "suggestedAction": "how to resolve"
                }
                Allowed category values: RUNTIME, COMPILATION, SYNTAX, TIMEOUT, SECURITY, PERMISSION, PROVIDER_UNAVAILABLE, UNKNOWN.
            """.trimIndent()

            val response = AIManager.execute(
                prompt = "Context: $context\n\nError:\n$errorOutput",
                systemInstruction = systemPrompt,
                preferredProviderId = onlineProvider.id,
                requiredCapabilities = setOf(ProviderCapability.TEXT_GENERATION)
            )

            if (response.isError || response.content.isBlank()) {
                return fallback.analyzeError(errorOutput, context)
            }

            parseDiagnosticResponse(response.content, errorOutput) ?: fallback.analyzeError(errorOutput, context)
        } catch (_: Throwable) {
            fallback.analyzeError(errorOutput, context)
        }
    }

    override suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse {
        val availableProviders = AIManager.capabilityRegistry.getAvailableProviders()
        val onlineProvider = availableProviders.firstOrNull { it.id != "offline" && it.isAvailable() }
        if (onlineProvider == null) {
            return fallback.proposeCorrection(diagnostic, context)
        }

        return try {
            val systemPrompt = """
                You are Wasti OS Self-Correction Engine.
                Given the diagnostic: category=${diagnostic.category}, summary=${diagnostic.summary}, evidence=${diagnostic.evidence}.
                Context: $context.
                Propose a corrective PlannedStep or alternative strategy in JSON:
                {
                  "explanation": "why this correction works",
                  "alternativeStrategy": "optional alternative or null",
                  "proposedAction": {
                    "stepId": 1,
                    "toolName": "tool_name",
                    "arguments": {},
                    "description": "description"
                  }
                }
            """.trimIndent()

            val response = AIManager.execute(
                prompt = "Propose correction for error: ${diagnostic.summary}",
                systemInstruction = systemPrompt,
                preferredProviderId = onlineProvider.id,
                requiredCapabilities = setOf(ProviderCapability.TEXT_GENERATION)
            )

            if (response.isError || response.content.isBlank()) {
                return fallback.proposeCorrection(diagnostic, context)
            }

            parseCorrectionResponse(response.content) ?: fallback.proposeCorrection(diagnostic, context)
        } catch (_: Throwable) {
            fallback.proposeCorrection(diagnostic, context)
        }
    }

    private fun parsePlanResponse(jsonStr: String, goal: String): ModelPlanResponse? {
        return try {
            val clean = cleanJson(jsonStr)
            val obj = JSONObject(clean)
            val reasoning = obj.optString("rawReasoning", "AI synthesized plan for: $goal")
            val stepsArray = obj.optJSONArray("steps") ?: JSONArray()
            val stepsList = mutableListOf<PlannedStep>()
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val stepId = stepObj.optInt("stepId", i + 1)
                val toolName = stepObj.optString("toolName", "")
                val desc = stepObj.optString("description", "")
                val argsObj = stepObj.optJSONObject("arguments")
                val argsMap = mutableMapOf<String, Any?>()
                argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.get(k) }
                if (toolName.isNotBlank()) {
                    stepsList.add(PlannedStep(stepId, toolName, argsMap, desc))
                }
            }
            if (stepsList.isNotEmpty()) {
                ModelPlanResponse(rawReasoning = reasoning, steps = stepsList, isValid = true)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDiagnosticResponse(jsonStr: String, errorOutput: String): ModelDiagnosticResponse? {
        return try {
            val clean = cleanJson(jsonStr)
            val obj = JSONObject(clean)
            val catStr = obj.optString("category", "RUNTIME").uppercase()
            val cat = try { ExecutionErrorType.valueOf(catStr) } catch (_: Exception) { ExecutionErrorType.RUNTIME }
            ModelDiagnosticResponse(
                category = cat,
                summary = obj.optString("summary", "Diagnosed: ${errorOutput.take(100)}"),
                evidence = obj.optString("evidence", errorOutput.take(300)),
                probableCause = obj.optString("probableCause", "Error detected by AI diagnostic model"),
                suggestedAction = obj.optString("suggestedAction", "Inspect arguments and retry")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCorrectionResponse(jsonStr: String): ModelCorrectionResponse? {
        return try {
            val clean = cleanJson(jsonStr)
            val obj = JSONObject(clean)
            val explanation = obj.optString("explanation", "AI proposed correction")
            val alt = if (obj.has("alternativeStrategy") && !obj.isNull("alternativeStrategy")) obj.getString("alternativeStrategy") else null
            val actionObj = obj.optJSONObject("proposedAction")
            val step = if (actionObj != null) {
                val stepId = actionObj.optInt("stepId", 1)
                val toolName = actionObj.optString("toolName", "")
                val desc = actionObj.optString("description", "")
                val argsObj = actionObj.optJSONObject("arguments")
                val argsMap = mutableMapOf<String, Any?>()
                argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.get(k) }
                PlannedStep(stepId, toolName, argsMap, desc)
            } else null
            ModelCorrectionResponse(explanation = explanation, proposedAction = step, alternativeStrategy = alt)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```json")) {
            s = s.removePrefix("```json").trim()
        } else if (s.startsWith("```")) {
            s = s.removePrefix("```").trim()
        }
        if (s.endsWith("```")) {
            s = s.removeSuffix("```").trim()
        }
        val firstBrace = s.indexOf('{')
        val lastBrace = s.lastIndexOf('}')
        return if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            s.substring(firstBrace, lastBrace + 1)
        } else s
    }
}
