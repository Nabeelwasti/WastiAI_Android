package com.example.data.transport

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.CapabilityAuthStatus
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.ExecutionMode
import com.example.data.agent.runtime.ImplementationStatus
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.GlobalExecutionContext
import com.example.data.core.WastiOSRuntime
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 10: Transport Event Listener for real-time multi-interface synchronization.
 */
fun interface TransportEventListener {
    fun onContextUpdated(context: GlobalExecutionContext)
}

/**
 * Stage 10: Canonical Cross-Interface Transport Layer.
 *
 * Provides a secure, zero-overhead bridge connecting every interface
 * (Chat, Floating Bubble, Voice, Terminal, Local Server HTTP/WebSocket, Web Companion, Background Workers)
 * into the single Wasti OS Brain (WastiOSRuntime).
 *
 * No interface gets its own brain.
 */
class WastiCommandTransport(
    private val runtime: WastiOSRuntime = WastiOSRuntime.getInstance(),
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "WastiCommandTransport"

        @Volatile
        private var instance: WastiCommandTransport? = null

        fun getInstance(context: Context? = null): WastiCommandTransport {
            return instance ?: synchronized(this) {
                instance ?: WastiCommandTransport(
                    runtime = WastiOSRuntime.getInstance(context),
                    context = context ?: com.example.WastiApplication.instance
                ).also { instance = it }
            }
        }
    }

    private val listeners = CopyOnWriteArrayList<TransportEventListener>()
    private val authenticatedSessions = ConcurrentHashMap<String, Long>()
    private var defaultLocalToken: String = "wasti-local-secure-token-${System.currentTimeMillis() % 100000}"

    val activeContext: StateFlow<GlobalExecutionContext> = runtime.activeContext
    val executionHistory = runtime.executionHistory

    init {
        registerTransportCapabilities()
    }

    private fun registerTransportCapabilities() {
        UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
            CapabilityReality(
                capabilityId = "CROSS_INTERFACE_TRANSPORT",
                category = "TRANSPORT",
                implementationStatus = ImplementationStatus.READY,
                liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                authenticationStatus = CapabilityAuthStatus.AUTHENTICATED,
                provider = "WastiCommandTransport",
                supportedOperations = listOf(
                    "dispatch_chat", "dispatch_terminal", "dispatch_floating_bubble",
                    "dispatch_voice", "dispatch_local_server", "dispatch_web_companion"
                ),
                limitations = listOf("Requires valid origin and security token validation"),
                realityState = CapabilityRealityState.NATIVE
            )
        )
    }

    /**
     * Transport Security Gate: Validates request origin, IP address, and security tokens.
     */
    fun validateRequestSecurity(
        origin: CommandOrigin,
        clientHost: String = "127.0.0.1",
        authToken: String? = null
    ): Boolean {
        // 1. Local device native interfaces (Chat, Terminal, Bubble, Voice, System) are trusted locally
        if (origin.isLocal && (clientHost == "127.0.0.1" || clientHost == "localhost" || clientHost == "::1")) {
            return true
        }

        // 2. External Web Companion or Network HTTP calls require token or authenticated session
        if (authToken != null && (authToken == defaultLocalToken || authenticatedSessions.containsKey(authToken))) {
            return true
        }

        Log.w(TAG, "Transport security rejected command from origin $origin, host=$clientHost")
        return false
    }

    /**
     * Dispatch a user/system command from any interface room directly into WastiOSRuntime.
     */
    fun dispatchCommand(
        command: String,
        origin: CommandOrigin,
        executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
        targetAgentId: String = "ceo_agent",
        parameters: Map<String, Any> = emptyMap(),
        clientHost: String = "127.0.0.1",
        authToken: String? = null
    ): CommandSubmissionResult {
        if (!validateRequestSecurity(origin, clientHost, authToken)) {
            return CommandSubmissionResult.Rejected(
                commandId = "",
                origin = origin,
                reason = "TRANSPORT_SECURITY_DENIED: Unauthenticated request from $clientHost"
            )
        }

        return runtime.submitCommand(
            command = command,
            origin = origin,
            executionMode = executionMode,
            targetAgentId = targetAgentId,
            parameters = parameters
        )
    }

    fun addListener(listener: TransportEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TransportEventListener) {
        listeners.remove(listener)
    }

    fun cancelActiveExecution(reason: String = "Cancelled by transport client"): Boolean {
        return runtime.cancelActiveExecution(reason)
    }

    fun triggerEmergencyStop(reason: String = "Emergency stop initiated via transport") {
        runtime.triggerEmergencyStop(reason)
    }

    fun clearEmergencyStop() {
        runtime.clearEmergencyStop()
    }

    fun generateSessionToken(): String {
        val token = "wasti-session-${java.util.UUID.randomUUID()}"
        authenticatedSessions[token] = System.currentTimeMillis()
        return token
    }
}
