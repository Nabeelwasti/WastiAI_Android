package com.example.data.agent.runtime

/**
 * Stage 1: Provider-independent Code Execution interface.
 * Process creation, native shell execution, and remote execution engines are disabled.
 */
interface CodeExecutionProvider {
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}

/**
 * Non-operational stub returning a controlled SECURITY error.
 * Guaranteed never to spawn shell commands or execute binaries.
 */
class SafeLocalExecutionStub : CodeExecutionProvider {
    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        return ExecutionResult(
            stdout = "",
            stderr = "Execution blocked: Local execution is disabled in Stage 1.",
            exitCode = -1,
            executionTimeMs = 0L,
            status = ExecutionStatus(
                isSuccess = false,
                message = "NOT_ENABLED: Local process execution is not permitted in Stage 1."
            ),
            errorType = ExecutionErrorType.SECURITY
        )
    }
}
