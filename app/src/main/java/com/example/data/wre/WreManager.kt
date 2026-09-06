package com.example.data.wre

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.SelfModificationSafetyEngine
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.db.TerminalSessionEntity
import com.example.data.db.WastiDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 9C: Central Wasti Runtime Environment (WRE) Manager
 *
 * Enforces the authoritative execution pipeline:
 * Security → Permission → Execution → Observation → Verification → Audit
 *
 * Coordinates execution providers, package manager, security gates,
 * autocompletion, process/job lifecycle, and persistent audit log tracking.
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
     * Request -> Security Gate -> Permission Gate -> Provider Selection ->
     * Process Lifecycle -> Observation & Verification -> Durable Audit -> Execution Result
     */
    suspend fun execute(request: ExecutionRequest): ExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // 1. Security Gate: Emergency stop, destructive OS commands, protected paths
        val securityDenied = checkSecurityPolicy(request)
        if (securityDenied != null) {
            val res = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 126,
                stdout = "",
                stderr = "Security Policy Violation: $securityDenied",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.DENIED,
                verified = false,
                verificationEvidence = null
            )
            logResult(res, "SECURITY_GATE", "DENIED")
            return@withContext res
        }

        // 2. Permission Gate: Granular execution permission validation
        val permissionDenied = checkPermissionPolicy(request)
        if (permissionDenied != null) {
            val res = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 126,
                stdout = "",
                stderr = "Permission Denied: $permissionDenied",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.DENIED,
                verified = false,
                verificationEvidence = null
            )
            logResult(res, "PERMISSION_GATE", "DENIED")
            return@withContext res
        }

        // 3. Provider Selection
        val provider = providers.firstOrNull { it.canExecute(request) }
        if (provider == null) {
            val res = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 127,
                stdout = "",
                stderr = "No execution provider available for '${request.command}'",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.UNAVAILABLE,
                verified = false,
                verificationEvidence = null
            )
            logResult(res, "NO_PROVIDER", "ALLOWED")
            return@withContext res
        }

        // 4. Live Process Registration
        val process = processManager.createProcess(request, provider.name)
        processManager.updateProcessStatus(process.processId, ExecutionStatus.RUNNING)

        // 5. Execution with Strict Coroutine Cancellation Preservation
        val rawResult = try {
            provider.execute(request)
        } catch (e: CancellationException) {
            processManager.updateProcessStatus(process.processId, ExecutionStatus.CANCELLED, 130)
            val cancelledResult = ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 130,
                stdout = "",
                stderr = "Execution cancelled: ${e.message ?: "Task cancelled"}",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.CANCELLED,
                verified = false,
                verificationEvidence = null
            )
            logResult(cancelledResult, provider.name, "CANCELLED")
            throw e
        } catch (e: Exception) {
            ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 1,
                stdout = "",
                stderr = "Execution exception: ${e.localizedMessage}",
                durationMs = System.currentTimeMillis() - startTime,
                status = ExecutionStatus.FAILED,
                verified = false,
                verificationEvidence = null
            )
        }

        // 6. Observation & Verification Gate: Enforce real-world evidence
        val verifiedResult = observeAndVerify(rawResult, request)

        // 7. Update Process Lifecycle
        processManager.updateProcessStatus(process.processId, verifiedResult.status, verifiedResult.exitCode)

        // 8. Audit & Persistent Logging
        logResult(verifiedResult, provider.name, "ALLOWED")

        verifiedResult
    }

    /**
     * Evaluates security policy including Emergency Stop, dangerous commands, and protected paths.
     */
    fun checkSecurityPolicy(request: ExecutionRequest): String? {
        // Emergency Stop Check
        if (WastiEmergencyStopController.isEmergencyStopped) {
            return "Emergency stop active: WRE execution blocked"
        }

        val rawCmd = request.command.trim()
        val cmdLower = rawCmd.lowercase()

        // Destructive OS-level Operations forbidden
        if (cmdLower.startsWith("rm -rf /") ||
            cmdLower.startsWith("rm -rf /*") ||
            cmdLower.startsWith("mkfs") ||
            cmdLower.startsWith("reboot") ||
            cmdLower.startsWith("shutdown") ||
            cmdLower.contains(":(){ :|:& };:")) {
            return "Destructive system operations forbidden"
        }

        // Protected Path Mutation Gate
        val subCmds = extractAllSubCommands(rawCmd)
        val mutatingOps = setOf("mkdir", "touch", "rm", "cp", "mv")
        for (sub in subCmds) {
            val tokens = WreCommandParser.tokenize(sub)
            if (tokens.isNotEmpty()) {
                val op = tokens[0].lowercase()
                if (op in mutatingOps) {
                    val paths = tokens.drop(1).filter { !it.startsWith("-") }
                    for (p in paths) {
                        if (SelfModificationSafetyEngine.isProtectedPath(p)) {
                            if (!SelfModificationSafetyEngine.isValidAdminToken(request.adminAuthToken)) {
                                return "Protected system path modification forbidden without admin authorization: $p"
                            }
                        }
                    }
                }
            }
            // Check redirection target (e.g. echo "..." > /path/to/protected)
            if (sub.contains(">")) {
                val redirTarget = sub.substringAfter(">").trim().split("\\s+".toRegex())[0]
                if (redirTarget.isNotEmpty() && SelfModificationSafetyEngine.isProtectedPath(redirTarget)) {
                    if (!SelfModificationSafetyEngine.isValidAdminToken(request.adminAuthToken)) {
                        return "Protected system path modification via redirection forbidden without admin authorization: $redirTarget"
                    }
                }
            }
        }

        return null
    }

    /**
     * Validates that requested command operations have corresponding granted execution permissions.
     */
    fun checkPermissionPolicy(request: ExecutionRequest): String? {
        val permissions = request.permissions
        val rawCmd = request.command.trim()
        val subCmds = extractAllSubCommands(rawCmd)

        for (sub in subCmds) {
            val tokens = WreCommandParser.tokenize(sub)
            if (tokens.isEmpty()) continue
            val exe = tokens[0].lowercase()

            // File Redirection Requires FILE_WRITE
            if (sub.contains(">")) {
                if (!permissions.contains(ExecutionPermission.FILE_WRITE)) {
                    return "Missing required permission 'FILE_WRITE' for file redirection in '$sub'"
                }
            }

            when (exe) {
                "mkdir", "touch", "rm", "cp", "mv" -> {
                    if (!permissions.contains(ExecutionPermission.FILE_WRITE)) {
                        return "Missing required permission 'FILE_WRITE' for command '$exe'"
                    }
                }
                "python", "python3", "node", "sh", "bash" -> {
                    if (!permissions.contains(ExecutionPermission.PROCESS_EXECUTION) &&
                        !permissions.contains(ExecutionPermission.SCRIPT_EXECUTION)) {
                        return "Missing required permission 'PROCESS_EXECUTION' for command '$exe'"
                    }
                }
                "curl", "wget", "ping" -> {
                    if (!permissions.contains(ExecutionPermission.NETWORK_ACCESS)) {
                        return "Missing required permission 'NETWORK_ACCESS' for command '$exe'"
                    }
                }
                "wre" -> {
                    val subAction = tokens.getOrNull(1)?.lowercase()
                    if (subAction in listOf("install", "remove", "update")) {
                        if (!permissions.contains(ExecutionPermission.FILE_WRITE)) {
                            return "Missing required permission 'FILE_WRITE' for package action '$subAction'"
                        }
                    } else {
                        if (!permissions.contains(ExecutionPermission.FILE_READ)) {
                            return "Missing required permission 'FILE_READ' for command 'wre $subAction'"
                        }
                    }
                }
                else -> {
                    if (exe.startsWith("./") || exe.endsWith(".sh") || exe.endsWith(".py") || exe.endsWith(".js")) {
                        if (!permissions.contains(ExecutionPermission.PROCESS_EXECUTION) &&
                            !permissions.contains(ExecutionPermission.SCRIPT_EXECUTION)) {
                            return "Missing required permission 'PROCESS_EXECUTION' for script '$exe'"
                        }
                    } else {
                        // Inspect, read, and general query commands require at least FILE_READ
                        if (!permissions.contains(ExecutionPermission.FILE_READ)) {
                            return "Missing required permission 'FILE_READ' for command '$exe'"
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Inspects and validates real execution side-effects against physical disk/process truth.
     */
    fun observeAndVerify(result: ExecutionResult, request: ExecutionRequest): ExecutionResult {
        // Truth Invariant 1: If exitCode != 0 or status != SUCCESS, cannot be verified
        if (result.exitCode != 0 || result.status != ExecutionStatus.SUCCESS) {
            return result.copy(
                verified = false,
                verificationEvidence = result.verificationEvidence ?: if (result.stderr.isNotBlank()) result.stderr else "Execution returned non-zero exit code ${result.exitCode}"
            )
        }

        // Truth Invariant 2: Filesystem Mutation Verification
        val workingDirResult = workspaceManager.resolve(request.workingDirectory)
        val workingDir = workingDirResult.getOrNull() ?: workspaceManager.getRootDirectory()

        val subCmds = extractAllSubCommands(request.command)
        for (sub in subCmds) {
            val tokens = WreCommandParser.tokenize(sub)
            if (tokens.isEmpty()) continue
            val cmd = tokens[0].lowercase()
            val args = tokens.drop(1).filter { !it.startsWith("-") }

            when (cmd) {
                "mkdir" -> {
                    if (args.isNotEmpty()) {
                        val path = args[0]
                        val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$path").getOrNull()
                        val exists = target != null && target.exists() && target.isDirectory
                        if (!exists) {
                            return result.copy(
                                verified = false,
                                verificationEvidence = "Filesystem verification failed: directory '$path' does not exist on disk"
                            )
                        }
                        return result.copy(
                            verified = true,
                            verificationEvidence = "Filesystem state verified on disk: ${target?.canonicalPath}"
                        )
                    }
                }
                "touch" -> {
                    if (args.isNotEmpty()) {
                        val path = args[0]
                        val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$path").getOrNull()
                        val exists = target != null && target.exists() && target.isFile
                        if (!exists) {
                            return result.copy(
                                verified = false,
                                verificationEvidence = "Filesystem verification failed: file '$path' does not exist on disk"
                            )
                        }
                        return result.copy(
                            verified = true,
                            verificationEvidence = "Filesystem state verified on disk: ${target?.canonicalPath} (${target?.length()} bytes)"
                        )
                    }
                }
                "rm" -> {
                    if (args.isNotEmpty()) {
                        val path = args[0]
                        val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$path").getOrNull()
                        val deleted = target == null || !target.exists()
                        if (!deleted) {
                            return result.copy(
                                verified = false,
                                verificationEvidence = "Filesystem verification failed: file '$path' still exists on disk"
                            )
                        }
                        return result.copy(
                            verified = true,
                            verificationEvidence = "Filesystem deletion confirmed on disk: $path"
                        )
                    }
                }
                "cp", "mv" -> {
                    if (args.size >= 2) {
                        val dest = args[1]
                        val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$dest").getOrNull()
                        val exists = target != null && target.exists()
                        if (!exists) {
                            return result.copy(
                                verified = false,
                                verificationEvidence = "Filesystem verification failed: destination '$dest' does not exist on disk"
                            )
                        }
                        return result.copy(
                            verified = true,
                            verificationEvidence = "Filesystem state verified on disk: ${target?.canonicalPath}"
                        )
                    }
                }
            }
        }

        // If provider already supplied verified flag and evidence, preserve it
        if (result.verified && !result.verificationEvidence.isNullOrBlank()) {
            return result
        }

        // For non-mutating successful commands, populate truthful verification
        return result.copy(
            verified = true,
            verificationEvidence = result.verificationEvidence ?: "Execution succeeded with exit code 0"
        )
    }

    private fun extractAllSubCommands(rawCommand: String): List<String> {
        val chained = WreCommandParser.parseChained(rawCommand)
        val result = mutableListOf<String>()
        for ((stage, _) in chained) {
            if (stage.contains("|")) {
                val piped = WreCommandParser.parsePipeline(stage)
                for (p in piped) {
                    result.add(p.raw)
                }
            } else {
                result.add(stage)
            }
        }
        return if (result.isEmpty()) listOf(rawCommand) else result
    }

    private suspend fun logResult(result: ExecutionResult, provider: String, permissionDecision: String = "ALLOWED") {
        val verificationOutcome = when {
            result.verified -> "VERIFIED: ${result.verificationEvidence ?: "OK"}"
            result.status == ExecutionStatus.SUCCESS -> "UNVERIFIED"
            else -> "FAILED: ${result.stderr.ifBlank { "Exit code ${result.exitCode}" }}"
        }

        val logEntry = WreExecutionLog(
            executionId = result.executionId,
            provider = provider,
            command = result.command,
            workingDirectory = "/home/wasti",
            status = result.status,
            durationMs = result.durationMs,
            exitCode = result.exitCode,
            stdoutLength = result.stdout.length,
            stderrLength = result.stderr.length,
            permissionDecision = permissionDecision,
            verificationResult = verificationOutcome
        )
        executionLogger.log(logEntry)

        // Durable persistence to Room terminalSessionDao
        try {
            val db = WastiDatabase.getDatabase(context)
            db.terminalSessionDao().insertSessionEntry(
                TerminalSessionEntity(
                    id = result.executionId,
                    sessionId = "default",
                    command = result.command,
                    output = result.stdout,
                    stderr = result.stderr,
                    workingDirectory = "home/wasti",
                    status = result.status.name,
                    exitCode = result.exitCode,
                    durationMs = result.durationMs,
                    verified = result.verified,
                    verificationEvidence = result.verificationEvidence,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("WreManager", "Could not persist terminal session entry: ${e.message}")
        }
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
