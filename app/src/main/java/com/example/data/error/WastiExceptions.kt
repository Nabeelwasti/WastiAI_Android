package com.example.data.error

import java.io.IOException

/**
 * Domain Exception Hierarchy for Wasti AI OS.
 * Guarantees typed, diagnosable errors across all subsystems.
 */
sealed class WastiException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String = "WASTI_GENERAL_ERROR",
    val isRecoverable: Boolean = true
) : Exception(message, cause)

class NetworkException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null
) : WastiException(message, cause, errorCode = "NETWORK_ERROR", isRecoverable = true)

class CapabilityUnavailableException(
    val capabilityId: String,
    message: String = "Capability '$capabilityId' is unavailable or not registered on this device."
) : WastiException(message, errorCode = "CAPABILITY_UNAVAILABLE", isRecoverable = false)

class SyntaxException(
    message: String,
    val language: String,
    val line: Int? = null
) : WastiException(message, errorCode = "SYNTAX_ERROR", isRecoverable = true)

class SecurityPolicyException(
    message: String,
    val requestedPath: String? = null,
    val policyRule: String? = null
) : WastiException(message, errorCode = "SECURITY_POLICY_VIOLATION", isRecoverable = false)

class AuthRequiredException(
    val providerId: String,
    message: String = "Authentication or API key required for provider '$providerId'."
) : WastiException(message, errorCode = "AUTH_REQUIRED", isRecoverable = true)

class DeviceControlException(
    val targetAppOrAction: String,
    message: String,
    cause: Throwable? = null
) : WastiException(message, cause, errorCode = "DEVICE_CONTROL_ERROR", isRecoverable = true)

/**
 * Exception raised when a local, sandboxed, or remote tool execution fails.
 */
class ToolExecutionException(
    val toolName: String,
    message: String,
    cause: Throwable? = null
) : WastiException(message, cause, errorCode = "TOOL_EXECUTION_ERROR", isRecoverable = true)
