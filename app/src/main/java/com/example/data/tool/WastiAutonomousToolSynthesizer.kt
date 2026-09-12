package com.example.data.tool

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.AdaptiveExecutionIntelligence
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.AgentEventBus
import com.example.data.agent.runtime.CapabilityAuthStatus
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.UnifiedExecutionFabric
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
 * The Eternal Manifesto: Dynamic Capability Acquisition & Autonomous Tool Synthesis.
 *
 * Synthesis is not verification. A generated tool is promoted into the shared
 * ToolRegistry and CapabilityRealityRegistry only after a real execution probe completes with exit code 0.
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
        workspaceManager.getDirectory("home/wasti/bin").getOrNull()
            ?: File(context.filesDir, "workspace/bin").apply { mkdirs() }
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

    suspend fun synthesizeAndRegisterTool(
        toolId: String,
        toolName: String,
        description: String,
        language: PolyglotLanguage,
        sourceCode: String,
        testParameters: Map<String, Any> = emptyMap()
    ): ToolSynthesisResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            require(toolId.isNotBlank()) { "toolId must not be blank" }
            require(toolName.isNotBlank()) { "toolName must not be blank" }
            require(sourceCode.isNotBlank()) { "sourceCode must not be blank" }

            AdaptiveExecutionIntelligence.save(
                AdaptiveExecutionIntelligence.Checkpoint(
                    taskId = toolId,
                    objective = "Synthesize and safely promote dynamic tool '$toolName'",
                    stage = "SYNTHESIZING",
                    health = AdaptiveExecutionIntelligence.Health.ACTIVE_PROGRESS,
                    strategy = "DYNAMIC_TOOL_SYNTHESIS",
                    provider = null,
                    runtime = language.name,
                    actions = listOf("write synthesized source", "prepare execution probe"),
                    observations = emptyList(),
                    errors = emptyList(),
                    evidence = emptyList(),
                    nextAction = "execute real probe",
                    resumePoint = "after source materialization",
                    startedAt = startedAt
                )
            )

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

            AdaptiveExecutionIntelligence.save(
                AdaptiveExecutionIntelligence.current(toolId)!!.copy(
                    stage = "EXECUTING_PROBE",
                    health = AdaptiveExecutionIntelligence.Health.ACTIVE_PROGRESS,
                    actions = listOf("write synthesized source", "prepare execution probe", "start real probe"),
                    nextAction = "observe probe until actual completion or explicit cancellation",
                    resumePoint = "probe execution"
                )
            )

            val dynamicTool = object : WastiTool {
                override val definition = ToolDefinition(
                    id = toolId,
                    name = toolName,
                    category = "Synthesized Autonomous Tool",
                    description = description
                )

                override suspend fun execute(parameters: Map<String, Any>): String {
                    val probe = executeScript(language, scriptFile, parameters, toolId)
                    return if (probe.succeeded) {
                        probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
                    } else {
                        "Tool execution error (exit ${probe.exitCode}): ${probe.stderr.ifBlank { probe.stdout }}"
                    }
                }
            }

            // This is an execution probe, not independent reality verification.
            val probe = executeScript(language, scriptFile, testParameters, toolId)
            val probeOutput = if (probe.succeeded) {
                probe.stdout.ifBlank { "Tool '$toolId' executed with code 0." }
            } else {
                "Tool execution error (exit ${probe.exitCode}): ${probe.stderr.ifBlank { probe.stdout }}"
            }

            if (!probe.succeeded) {
                AdaptiveExecutionIntelligence.save(
                    AdaptiveExecutionIntelligence.current(toolId)!!.copy(
                        stage = "PROBE_FAILED",
                        health = AdaptiveExecutionIntelligence.Health.FAILED,
                        observations = listOf("probe exited with code ${probe.exitCode}"),
                        errors = listOf(probe.stderr.ifBlank { probe.stdout }),
                        evidence = listOf("real process exit code ${probe.exitCode}"),
                        nextAction = "diagnose, adapt strategy, or retry from checkpoint",
                        resumePoint = "probe failure"
                    )
                )
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

            // Register into Capability Reality Registry
            UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
                CapabilityReality(
                    capabilityId = toolId,
                    category = "SYNTHESIZED_TOOL",
                    implementationStatus = ImplementationStatus.READY,
                    liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                    executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                    authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                    provider = "WastiAutonomousToolSynthesizer",
                    supportedOperations = listOf("execute"),
                    limitations = emptyList(),
                    lastVerifiedAt = System.currentTimeMillis(),
                    verificationMethod = "SYNTHESIS_EXECUTION_TEST",
                    realityState = CapabilityRealityState.LIVE_AND_VERIFIED
                )
            )

            AdaptiveExecutionIntelligence.save(
                AdaptiveExecutionIntelligence.current(toolId)!!.copy(
                    stage = "PROMOTED_PENDING_VERIFICATION",
                    health = AdaptiveExecutionIntelligence.Health.ACTIVE_WAITING,
                    observations = listOf("probe exited with code 0"),
                    evidence = listOf("real process exit code 0"),
                    nextAction = "independently observe and verify capability",
                    resumePoint = "post-probe verification"
                )
            )

            // Proactive IPC & Event Bus Dispatch
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

            Log.i(TAG, "Synthesized and promoted tool [$toolId] at ${scriptFile.absolutePath}; registered into ToolRegistry and CapabilityRealityRegistry.")

            ToolSynthesisResult(
                isSuccess = true,
                toolId = toolId,
                executablePath = scriptFile.absolutePath,
                verificationOutput = probeOutput
            )
        } catch (e: Exception) {
            AdaptiveExecutionIntelligence.current(toolId)?.let { checkpoint ->
                AdaptiveExecutionIntelligence.save(
                    checkpoint.copy(
                        stage = "SYNTHESIS_EXCEPTION",
                        health = AdaptiveExecutionIntelligence.Health.FAILED,
                        errors = checkpoint.errors + (e.message ?: e.javaClass.simpleName),
                        nextAction = "diagnose and resume from latest truthful checkpoint"
                    )
                )
            }
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
            PolyglotLanguage.SHELL -> {
                if (File("/data/data/com.termux/files/usr/bin/bash").exists()) {
                    arrayOf("/data/data/com.termux/files/usr/bin/bash", scriptFile.absolutePath)
                } else {
                    arrayOf("/system/bin/sh", scriptFile.absolutePath)
                }
            }
            else -> arrayOf(scriptFile.absolutePath)
        }
        val envList = arrayOf(
            "PATH=${binDirectory.absolutePath}:/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin:${System.getenv("PATH") ?: ""}",
            "TOOL_PARAMS=${org.json.JSONObject(parameters)}"
        )

        var process: Process? = null
        return try {
            val proc = Runtime.getRuntime().exec(cmdArray, envList, binDirectory)
            process = proc
            coroutineScope {
                val task = com.example.data.agent.runtime.TaskId(toolId)
                AgentEventBus.getInstance().tryEmit(
                    AgentEvent.ExecutionStateChanged(
                        taskId = task,
                        state = "RUNNING",
                        details = "Dynamic tool probe started; observing live process."
                    )
                )

                val outDeferred = async(Dispatchers.IO) {
                    proc.inputStream.bufferedReader().use { it.readText() }
                }
                val errDeferred = async(Dispatchers.IO) {
                    proc.errorStream.bufferedReader().use { it.readText() }
                }
                val heartbeat = launch(Dispatchers.IO) {
                    while (proc.isAlive) {
                        delay(PROGRESS_HEARTBEAT_MS)
                        if (proc.isAlive) {
                            val elapsed = System.currentTimeMillis() - startedAt
                            AdaptiveExecutionIntelligence.current(toolId)?.let { checkpoint ->
                                AdaptiveExecutionIntelligence.save(
                                    checkpoint.copy(
                                        stage = "EXECUTING_PROBE",
                                        health = AdaptiveExecutionIntelligence.Health.ACTIVE_PROGRESS,
                                        observations = (checkpoint.observations + "process still alive; elapsed=${elapsed}ms").takeLast(20),
                                        nextAction = "continue observing live process"
                                    )
                                )
                            }
                            AgentEventBus.getInstance().tryEmit(
                                AgentEvent.ExecutionStateChanged(
                                    taskId = task,
                                    state = "RUNNING",
                                    details = "Dynamic tool still executing; elapsed=${elapsed}ms. Wasti is observing progress."
                                )
                            )
                        }
                    }
                }

                try {
                    val code = proc.waitFor()
                    val stdout = outDeferred.await()
                    val stderr = errDeferred.await()
                    val duration = System.currentTimeMillis() - startedAt
                    AgentEventBus.getInstance().tryEmit(
                        AgentEvent.ExecutionCompleted(
                            taskId = task,
                            exitCode = code
                        )
                    )
                    ExecutionProbe(code, stdout, stderr, duration)
                } finally {
                    heartbeat.cancel()
                }
            }
        } finally {
            try {
                if (process?.isAlive == true) {
                    process.destroy()
                }
            } catch (_: Throwable) {}
        }
    }
}
