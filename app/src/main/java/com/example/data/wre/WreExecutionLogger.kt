package com.example.data.wre

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 9A: WRE Execution Logger & Observability
 * Records sanitized execution traces, exit codes, durations, and verification outcomes.
 */
class WreExecutionLogger {

    private val logs = CopyOnWriteArrayList<WreExecutionLog>()
    private val maxLogs = 500

    fun log(logEntry: WreExecutionLog) {
        val sanitizedLog = logEntry.copy(
            command = sanitizeSensitive(logEntry.command)
        )
        logs.add(sanitizedLog)
        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
    }

    fun getLogs(limit: Int = 50): List<WreExecutionLog> {
        return logs.takeLast(limit).reversed()
    }

    fun clear() {
        logs.clear()
    }

    private fun sanitizeSensitive(input: String): String {
        // Redact bearer tokens, API keys, passwords
        return input.replace(Regex("(?i)(api[-_]?key|password|token|secret|bearer)\\s*[=:]\\s*['\"]?([^\\s'\"]+)['\"]?")) { match ->
            val key = match.groupValues[1]
            "$key=***REDACTED***"
        }
    }
}
