package com.example.data.agent.runtime

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7: Canonical Wasti Sandbox.
 *
 * This is an execution boundary for work authorized by the user and platform.
 * It does not, and cannot, bypass Android permissions, another app's isolation,
 * operating-system policy, or provider authorization.
 */
enum class SandboxNetworkPolicy {
    DENIED,
    ALLOWED_FOR_DOMAIN,
    ALLOWED_FOR_OPERATION,
    ALLOWED_FOR_PROJECT,
    FULLY_ALLOWED
}

data class SandboxResourceLimits(
    val maxDurationMs: Long = 30_000L,
    val maxOutputBytes: Int = 1_024 * 1_024,
    val allowSubprocesses: Boolean = true,
    val networkPolicy: SandboxNetworkPolicy = SandboxNetworkPolicy.ALLOWED_FOR_OPERATION
) {
    init {
        require(maxDurationMs > 0L) { "maxDurationMs must be greater than zero." }
        require(maxOutputBytes > 0) { "maxOutputBytes must be greater than zero." }
    }
}

data class SandboxExecutionRequest(
    val executionId: String = UUID.randomUUID().toString(),
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val resourceLimits: SandboxResourceLimits = SandboxResourceLimits(),
    val requiresNetwork: Boolean = false,
    val allowedDomains: Set<String> = emptySet(),
    val projectId: String? = null,
    val networkAuthorizationId: String? = null,
    val correlationId: String? = null
)

data class SandboxExecutionResult(
    val executionId: String,
    val command: String,
    val arguments: List<String>,
    val startedAt: Long,
    val completedAt: Long,
    val durationMs: Long,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isSuccess: Boolean,
    val isTimeout: Boolean,
    val isCancelled: Boolean,
    val isPolicyBlocked: Boolean,
    val blockedReason: String? = null,
    val verificationState: String,
    val wasStdoutTruncated: Boolean = false,
    val wasStderrTruncated: Boolean = false,
    val correlationId: String? = null
)

/**
 * A policy decision is intentionally separate from process execution. The
 * execution provider must enforce the decision for actual network access.
 */
data class SandboxNetworkPolicyDecision(
    val isAllowed: Boolean,
    val reason: String? = null
)

interface SandboxNetworkPolicyValidator {
    fun evaluate(request: SandboxExecutionRequest): SandboxNetworkPolicyDecision
}

/**
 * Default preflight prevents declared network work when it is explicitly denied.
 * Applications can inject a validator that checks operation grants, project
 * membership, approved domains, and runtime network state.
 */
object DefaultSandboxNetworkPolicyValidator : SandboxNetworkPolicyValidator {
    override fun evaluate(request: SandboxExecutionRequest): SandboxNetworkPolicyDecision {
        if (!request.requiresNetwork) {
            return SandboxNetworkPolicyDecision(isAllowed = true)
        }

        return when (request.resourceLimits.networkPolicy) {
            SandboxNetworkPolicy.DENIED -> SandboxNetworkPolicyDecision(
                isAllowed = false,
                reason = "NETWORK_ACCESS_DENIED"
            )
            SandboxNetworkPolicy.ALLOWED_FOR_DOMAIN -> {
                if (request.allowedDomains.isEmpty()) {
                    SandboxNetworkPolicyDecision(
                        isAllowed = false,
                        reason = "NETWORK_DOMAIN_SCOPE_REQUIRED"
                    )
                } else {
                    SandboxNetworkPolicyDecision(isAllowed = true)
                }
            }
            SandboxNetworkPolicy.ALLOWED_FOR_OPERATION -> {
                if (request.networkAuthorizationId.isNullOrBlank()) {
                    SandboxNetworkPolicyDecision(
                        isAllowed = false,
                        reason = "NETWORK_OPERATION_AUTHORIZATION_REQUIRED"
                    )
                } else {
                    SandboxNetworkPolicyDecision(isAllowed = true)
                }
            }
            SandboxNetworkPolicy.ALLOWED_FOR_PROJECT -> {
                if (request.projectId.isNullOrBlank()) {
                    SandboxNetworkPolicyDecision(
                        isAllowed = false,
                        reason = "NETWORK_PROJECT_SCOPE_REQUIRED"
                    )
                } else {
                    SandboxNetworkPolicyDecision(isAllowed = true)
                }
            }
            SandboxNetworkPolicy.FULLY_ALLOWED -> SandboxNetworkPolicyDecision(isAllowed = true)
        }
    }
}

class WastiSandbox(
    private val context: Context,
    val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    val runtimeManager: WastiRuntimeManager = WastiRuntimeManager(context, workspaceManager),
    val projectManager: WastiProjectManager = WastiProjectManager(context, workspaceManager),
    val buildAndTestManager: WastiBuildAndTestManager =
        WastiBuildAndTestManager(context, workspaceManager, runtimeManager),
    private val localProvider: LocalAndroidProvider = LocalAndroidProvider(workspaceManager),
    private val emergencyStopController: EmergencyStopController = WastiEmergencyStopController(),
    private val networkPolicyValidator: SandboxNetworkPolicyValidator =
        DefaultSandboxNetworkPolicyValidator
) {

    /**
     * Runs one authorized execution request. Independent requests are not
     * serialized here and may run concurrently if their provider supports it.
     */
    suspend fun executeInSandbox(request: SandboxExecutionRequest): SandboxExecutionResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()

            if (request.command.isBlank()) {
                return@withContext blockedResult(
                    request = request,
                    startedAt = startedAt,
                    exitCode = 2,
                    reason = "COMMAND_REQUIRED",
                    verificationState = "FAILED_INVALID_REQUEST",
                    policyBlocked = false
                )
            }

            if (emergencyStopController.isEmergencyStopped) {
                return@withContext blockedResult(
                    request = request,
                    startedAt = startedAt,
                    exitCode = 126,
                    reason = "EMERGENCY_STOP_ACTIVE",
                    verificationState = "BLOCKED_EMERGENCY_STOP",
                    cancelled = true
                )
            }

            val networkDecision = networkPolicyValidator.evaluate(request)
            if (!networkDecision.isAllowed) {
                return@withContext blockedResult(
                    request = request,
                    startedAt = startedAt,
                    exitCode = 126,
                    reason = networkDecision.reason ?: "NETWORK_POLICY_BLOCKED",
                    verificationState = "BLOCKED_NETWORK_POLICY"
                )
            }

            val workDirResult = workspaceManager.resolvePathSafely(request.workingDirectory)
            if (workDirResult.isFailure) {
                return@withContext blockedResult(
                    request = request,
                    startedAt = startedAt,
                    exitCode = 1,
                    reason = "PATH_TRAVERSAL_BLOCKED",
                    verificationState = "BLOCKED_WORKSPACE_BOUNDARY",
                    stderr = "Workspace path escape blocked: " +
                        (workDirResult.exceptionOrNull()?.message ?: "Invalid workspace path")
                )
            }

            val normalizedCommand = request.command.trim().lowercase(Locale.ROOT)
            val requiredRuntime = requiredRuntimeFor(normalizedCommand)
            if (requiredRuntime != null &&
                runtimeManager.getRuntime(requiredRuntime)?.status != RuntimeRealityStatus.AVAILABLE
            ) {
                return@withContext runtimeUnavailableResult(
                    request = request,
                    startedAt = startedAt,
                    runtimeName = requiredRuntime
                )
            }

            val executable = resolveExecutable(request.command, normalizedCommand)
            val executionRequest = ExecutionRequest(
                executable = executable,
                arguments = request.arguments,
                workingDirectory = request.workingDirectory,
                timeoutMs = request.resourceLimits.maxDurationMs,
                environment = request.environment
            )

            val executionResult = try {
                localProvider.execute(executionRequest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withContext executionFailureResult(request, startedAt, error)
            }

            val completedAt = System.currentTimeMillis()
            val stdout = truncateUtf8(executionResult.stdout, request.resourceLimits.maxOutputBytes)
            val stderr = truncateUtf8(executionResult.stderr, request.resourceLimits.maxOutputBytes)
            val emergencyStopTriggeredDuringExecution = emergencyStopController.isEmergencyStopped

            SandboxExecutionResult(
                executionId = request.executionId,
                command = request.command,
                arguments = request.arguments,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = completedAt - startedAt,
                exitCode = executionResult.exitCode,
                stdout = stdout.value,
                stderr = stderr.value,
                isSuccess = executionResult.status.isSuccess && !emergencyStopTriggeredDuringExecution,
                isTimeout = executionResult.errorType == ExecutionErrorType.TIMEOUT,
                isCancelled = emergencyStopTriggeredDuringExecution,
                isPolicyBlocked = emergencyStopTriggeredDuringExecution,
                blockedReason = if (emergencyStopTriggeredDuringExecution) {
                    "EMERGENCY_STOP_TRIGGERED_DURING_EXECUTION"
                } else {
                    null
                },
                verificationState = when {
                    emergencyStopTriggeredDuringExecution -> "STOPPED_DURING_EXECUTION"
                    executionResult.status.isSuccess -> "VERIFIED_SANDBOX_EXECUTION"
                    executionResult.errorType == ExecutionErrorType.TIMEOUT -> "FAILED_TIMEOUT"
                    else -> "FAILED_EXECUTION"
                },
                wasStdoutTruncated = stdout.wasTruncated,
                wasStderrTruncated = stderr.wasTruncated,
                correlationId = request.correlationId
            )
        }

    private fun requiredRuntimeFor(command: String): String? = when (command) {
        "python", "python3" -> "PYTHON"
        "node", "npm" -> "NODE"
        else -> null
    }

    private fun resolveExecutable(command: String, normalizedCommand: String): String =
        if (normalizedCommand == "sh" || normalizedCommand == "bash") {
            listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
                .firstOrNull { File(it).exists() }
                ?: command
        } else {
            command
        }

    private fun runtimeUnavailableResult(
        request: SandboxExecutionRequest,
        startedAt: Long,
        runtimeName: String
    ): SandboxExecutionResult {
        val completedAt = System.currentTimeMillis()
        return SandboxExecutionResult(
            executionId = request.executionId,
            command = request.command,
            arguments = request.arguments,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = completedAt - startedAt,
            exitCode = 127,
            stdout = "",
            stderr = "NOT_INSTALLED: $runtimeName runtime is not available on this system.",
            isSuccess = false,
            isTimeout = false,
            isCancelled = false,
            isPolicyBlocked = false,
            verificationState = "FAILED_RUNTIME_NOT_INSTALLED",
            correlationId = request.correlationId
        )
    }

    private fun executionFailureResult(
        request: SandboxExecutionRequest,
        startedAt: Long,
        error: Exception
    ): SandboxExecutionResult {
        val completedAt = System.currentTimeMillis()
        return SandboxExecutionResult(
            executionId = request.executionId,
            command = request.command,
            arguments = request.arguments,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = completedAt - startedAt,
            exitCode = 1,
            stdout = "",
            stderr = error.message ?: error::class.java.simpleName,
            isSuccess = false,
            isTimeout = false,
            isCancelled = false,
            isPolicyBlocked = false,
            verificationState = "FAILED_EXECUTION_EXCEPTION",
            correlationId = request.correlationId
        )
    }

    private fun blockedResult(
        request: SandboxExecutionRequest,
        startedAt: Long,
        exitCode: Int,
        reason: String,
        verificationState: String,
        cancelled: Boolean = false,
        policyBlocked: Boolean = true,
        stderr: String = "Execution blocked: $reason"
    ): SandboxExecutionResult {
        val completedAt = System.currentTimeMillis()
        return SandboxExecutionResult(
            executionId = request.executionId,
            command = request.command,
            arguments = request.arguments,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = completedAt - startedAt,
            exitCode = exitCode,
            stdout = "",
            stderr = stderr,
            isSuccess = false,
            isTimeout = false,
            isCancelled = cancelled,
            isPolicyBlocked = policyBlocked,
            blockedReason = reason,
            verificationState = verificationState,
            correlationId = request.correlationId
        )
    }

    private data class TruncatedOutput(
        val value: String,
        val wasTruncated: Boolean
    )

    private fun truncateUtf8(value: String, maxBytes: Int): TruncatedOutput {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return TruncatedOutput(value = value, wasTruncated = false)
        }

        val marker = "\n...[OUTPUT TRUNCATED BY WASTI SANDBOX]..."
        val markerBytes = marker.toByteArray(Charsets.UTF_8).size
        val contentBudget = (maxBytes - markerBytes).coerceAtLeast(0)
        val builder = StringBuilder()
        var usedBytes = 0
        var index = 0

        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val fragment = String(Character.toChars(codePoint))
            val fragmentBytes = fragment.toByteArray(Charsets.UTF_8).size
            if (usedBytes + fragmentBytes > contentBudget) break

            builder.append(fragment)
            usedBytes += fragmentBytes
            index += Character.charCount(codePoint)
        }

        val truncatedValue = if (markerBytes <= maxBytes) {
            builder.append(marker).toString()
        } else {
            builder.toString()
        }
        return TruncatedOutput(value = truncatedValue, wasTruncated = true)
    }
}
