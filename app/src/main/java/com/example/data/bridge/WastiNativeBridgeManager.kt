package com.example.data.bridge

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.CapabilityAuthStatus
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionResult
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.wre.ExecutionRequest
import com.example.data.wre.ExecutionStatus
import com.example.data.wre.WreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class NativeBridgeExecutionResult(
    val isSuccess: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val executionDurationMs: Long,
    val bridgeType: String,
    val verificationEvidence: String? = null
)

/**
 * Stage 10: Canonical Wasti Native Bridge Manager.
 * Governs Python (Chaquopy / Embedded Python) and Termux native environment execution bridges.
 */
class WastiNativeBridgeManager(
    private val context: Context? = null
) {
    private val wreManager: WreManager by lazy {
        val ctx = context ?: com.example.WastiApplication.instance
        if (ctx != null) WreManager.getInstance(ctx) else WreManager(com.example.WastiApplication.instance ?: throw IllegalStateException("Context required for WreManager"))
    }

    init {
        registerBridgeCapabilities()
    }

    private fun registerBridgeCapabilities() {
        // Python Runtime Capability
        val pythonBinaryExists = File("/system/bin/python3").exists() || File("/system/bin/python").exists()
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "PYTHON_BRIDGE",
                category = "RUNTIME_BRIDGE",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = if (pythonBinaryExists) LiveConnectionStatus.VERIFIED else LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiNativeBridgeManager",
                supportedOperations = listOf("execute_python", "run_script", "pip_install"),
                limitations = listOf("Executes via sandboxed WRE or Python interpreter"),
                realityState = if (pythonBinaryExists) CapabilityRealityState.NATIVE else CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            )
        )

        // Termux Bridge Capability
        val termuxExists = File("/data/data/com.termux").exists()
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "TERMUX_BRIDGE",
                category = "RUNTIME_BRIDGE",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = if (termuxExists) LiveConnectionStatus.VERIFIED else LiveConnectionStatus.NOT_VERIFIED,
                executionStatus = if (termuxExists) CapabilityExecutionStatus.OPERATIONAL else CapabilityExecutionStatus.DEGRADED,
                authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                provider = "WastiNativeBridgeManager",
                supportedOperations = listOf("execute_termux_command", "run_pkg", "apt"),
                limitations = listOf("Requires Termux app and permissions on Android host"),
                realityState = if (termuxExists) CapabilityRealityState.LIVE_CONNECTED else CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED
            )
        )
    }

    suspend fun executePythonScript(
        scriptContent: String,
        arguments: List<String> = emptyList(),
        timeoutMs: Long = 30000L
    ): NativeBridgeExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Write script to WRE workspace
        val scriptName = "wasti_py_${System.currentTimeMillis() % 10000}.py"
        val homeDir = wreManager.workspaceManager.getHomeDirectory()
        val scriptFile = File(homeDir, scriptName)

        try {
            scriptFile.writeText(scriptContent)

            val req = ExecutionRequest(
                command = "python3",
                arguments = listOf(scriptFile.absolutePath) + arguments,
                timeoutMs = timeoutMs,
                initiatedBy = "WastiNativeBridgeManager:Python"
            )
            val res = wreManager.execute(req)
            val duration = System.currentTimeMillis() - startTime

            // Cleanup script file
            if (scriptFile.exists()) scriptFile.delete()

            NativeBridgeExecutionResult(
                isSuccess = res.status == ExecutionStatus.SUCCESS,
                stdout = res.stdout,
                stderr = res.stderr,
                exitCode = res.exitCode,
                executionDurationMs = duration,
                bridgeType = "PYTHON_WRE_BRIDGE",
                verificationEvidence = if (res.status == ExecutionStatus.SUCCESS) "Python script exited with code 0 (exitCode=${res.exitCode})" else "Python script failed with code ${res.exitCode}"
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            if (scriptFile.exists()) scriptFile.delete()
            NativeBridgeExecutionResult(
                isSuccess = false,
                stdout = "",
                stderr = e.localizedMessage ?: "Python execution exception",
                exitCode = -1,
                executionDurationMs = duration,
                bridgeType = "PYTHON_WRE_BRIDGE"
            )
        }
    }

    suspend fun executeTermuxCommand(
        command: String,
        arguments: List<String> = emptyList(),
        timeoutMs: Long = 30000L
    ): NativeBridgeExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val req = ExecutionRequest(
            command = command,
            arguments = arguments,
            timeoutMs = timeoutMs,
            initiatedBy = "WastiNativeBridgeManager:Termux"
        )
        val res = wreManager.execute(req)
        val duration = System.currentTimeMillis() - startTime

        NativeBridgeExecutionResult(
            isSuccess = res.status == ExecutionStatus.SUCCESS,
            stdout = res.stdout,
            stderr = res.stderr,
            exitCode = res.exitCode,
            executionDurationMs = duration,
            bridgeType = "TERMUX_CLI_BRIDGE",
            verificationEvidence = "Termux command '$command' exited with ${res.exitCode}"
        )
    }

    companion object {
        @Volatile
        private var instance: WastiNativeBridgeManager? = null

        fun getInstance(context: Context? = null): WastiNativeBridgeManager {
            return instance ?: synchronized(this) {
                instance ?: WastiNativeBridgeManager(context).also { instance = it }
            }
        }
    }
}
