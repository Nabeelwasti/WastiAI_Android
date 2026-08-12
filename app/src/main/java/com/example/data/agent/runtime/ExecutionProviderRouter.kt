package com.example.data.agent.runtime

class ExecutionProviderRouter(
    val providerRegistry: ExecutionProviderRegistry = WastiExecutionProviderRegistry()
) {

    fun registerProvider(
        provider: CodeExecutionProvider,
        advertisement: ProviderCapabilityAdvertisement
    ) {
        providerRegistry.registerProvider(provider, advertisement)
    }

    fun selectProvider(request: ExecutionRequest): CodeExecutionProvider? {
        return providerRegistry.findBestProvider(request)?.first
    }

    suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val selectedProvider = selectProvider(request)
            ?: return ExecutionResult(
                stdout = "",
                stderr = "PROVIDER_UNAVAILABLE: No suitable execution provider registered for executable '${request.executable}' and language '${request.language ?: "unspecified"}'",
                exitCode = -1,
                executionTimeMs = 0L,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "PROVIDER_UNAVAILABLE: No execution provider found for request"
                ),
                errorType = ExecutionErrorType.PROVIDER_UNAVAILABLE
            )

        return try {
            selectedProvider.execute(request)
        } catch (e: Exception) {
            ExecutionResult(
                stdout = "",
                stderr = e.message ?: "Execution error",
                exitCode = -1,
                executionTimeMs = 0L,
                status = ExecutionStatus(
                    isSuccess = false,
                    message = "EXECUTION_EXCEPTION: ${e.message}"
                ),
                errorType = ExecutionErrorType.PROVIDER
            )
        }
    }
}
