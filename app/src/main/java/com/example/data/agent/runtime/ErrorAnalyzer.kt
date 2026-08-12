package com.example.data.agent.runtime

data class ErrorDiagnostic(
    val category: ExecutionErrorType,
    val summary: String,
    val evidence: String,
    val probableCause: String,
    val suggestedCorrection: String,
    val timestamp: Long = System.currentTimeMillis()
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
}
