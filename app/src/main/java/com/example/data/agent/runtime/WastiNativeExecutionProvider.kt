package com.example.data.agent.runtime

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RuntimeCapabilityState {
    AVAILABLE,
    NOT_INSTALLED,
    UNAVAILABLE,
    IMPLEMENTED_NOT_LIVE_VERIFIED
}

data class RuntimeInfo(
    val runtimeId: String,
    val name: String,
    val state: RuntimeCapabilityState,
    val binaryPath: String? = null,
    val details: String
)

data class NativeCommandResult(
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
    val verificationState: String
)

/**
 * Stage 7: Wasti-Native Execution Provider & Runtime Environment.
 * Acts as Wasti's native terminal, workspace execution engine, and runtime detector.
 * Enforces strict workspace boundaries via [WorkspaceManager] and truthful status reporting.
 */
class WastiNativeExecutionProvider(
    private val context: Context,
    private val workspaceManager: WorkspaceManager = WorkspaceManager(context),
    private val localProvider: LocalAndroidProvider = LocalAndroidProvider(workspaceManager)
) {

    /**
     * Detects actual runtime capabilities present on the host device.
     */
    fun detectRuntimes(): Map<String, RuntimeInfo> {
        val runtimes = mutableMapOf<String, RuntimeInfo>()

        // 1. Shell Runtime
        val shPaths = listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh")
        val foundSh = shPaths.find { File(it).exists() }
        if (foundSh != null) {
            runtimes["SHELL"] = RuntimeInfo(
                runtimeId = "SHELL",
                name = "Android Shell Environment",
                state = RuntimeCapabilityState.AVAILABLE,
                binaryPath = foundSh,
                details = "Native shell available at $foundSh"
            )
        } else {
            runtimes["SHELL"] = RuntimeInfo(
                runtimeId = "SHELL",
                name = "Android Shell Environment",
                state = RuntimeCapabilityState.UNAVAILABLE,
                details = "Shell binary missing or unexecutable"
            )
        }

        // 2. Dalvik / Java Runtime
        val dalvikFile = File("/system/bin/dalvikvm")
        if (dalvikFile.exists()) {
            runtimes["JAVA_DALVIK"] = RuntimeInfo(
                runtimeId = "JAVA_DALVIK",
                name = "Dalvik / ART VM Runtime",
                state = RuntimeCapabilityState.AVAILABLE,
                binaryPath = "/system/bin/dalvikvm",
                details = "Android Runtime (ART) ART/Dalvik virtual machine"
            )
        } else {
            runtimes["JAVA_DALVIK"] = RuntimeInfo(
                runtimeId = "JAVA_DALVIK",
                name = "Dalvik / ART VM Runtime",
                state = RuntimeCapabilityState.UNAVAILABLE,
                details = "DalvikVM binary not directly invokable"
            )
        }

        // 3. Python Runtime Detection
        val pythonPaths = listOf("/system/bin/python3", "/system/bin/python", "/data/local/tmp/python3")
        val foundPython = pythonPaths.find { File(it).exists() }
        if (foundPython != null) {
            runtimes["PYTHON_RUNTIME"] = RuntimeInfo(
                runtimeId = "PYTHON_RUNTIME",
                name = "Python 3 Interpreter",
                state = RuntimeCapabilityState.AVAILABLE,
                binaryPath = foundPython,
                details = "Python 3 binary found at $foundPython"
            )
        } else {
            runtimes["PYTHON_RUNTIME"] = RuntimeInfo(
                runtimeId = "PYTHON_RUNTIME",
                name = "Python 3 Interpreter",
                state = RuntimeCapabilityState.NOT_INSTALLED,
                details = "Python runtime is not currently available in the native Wasti environment."
            )
        }

        // 4. Node.js Runtime Detection
        val nodePaths = listOf("/system/bin/node", "/data/local/tmp/node")
        val foundNode = nodePaths.find { File(it).exists() }
        if (foundNode != null) {
            runtimes["NODE_RUNTIME"] = RuntimeInfo(
                runtimeId = "NODE_RUNTIME",
                name = "Node.js JavaScript Engine",
                state = RuntimeCapabilityState.AVAILABLE,
                binaryPath = foundNode,
                details = "Node.js binary found at $foundNode"
            )
        } else {
            runtimes["NODE_RUNTIME"] = RuntimeInfo(
                runtimeId = "NODE_RUNTIME",
                name = "Node.js JavaScript Engine",
                state = RuntimeCapabilityState.NOT_INSTALLED,
                details = "Node.js runtime is not currently available in the native Wasti environment."
            )
        }

        // 5. Git Version Control
        val gitFile = File("/system/bin/git")
        if (gitFile.exists()) {
            runtimes["GIT"] = RuntimeInfo(
                runtimeId = "GIT",
                name = "Git Version Control",
                state = RuntimeCapabilityState.AVAILABLE,
                binaryPath = "/system/bin/git",
                details = "Git binary available at /system/bin/git"
            )
        } else {
            runtimes["GIT"] = RuntimeInfo(
                runtimeId = "GIT",
                name = "Git Version Control",
                state = RuntimeCapabilityState.NOT_INSTALLED,
                details = "Git CLI binary is not installed on stock Android system image."
            )
        }

        return runtimes
    }

    /**
     * Executes a process or command safely within the Wasti workspace.
     */
    suspend fun executeCommand(
        command: String,
        args: List<String> = emptyList(),
        workingDirRelative: String = "",
        timeoutMs: Long = 10000L
    ): NativeCommandResult = withContext(Dispatchers.IO) {
        val execId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        // Runtime check for specific interpreters
        val runtimes = detectRuntimes()
        if (command == "python" || command == "python3") {
            val pyInfo = runtimes["PYTHON_RUNTIME"]
            if (pyInfo?.state != RuntimeCapabilityState.AVAILABLE) {
                val completedAt = System.currentTimeMillis()
                return@withContext NativeCommandResult(
                    executionId = execId,
                    command = command,
                    arguments = args,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMs = completedAt - startedAt,
                    exitCode = 127,
                    stdout = "",
                    stderr = "NOT_INSTALLED: Python runtime is not currently available in the native Wasti environment.",
                    isSuccess = false,
                    isTimeout = false,
                    isCancelled = false,
                    verificationState = "FAILED_RUNTIME_NOT_INSTALLED"
                )
            }
        }

        if (command == "node" || command == "npm") {
            val nodeInfo = runtimes["NODE_RUNTIME"]
            if (nodeInfo?.state != RuntimeCapabilityState.AVAILABLE) {
                val completedAt = System.currentTimeMillis()
                return@withContext NativeCommandResult(
                    executionId = execId,
                    command = command,
                    arguments = args,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    durationMs = completedAt - startedAt,
                    exitCode = 127,
                    stdout = "",
                    stderr = "NOT_INSTALLED: Node.js runtime is not currently available in the native Wasti environment.",
                    isSuccess = false,
                    isTimeout = false,
                    isCancelled = false,
                    verificationState = "FAILED_RUNTIME_NOT_INSTALLED"
                )
            }
        }

        val shExecutable = if (command == "sh" || command == "bash") {
            listOf("/system/bin/sh", "/bin/sh", "/usr/bin/sh").find { File(it).exists() } ?: "sh"
        } else command

        // Delegate execution through LocalAndroidProvider with strict workspace boundary checks
        val req = ExecutionRequest(
            executable = shExecutable,
            arguments = args,
            workingDirectory = workingDirRelative,
            timeoutMs = timeoutMs
        )

        val res = localProvider.execute(req)
        val completedAt = System.currentTimeMillis()

        NativeCommandResult(
            executionId = execId,
            command = command,
            arguments = args,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMs = res.executionTimeMs,
            exitCode = res.exitCode,
            stdout = res.stdout,
            stderr = res.stderr,
            isSuccess = res.status.isSuccess,
            isTimeout = res.errorType == ExecutionErrorType.TIMEOUT,
            isCancelled = false,
            verificationState = if (res.status.isSuccess) "VERIFIED_NATIVE_EXECUTION" else "FAILED_EXECUTION"
        )
    }

    // Workspace File Management Operations
    fun createFile(relativePath: String, content: String): Result<Unit> = workspaceManager.writeFile(relativePath, content)
    fun readFile(relativePath: String): Result<String> = workspaceManager.readFile(relativePath)
    fun modifyFile(relativePath: String, content: String): Result<Unit> = workspaceManager.writeFile(relativePath, content)
    fun deleteFile(relativePath: String): Result<Boolean> {
        val resolved = workspaceManager.resolvePathSafely(relativePath).getOrNull() ?: return Result.failure(SecurityException("Workspace boundary escaped"))
        return if (resolved.exists()) {
            Result.success(resolved.delete())
        } else {
            Result.success(false)
        }
    }
    fun listFiles(relativePath: String = ""): Result<List<String>> = workspaceManager.listDirectory(relativePath)
    fun createProject(projectName: String): Result<Unit> = workspaceManager.createProjectStructure(projectName)
    fun inspectProject(projectName: String): Result<List<String>> = workspaceManager.listDirectory("projects/$projectName")
}
