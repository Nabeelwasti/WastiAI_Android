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
import com.example.data.node.NodeConnectionState
import com.example.data.node.NodePlatform
import com.example.data.node.NodeTrustState
import com.example.data.node.WastiNode
import com.example.data.node.WastiNodeManager
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 11/12: Companion Device Model with Explicit Trust State.
 */
data class PairedCompanionDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: NodePlatform,
    val sessionToken: String,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val capabilities: Set<String> = emptySet(),
    val trustState: NodeTrustState = NodeTrustState.PAIRED,
    val isRevoked: Boolean = false
)

/**
 * Stage 11: Short-lived Pairing Challenge Code.
 */
data class PairingChallenge(
    val code: String,
    val deviceId: String,
    val deviceName: String,
    val platform: NodePlatform,
    val expiresAt: Long = System.currentTimeMillis() + 5 * 60 * 1000L, // 5 minutes
    var isClaimed: Boolean = false
)

/**
 * Stage 11: Idempotency Record for duplicate submission protection.
 */
data class IdempotencyRecord(
    val requestId: String,
    val commandHash: String,
    val result: CommandSubmissionResult,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stage 10/11: Transport Event Listener for real-time multi-interface synchronization.
 */
fun interface TransportEventListener {
    fun onContextUpdated(context: GlobalExecutionContext)
}

/**
 * Stage 11: Canonical Cross-Interface & Multi-Device Transport Layer.
 *
 * Provides a secure, zero-overhead bridge connecting every interface
 * (Chat, Floating Bubble, Voice, Terminal, Local Server HTTP/WebSocket, Web Companion, Desktop, Remote Nodes)
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
    private val pairedDevices = ConcurrentHashMap<String, PairedCompanionDevice>()
    private val pendingPairingChallenges = ConcurrentHashMap<String, PairingChallenge>()
    private val idempotencyCache = ConcurrentHashMap<String, IdempotencyRecord>()
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
                    "dispatch_voice", "dispatch_local_server", "dispatch_web_companion",
                    "dispatch_desktop_companion", "device_pairing", "idempotent_dispatch"
                ),
                limitations = listOf("Requires valid origin and security token validation"),
                realityState = CapabilityRealityState.NATIVE
            )
        )
    }

    // ==========================================
    // STAGE 11: DEVICE PAIRING & AUTHENTICATION
    // ==========================================

    /**
     * Creates a secure 6-character one-time pairing code for a new companion device.
     */
    fun createPairingChallenge(
        deviceId: String,
        deviceName: String,
        platform: NodePlatform = NodePlatform.WEB
    ): PairingChallenge {
        val randomNum = (100000..999999).random()
        val code = "PAIR-$randomNum"
        val challenge = PairingChallenge(
            code = code,
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform
        )
        pendingPairingChallenges[code] = challenge
        Log.i(TAG, "Created pairing challenge $code for device $deviceName ($deviceId)")
        return challenge
    }

    /**
     * Verifies pairing code and generates a permanent session token for the companion device.
     */
    fun verifyPairingChallenge(
        code: String,
        deviceId: String,
        endpointUrl: String? = null
    ): PairedCompanionDevice? {
        val challenge = pendingPairingChallenges[code] ?: run {
            Log.w(TAG, "Pairing failed: Code $code not found")
            return null
        }

        if (challenge.deviceId != deviceId) {
            Log.w(TAG, "Pairing failed: DeviceId mismatch for code $code")
            return null
        }

        if (System.currentTimeMillis() > challenge.expiresAt) {
            pendingPairingChallenges.remove(code)
            Log.w(TAG, "Pairing failed: Code $code expired")
            return null
        }

        challenge.isClaimed = true
        pendingPairingChallenges.remove(code)

        val sessionToken = "wasti-dev-sess-${java.util.UUID.randomUUID()}"
        val paired = PairedCompanionDevice(
            deviceId = deviceId,
            deviceName = challenge.deviceName,
            platform = challenge.platform,
            sessionToken = sessionToken,
            capabilities = setOf("remote_command", "event_stream", "sync_state"),
            trustState = NodeTrustState.ACTIVE
        )

        pairedDevices[deviceId] = paired
        authenticatedSessions[sessionToken] = System.currentTimeMillis()

        // Register in WastiNodeManager
        WastiNodeManager.getInstance().registerNode(
            WastiNode(
                nodeId = deviceId,
                nodeName = challenge.deviceName,
                platform = challenge.platform,
                capabilities = paired.capabilities,
                connectionState = NodeConnectionState.CONNECTED,
                trustState = NodeTrustState.ACTIVE,
                isLocal = false,
                endpointUrl = endpointUrl
            )
        )

        Log.i(TAG, "Device successfully paired: ${challenge.deviceName} ($deviceId)")
        return paired
    }

    fun getPairedDevices(): List<PairedCompanionDevice> {
        return pairedDevices.values.filter { !it.isRevoked }
    }

    fun revokeDevice(deviceId: String): Boolean {
        val device = pairedDevices[deviceId] ?: return false
        pairedDevices[deviceId] = device.copy(isRevoked = true, trustState = NodeTrustState.REVOKED)
        authenticatedSessions.remove(device.sessionToken)
        WastiNodeManager.getInstance().unregisterNode(deviceId)
        Log.i(TAG, "Revoked paired device: ${device.deviceName} ($deviceId)")
        return true
    }

    /**
     * Transport Security Gate: Validates request origin, IP address, and security tokens.
     */
    fun validateRequestSecurity(
        origin: CommandOrigin,
        clientHost: String = "127.0.0.1",
        authToken: String? = null,
        deviceId: String? = null
    ): Boolean {
        // 1. Local device native interfaces (Chat, Terminal, Bubble, Voice, System) are trusted locally
        if (origin.isLocal && (clientHost == "127.0.0.1" || clientHost == "localhost" || clientHost == "::1")) {
            return true
        }

        // 2. Token-authenticated sessions
        if (authToken != null) {
            if (authToken == defaultLocalToken || authenticatedSessions.containsKey(authToken)) {
                return true
            }
        }

        // 3. Paired companion devices
        if (deviceId != null && authToken != null) {
            val dev = pairedDevices[deviceId]
            if (dev != null && !dev.isRevoked && (dev.trustState == NodeTrustState.ACTIVE || dev.trustState == NodeTrustState.PAIRED) && dev.sessionToken == authToken) {
                pairedDevices[deviceId] = dev.copy(lastSeenAt = System.currentTimeMillis(), trustState = NodeTrustState.ACTIVE)
                return true
            }
        }

        Log.w(TAG, "Transport security rejected command from origin $origin, host=$clientHost, deviceId=$deviceId")
        return false
    }

    /**
     * Dispatch a user/system command from any interface room directly into WastiOSRuntime with Idempotency.
     */
    fun dispatchCommand(
        command: String,
        origin: CommandOrigin,
        executionMode: ExecutionMode = ExecutionMode.AUTONOMOUS,
        targetAgentId: String = "ceo_agent",
        parameters: Map<String, Any> = emptyMap(),
        clientHost: String = "127.0.0.1",
        authToken: String? = null,
        deviceId: String? = null,
        requestId: String? = null,
        correlationId: String? = null
    ): CommandSubmissionResult {
        // 1. Idempotency Check
        val effectiveReqId = requestId ?: correlationId
        if (effectiveReqId != null) {
            val cached = idempotencyCache[effectiveReqId]
            if (cached != null) {
                // If within 5 minutes, return existing result without duplicate execution
                if (System.currentTimeMillis() - cached.timestamp < 5 * 60 * 1000L) {
                    Log.i(TAG, "Idempotent hit: returning cached result for requestId $effectiveReqId")
                    return cached.result
                }
            }
        }

        // 2. Security Validation
        if (!validateRequestSecurity(origin, clientHost, authToken, deviceId)) {
            val rejectedResult = CommandSubmissionResult.Rejected(
                commandId = "",
                origin = origin,
                reason = "TRANSPORT_SECURITY_DENIED: Unauthenticated request from $clientHost (deviceId=$deviceId)"
            )
            if (effectiveReqId != null) {
                idempotencyCache[effectiveReqId] = IdempotencyRecord(effectiveReqId, command.hashCode().toString(), rejectedResult)
            }
            return rejectedResult
        }

        // 3. Submit to WastiOSRuntime
        val subParams = parameters.toMutableMap()
        if (effectiveReqId != null) subParams["requestId"] = effectiveReqId
        if (deviceId != null) subParams["deviceId"] = deviceId

        val result = runtime.submitCommand(
            command = command,
            origin = origin,
            executionMode = executionMode,
            targetAgentId = targetAgentId,
            parameters = subParams
        )

        // 4. Cache for idempotency
        if (effectiveReqId != null) {
            idempotencyCache[effectiveReqId] = IdempotencyRecord(effectiveReqId, command.hashCode().toString(), result)
        }

        return result
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

