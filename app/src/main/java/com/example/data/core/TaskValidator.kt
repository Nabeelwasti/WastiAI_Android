package com.example.data.core

import android.content.Context
import android.util.Log
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class ValidationResult(
    val isVerified: Boolean,
    val actionName: String,
    val responseCode: Int,
    val detailMessage: String,
    val errorExplanation: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Task 41A: The TaskValidator Execution Verification Engine
 * Centralized validator that logs tool execution results (HTTP status codes, API payloads,
 * exception traces) and verifies operational outcome.
 */
object TaskValidator {

    private const val TAG = "TaskValidator"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Validates an execution result for tool/service calls.
     * Checks HTTP status codes, error terms, authentication statuses (missing OAuth tokens, etc.).
     */
    fun validateExecution(
        actionName: String,
        isSuccess: Boolean,
        responseCode: Int,
        message: String,
        context: Context? = null
    ): ValidationResult {
        val lowerMsg = message.lowercase()

        val isAuthError = responseCode == 401 || responseCode == 403 ||
                lowerMsg.contains("unauthorized") || lowerMsg.contains("unauthenticated") ||
                lowerMsg.contains("invalid_token") || lowerMsg.contains("missing oauth") ||
                lowerMsg.contains("permission_denied") || lowerMsg.contains("oauth_token_missing")

        val isServerError = responseCode in 500..599
        val isClientError = responseCode in 400..499
        val containsErrorKeyword = lowerMsg.contains("exception") || lowerMsg.contains("failed") ||
                lowerMsg.contains("error") || lowerMsg.contains("denied")

        val isVerified = isSuccess && responseCode in 200..299 && !isAuthError && !isServerError

        val errorExplanation = when {
            isVerified -> null
            isAuthError -> "Authentication Failure: Missing or invalid OAuth credentials / permissions for '$actionName' (HTTP $responseCode)."
            isServerError -> "Remote Server Failure: Backend service returned HTTP $responseCode during '$actionName'."
            isClientError -> "Client Request Error: Invalid payload or missing resource for '$actionName' (HTTP $responseCode)."
            containsErrorKeyword -> "Execution Failure: Tool '$actionName' returned error trace: '$message'."
            else -> "Execution Unverified: Action '$actionName' returned unverified status code $responseCode."
        }

        val result = ValidationResult(
            isVerified = isVerified,
            actionName = actionName,
            responseCode = responseCode,
            detailMessage = message,
            errorExplanation = errorExplanation
        )

        Log.i(TAG, "TaskValidation Result [$actionName]: isVerified=$isVerified, code=$responseCode, explanation=$errorExplanation")

        // Persist validation result to system log database
        context?.let { ctx ->
            scope.launch {
                try {
                    val db = WastiDatabase.getDatabase(ctx)
                    db.systemLogDao().insertLog(
                        SystemLogEntity(
                            level = if (isVerified) "INFO" else "ERROR",
                            source = "TaskValidator",
                            message = "Action '$actionName' validation: isVerified=$isVerified, code=$responseCode",
                            details = errorExplanation ?: message,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist validation log to WastiDatabase", e)
                }
            }
        }

        return result
    }
}
