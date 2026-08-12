package com.example.data.agent.runtime

import java.util.concurrent.CopyOnWriteArrayList

data class AuditRecord(
    val taskId: TaskId,
    val toolName: String,
    val sanitizedInput: Map<String, Any?>,
    val decision: AuthorizationDecision,
    val permissionGranted: Boolean?,
    val result: Map<String, Any?>?,
    val executionTimeMs: Long,
    val timestamp: Long
)

data class SecurityViolationRecord(
    val taskId: TaskId,
    val violationType: String,
    val details: String,
    val timestamp: Long
)

class WastiAuditLogger : AuditLogger {

    private val _logs = CopyOnWriteArrayList<AuditRecord>()
    private val _violations = CopyOnWriteArrayList<SecurityViolationRecord>()

    val logs: List<AuditRecord> get() = _logs.toList()
    val violations: List<SecurityViolationRecord> get() = _violations.toList()

    private val SENSITIVE_KEYS = setOf(
        "key", "secret", "token", "password", "auth", "credential", "bearer", "cookie", "private", "api_key"
    )

    override suspend fun logToolInvocation(
        taskId: TaskId,
        toolName: String,
        sanitizedInput: Map<String, Any?>,
        decision: AuthorizationDecision,
        permissionGranted: Boolean?,
        result: Map<String, Any?>?,
        executionTimeMs: Long,
        timestamp: Long
    ) {
        val sanitized = sanitizeMetadata(sanitizedInput)
        val sanitizedResult = result?.let { sanitizeMetadata(it) }
        _logs.add(
            AuditRecord(
                taskId = taskId,
                toolName = toolName,
                sanitizedInput = sanitized,
                decision = decision,
                permissionGranted = permissionGranted,
                result = sanitizedResult,
                executionTimeMs = executionTimeMs,
                timestamp = timestamp
            )
        )
    }

    override suspend fun logSecurityViolation(
        taskId: TaskId,
        violationType: String,
        details: String,
        timestamp: Long
    ) {
        _violations.add(
            SecurityViolationRecord(
                taskId = taskId,
                violationType = violationType,
                details = details,
                timestamp = timestamp
            )
        )
    }

    override fun sanitizeMetadata(rawInput: Map<String, Any?>): Map<String, Any?> {
        return rawInput.mapValues { (key, value) ->
            val lowerKey = key.lowercase()
            if (SENSITIVE_KEYS.any { lowerKey.contains(it) }) {
                "[REDACTED_SECRET]"
            } else when (value) {
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") sanitizeMetadata(value as Map<String, Any?>)
                is String -> {
                    if (containsSecretValue(value)) "[REDACTED_SECRET]" else value
                }
                else -> value
            }
        }
    }

    private fun containsSecretValue(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("bearer ") || lower.contains("api_key=") || lower.contains("secret=")
    }
}
