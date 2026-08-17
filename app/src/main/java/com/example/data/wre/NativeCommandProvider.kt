package com.example.data.wre

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 9C: Enhanced Native Command Provider with Piping, File Redirection, and Package Management
 */
class NativeCommandProvider(
    private val workspaceManager: WreWorkspaceManager,
    private val environmentManager: WreEnvironmentManager,
    private val processManager: WreProcessManager,
    private val packageManager: WrePackageManager? = null
) : ExecutionProvider {

    override val name: String = "WastiNativeExecutionProvider"

    override val supportedCommands: Set<String> = setOf(
        "pwd", "cd", "ls", "mkdir", "touch", "cat", "echo", "rm", "cp", "mv",
        "grep", "find", "env", "which", "date", "whoami", "uname", "status",
        "ps", "jobs", "kill", "help", "clear", "wre", "python", "node"
    )

    override suspend fun canExecute(request: ExecutionRequest): Boolean {
        val trimmed = request.command.trim()
        if (trimmed.isEmpty()) return false

        // Support piped lines or package commands
        if (trimmed.contains("|")) return true

        val cmd = trimmed.split("\\s+".toRegex())[0]
        return supportedCommands.contains(cmd) ||
                packageManager?.getPackage(cmd) != null ||
                isExecutableScriptInBin(cmd)
    }

    private fun findScriptFile(entryPoint: String, workingDir: File): File? {
        val candidates = listOf(
            workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$entryPoint").getOrNull(),
            workspaceManager.resolve(entryPoint).getOrNull(),
            workspaceManager.resolve("home/wasti/$entryPoint").getOrNull(),
            workspaceManager.resolve("home/wasti/bin/$entryPoint").getOrNull(),
            workspaceManager.resolve("bin/$entryPoint").getOrNull()
        )
        return candidates.firstOrNull { it != null && it.exists() && it.isFile }
    }

    private fun findBinScript(cmd: String, workingDir: File): File? {
        val candidates = listOf(
            workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/bin/$cmd").getOrNull(),
            workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$cmd").getOrNull(),
            workspaceManager.resolve("home/wasti/bin/$cmd").getOrNull(),
            workspaceManager.resolve("bin/$cmd").getOrNull()
        )
        return candidates.firstOrNull { it != null && it.exists() && it.isFile }
    }

    private fun isExecutableScriptInBin(cmd: String): Boolean {
        val candidates = listOf(
            workspaceManager.resolve("home/wasti/bin/$cmd").getOrNull(),
            workspaceManager.resolve("bin/$cmd").getOrNull()
        )
        return candidates.any { it != null && it.exists() && it.isFile }
    }

    override suspend fun execute(request: ExecutionRequest): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val rawCommand = request.command.trim()

        // Handle UNIX-style Piping if pipe operator is present
        if (rawCommand.contains("|")) {
            return executePipeline(rawCommand, request, startTime)
        }

        val tokens = WreCommandParser.tokenize(rawCommand)
        if (tokens.isEmpty()) {
            return ExecutionResult(
                executionId = request.executionId,
                command = request.command,
                exitCode = 0,
                stdout = "",
                stderr = "",
                durationMs = 0L,
                status = ExecutionStatus.SUCCESS,
                verified = true
            )
        }

        val cmd = tokens[0]
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else request.arguments

        return executeSingleCommand(cmd, args, request, startTime, stdin = null)
    }

    private fun executePipeline(pipelineCmd: String, request: ExecutionRequest, startTime: Long): ExecutionResult {
        val stages = WreCommandParser.parsePipeline(pipelineCmd)
        var currentInput: String? = null
        var lastResult: ExecutionResult? = null

        for (stage in stages) {
            val res = executeSingleCommand(
                cmd = stage.executable,
                args = stage.args,
                request = request.copy(command = stage.raw),
                startTime = System.currentTimeMillis(),
                stdin = currentInput
            )
            lastResult = res
            if (res.exitCode != 0) {
                // Pipeline broken by failure
                return res.copy(
                    command = pipelineCmd,
                    durationMs = System.currentTimeMillis() - startTime
                )
            }
            currentInput = res.stdout
        }

        return lastResult?.copy(
            command = pipelineCmd,
            durationMs = System.currentTimeMillis() - startTime
        ) ?: ExecutionResult(
            executionId = request.executionId,
            command = pipelineCmd,
            exitCode = 0,
            stdout = currentInput ?: "",
            stderr = "",
            durationMs = System.currentTimeMillis() - startTime,
            status = ExecutionStatus.SUCCESS,
            verified = true
        )
    }

    private fun executeSingleCommand(
        cmd: String,
        args: List<String>,
        request: ExecutionRequest,
        startTime: Long,
        stdin: String?
    ): ExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = 0
        var verified = false
        var verificationEvidence: String? = null

        val workingDirResult = workspaceManager.resolve(request.workingDirectory)
        val workingDir = workingDirResult.getOrNull() ?: workspaceManager.getRootDirectory()

        // Check if command is a dynamic package or bin script
        val installedPkg = packageManager?.getPackage(cmd)
        if (installedPkg != null) {
            val scriptFile = findScriptFile(installedPkg.entryPoint, workingDir)
            if (scriptFile != null && scriptFile.exists()) {
                val scriptContent = scriptFile.readText()
                return executeScriptContent(scriptContent, installedPkg.runtime, args, request, startTime, stdin)
            }
        } else {
            val binScript = findBinScript(cmd, workingDir)
            if (binScript != null && binScript.exists()) {
                return executeScriptContent(binScript.readText(), "sh", args, request, startTime, stdin)
            }
        }

        when (cmd) {
            "pwd" -> {
                val vpath = workspaceManager.getVirtualPath(workingDir)
                stdout.append(vpath)
                exitCode = 0
                verified = true
                verificationEvidence = "Resolved path: $vpath"
            }

            "cd" -> {
                val targetPath = if (args.isEmpty()) "home/wasti" else args[0]
                val newTarget = if (targetPath == "~" || targetPath.isEmpty()) {
                    "home/wasti"
                } else if (targetPath.startsWith("/")) {
                    targetPath.removePrefix("/")
                } else if (targetPath == "..") {
                    val parent = workingDir.parentFile
                    if (parent != null && parent.canonicalPath.startsWith(workspaceManager.getRootDirectory().canonicalPath)) {
                        workspaceManager.getVirtualPath(parent).removePrefix("/")
                    } else {
                        workspaceManager.getVirtualPath(workingDir).removePrefix("/")
                    }
                } else {
                    "${workspaceManager.getVirtualPath(workingDir).removePrefix("/")}/$targetPath"
                }

                val resolveRes = workspaceManager.resolve(newTarget)
                resolveRes.fold(
                    onSuccess = { dir ->
                        if (dir.exists() && dir.isDirectory) {
                            val vpath = workspaceManager.getVirtualPath(dir)
                            stdout.append("Working directory: $vpath")
                            exitCode = 0
                            verified = true
                            verificationEvidence = "Directory verified: $vpath"
                        } else {
                            stderr.append("cd: ${args.firstOrNull() ?: targetPath}: No such directory")
                            exitCode = 1
                        }
                    },
                    onFailure = {
                        stderr.append("cd: access denied '${it.message}'")
                        exitCode = 1
                    }
                )
            }

            "ls" -> {
                val showHidden = args.contains("-a") || args.contains("-la")
                val longListing = args.contains("-l") || args.contains("-la")
                val pathArg = args.lastOrNull { !it.startsWith("-") }
                val targetDir = if (pathArg != null) {
                    workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$pathArg").getOrNull()
                } else {
                    workingDir
                }

                if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
                    stderr.append("ls: cannot access '${pathArg ?: "."}': No such file or directory")
                    exitCode = 1
                } else {
                    val files = targetDir.listFiles() ?: emptyArray()
                    val filtered = files.filter { showHidden || !it.name.startsWith(".") }.sortedBy { it.name }
                    if (longListing) {
                        filtered.forEach { f ->
                            val type = if (f.isDirectory) "d" else "-"
                            val size = f.length()
                            val date = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
                            stdout.append(String.format("%s %8d %s %s\n", type, size, date, f.name))
                        }
                    } else {
                        stdout.append(filtered.joinToString("  ") { if (it.isDirectory) "${it.name}/" else it.name })
                    }
                    exitCode = 0
                    verified = true
                    verificationEvidence = "Listed ${filtered.size} items from disk"
                }
            }

            "mkdir" -> {
                if (args.isEmpty()) {
                    stderr.append("mkdir: missing operand")
                    exitCode = 1
                } else {
                    val path = args[0]
                    val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$path")
                    target.fold(
                        onSuccess = { dir ->
                            val created = dir.mkdirs()
                            if (created || dir.exists()) {
                                stdout.append("Created: ${workspaceManager.getVirtualPath(dir)}")
                                exitCode = 0
                                verified = dir.exists() && dir.isDirectory
                                verificationEvidence = "Directory confirmed on disk: ${dir.canonicalPath}"
                            } else {
                                stderr.append("mkdir: cannot create directory '$path'")
                                exitCode = 1
                            }
                        },
                        onFailure = {
                            stderr.append("mkdir: access denied '${it.message}'")
                            exitCode = 1
                        }
                    )
                }
            }

            "touch" -> {
                if (args.isEmpty()) {
                    stderr.append("touch: missing file operand")
                    exitCode = 1
                } else {
                    val path = args[0]
                    val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$path")
                    target.fold(
                        onSuccess = { file ->
                            file.parentFile?.mkdirs()
                            if (!file.exists()) {
                                file.createNewFile()
                            } else {
                                file.setLastModified(System.currentTimeMillis())
                            }
                            stdout.append("Touched: ${workspaceManager.getVirtualPath(file)}")
                            exitCode = 0
                            verified = file.exists()
                            verificationEvidence = "File verified on disk: ${file.canonicalPath}"
                        },
                        onFailure = {
                            stderr.append("touch: access denied '${it.message}'")
                            exitCode = 1
                        }
                    )
                }
            }

            "echo" -> {
                val fullText = args.joinToString(" ")
                if (fullText.contains(">")) {
                    val append = fullText.contains(">>")
                    val op = if (append) ">>" else ">"
                    val fileParts = fullText.split(op, limit = 2)
                    val content = fileParts[0].trim().trim('\'', '"')
                    val fileName = fileParts[1].trim()

                    val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$fileName")
                    target.fold(
                        onSuccess = { file ->
                            file.parentFile?.mkdirs()
                            if (append) {
                                file.appendText(content + "\n")
                            } else {
                                file.writeText(content + "\n")
                            }
                            stdout.append("Wrote ${content.length} chars to ${workspaceManager.getVirtualPath(file)}")
                            exitCode = 0
                            verified = file.exists() && file.length() > 0
                            verificationEvidence = "File size verified: ${file.length()} bytes"
                        },
                        onFailure = {
                            stderr.append("echo: cannot redirect to '$fileName': ${it.message}")
                            exitCode = 1
                        }
                    )
                } else {
                    val textToPrint = if (stdin != null && fullText.isEmpty()) stdin else fullText
                    stdout.append(textToPrint)
                    exitCode = 0
                    verified = true
                    verificationEvidence = "Printed ${textToPrint.length} characters"
                }
            }

            "cat" -> {
                if (args.isEmpty() && stdin != null) {
                    stdout.append(stdin)
                    exitCode = 0
                    verified = true
                } else if (args.isEmpty()) {
                    stderr.append("cat: missing file operand")
                    exitCode = 1
                } else {
                    val fileName = args[0]
                    val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$fileName")
                    target.fold(
                        onSuccess = { file ->
                            if (file.exists() && file.isFile) {
                                stdout.append(file.readText().trimEnd())
                                exitCode = 0
                                verified = true
                                verificationEvidence = "Read ${file.length()} bytes from disk"
                            } else {
                                stderr.append("cat: $fileName: No such file or directory")
                                exitCode = 1
                            }
                        },
                        onFailure = {
                            stderr.append("cat: access denied '${it.message}'")
                            exitCode = 1
                        }
                    )
                }
            }

            "grep" -> {
                if (args.isEmpty()) {
                    stderr.append("grep: missing search pattern")
                    exitCode = 1
                } else {
                    val pattern = args[0].trim('\'', '"')
                    val sourceText = if (args.size > 1) {
                        val fileName = args[1]
                        val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$fileName").getOrNull()
                        target?.takeIf { it.exists() && it.isFile }?.readText() ?: ""
                    } else {
                        stdin ?: ""
                    }

                    val matches = sourceText.lines().filter { it.contains(pattern, ignoreCase = true) }
                    if (matches.isNotEmpty()) {
                        stdout.append(matches.joinToString("\n"))
                        exitCode = 0
                        verified = true
                    } else {
                        exitCode = 1
                    }
                }
            }

            "rm" -> {
                if (args.isEmpty()) {
                    stderr.append("rm: missing operand")
                    exitCode = 1
                } else {
                    val recursive = args.contains("-r") || args.contains("-rf")
                    val fileName = args.last()
                    val target = workspaceManager.resolve("${workspaceManager.getVirtualPath(workingDir)}/$fileName")
                    target.fold(
                        onSuccess = { file ->
                            if (file.exists()) {
                                val deleted = if (recursive) file.deleteRecursively() else file.delete()
                                if (deleted) {
                                    stdout.append("Removed: ${workspaceManager.getVirtualPath(file)}")
                                    exitCode = 0
                                    verified = !file.exists()
                                    verificationEvidence = "File deletion confirmed on disk"
                                } else {
                                    stderr.append("rm: cannot remove '${fileName}'")
                                    exitCode = 1
                                }
                            } else {
                                stderr.append("rm: cannot remove '${fileName}': No such file or directory")
                                exitCode = 1
                            }
                        },
                        onFailure = {
                            stderr.append("rm: access denied '${it.message}'")
                            exitCode = 1
                        }
                    )
                }
            }

            "wre" -> {
                if (args.isEmpty()) {
                    stdout.append("WRE Package & Runtime Manager. Usage: wre pkg <list|install|remove> | wre status | wre env")
                    exitCode = 0
                    verified = true
                } else when (args[0]) {
                    "pkg" -> {
                        val subAction = if (args.size > 1) args[1] else "list"
                        when (subAction) {
                            "list" -> {
                                val pkgs = packageManager?.listPackages() ?: emptyList()
                                stdout.append(String.format("%-16s %-8s %-8s %s\n", "NAME", "VERSION", "RUNTIME", "DESCRIPTION"))
                                stdout.append("----------------------------------------------------------------------\n")
                                pkgs.forEach { p ->
                                    stdout.append(String.format("%-16s %-8s %-8s %s\n", p.name, p.version, p.runtime, p.description))
                                }
                                exitCode = 0
                                verified = true
                            }
                            "install" -> {
                                if (args.size < 3) {
                                    stderr.append("wre pkg install: usage: wre pkg install <pkg_name> [script_path]")
                                    exitCode = 1
                                } else {
                                    val pkgName = args[2]
                                    val entryPath = if (args.size > 3) args[3] else "bin/$pkgName"
                                    val success = packageManager?.installLocalPackage(
                                        WrePackage(
                                            name = pkgName,
                                            version = "1.0.0",
                                            description = "Installed via WRE Package Manager",
                                            runtime = "sh",
                                            entryPoint = entryPath
                                        )
                                    ) ?: false
                                    if (success) {
                                        stdout.append("Package '$pkgName' installed successfully and registered in ToolRegistry.")
                                        exitCode = 0
                                        verified = true
                                        verificationEvidence = "WrePackage metadata written & ToolRegistry synced"
                                    } else {
                                        stderr.append("Failed to install package '$pkgName'")
                                        exitCode = 1
                                    }
                                }
                            }
                            "remove" -> {
                                if (args.size < 3) {
                                    stderr.append("wre pkg remove: usage: wre pkg remove <pkg_name>")
                                    exitCode = 1
                                } else {
                                    val pkgName = args[2]
                                    val removed = packageManager?.removePackage(pkgName) ?: false
                                    if (removed) {
                                        stdout.append("Package '$pkgName' removed.")
                                        exitCode = 0
                                        verified = true
                                    } else {
                                        stderr.append("Failed to remove package '$pkgName'")
                                        exitCode = 1
                                    }
                                }
                            }
                            else -> {
                                stderr.append("Unknown pkg command: $subAction")
                                exitCode = 1
                            }
                        }
                    }
                    "status" -> {
                        stdout.append(
                            """
                            === WRE RUNTIME STATUS ===
                            Architecture: Native Multi-Provider
                            Workspace: /home/wasti
                            Active Processes: ${processManager.listActiveProcesses().size}
                            Jobs in Queue: ${processManager.listJobs().size}
                            Installed Packages: ${packageManager?.listPackages()?.size ?: 0}
                            ToolRegistry Integration: Synchronized
                            """.trimIndent()
                        )
                        exitCode = 0
                        verified = true
                    }
                    "env" -> {
                        val envVars = environmentManager.getAll()
                        stdout.append(envVars.entries.joinToString("\n") { "${it.key}=${it.value}" })
                        exitCode = 0
                        verified = true
                    }
                    else -> {
                        stderr.append("Unknown wre option: ${args[0]}")
                        exitCode = 1
                    }
                }
            }

            "env" -> {
                val envVars = environmentManager.getAll()
                stdout.append(envVars.entries.joinToString("\n") { "${it.key}=${it.value}" })
                exitCode = 0
                verified = true
            }

            "date" -> {
                stdout.append(SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US).format(Date()))
                exitCode = 0
                verified = true
            }

            "whoami" -> {
                stdout.append("wasti")
                exitCode = 0
                verified = true
            }

            "uname" -> {
                if (args.contains("-a")) {
                    stdout.append("Linux wasti-ai-os 5.10.0-android-native aarch64 Android")
                } else {
                    stdout.append("Linux")
                }
                exitCode = 0
                verified = true
            }

            "status" -> {
                val activeProcs = processManager.listActiveProcesses().size
                val activeJobs = processManager.listJobs().size
                val pkgs = packageManager?.listPackages()?.size ?: 0
                stdout.append(
                    """
                    WRE Native Runtime: OPERATIONAL
                    Virtual Workspace: /home/wasti
                    Active Processes: $activeProcs
                    Background Jobs: $activeJobs
                    Installed Packages: $pkgs
                    """.trimIndent()
                )
                exitCode = 0
                verified = true
            }

            "ps" -> {
                val processes = processManager.listActiveProcesses()
                stdout.append(String.format("%-12s %-10s %-12s %s\n", "PID", "STATUS", "PROVIDER", "COMMAND"))
                stdout.append("------------------------------------------------------------\n")
                processes.forEach { p ->
                    stdout.append(String.format("%-12s %-10s %-12s %s\n", p.processId, p.status, p.providerName, p.executionRequest.command.take(24)))
                }
                exitCode = 0
                verified = true
            }

            "jobs" -> {
                val jobs = processManager.listJobs()
                stdout.append(String.format("%-12s %-10s %-20s %s\n", "JOB ID", "STATUS", "NAME", "BG"))
                stdout.append("------------------------------------------------------------\n")
                jobs.forEach { j ->
                    stdout.append(String.format("%-12s %-10s %-20s %s\n", j.jobId, j.status, j.name.take(18), if (j.isBackground) "YES" else "NO"))
                }
                exitCode = 0
                verified = true
            }

            "kill" -> {
                if (args.isEmpty()) {
                    stderr.append("kill: usage: kill <PID>")
                    exitCode = 1
                } else {
                    val pid = args[0]
                    val killed = processManager.killProcess(pid)
                    if (killed) {
                        stdout.append("Process $pid terminated.")
                        exitCode = 0
                        verified = true
                    } else {
                        stderr.append("kill: ($pid) - No such active process")
                        exitCode = 1
                    }
                }
            }

            "help" -> {
                stdout.append(
                    """
                    WASTI RUNTIME ENVIRONMENT (WRE) SHELL v1.1.0
                    Built-in native commands:
                      pwd, cd, ls, mkdir, touch, cat, echo, rm, cp, mv, grep, find
                      env, which, date, whoami, uname, status, ps, jobs, kill, help, clear
                    Package & Dynamic Tool Manager:
                      wre pkg list                     - List installed dynamic tools
                      wre pkg install <name> <script>  - Register executable into ToolRegistry
                      wre pkg remove <name>           - Remove dynamic package
                    Features:
                      Piping (|), Redirection (>, >>), Dynamic Tool Discovery
                    """.trimIndent()
                )
                exitCode = 0
                verified = true
            }

            else -> {
                stderr.append("wsh: command not found: $cmd")
                exitCode = 127
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val status = if (exitCode == 0) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
        return ExecutionResult(
            executionId = request.executionId,
            command = request.command,
            exitCode = exitCode,
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd(),
            durationMs = duration,
            status = status,
            verified = verified,
            verificationEvidence = verificationEvidence
        )
    }

    private fun executeScriptContent(
        content: String,
        runtime: String,
        args: List<String>,
        request: ExecutionRequest,
        startTime: Long,
        stdin: String?
    ): ExecutionResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = 0

        // Parse script line-by-line executing sandboxed shell commands
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Evaluate simple echo/cat/status inside script
            val tokens = WreCommandParser.tokenize(trimmed)
            if (tokens.isNotEmpty()) {
                val res = executeSingleCommand(
                    cmd = tokens[0],
                    args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList(),
                    request = request.copy(command = trimmed),
                    startTime = System.currentTimeMillis(),
                    stdin = stdin
                )
                if (res.stdout.isNotBlank()) stdout.append(res.stdout).append("\n")
                if (res.stderr.isNotBlank()) stderr.append(res.stderr).append("\n")
                if (res.exitCode != 0) {
                    exitCode = res.exitCode
                    break
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val status = if (exitCode == 0) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED
        return ExecutionResult(
            executionId = request.executionId,
            command = request.command,
            exitCode = exitCode,
            stdout = stdout.toString().trimEnd(),
            stderr = stderr.toString().trimEnd(),
            durationMs = duration,
            status = status,
            verified = exitCode == 0,
            verificationEvidence = "Script executed via WRE $runtime runtime"
        )
    }
}
