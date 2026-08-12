package com.example.data.agent.runtime

data class ToolResult(
    val isSuccess: Boolean,
    val output: Map<String, Any?>,
    val error: String? = null,
    val isSecurityBlocked: Boolean = false,
    val isCancelled: Boolean = false,
    val executionTimeMs: Long = 0L
)
