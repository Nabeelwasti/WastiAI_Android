package com.example.data.agent.runtime

data class ErrorDiagnostic(
    val category: ExecutionErrorType,
    val summary: String,
    val evidence: String,
    val probableCause: String,
    val suggestedCorrection: String,
    val timestamp: Long = System.currentTimeMillis(),
    val onlineResearchFindings: String? = null,
    val alternativePaths: List<String> = emptyList()
)

/**
 * Task 8: Error Analyzer.
 * Classifies execution and tool failures into structured ErrorDiagnostics.
 * Does NOT directly execute fixes.
 */
class ErrorAnalyzer(
    private val modelProvider: AgentModelProvider? = null
) {

    suspend fun analyzeFailure(observation: AgentObservation): ErrorDiagnostic {
        val combinedText = "${observation.stdout}\n${observation.stderr}"

        // 1. Rule-based fast classification
        val category = when {
            observation.outputMap["errorType"] == "SECURITY" || observation.stderr.contains("SECURITY_BLOCKED") || combinedText.contains("SecurityBlocked") ->
                ExecutionErrorType.SECURITY

            observation.outputMap["errorType"] == "PERMISSION" || observation.stderr.contains("PERMISSION_DENIED") ->
                ExecutionErrorType.PERMISSION

            observation.outputMap["errorType"] == "TIMEOUT" || observation.stderr.contains("TIMEOUT") ->
                ExecutionErrorType.TIMEOUT

            observation.outputMap["errorType"] == "PROVIDER_UNAVAILABLE" || observation.stderr.contains("PROVIDER_UNAVAILABLE") ->
                ExecutionErrorType.PROVIDER_UNAVAILABLE

            combinedText.contains("COMPILATION_FAILED") || combinedText.contains("e: file://") || combinedText.contains("Unresolved reference") ->
                ExecutionErrorType.COMPILATION

            combinedText.contains("SyntaxError") || combinedText.contains("unexpected token") ->
                ExecutionErrorType.SYNTAX

            combinedText.contains("TEST FAILED") || combinedText.contains("AssertionError") ->
                ExecutionErrorType.RUNTIME

            else -> ExecutionErrorType.UNKNOWN
        }

        val summary = when (category) {
            ExecutionErrorType.SECURITY -> "Security policy blocked tool execution: ${observation.stderr.take(150)}"
            ExecutionErrorType.PERMISSION -> "Permission model rejected tool execution"
            ExecutionErrorType.TIMEOUT -> "Execution exceeded timeout limit"
            ExecutionErrorType.COMPILATION -> "Code compilation failed with errors"
            ExecutionErrorType.SYNTAX -> "Syntax error detected in source code"
            ExecutionErrorType.RUNTIME -> "Runtime error or test assertion failure"
            ExecutionErrorType.PROVIDER_UNAVAILABLE -> "No active execution provider available for request"
            else -> "Execution failed with message: ${observation.stderr.ifEmpty { observation.stdout }.take(150)}"
        }

        // 2. Query model provider for enhanced diagnosis if available
        if (modelProvider != null && combinedText.isNotBlank()) {
            try {
                val modelDiag = modelProvider.analyzeError(combinedText, "Tool: ${observation.toolName}")
                return ErrorDiagnostic(
                    category = modelDiag.category,
                    summary = modelDiag.summary,
                    evidence = modelDiag.evidence,
                    probableCause = modelDiag.probableCause,
                    suggestedCorrection = modelDiag.suggestedAction
                )
            } catch (_: Exception) {
                // Fall back to rule-based diagnostic
            }
        }

        return ErrorDiagnostic(
            category = category,
            summary = summary,
            evidence = combinedText.take(500),
            probableCause = "Tool execution returned non-zero exit code or error output",
            suggestedCorrection = getSuggestedFix(category, observation)
        )
    }

    private fun getSuggestedFix(category: ExecutionErrorType, obs: AgentObservation): String {
        return when (category) {
            ExecutionErrorType.SECURITY -> "Verify target path is within workspace boundaries and execution mode is authorized"
            ExecutionErrorType.PERMISSION -> "Request appropriate permission level or biometric prompt"
            ExecutionErrorType.TIMEOUT -> "Increase execution timeout or optimize loop logic"
            ExecutionErrorType.COMPILATION -> "Fix syntax/import errors in source file before re-compiling"
            ExecutionErrorType.SYNTAX -> "Correct invalid language syntax in script file"
            ExecutionErrorType.PROVIDER_UNAVAILABLE -> "Register required execution provider or check network capabilities"
            else -> "Inspect stderr logs and revise tool arguments"
        }
    }

    suspend fun analyzeFailureWithLiveResearch(observation: AgentObservation): ErrorDiagnostic {
        val base = analyzeFailure(observation)
        val errorSnippet = observation.stderr.ifBlank { observation.stdout }.take(100).replace("\n", " ").trim()
        val query = "fix ${base.category} error $errorSnippet".trim()

        val searchOutcome = runCatching {
            SovereignAlternativeRegistry.executeSovereignWebSearch(query)
        }.getOrNull()

        val researchFindings = searchOutcome?.results
            ?.filter { it.snippet.isNotBlank() }
            ?.take(2)
            ?.joinToString(" | ") { "${it.title}: ${it.snippet}" }

        val alternatives = mutableListOf<String>()
        when (base.category) {
            ExecutionErrorType.COMPILATION, ExecutionErrorType.SYNTAX -> {
                alternatives.add("Validate syntax in isolated WRE scratchpad")
                alternatives.add("Consult language compiler documentation via live research")
                alternatives.add("Apply iterative AST auto-patching")
            }
            ExecutionErrorType.TIMEOUT -> {
                alternatives.add("Split execution payload into smaller batches")
                alternatives.add("Increase per-command execution timeout in ExecutionRequest")
                alternatives.add("Offload heavy batch processing to background worker job")
            }
            ExecutionErrorType.SECURITY, ExecutionErrorType.PERMISSION -> {
                alternatives.add("Verify workspace sandbox boundaries")
                alternatives.add("Request explicit user elevation or biometric approval")
            }
            ExecutionErrorType.PROVIDER_UNAVAILABLE -> {
                alternatives.add("Fallback to NativeCommandProvider or Polyglot Terminal Engine")
                alternatives.add("Check local Ollama / llama.cpp server status")
            }
            else -> {
                alternatives.add("Execute detailed error probe via polyglot terminal")
                alternatives.add("Use online research recommendations to formulate fix")
            }
        }

        val enrichedCorrection = if (!researchFindings.isNullOrBlank()) {
            "${base.suggestedCorrection} [Web Research: $researchFindings]"
        } else {
            base.suggestedCorrection
        }

        return base.copy(
            suggestedCorrection = enrichedCorrection,
            onlineResearchFindings = researchFindings,
            alternativePaths = alternatives
        )
    }
}
