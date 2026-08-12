package com.example.data.agent.runtime

/**
 * Stage 3 Task 4: Execute Code Tool.
 * An AgentTool that passes structured code execution requests through
 * WastiAgentToolRouter, SecurityPolicy, PermissionModel, EmergencyStopController,
 * and ExecutionProviderRouter.
 * ABSOLUTELY NO generic unrestricted command string execution.
 */
class ExecuteCodeTool(
    private val executionRouter: ExecutionProviderRouter
) : AgentTool {

    override val name: String = "execute_code"
    override val description: String = "Executes code/binaries via structured parameters through execution providers within workspace boundaries."
    override val permissionLevel: PermissionLevel = PermissionLevel.PRIVILEGED

    override suspend fun execute(input: Map<String, Any?>): Map<String, Any?> {
        val executable = input["executable"] as? String
            ?: return mapOf("success" to false, "error" to "Missing required argument 'executable'")

        val workingDirectory = (input["workingDirectory"] as? String) ?: "."
        val language = input["language"] as? String
        val timeoutMs = (input["timeoutMs"] as? Number)?.toLong() ?: 30000L

        @Suppress("UNCHECKED_CAST")
        val args = (input["arguments"] as? List<String>) ?: emptyList()

        @Suppress("UNCHECKED_CAST")
        val env = (input["environment"] as? Map<String, String>) ?: emptyMap()

        val request = ExecutionRequest(
            executable = executable,
            arguments = args,
            workingDirectory = workingDirectory,
            language = language,
            timeoutMs = timeoutMs,
            environment = env
        )

        val result = executionRouter.execute(request)

        return mapOf(
            "success" to result.status.isSuccess,
            "stdout" to result.stdout,
            "stderr" to result.stderr,
            "exitCode" to result.exitCode,
            "executionTimeMs" to result.executionTimeMs,
            "errorType" to result.errorType.name,
            "message" to result.status.message,
            "error" to if (result.status.isSuccess) null else result.stderr.ifEmpty { result.status.message }
        )
    }
}
