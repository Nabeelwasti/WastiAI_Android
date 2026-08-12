package com.example.data.agent.runtime

/**
 * Task 7: Agent Observation.
 * Structured observation model representing tool outputs, execution results, and diagnostics.
 * Caps stdout/stderr string lengths to prevent unbounded memory growth.
 */
data class AgentObservation(
    val taskId: TaskId,
    val toolName: String,
    val isSuccess: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val outputMap: Map<String, Any?> = emptyMap(),
    val testResultSummary: String? = null,
    val compilationResultSummary: String? = null,
    val filesystemChanges: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private const val MAX_OBSERVATION_TEXT_LENGTH = 10000

        fun fromToolResult(
            taskId: TaskId,
            toolName: String,
            toolResult: ToolResult
        ): AgentObservation {
            val stdoutRaw = (toolResult.output["stdout"] as? String) ?: toolResult.output.toString()
            val stderrRaw = (toolResult.output["stderr"] as? String) ?: toolResult.error.orEmpty()
            val exitCode = (toolResult.output["exitCode"] as? Number)?.toInt()

            return AgentObservation(
                taskId = taskId,
                toolName = toolName,
                isSuccess = toolResult.isSuccess,
                stdout = stdoutRaw.take(MAX_OBSERVATION_TEXT_LENGTH),
                stderr = stderrRaw.take(MAX_OBSERVATION_TEXT_LENGTH),
                exitCode = exitCode,
                outputMap = toolResult.output,
                filesystemChanges = (toolResult.output["modifiedFiles"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            )
        }
    }
}
