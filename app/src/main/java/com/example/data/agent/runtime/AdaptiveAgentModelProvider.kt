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
            val plan = fallback.generatePlan(goal, availableCapabilities)
            return plan.copy(
                isFallback = true,
                fallbackReason = "No online AI providers available; downgraded to rule-based planner",
                isNeuralModel = false,
                providerSource = "RULE_BASED_FALLBACK"
            )
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
                val plan = fallback.generatePlan(goal, availableCapabilities)
                return plan.copy(
                    isFallback = true,
                    fallbackReason = "Online AI provider '${onlineProvider.name}' failed (${response.errorMessage ?: "Empty content"}); downgraded to rule-based planner",
                    isNeuralModel = false,
                    providerSource = "RULE_BASED_FALLBACK"
                )
            }

            parsePlanResponse(response.content, goal, onlineProvider.name) ?: run {
                val plan = fallback.generatePlan(goal, availableCapabilities)
                plan.copy(
                    isFallback = true,
                    fallbackReason = "Failed to parse JSON plan from '${onlineProvider.name}'; downgraded to rule-based planner",
                    isNeuralModel = false,
                    providerSource = "RULE_BASED_FALLBACK"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val plan = fallback.generatePlan(goal, availableCapabilities)
            plan.copy(
                isFallback = true,
                fallbackReason = "Exception from online planner: ${e.message}; downgraded to rule-based planner",
                isNeuralModel = false,
                providerSource = "RULE_BASED_FALLBACK"
            )
        }
    }

    override suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse {
        val availableProviders = AIManager.capabilityRegistry.getAvailableProviders()
        val onlineProvider = availableProviders.firstOrNull { it.id != "offline" && it.isAvailable() }
        if (onlineProvider == null) {
            val diag = fallback.analyzeError(errorOutput, context)
            return diag.copy(isFallback = true, fallbackReason = "No online AI providers available; downgraded to rule-based diagnostics")
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
                val diag = fallback.analyzeError(errorOutput, context)
                return diag.copy(
                    isFallback = true,
                    fallbackReason = "Online AI provider '${onlineProvider.name}' failed (${response.errorMessage ?: "Empty content"}); downgraded to rule-based diagnostics"
                )
            }

            parseDiagnosticResponse(response.content, errorOutput) ?: run {
                val diag = fallback.analyzeError(errorOutput, context)
                diag.copy(
                    isFallback = true,
                    fallbackReason = "Failed to parse JSON diagnostics from '${onlineProvider.name}'; downgraded to rule-based diagnostics"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val diag = fallback.analyzeError(errorOutput, context)
            diag.copy(
                isFallback = true,
                fallbackReason = "Exception from online diagnostics: ${e.message}; downgraded to rule-based diagnostics"
            )
        }
    }

    override suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse {
        val availableProviders = AIManager.capabilityRegistry.getAvailableProviders()
        val onlineProvider = availableProviders.firstOrNull { it.id != "offline" && it.isAvailable() }
        if (onlineProvider == null) {
            val corr = fallback.proposeCorrection(diagnostic, context)
            return corr.copy(isFallback = true, fallbackReason = "No online AI providers available; downgraded to rule-based correction")
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
                val corr = fallback.proposeCorrection(diagnostic, context)
                return corr.copy(
                    isFallback = true,
                    fallbackReason = "Online AI provider '${onlineProvider.name}' failed (${response.errorMessage ?: "Empty content"}); downgraded to rule-based correction"
                )
            }

            parseCorrectionResponse(response.content) ?: run {
                val corr = fallback.proposeCorrection(diagnostic, context)
                corr.copy(
                    isFallback = true,
                    fallbackReason = "Failed to parse JSON correction from '${onlineProvider.name}'; downgraded to rule-based correction"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val corr = fallback.proposeCorrection(diagnostic, context)
            corr.copy(
                isFallback = true,
                fallbackReason = "Exception from online correction: ${e.message}; downgraded to rule-based correction"
            )
        }
    }

    internal fun parsePlanResponse(jsonStr: String, goal: String, providerSource: String = "AI_ONLINE"): ModelPlanResponse? {
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
                ModelPlanResponse(
                    rawReasoning = reasoning,
                    steps = stepsList,
                    isValid = true,
                    isNeuralModel = true,
                    providerSource = providerSource
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseDiagnosticResponse(jsonStr: String, errorOutput: String): ModelDiagnosticResponse? {
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
