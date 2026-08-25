package com.example.data.agent.runtime

data class CorrectionProposal(
    val explanation: String,
    val toolName: String,
    val toolArguments: Map<String, Any?>,
    val alternativeStrategy: String? = null
)

/**
 * Task 9: Self-Correction Engine.
 * Proposes targeted tool calls or script modifications to resolve detected error diagnostics.
 * ABSOLUTELY MUST NOT bypass WastiAgentToolRouter.
 */
class SelfCorrectionEngine(
    private val modelProvider: AgentModelProvider,
    private val toolRouter: WastiAgentToolRouter,
    private val workspaceManager: WorkspaceManager
) {

    suspend fun proposeCorrection(
        task: AgentTask,
        diagnostic: ErrorDiagnostic,
        failedObservation: AgentObservation
    ): CorrectionProposal {
        // 1. Ask ModelProvider for correction strategy
        val modelCorrection = modelProvider.proposeCorrection(diagnostic, "Failed tool: ${failedObservation.toolName}")

        val proposedStep = modelCorrection.proposedAction
        if (proposedStep != null) {
            return CorrectionProposal(
                explanation = modelCorrection.explanation,
                toolName = proposedStep.toolName,
                toolArguments = proposedStep.arguments,
                alternativeStrategy = modelCorrection.alternativeStrategy
            )
        }

        // 2. Rule-based fallback proposals using StructuredDiagnostic analysis
        val fallbackProposal = when (diagnostic.category) {
            ExecutionErrorType.COMPILATION, ExecutionErrorType.SYNTAX -> {
                val rawLogs = buildString {
                    appendLine(failedObservation.stdout)
                    appendLine(failedObservation.stderr)
                }
                val structuredReport = DiagnosticParser.parseLogs(
                    projectId = task.taskId.value,
                    language = "KOTLIN",
                    rawLogs = rawLogs
                )
                val primaryDiag = structuredReport.diagnostics.firstOrNull()
                val targetFile = primaryDiag?.file ?: failedObservation.outputMap["filePath"]?.toString() ?: "main.kt"
                val fixDescription = primaryDiag?.suggestedFix ?: structuredReport.actionableSuggestions.firstOrNull()
                    ?: "Inspect and correct syntax error in $targetFile at line ${primaryDiag?.line ?: 1}"

                CorrectionProposal(
                    explanation = fixDescription,
                    toolName = "read_file",
                    toolArguments = mapOf("path" to targetFile),
                    alternativeStrategy = primaryDiag?.probableCause
                )
            }
            ExecutionErrorType.TIMEOUT -> {
                val origArgs = failedObservation.outputMap.toMutableMap()
                origArgs["timeoutMs"] = 60000L
                CorrectionProposal(
                    explanation = "Retry execution with increased timeout limit",
                    toolName = failedObservation.toolName,
                    toolArguments = origArgs
                )
            }
            else -> {
                CorrectionProposal(
                    explanation = "Fallback to safe file workspace inspection",
                    toolName = "list_files",
                    toolArguments = mapOf("path" to ".")
                )
            }
        }

        return fallbackProposal
    }

    /**
     * Executes the proposed correction strictly through WastiAgentToolRouter.
     * NEVER executes tool logic directly.
     */
    suspend fun applyCorrectionThroughRouter(
        task: AgentTask,
        proposal: CorrectionProposal
    ): ToolResult {
        return toolRouter.routeAndExecute(
            toolName = proposal.toolName,
            args = proposal.toolArguments,
            context = task
        )
    }
}
