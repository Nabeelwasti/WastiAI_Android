package com.example.data.wre

import java.util.UUID

/**
 * Stage 9A: Wasti Runtime Environment (WRE) Core Contracts & Models
 *
 * One Brain -> One Command -> Any Capability -> Any Environment -> Real Execution -> Real Verification
 */

enum class ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMEOUT,
    DENIED,
    UNAVAILABLE
}

enum class ExecutionPermission {
    FILE_READ,
    FILE_WRITE,
    PROCESS_EXECUTION,
    NETWORK_ACCESS,
    SCRIPT_EXECUTION,
    BACKGROUND_EXECUTION,
    EXTERNAL_STORAGE,
    SYSTEM_ACTION
}

data class ExecutionRequest(
    val executionId: String = "WRE-${UUID.randomUUID().toString().take(8).uppercase()}",
    val command: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String = "home/wasti",
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long? = 30000L,
    val permissions: Set<ExecutionPermission> = setOf(ExecutionPermission.FILE_READ, ExecutionPermission.FILE_WRITE),
    val initiatedBy: String = "user"
)

data class ExecutionResult(
    val executionId: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val status: ExecutionStatus,
    val verified: Boolean = false,
    val verificationEvidence: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class WastiProcess(
    val processId: String,
    val executionRequest: ExecutionRequest,
    val status: ExecutionStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val stdout: StringBuilder = StringBuilder(),
    val stderr: StringBuilder = StringBuilder(),
    val exitCode: Int? = null,
    val providerName: String = "Internal"
)

data class WastiJob(
    val jobId: String,
    val name: String,
    val request: ExecutionRequest,
    val status: ExecutionStatus,
    val submittedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val result: ExecutionResult? = null,
    val isBackground: Boolean = false
)

data class WreExecutionLog(
    val timestamp: Long = System.currentTimeMillis(),
    val executionId: String,
    val provider: String,
    val command: String,
    val workingDirectory: String,
    val status: ExecutionStatus,
    val durationMs: Long,
    val exitCode: Int,
    val stdoutLength: Int,
    val stderrLength: Int,
    val permissionDecision: String,
    val verificationResult: String
)

interface ExecutionProvider {
    val name: String
    val supportedCommands: Set<String>
    
    suspend fun canExecute(request: ExecutionRequest): Boolean
    suspend fun execute(request: ExecutionRequest): ExecutionResult
}
