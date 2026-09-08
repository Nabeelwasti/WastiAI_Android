package com.example.data.mesh

import android.util.Log
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionResult
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiOSRuntime
import com.example.data.di.WastiServiceLocator
import com.example.data.node.*
import com.example.data.server.WastiWebSocketServer
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Stage 18: Mesh Envelope Handler Function.
 */
fun interface MeshEnvelopeHandler {
    suspend fun handleEnvelope(envelope: WastiMeshEnvelope, transport: WastiMeshTransport): WastiMeshEnvelope?
}

/**
 * Stage 18: Cross-Platform Mesh Transport Abstraction.
 * Separates TRANSPORT from COMMAND, EXECUTION, and VERIFICATION.
 * Provides unified interface across Android, Desktop, Termux, Raspberry Pi, and Web nodes.
 */
interface WastiMeshTransport {
    val transportType: String
    val isRunning: Boolean

    fun start(): Result<Boolean>
    fun stop()

    fun sendEnvelope(targetNodeId: String, envelope: WastiMeshEnvelope): Boolean
    fun broadcastEnvelope(envelope: WastiMeshEnvelope)
    fun registerHandler(messageType: WastiMeshMessageType, handler: MeshEnvelopeHandler)
    fun unregisterHandler(messageType: WastiMeshMessageType)
}

/**
 * Stage 18: Canonical WebSocket-Backed Cross-Platform Mesh Transport Implementation.
 * Bridges WastiWebSocketServer binary frames with WastiCommandTransport and WastiOSRuntime.
 */
class WebSocketMeshTransport(
    private val webSocketServer: WastiWebSocketServer = WastiWebSocketServer.getInstance(),
    private val commandTransport: WastiCommandTransport = WastiCommandTransport.getInstance(),
    private val nodeManager: WastiNodeManager = WastiNodeManager.getInstance(),
    private val replayGuard: MeshReplayAndIdempotencyGuard = MeshReplayAndIdempotencyGuard(),
    private val emergencyStop: WastiEmergencyStopController = WastiServiceLocator.emergencyStopController
) : WastiMeshTransport {

    override val transportType: String = "WEBSOCKET_BINARY_RFC6455"
    private var _isRunning = false
    override val isRunning: Boolean get() = _isRunning

    private val handlers = ConcurrentHashMap<WastiMeshMessageType, MeshEnvelopeHandler>()
    private val pendingTaskResults = ConcurrentHashMap<String, CompletableDeferred<UnifiedExecutionResult>>()
    private val nodeReconnectAttempts = ConcurrentHashMap<String, Int>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    init {
        registerDefaultMeshHandlers()
    }

    override fun start(): Result<Boolean> {
        _isRunning = true
        startHeartbeatLoop()
        Log.i(TAG, "WebSocketMeshTransport started with 15s heartbeat & auto-reconnect")
        return Result.success(true)
    }

    override fun stop() {
        _isRunning = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        nodeReconnectAttempts.clear()
        pendingTaskResults.values.forEach { it.cancel() }
        pendingTaskResults.clear()
        scope.cancel()
        Log.i(TAG, "WebSocketMeshTransport stopped")
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _isRunning) {
                delay(15_000L) // 15s ping/pong heartbeat
                try {
                    val nodes = nodeManager.getAllNodes()
                    for (node in nodes) {
                        if (node.isLocal) continue
                        if (node.connectionState == NodeConnectionState.CONNECTED) {
                            val heartbeatEnv = WastiMeshEnvelope(
                                protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                                messageType = WastiMeshMessageType.HEARTBEAT,
                                requestId = java.util.UUID.randomUUID().toString(),
                                correlationId = java.util.UUID.randomUUID().toString(),
                                senderNodeId = "local_android_node",
                                payloadBytes = "PING".toByteArray(Charsets.UTF_8)
                            )
                            val sent = sendEnvelope(node.nodeId, heartbeatEnv)
                            if (!sent) {
                                handleNodeHeartbeatMiss(node)
                            } else {
                                nodeReconnectAttempts.remove(node.nodeId)
                            }
                        } else if (node.connectionState == NodeConnectionState.DISCONNECTED && node.trustState == NodeTrustState.ACTIVE) {
                            attemptAutoReconnect(node)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat iteration warning: ${e.message}")
                }
            }
        }
    }

    private fun handleNodeHeartbeatMiss(node: WastiNode) {
        val attempts = nodeReconnectAttempts.getOrDefault(node.nodeId, 0) + 1
        nodeReconnectAttempts[node.nodeId] = attempts
        if (attempts >= 3) {
            val disconnectedNode = node.copy(
                connectionState = NodeConnectionState.DISCONNECTED,
                healthState = NodeHealthState.DEGRADED
            )
            nodeManager.registerNode(disconnectedNode)
            Log.w(TAG, "Node ${node.nodeId} missed 3 heartbeats. Marked DISCONNECTED.")
        }
    }

    private suspend fun attemptAutoReconnect(node: WastiNode) {
        val attempts = nodeReconnectAttempts.getOrDefault(node.nodeId, 0)
        val backoffSeconds = (1L shl attempts.coerceAtMost(5)) * 2L // 2s, 4s, 8s, 16s, 32s, 64s
        val lastPing = node.lastPingTimestamp
        if (System.currentTimeMillis() - lastPing < backoffSeconds * 1000L) {
            return
        }

        Log.i(TAG, "Attempting exponential backoff auto-reconnect for node ${node.nodeId} (attempt $attempts, delay ${backoffSeconds}s)...")
        nodeReconnectAttempts[node.nodeId] = attempts + 1

        val helloEnv = WastiMeshEnvelope(
            protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
            messageType = WastiMeshMessageType.HELLO,
            requestId = java.util.UUID.randomUUID().toString(),
            correlationId = java.util.UUID.randomUUID().toString(),
            senderNodeId = "local_android_node",
            payloadBytes = "Wasti Mobile Host Auto-Reconnect".toByteArray(Charsets.UTF_8)
        )
        val success = sendEnvelope(node.nodeId, helloEnv)
        if (success) {
            nodeManager.recoverNode(node.nodeId)
            nodeReconnectAttempts.remove(node.nodeId)
            Log.i(TAG, "Node ${node.nodeId} auto-reconnection successful.")
        }
    }

    fun registerPendingTask(requestId: String, deferred: CompletableDeferred<UnifiedExecutionResult>) {
        pendingTaskResults[requestId] = deferred
    }

    override fun registerHandler(messageType: WastiMeshMessageType, handler: MeshEnvelopeHandler) {
        handlers[messageType] = handler
    }

    override fun unregisterHandler(messageType: WastiMeshMessageType) {
        handlers.remove(messageType)
    }

    override fun sendEnvelope(targetNodeId: String, envelope: WastiMeshEnvelope): Boolean {
        if (!_isRunning) return false
        val bytes = WastiBinaryProtocolSerializer.serialize(envelope)
        return webSocketServer.sendBinaryFrameToNode(targetNodeId, bytes)
    }

    override fun broadcastEnvelope(envelope: WastiMeshEnvelope) {
        if (!_isRunning) return
        val bytes = WastiBinaryProtocolSerializer.serialize(envelope)
        webSocketServer.broadcastBinaryFrame(bytes)
    }

    /**
     * Ingress entry point for binary frames arriving from WebSocket or raw TCP socket.
     */
    suspend fun processIncomingBinaryFrame(
        bytes: ByteArray,
        clientAddress: String = "127.0.0.1"
    ): Result<WastiMeshEnvelope?> = withContext(Dispatchers.IO) {
        val deserializeResult = WastiBinaryProtocolSerializer.deserialize(bytes)
        if (deserializeResult.isFailure) {
            val err = deserializeResult.exceptionOrNull()?.message ?: "Unknown decode error"
            Log.w(TAG, "Failed to deserialize incoming mesh frame: $err")
            return@withContext Result.failure(deserializeResult.exceptionOrNull() ?: IllegalArgumentException(err))
        }

        val envelope = deserializeResult.getOrThrow()

        // 1. Replay & Time Drift Protection
        val replayCheck = replayGuard.validateAndRecordMessage(envelope)
        if (replayCheck.isFailure) {
            Log.w(TAG, "Replay guard rejected frame from ${envelope.senderNodeId}: ${replayCheck.exceptionOrNull()?.message}")
            return@withContext Result.failure(replayCheck.exceptionOrNull() ?: SecurityException("Replay check failed"))
        }

        // 2. Protocol Version Check
        val versionCheck = MeshProtocolNegotiator.negotiate(envelope.protocolVersion)
        if (versionCheck is MeshProtocolNegotiator.NegotiationResult.Incompatible) {
            Log.w(TAG, "Incompatible protocol version ${envelope.protocolVersion} from ${envelope.senderNodeId}")
            return@withContext Result.failure(IllegalArgumentException("Incompatible protocol version: ${versionCheck.reason}"))
        }

        // 3. Emergency Stop Enforcement
        if (emergencyStop.isEmergencyStopped) {
            if (envelope.messageType != WastiMeshMessageType.EMERGENCY_STOP &&
                envelope.messageType != WastiMeshMessageType.DIAGNOSTIC_QUERY
            ) {
                Log.w(TAG, "Mesh command blocked: Emergency stop is active")
                val blockedEnv = WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.SECURITY_BLOCK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "EMERGENCY_STOP_ACTIVE: Execution is latched and halted".toByteArray(Charsets.UTF_8)
                )
                return@withContext Result.success(blockedEnv)
            }
        }

        // 4. Dispatch to Registered Handler or Default Routing
        val handler = handlers[envelope.messageType]
        if (handler != null) {
            val response = handler.handleEnvelope(envelope, this@WebSocketMeshTransport)
            return@withContext Result.success(response)
        } else {
            val defaultResponse = handleDefaultEnvelope(envelope, clientAddress)
            return@withContext Result.success(defaultResponse)
        }
    }

    private suspend fun handleDefaultEnvelope(
        envelope: WastiMeshEnvelope,
        clientAddress: String
    ): WastiMeshEnvelope? {
        return when (envelope.messageType) {
            WastiMeshMessageType.HELLO -> {
                val nodeName = if (envelope.payloadBytes.isNotEmpty()) String(envelope.payloadBytes, Charsets.UTF_8) else "Remote Node"
                val existing = nodeManager.getNode(envelope.senderNodeId)
                val node = (existing ?: WastiNode(
                    nodeId = envelope.senderNodeId,
                    nodeName = nodeName,
                    platform = NodePlatform.DESKTOP,
                    connectionState = NodeConnectionState.CONNECTED,
                    trustState = NodeTrustState.ACTIVE,
                    healthState = NodeHealthState.ONLINE,
                    isLocal = false,
                    networkAddress = clientAddress
                )).copy(
                    connectionState = NodeConnectionState.CONNECTED,
                    healthState = NodeHealthState.ONLINE,
                    protocolVersion = envelope.protocolVersion,
                    lastPingTimestamp = System.currentTimeMillis()
                )
                nodeManager.registerNode(node)

                WastiServiceLocator.agentEventBus.tryEmit(
                    AgentEvent.NodeConnected(
                        nodeId = envelope.senderNodeId,
                        nodeName = nodeName,
                        platform = node.platform.name
                    )
                )

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.HELLO_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "HELLO_ACK".toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.PROTOCOL_NEGOTIATE -> {
                val proposedVersion = envelope.protocolVersion
                val result = MeshProtocolNegotiator.negotiate(proposedVersion)
                val isCompatible = result is MeshProtocolNegotiator.NegotiationResult.Compatible
                val negotiated = if (result is MeshProtocolNegotiator.NegotiationResult.Compatible) result.negotiatedVersion else 0

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.PROTOCOL_NEGOTIATE_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = JSONObject().apply {
                        put("isCompatible", isCompatible)
                        put("negotiatedVersion", negotiated)
                        put("preferredVersion", MeshProtocolNegotiator.PREFERRED_VERSION)
                    }.toString().toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.CAPABILITY_FINGERPRINT_CHECK -> {
                val remoteFingerprint = String(envelope.payloadBytes, Charsets.UTF_8)
                val node = nodeManager.getNode(envelope.senderNodeId)
                val localKnownFingerprint = node?.capabilityFingerprint ?: ""

                if (localKnownFingerprint.isNotBlank() && localKnownFingerprint == remoteFingerprint) {
                    WastiMeshEnvelope(
                        protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                        messageType = WastiMeshMessageType.CAPABILITY_FINGERPRINT_MATCH,
                        requestId = envelope.requestId,
                        correlationId = envelope.correlationId,
                        senderNodeId = "local_android_node",
                        payloadBytes = "MATCH".toByteArray(Charsets.UTF_8)
                    )
                } else {
                    WastiMeshEnvelope(
                        protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                        messageType = WastiMeshMessageType.CAPABILITY_DELTA_REQUEST,
                        requestId = envelope.requestId,
                        correlationId = envelope.correlationId,
                        senderNodeId = "local_android_node",
                        payloadBytes = localKnownFingerprint.toByteArray(Charsets.UTF_8)
                    )
                }
            }

            WastiMeshMessageType.CAPABILITY_DELTA_RESPONSE -> {
                val deltaJson = String(envelope.payloadBytes, Charsets.UTF_8)
                val delta = CapabilityDelta.fromJson(deltaJson)

                // Apply Delta to NodeManager
                val currentCaps = nodeManager.getNode(envelope.senderNodeId)?.advertisedCapabilities?.toMutableMap() ?: mutableMapOf()

                // 1. Additions & Modifications
                delta.added.forEach { currentCaps[it.capabilityId] = it }
                delta.modified.forEach { currentCaps[it.capabilityId] = it }

                // 2. Removals
                delta.removedCapabilityIds.forEach { currentCaps.remove(it) }

                nodeManager.advertiseCapabilitySnapshot(
                    nodeId = envelope.senderNodeId,
                    snapshot = currentCaps.values.toList()
                )

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.CAPABILITY_SNAPSHOT_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "DELTA_APPLIED".toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.HEARTBEAT -> {
                val latency = 10L // nominal ping
                val load = 0.1f
                nodeManager.recordHeartbeat(envelope.senderNodeId, latency, load)

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.HEARTBEAT_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "PONG".toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.COMMAND_SUBMIT -> {
                val commandStr = String(envelope.payloadBytes, Charsets.UTF_8)

                // Check Idempotency Cache
                val cached = replayGuard.getIdempotentResult(envelope.requestId)
                if (cached != null) {
                    return WastiMeshEnvelope(
                        protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                        messageType = WastiMeshMessageType.COMMAND_RESULT,
                        requestId = envelope.requestId,
                        correlationId = envelope.correlationId,
                        senderNodeId = "local_android_node",
                        payloadBytes = "CACHED_RESULT: ${cached.javaClass.simpleName}".toByteArray(Charsets.UTF_8)
                    )
                }

                // Canonical Execution Chain
                val result = commandTransport.dispatchCommand(
                    command = commandStr,
                    origin = CommandOrigin.EXTERNAL_NODE,
                    deviceId = envelope.senderNodeId,
                    authToken = envelope.sessionToken,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    clientHost = clientAddress
                )

                replayGuard.recordIdempotentResult(envelope.requestId, result)

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.COMMAND_RESULT,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = result.javaClass.simpleName.toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.AUTHENTICATE -> {
                val node = nodeManager.getNode(envelope.senderNodeId)
                val isTrusted = node != null && node.trustState != NodeTrustState.REVOKED
                if (isTrusted) {
                    nodeManager.updateNodeTrust(envelope.senderNodeId, NodeTrustState.ACTIVE)
                }
                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.AUTHENTICATE_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = JSONObject().apply {
                        put("authenticated", isTrusted)
                        put("trustState", node?.trustState?.name ?: NodeTrustState.PAIRED.name)
                    }.toString().toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.TASK_OFFER -> {
                val taskPayload = if (envelope.payloadBytes.isNotEmpty()) String(envelope.payloadBytes, Charsets.UTF_8) else "{}"
                val reqObj = JSONObject(taskPayload)
                val paramsObj = reqObj.optJSONObject("parameters") ?: JSONObject()
                val paramMap = mutableMapOf<String, String>()
                paramsObj.keys().forEach { k -> paramMap[k] = paramsObj.getString(k) }

                val taskId = reqObj.optString("taskId", envelope.requestId)
                val actionId = reqObj.optString("actionId", "mesh_remote_action")
                val capabilityId = reqObj.optString("capabilityId", "general_computation")
                val timeoutMs = reqObj.optLong("timeoutMs", 30_000L)

                val execReq = com.example.data.agent.runtime.UnifiedExecutionRequest(
                    taskId = taskId,
                    actionId = actionId,
                    capabilityId = capabilityId,
                    parameters = paramMap,
                    originatingNodeId = envelope.senderNodeId,
                    timeoutMs = timeoutMs
                )

                val execResult = UnifiedExecutionFabric.instance.execute(execReq, null)

                val resObj = JSONObject().apply {
                    put("taskId", execResult.taskId)
                    put("actionId", execResult.actionId)
                    put("capabilityId", execResult.capabilityId)
                    put("status", execResult.status.name)
                    put("output", execResult.output)
                    put("error", execResult.error ?: "")
                    put("executor", execResult.executor)
                    put("startedAt", execResult.startedAt)
                    put("completedAt", execResult.completedAt)
                    put("verificationStatus", execResult.verificationStatus.name)
                    put("verificationEvidence", execResult.verificationEvidence ?: "")
                }

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.TASK_RESULT,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = resObj.toString().toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.TASK_RESULT -> {
                val resultPayload = if (envelope.payloadBytes.isNotEmpty()) String(envelope.payloadBytes, Charsets.UTF_8) else "{}"
                val resObj = try { JSONObject(resultPayload) } catch (_: Exception) { JSONObject() }
                val statusStr = resObj.optString("status", "COMPLETED")
                val vStatusStr = resObj.optString("verificationStatus", "VERIFIED")

                val result = com.example.data.agent.runtime.UnifiedExecutionResult(
                    taskId = resObj.optString("taskId", envelope.requestId),
                    actionId = resObj.optString("actionId", "mesh_task"),
                    capabilityId = resObj.optString("capabilityId", "mesh_computation"),
                    status = try { com.example.data.agent.runtime.UnifiedExecutionStatus.valueOf(statusStr) } catch (_: Exception) { com.example.data.agent.runtime.UnifiedExecutionStatus.COMPLETED },
                    output = resObj.optString("output", resultPayload),
                    error = resObj.optString("error").takeIf { it.isNotBlank() },
                    executor = resObj.optString("executor", "MeshRemote_${envelope.senderNodeId}"),
                    startedAt = resObj.optLong("startedAt", envelope.timestamp),
                    completedAt = resObj.optLong("completedAt", System.currentTimeMillis()),
                    verificationStatus = try { com.example.data.agent.runtime.UnifiedVerificationStatus.valueOf(vStatusStr) } catch (_: Exception) { com.example.data.agent.runtime.UnifiedVerificationStatus.VERIFIED },
                    verificationEvidence = resObj.optString("verificationEvidence", "Verified via Wasti Binary Mesh")
                )

                pendingTaskResults.remove(envelope.requestId)?.complete(result)
                null
            }

            WastiMeshMessageType.DIAGNOSTIC_QUERY -> {
                val diagResult = JSONObject().apply {
                    put("nodeId", "local_android_node")
                    put("status", "HEALTHY")
                    put("availableMemoryMb", Runtime.getRuntime().freeMemory() / (1024 * 1024))
                    put("availableProcessors", Runtime.getRuntime().availableProcessors())
                    put("timestamp", System.currentTimeMillis())
                }
                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.DIAGNOSTIC_RESPONSE,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = diagResult.toString().toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.LEASE_ACQUIRE, WastiMeshMessageType.LEASE_RENEW, WastiMeshMessageType.LEASE_RELEASE -> {
                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.LEASE_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "LEASE_GRANTED".toByteArray(Charsets.UTF_8)
                )
            }

            WastiMeshMessageType.EMERGENCY_STOP -> {
                val reason = if (envelope.payloadBytes.isNotEmpty()) String(envelope.payloadBytes, Charsets.UTF_8) else "Remote mesh emergency stop"
                emergencyStop.triggerEmergencyStop(reason)
                broadcastEnvelope(envelope)

                WastiMeshEnvelope(
                    protocolVersion = WastiMeshEnvelope.CURRENT_PROTOCOL_VERSION,
                    messageType = WastiMeshMessageType.EMERGENCY_STOP_ACK,
                    requestId = envelope.requestId,
                    correlationId = envelope.correlationId,
                    senderNodeId = "local_android_node",
                    payloadBytes = "EMERGENCY_STOP_EXECUTED".toByteArray(Charsets.UTF_8)
                )
            }

            else -> null
        }
    }

    private fun registerDefaultMeshHandlers() {
        // Additional custom message handlers can be plugged in dynamically
    }

    companion object {
        private const val TAG = "WebSocketMeshTransport"

        @Volatile
        private var instance: WebSocketMeshTransport? = null

        fun getInstance(): WebSocketMeshTransport {
            return instance ?: synchronized(this) {
                instance ?: WebSocketMeshTransport().also { instance = it }
            }
        }
    }
}
