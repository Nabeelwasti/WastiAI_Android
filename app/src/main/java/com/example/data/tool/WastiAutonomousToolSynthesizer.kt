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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Eternal Manifesto: Dynamic Capability Acquisition.
 *
 * Synthesis is not verification. A generated tool is promoted into the shared
 * ToolRegistry only after a real execution probe completes with exit code 0.
 *
 * Execution has no fixed wall-clock kill switch here. Long-running work is
 * allowed to continue while Wasti observes it and publishes truthful progress.
 * Cancellation remains an explicit user/system action, not an automatic timeout.
 */
class WastiAutonomousToolSynthesizer(
    private val context: Context,
    private val workspaceManager: WreWorkspaceManager
) {

    companion object {
        private const val TAG = "ToolSynthesizer"
        private const val PROGRESS_HEARTBEAT_MS = 5_000L
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
        val durationMs: Long
    ) {
        val succeeded: Boolean get() = exitCode == 0
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
                    val probe = executeScript(language, scriptFile, parameters, toolId)
                    return when {
                        probe.succeeded -> probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
                        else -> "Tool execution error (exit ${probe.exitCode}): ${probe.stderr.ifBlank { probe.stdout }}"
                    }
                }
            }

            // This is an execution probe, not independent reality verification.
            val probe = executeScript(language, scriptFile, testParameters, toolId)
            val probeOutput = when {
                probe.succeeded -> probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
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
        parameters: Map<String, Any>,
        toolId: String
    ): ExecutionProbe {
        val startedAt = System.currentTimeMillis()
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

        val process = Runtime.getRuntime().exec(cmdArray, envList, binDirectory)
        WastiEventBus.tryEmit(WastiEvent.ExecutionStateChanged(
            taskId = toolId,
            state = "RUNNING",
            details = "Dynamic tool probe started; no fixed execution timeout is applied."
        ))

        return coroutineScope {
            val outDeferred = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val errDeferred = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val heartbeat = launch(Dispatchers.IO) {
                while (process.isAlive) {
                    delay(PROGRESS_HEARTBEAT_MS)
                    if (process.isAlive) {
                        val elapsed = System.currentTimeMillis() - startedAt
                        AgentEventBus.getInstance().tryEmit(
                            AgentEvent.ExecutionStateChanged(
                                taskId = com.example.data.agent.runtime.TaskId(toolId),
                                state = "RUNNING",
                                details = "Dynamic tool still executing; elapsed=${elapsed}ms. Wasti is observing rather than terminating it."
                            )
                        )
                    }
                }
            }

            try {
                val code = process.waitFor()
                val stdout = outDeferred.await()
                val stderr = errDeferred.await()
                val duration = System.currentTimeMillis() - startedAt
                AgentEventBus.getInstance().tryEmit(
                    AgentEvent.ExecutionCompleted(
                        taskId = com.example.data.agent.runtime.TaskId(toolId),
                        exitCode = code
                    )
                )
                ExecutionProbe(code, stdout, stderr, duration)
            } finally {
                heartbeat.cancel()
            }
        }
    }
}
