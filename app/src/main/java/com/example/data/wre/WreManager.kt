package com.example.data.wre

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 9C: Central Wasti Runtime Environment (WRE) Manager
 * 
 * Coordinates execution providers, package manager, security gates,
 * autocompletion, process/job lifecycle, and log tracking.
 * Unifies AI Brain, UI, Terminal, and background automation requests.
 */
class WreManager(val context: Context) {

    val workspaceManager = WreWorkspaceManager(context)
    val environmentManager = WreEnvironmentManager()
    val processManager = WreProcessManager()
    val executionLogger = WreExecutionLogger()
    val packageManager = WrePackageManager(context, workspaceManager)
    val autocompleteEngine = WreAutocompleteEngine(workspaceManager, packageManager)

    private val providers = CopyOnWriteArrayList<ExecutionProvider>()

    init {
        // Register Native Commands Provider with Package Manager integration
        registerProvider(NativeCommandProvider(workspaceManager, environmentManager, processManager, packageManager))
    }

    fun registerProvider(provider: ExecutionProvider) {
        providers.add(provider)
    }

    fun getRegisteredProviders(): List<ExecutionProvider> = providers.toList()

    /**
     * Authoritative execution pipeline:
     * Request -> Security/Permission Gate -> Provider Selection -> Process Lifecycle -> Observation & Verification -> Execution Result
     */
    suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Security & Permission Check
        val permissionDenied = checkSecurityPolicy(request)
        if (permissionDenied != null) {
            val res = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 126,
                stdout = "",
                stderr = "Permission Denied: $permissionDenied",
                durationMs = 0L,
                status = ExecutionStatus.DENIED
            )
            logResult(res, "DENIED")
            return@withContext res
        }

        // 2. Select Provider
        val provider = providers.firstOrNull { it.canExecute(request) }
        if (provider == null) {
            val res = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 127,
                stdout = "",
                stderr = "No execution provider available for '${request.command}'",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.UNAVAILABLE
            )
            logResult(res, "NO_PROVIDER")
            return@withContext res
        }

        // 3. Register Live Process
        val process = processManager.createProcess(request, provider.name)
        processManager.updateProcessStatus(process.processId, ExecutionStatus.RUNNING)

        // 4. Execute
        val result = try {
            provider.execute(request)
        } catch (e: Exception) {
            ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 1,
                stdout = "",
                stderr = "Execution exception: ${e.localizedMessage}",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.FAILED
            )
        }

        // 5. Update Process & Logger
        processManager.updateProcessStatus(process.processId, result.status, result.exitCode)
        logResult(result, provider.name)

        result
    }

    private fun checkSecurityPolicy(request: ExecutionRequest): String? {
        val cmd = request.command.trim().lowercase()
        // Reject dangerous OS-level operations outside workspace
        if (cmd.startsWith("rm -rf /") || cmd.startsWith("mkfs") || cmd.startsWith("reboot")) {
            return "Destructive system operations forbidden"
        }
        return null
    }

    private fun logResult(result: ExecutionResult, provider: String) {
        executionLogger.log(
            WreExecutionLog(
                executionId = result.executionId,
                provider = provider,
                command = result.command,
                workingDirectory = "/home/wasti",
                status = result.status,
                durationMs = result.durationMs,
                exitCode = result.exitCode,
                stdoutLength = result.stdout.length,
                stderrLength = result.stderr.length,
                permissionDecision = "ALLOWED",
                verificationResult = if (result.verified) "VERIFIED: ${result.verificationEvidence ?: "OK"}" else "UNVERIFIED"
            )
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: WreManager? = null

        fun getInstance(context: Context): WreManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WreManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
