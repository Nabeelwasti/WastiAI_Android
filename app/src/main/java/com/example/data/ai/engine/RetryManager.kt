package com.example.data.ai.engine

import kotlinx.coroutines.delay

class RetryManager(
    private val maxRetries: Int = 2,
    private val initialDelayMs: Long = 500,
    private val factor: Double = 2.0
) {
    suspend fun <T> executeWithRetry(
        actionName: String = "AI Request",
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Throwable? = null

        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Throwable) {
                lastException = e
                if (attempt < maxRetries && isRetryable(e)) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: IllegalStateException("Execution failed after $maxRetries retries for $actionName")
    }

    private fun isRetryable(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("503") || msg.contains("429") || msg.contains("timeout") || msg.contains("rate limit") || msg.contains("connection")
    }
}
