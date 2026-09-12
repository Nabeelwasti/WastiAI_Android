package com.example.data.tool

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.AgentEventBus
import com.example.data.bus.WastiEvent
import com.example.data.bus.WastiEventBus
import com.example.data.wre.PolyglotLanguage
import com.example.data.wre.WreWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File

/**
 * The Eternal Manifesto: Dynamic Capability Acquisition.
 *
 * Synthesis is not verification. A generated tool is promoted into the shared
 * ToolRegistry only after a real execution probe completes with exit code 0.
 * Timeout/failure is fail-closed and never emits a successful synthesis event.
 */
class WastiAutonomousToolSynthesizer(
    private val context: Context,
    private val workspaceManager: WreWorkspaceManager
) {

    companion object {
        private const val TAG = "ToolSynthesizer"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    private val binDirectory: File by lazy {
        File(context.filesDir, "workspace/bin").apply { mkdirs() }
    }

    data class ToolSynthesisResult(
        val isSuccess: Boolean,
        val toolId: String,
        val executablePath: String,
        val verificationOutput: String,
        val errorMessage: String? = null
    )

    private data class ExecutionProbe(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    ) {
        val succeeded: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * Synthesizes a tool, probes it against the supplied test parameters, and
     * registers it only when the real process exits successfully.
     */
    suspend fun synthesizeAndRegisterTool(
        toolId: String,
        toolName: String,
        description: String,
        language: PolyglotLanguage,
        sourceCode: String,
        testParameters: Map<String, Any> = emptyMap()
    ): ToolSynthesisResult = withContext(Dispatchers.IO) {
        try {
            require(toolId.isNotBlank()) { "toolId must not be blank" }
            require(toolName.isNotBlank()) { "toolName must not be blank" }
            require(sourceCode.isNotBlank()) { "sourceCode must not be blank" }

            val fileName = when (language) {
                PolyglotLanguage.PYTHON -> "$toolId.py"
                PolyglotLanguage.NODE_JAVASCRIPT -> "$toolId.js"
                PolyglotLanguage.SHELL -> "$toolId.sh"
                else -> "$toolId.bin"
            }

            val scriptFile = File(binDirectory, fileName).canonicalFile
            require(scriptFile.parentFile == binDirectory.canonicalFile) { "Invalid synthesized tool path" }
            scriptFile.writeText(sourceCode)
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)

            val dynamicTool = object : WastiTool {
                override val definition = ToolDefinition(
                    id = toolId,
                    name = toolName,
                    category = "Synthesized Autonomous Tool",
                    description = description
                )

                override suspend fun execute(parameters: Map<String, Any>): String {
                    val probe = executeScript(language, scriptFile, parameters)
                    return when {
                        probe.succeeded -> probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
                        probe.timedOut -> "Error: Tool execution timed out after ${DEFAULT_TIMEOUT_MS / 1000}s."
                        else -> "Tool execution error (exit ${probe.exitCode}): ${probe.stderr.ifBlank { probe.stdout }}"
                    }
                }
            }

            // This is an execution probe, not independent reality verification.
            val probe = executeScript(language, scriptFile, testParameters)
            val probeOutput = when {
                probe.succeeded -> probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
                probe.timedOut -> "Error: Tool execution timed out after ${DEFAULT_TIMEOUT_MS / 1000}s."
                else -> "Tool execution error (exit ${probe.exitCode}): ${probe.stderr.ifBlank { probe.stdout }}"
            }

            if (!probe.succeeded) {
                Log.w(TAG, "Dynamic tool [$toolId] was synthesized but NOT promoted: $probeOutput")
                return@withContext ToolSynthesisResult(
                    isSuccess = false,
                    toolId = toolId,
                    executablePath = scriptFile.absolutePath,
                    verificationOutput = probeOutput,
                    errorMessage = probeOutput
                )
            }

            ToolRegistry.registerTool(dynamicTool)

            WastiEventBus.tryEmit(
                WastiEvent.ToolSynthesized(
                    toolId = toolId,
                    toolName = toolName,
                    language = language.name,
                    executablePath = scriptFile.absolutePath
                )
            )
            AgentEventBus.getInstance().tryEmit(
                AgentEvent.DynamicToolSynthesized(
                    toolId = toolId,
                    toolName = toolName,
                    language = language.name,
                    executablePath = scriptFile.absolutePath
                )
            )

            Log.i(TAG, "Synthesized and promoted tool [$toolId] at ${scriptFile.absolutePath}; independent verification remains required.")

            ToolSynthesisResult(
                isSuccess = true,
                toolId = toolId,
                executablePath = scriptFile.absolutePath,
                verificationOutput = probeOutput
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synthesize dynamic tool $toolId", e)
            ToolSynthesisResult(
                isSuccess = false,
                toolId = toolId,
                executablePath = "",
                verificationOutput = "",
                errorMessage = e.message
            )
        }
    }

    private suspend fun executeScript(
        language: PolyglotLanguage,
        scriptFile: File,
        parameters: Map<String, Any>
    ): ExecutionProbe {
        var process: Process? = null
        return try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                coroutineScope {
                    val cmdArray = when (language) {
                        PolyglotLanguage.PYTHON -> arrayOf("python3", scriptFile.absolutePath)
                        PolyglotLanguage.NODE_JAVASCRIPT -> arrayOf("node", scriptFile.absolutePath)
                        PolyglotLanguage.SHELL -> arrayOf("/system/bin/sh", scriptFile.absolutePath)
                        else -> arrayOf(scriptFile.absolutePath)
                    }

                    val envList = arrayOf(
                        "PATH=${binDirectory.absolutePath}:/system/bin:/system/xbin:${System.getenv("PATH") ?: ""}",
                        "TOOL_PARAMS=${org.json.JSONObject(parameters)}"
                    )

                    process = Runtime.getRuntime().exec(cmdArray, envList, binDirectory)
                    val runningProcess = process ?: error("Failed to create process")
                    val outDeferred = async(Dispatchers.IO) {
                        runningProcess.inputStream.bufferedReader().use { it.readText() }
                    }
                    val errDeferred = async(Dispatchers.IO) {
                        runningProcess.errorStream.bufferedReader().use { it.readText() }
                    }
                    val code = runningProcess.waitFor()
                    ExecutionProbe(
                        exitCode = code,
                        stdout = outDeferred.await(),
                        stderr = errDeferred.await()
                    )
                }
            }
        } catch (_: TimeoutCancellationException) {
            process?.runCatching { destroyForcibly() }
            ExecutionProbe(
                exitCode = null,
                stdout = "",
                stderr = "Process forcibly terminated after ${DEFAULT_TIMEOUT_MS / 1000}s timeout.",
                timedOut = true
            )
        }
    }
}
