package com.example.data.agent.runtime

/**
 * Task 4: Model Provider Contract.
 * Abstraction for LLM intelligence (planning, reasoning, diagnosis, correction proposals).
 * Prevents Wasti from being hard-coded or strictly tied to a single AI provider.
 */
interface AgentModelProvider {
    suspend fun generatePlan(goal: String, availableCapabilities: List<String>): ModelPlanResponse
    suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse
    suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse
}

data class PlannedStep(
    val stepId: Int,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val description: String
)

data class ModelPlanResponse(
    val rawReasoning: String,
    val steps: List<PlannedStep>,
    val isValid: Boolean = true
)

data class ModelDiagnosticResponse(
    val category: ExecutionErrorType,
    val summary: String,
    val evidence: String,
    val probableCause: String,
    val suggestedAction: String
)

data class ModelCorrectionResponse(
    val explanation: String,
    val proposedAction: PlannedStep?,
    val alternativeStrategy: String? = null
)

/**
 * Default offline/rule-based model provider for unit testing and local fallback.
 */
class RuleBasedAgentModelProvider : AgentModelProvider {

    override suspend fun generatePlan(goal: String, availableCapabilities: List<String>): ModelPlanResponse {
        val lowerGoal = goal.lowercase()
        val steps = mutableListOf<PlannedStep>()

        when {
            lowerGoal.contains("read file") || lowerGoal.contains("read") -> {
                val path = extractPath(goal) ?: "README.md"
                steps.add(
                    PlannedStep(
                        stepId = 1,
                        toolName = "read_file",
                        arguments = mapOf("path" to path),
                        description = "Read file at $path"
                    )
                )
            }
            lowerGoal.contains("create file") || lowerGoal.contains("write file") || lowerGoal.contains("write") -> {
                val path = extractPath(goal) ?: "test.txt"
                steps.add(
                    PlannedStep(
                        stepId = 1,
                        toolName = "write_file",
                        arguments = mapOf("path" to path, "content" to "Autonomous test content"),
                        description = "Write file at $path"
                    )
                )
            }
            lowerGoal.contains("run code") || lowerGoal.contains("execute") || lowerGoal.contains("script") -> {
                steps.add(
                    PlannedStep(
                        stepId = 1,
                        toolName = "execute_code",
                        arguments = mapOf(
                            "executable" to "sh",
                            "arguments" to listOf("-c", "echo 'stage4_exec'"),
                            "workingDirectory" to "."
                        ),
                        description = "Execute script in workspace"
                    )
                )
            }
            else -> {
                steps.add(
                    PlannedStep(
                        stepId = 1,
                        toolName = "list_files",
                        arguments = mapOf("path" to "."),
                        description = "Inspect workspace files"
                    )
                )
            }
        }

        return ModelPlanResponse(
            rawReasoning = "Rule-based planning logic applied for goal: $goal",
            steps = steps,
            isValid = true
        )
    }

    override suspend fun analyzeError(errorOutput: String, context: String): ModelDiagnosticResponse {
        val category = when {
            errorOutput.contains("COMPILATION") || errorOutput.contains("e: file://") -> ExecutionErrorType.COMPILATION
            errorOutput.contains("SYNTAX") -> ExecutionErrorType.SYNTAX
            errorOutput.contains("TIMEOUT") -> ExecutionErrorType.TIMEOUT
            errorOutput.contains("SECURITY") || errorOutput.contains("blocked") -> ExecutionErrorType.SECURITY
            errorOutput.contains("PERMISSION") || errorOutput.contains("denied") -> ExecutionErrorType.PERMISSION
            else -> ExecutionErrorType.RUNTIME
        }

        return ModelDiagnosticResponse(
            category = category,
            summary = "Rule-based diagnosis: ${errorOutput.take(100)}",
            evidence = errorOutput.take(300),
            probableCause = "Execution returned failure output",
            suggestedAction = "Modify script or tool arguments and retry"
        )
    }

    override suspend fun proposeCorrection(diagnostic: ErrorDiagnostic, context: String): ModelCorrectionResponse {
        val proposedStep = PlannedStep(
            stepId = 1,
            toolName = "write_file",
            arguments = mapOf("path" to "fixed_script.sh", "content" to "echo 'fixed'"),
            description = "Apply corrected script to workspace"
        )

        return ModelCorrectionResponse(
            explanation = "Proposed fix for ${diagnostic.category}",
            proposedAction = proposedStep,
            alternativeStrategy = "Fallback to simplified script execution"
        )
    }

    private fun extractPath(goal: String): String? {
        val parts = goal.split(" ")
        return parts.firstOrNull { it.contains(".") || it.contains("/") }
    }
}
