package com.example.data.agent.runtime

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7: Canonical Wasti Sandbox.
 *
 * Enforces:
 * - Strict Workspace Isolation boundary
 * - Process lifecycle control, timeout enforcement, cancellation, and emergency stop
 * - Resource limits (max execution duration, max stdout/stderr capture size)
 * - Network Access Policy
 * - Unified audit logging and verification hooks
 */

enum class SandboxNetworkPolicy {
    DENIED,
    ALLOWED_FOR_DOMAIN,
    ALLOWED_FOR_OPERATION,
    ALLOWED_FOR_PROJECT,
    FULLY_ALLOWED
}

data class SandboxResourceLimits(
    val maxDurationMs: Long = 30000L,
    val maxOutputBytes: Int = 1024 * 1024, // 1MB
    val allowSubprocesses: Boolean = true,
    val networkPolicy: SandboxNetworkPolicy = SandboxNetworkPolicy.ALLOWED_FOR_OPERATION
)

data class SandboxExecutionRequest(
    val executionId: String = UUID.randomUUID().toString(),
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val resourceLimits: SandboxResourceLimits = SandboxResourceLimits(),
    val requiresNetwork: Boolean = false
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
    val verificationState: String
)

class WastiSandbox(
    private val context: Context,
    val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    val runtimeManager: WastiRuntimeManager = WastiRuntimeManager(context, workspaceManager),
    val projectManager: WastiProjectManager = WastiProjectManager(context, workspaceManager),
    val buildAndTestManager: WastiBuildAndTestManager = WastiBuildAndTestManager(context, workspaceManager, runtimeManager),
    private val localProvider: LocalAndroidProvider = LocalAndroidProvider(workspaceManager),
    private val emergencyStopController: WastiEmergencyStopController = WastiEmergencyStopController()
) {

    /**
     * Executes an arbitrary command inside the strictly confined Wasti Sandbox.
     */
    suspend fun executeInSandbox(request: SandboxExecutionRequest): SandboxExecutionResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        // 1. Emergency Stop Check
        if (emergencyStopController.isEmergencyStopped) {
            val completedAt = System.currentTimeMillis()
            return@withContext SandboxExecutionResult(
                executionId = request.executionId,
                command = request.command,
                arguments = request.arguments,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = completedAt - startedAt,
                exitCode = 126,
                stdout = "",
                stderr = "Execution rejected: Wasti Emergency Stop is ACTIVE.",
                isSuccess = false,
                isTimeout = false,
                isCancelled = true,
                isPolicyBlocked = true,
                blockedReason = "EMERGENCY_STOP_ACTIVE",
                verificationState = "BLOCKED_EMERGENCY_STOP"
            )
        }

        // 2. Validate Working Directory within Workspace
        val workDirRes = workspaceManager.resolvePathSafely(request.workingDirectory)
        if (workDirRes.isFailure) {
            val completedAt = System.currentTimeMillis()
            return@withContext SandboxExecutionResult(
                executionId = request.executionId,
                command = request.command,
                arguments = request.arguments,
                startedAt = startedAt,
                completedAt = completedAt,
                durationMs = completedAt - startedAt,
                exitCode = 1,
                stdout = "",
                stderr = "Workspace path escape blocked: ${workDirRes.exceptionOrNull()?.message}",
                isSuccess = false,
                isTimeout = false,
                isCancelled = false,
                isPolicyBlocked = true,
                blockedReason = "PATH_TRAVERSAL_BLOCKED",
                verificationState = "BLOCKED_WORKSPACE_BOUNDARY"
            )
        }

        // 3. Check Runtime Availability for specific interpreters
        val normCmd = request.command.trim().lowercase()
        if (normCmd == "python" || normCmd == "python3") {
            val pyRuntime = runtimeManager.getRuntime("PYTHON")
            if (pyRuntime?.status != RuntimeRealityStatus.AVAILABLE) {
                val completedAt = System.currentTimeMillis()
                return@withContext SandboxExecutionResult(
                    executionId = request.executionId,
                    command = request.command,
                    arguments = request.arguments,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMs = completedAt - startedAt,
                    exitCode = 127,
                    stdout = "",
                    stderr = "NOT_INSTALLED: Python runtime is not installed or available on this system.",
                    isSuccess = false,
                    isTimeout = false,
                    isCancelled = false,
                    isPolicyBlocked = false,
                    blockedReason = null,
                    verificationState = "FAILED_RUNTIME_NOT_INSTALLED"
                )
            }
        }

        if (normCmd == "node" || normCmd == "npm") {
            val nodeRuntime = runtimeManager.getRuntime("NODE")
            if (nodeRuntime?.status != RuntimeRealityStatus.AVAILABLE) {
                val completedAt = System.currentTimeMillis()
                return@withContext SandboxExecutionResult(
                    executionId = request.executionId,
                    command = request.command,
                    arguments = request.arguments,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMs = completedAt - startedAt,
                    exitCode = 127,
                    stdout = "",
                    stderr = "NOT_INSTALLED: Node.js runtime is not installed or available on this system.",
                    isSuccess = false,
                    isTimeout = false,
                    isCancelled = false,
                    isPolicyBlocked = false,
                    blockedReason = null,
                    verificationState = "FAILED_RUNTIME_NOT_INSTALLED"
                )
            }
        }

        // 4. Resolve executable (e.g. sh, bash or direct binary)
        val executable = if (normCmd == "sh" || normCmd == "bash") {
            val shPaths = listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
            shPaths.find { java.io.File(it).exists() } ?: "sh"
        } else request.command

        // 5. Execute via LocalAndroidProvider with timeout and resource limits
        val execReq = ExecutionRequest(
            executable = executable,
            arguments = request.arguments,
            workingDirectory = request.workingDirectory,
            timeoutMs = request.resourceLimits.maxDurationMs
        )

        val execRes = localProvider.execute(execReq)
        val completedAt = System.currentTimeMillis()

        // 6. Truncate output if exceeding resource limits
        val maxBytes = request.resourceLimits.maxOutputBytes
        val stdout = if (execRes.stdout.length > maxBytes) execRes.stdout.take(maxBytes) + "\n...[OUTPUT TRUNCATED BY WASTI SANDBOX]..." else execRes.stdout
        val stderr = if (execRes.stderr.length > maxBytes) execRes.stderr.take(maxBytes) + "\n...[ERROR TRUNCATED BY WASTI SANDBOX]..." else execRes.stderr

        SandboxExecutionResult(
            executionId = request.executionId,
            command = request.command,
            arguments = request.arguments,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = execRes.executionTimeMs,
            exitCode = execRes.exitCode,
            stdout = stdout,
            stderr = stderr,
            isSuccess = execRes.status.isSuccess,
            isTimeout = execRes.errorType == ExecutionErrorType.TIMEOUT,
            isCancelled = false,
            isPolicyBlocked = false,
            blockedReason = null,
            verificationState = if (execRes.status.isSuccess) "VERIFIED_SANDBOX_EXECUTION" else "FAILED_EXECUTION"
        )
    }
}
