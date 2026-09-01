package com.example.data.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.WastiOSRuntime
import com.example.data.node.*
import com.example.data.transport.WastiCommandTransport
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 13: Canonical RFC 6455 WebSocket Fabric for Wasti AI OS.
 * Provides high-throughput, low-latency bidirectional socket streaming across
 * Android host, Web Companion, Desktop Nodes, and Remote Devices.
 */
class WastiWebSocketServer private constructor(
    private val context: Context?
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connectedSessions = CopyOnWriteArrayList<WebSocketSession>()
    private var eventSubscriptionJob: Job? = null

    data class WebSocketSession(
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        var deviceId: String? = null,
        var isAuthenticated: Boolean = false,
        var platform: NodePlatform = NodePlatform.WEB,
        var trustState: NodeTrustState = NodeTrustState.PAIRING,
        val connectedAt: Long = System.currentTimeMillis()
    )

    @Synchronized
    fun start(port: Int): Result<Int> {
        if (isRunning.get()) {
            return Result.success(serverSocket?.localPort ?: port)
        }

        return try {
            val ss = ServerSocket(port)
            serverSocket = ss
            isRunning.set(true)

            // Start accepting TCP connections
            serverScope.launch {
                acceptLoop(ss)
            }

            // Start listening to AgentEventBus and forwarding to authenticated sessions
            startEventBroadcasting()

            Log.i(TAG, "Wasti RFC 6455 WebSocket Server started on port ${ss.localPort}")
            Result.success(ss.localPort)
        } catch (e: Exception) {
            val errMsg = "Failed to start WebSocket Server on port $port: ${e.message}"
            Log.e(TAG, errMsg, e)
            Result.failure(e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.get()) return
        isRunning.set(false)

        eventSubscriptionJob?.cancel()
        eventSubscriptionJob = null

        // Close all active sessions
        for (session in connectedSessions) {
            try {
                sendCloseFrame(session.outputStream)
                session.socket.close()
            } catch (ignored: Exception) {}
        }
        connectedSessions.clear()

        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket server socket: ${e.message}")
        }
    }

    private suspend fun acceptLoop(serverSocket: ServerSocket) {
        withContext(Dispatchers.IO) {
            while (isRunning.get()) {
                try {
                    val clientSocket = serverSocket.accept()
                    serverScope.launch {
                        handleClientConnection(clientSocket)
                    }
                } catch (e: SocketException) {
                    if (!isRunning.get()) break
                    Log.w(TAG, "WebSocket server socket exception: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in WebSocket accept loop: ${e.message}", e)
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // Read HTTP Upgrade Request Header precisely up to \r\n\r\n
                val headerBaos = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b == -1) {
                        socket.close()
                        return@withContext
                    }
                    headerBaos.write(b)
                    val current = headerBaos.toString(Charsets.UTF_8.name())
                    if (current.endsWith("\r\n\r\n")) {
                        break
                    }
                }

                val headerStr = headerBaos.toString(Charsets.UTF_8.name())
                val lines = headerStr.split("\r\n")
                if (lines.isEmpty()) {
                    socket.close()
                    return@withContext
                }

                val requestLine = lines[0]
                val headers = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val k = line.substring(0, colonIdx).trim().lowercase()
                        val v = line.substring(colonIdx + 1).trim()
                        headers[k] = v
                    }
                }

                val wsKey = headers["sec-websocket-key"]
                val upgrade = headers["upgrade"]
                if (upgrade?.equals("websocket", ignoreCase = true) != true || wsKey.isNullOrEmpty()) {
                    // Not a valid WebSocket upgrade request
                    output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                    socket.close()
                    return@withContext
                }

                // Compute Accept Key
                val acceptKey = computeWebSocketAccept(wsKey)

                // Complete Handshake
                val response = buildString {
                    append("HTTP/1.1 101 Switching Protocols\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n\r\n")
                }
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()

                // Check for inline auth token in headers or query params
                val tokenFromHeader = headers["x-wasti-auth-token"]
                val deviceIdFromHeader = headers["x-wasti-device-id"]

                val session = WebSocketSession(
                    socket = socket,
                    inputStream = input,
                    outputStream = output,
                    deviceId = deviceIdFromHeader
                )

                if (tokenFromHeader != null && context != null) {
                    val isValid = WastiCommandTransport.getInstance(context).validateRequestSecurity(
                        authToken = tokenFromHeader,
                        deviceId = deviceIdFromHeader,
                        origin = CommandOrigin.WEB_COMPANION,
                        clientHost = socket.inetAddress?.hostAddress ?: "127.0.0.1"
                    )
                    session.isAuthenticated = isValid
                    if (isValid) {
                        session.trustState = NodeTrustState.ACTIVE
                    }
                }

                connectedSessions.add(session)
                Log.i(TAG, "WebSocket client connected: ${socket.remoteSocketAddress}")

                // Send Welcome & Handshake event
                sendTextFrame(session, JSONObject().apply {
                    put("type", "CONNECTED")
                    put("serverVersion", "1.0.0")
                    put("protocol", "RFC-6455")
                    put("isAuthenticated", session.isAuthenticated)
                    put("timestamp", System.currentTimeMillis())
                }.toString())

                // Process Incoming RFC 6455 Frames
                readFramesLoop(session)
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket client session ended: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun readFramesLoop(session: WebSocketSession) {
        val input = session.inputStream
        try {
            while (isRunning.get() && !session.socket.isClosed) {
                val b1 = input.read()
                if (b1 == -1) break // Connection closed by client

                val fin = (b1 and 0x80) != 0
                val opcode = b1 and 0x0F

                val b2 = input.read()
                if (b2 == -1) break

                val masked = (b2 and 0x80) != 0
                var payloadLength = (b2 and 0x7F).toLong()

                if (payloadLength == 126L) {
                    val len1 = input.read()
                    val len2 = input.read()
                    if (len1 == -1 || len2 == -1) break
                    payloadLength = ((len1 shl 8) or len2).toLong()
                } else if (payloadLength == 127L) {
                    var len = 0L
                    for (i in 0 until 8) {
                        val b = input.read()
                        if (b == -1) break
                        len = (len shl 8) or (b.toLong() and 0xFF)
                    }
                    payloadLength = len
                }

                val maskingKey = ByteArray(4)
                if (masked) {
                    var read = 0
                    while (read < 4) {
                        val r = input.read(maskingKey, read, 4 - read)
                        if (r == -1) break
                        read += r
                    }
                }

                // Read payload
                val payload = ByteArray(payloadLength.toInt())
                var totalRead = 0
                while (totalRead < payloadLength.toInt()) {
                    val r = input.read(payload, totalRead, payloadLength.toInt() - totalRead)
                    if (r == -1) break
                    totalRead += r
                }

                // Unmask if needed
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x1 -> { // Text frame
                        val text = String(payload, Charsets.UTF_8)
                        handleIncomingTextMessage(session, text)
                    }
                    0x2 -> { // Stage 18: Binary mesh frame
                        handleIncomingBinaryFrame(session, payload)
                    }
                    0x8 -> { // Close frame
                        sendCloseFrame(session.outputStream)
                        break
                    }
                    0x9 -> { // Ping frame -> send Pong
                        sendPongFrame(session.outputStream, payload)
                    }
                    0xA -> { // Pong frame
                        // Handled ping response
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket frame reading exception: ${e.message}")
        } finally {
            connectedSessions.remove(session)
            try { session.socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun handleIncomingTextMessage(session: WebSocketSession, text: String) {
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val type = json.optString("type", "UNKNOWN").uppercase()

        when (type) {
            "PING" -> {
                sendTextFrame(session, JSONObject().apply {
                    put("type", "PONG")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "AUTHENTICATE" -> {
                val token = json.optString("token", "")
                val deviceId = json.optString("deviceId", session.deviceId ?: "")
                val platformStr = json.optString("platform", "WEB")

                val platform = try { NodePlatform.valueOf(platformStr.uppercase()) } catch (e: Exception) { NodePlatform.WEB }
                session.deviceId = deviceId
                session.platform = platform

                val isValid = if (context != null) {
                    WastiCommandTransport.getInstance(context).validateRequestSecurity(
                        authToken = token,
                        deviceId = deviceId,
                        origin = if (platform == NodePlatform.DESKTOP) CommandOrigin.DESKTOP_COMPANION else CommandOrigin.WEB_COMPANION,
                        clientHost = session.socket.inetAddress?.hostAddress ?: "127.0.0.1"
                    )
                } else false

                session.isAuthenticated = isValid
                session.trustState = if (isValid) NodeTrustState.ACTIVE else NodeTrustState.REVOKED

                sendTextFrame(session, JSONObject().apply {
                    put("type", if (isValid) "AUTHENTICATED" else "AUTH_FAILED")
                    put("deviceId", deviceId)
                    put("trustState", session.trustState.name)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "COMMAND" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "WebSocket session not authenticated. Submit AUTHENTICATE frame first.")
                    }.toString())
                    return
                }

                val command = json.optString("command", "")
                val originStr = json.optString("origin", "WEB_COMPANION")
                val origin = try { CommandOrigin.valueOf(originStr) } catch (e: Exception) { CommandOrigin.WEB_COMPANION }
                val requestId = json.optString("requestId", "ws_req_${System.currentTimeMillis()}")
                val correlationId = json.optString("correlationId", "ws_corr_${System.currentTimeMillis()}")

                if (context != null) {
                    val result = WastiCommandTransport.getInstance(context).dispatchCommand(
                        command = command,
                        origin = origin,
                        deviceId = session.deviceId,
                        authToken = "ws_session_token",
                        requestId = requestId,
                        correlationId = correlationId,
                        clientHost = session.socket.inetAddress?.hostAddress ?: "127.0.0.1"
                    )

                    sendTextFrame(session, JSONObject().apply {
                        put("type", "TASK_ACCEPTED")
                        put("command", command)
                        put("requestId", requestId)
                        put("correlationId", correlationId)
                        put("resultState", result.javaClass.simpleName)
                    }.toString())
                }
            }

            "EMERGENCY_STOP" -> {
                val reason = json.optString("reason", "Triggered via WebSocket Companion")
                com.example.data.di.WastiServiceLocator.emergencyStopController.triggerEmergencyStop(reason)
                broadcastText(JSONObject().apply {
                    put("type", "EMERGENCY_STOP")
                    put("reason", reason)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "SCHEDULE_PROACTIVE_TASK" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val title = json.optString("title", "Remote Proactive Task")
                val prompt = json.optString("prompt", "")
                val delayMs = json.optLong("delayMs", 0L)
                val intervalMs = json.optLong("intervalMs", 0L)
                val idempotencyKey = if (json.has("idempotencyKey")) json.optString("idempotencyKey") else null

                val engine = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine
                val task = if (intervalMs > 0) {
                    engine.scheduleRecurringTask(
                        title = title,
                        prompt = prompt,
                        intervalMs = intervalMs,
                        idempotencyKey = idempotencyKey,
                        origin = CommandOrigin.REMOTE_DEVICE
                    )
                } else {
                    engine.scheduleDelayedTask(
                        title = title,
                        prompt = prompt,
                        delayMs = delayMs,
                        idempotencyKey = idempotencyKey,
                        origin = CommandOrigin.REMOTE_DEVICE
                    )
                }

                sendTextFrame(session, JSONObject().apply {
                    put("type", "PROACTIVE_TASK_SCHEDULED_RESPONSE")
                    put("taskId", task.taskId)
                    put("title", task.title)
                    put("scheduledAt", task.scheduledAt)
                }.toString())
            }

            "CANCEL_PROACTIVE_TASK" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val taskId = json.optString("taskId", "")
                val reason = json.optString("reason", "Cancelled via companion")
                val isCancelled = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine.cancelTask(taskId, reason)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "PROACTIVE_TASK_CANCELLED_RESPONSE")
                    put("taskId", taskId)
                    put("isSuccess", isCancelled)
                }.toString())
            }

            "LIST_PROACTIVE_TASKS" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val tasks = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine.getAllTasks()
                val tasksArr = org.json.JSONArray()
                for (t in tasks) {
                    tasksArr.put(JSONObject().apply {
                        put("taskId", t.taskId)
                        put("title", t.title)
                        put("state", t.state.name)
                        put("scheduledAt", t.scheduledAt)
                        put("leaseOwner", t.leaseOwnerNode ?: "NONE")
                    })
                }

                sendTextFrame(session, JSONObject().apply {
                    put("type", "PROACTIVE_TASKS_LIST")
                    put("tasks", tasksArr)
                }.toString())
            }

            "ACQUIRE_LEASE" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val taskId = json.optString("taskId", "")
                val durationMs = json.optLong("durationMs", 30000L)
                val nodeId = session.deviceId ?: "UNKNOWN_NODE"
                val isAcquired = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine.acquireTaskLease(taskId, nodeId, durationMs)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "LEASE_ACQUIRED_RESPONSE")
                    put("taskId", taskId)
                    put("nodeId", nodeId)
                    put("isSuccess", isAcquired)
                }.toString())
            }

            "RELEASE_LEASE" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val taskId = json.optString("taskId", "")
                val nodeId = session.deviceId ?: "UNKNOWN_NODE"
                val isReleased = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine.releaseTaskLease(taskId, nodeId)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "LEASE_RELEASED_RESPONSE")
                    put("taskId", taskId)
                    put("nodeId", nodeId)
                    put("isSuccess", isReleased)
                }.toString())
            }

            // ==========================================
            // STAGE 17: MESH PROTOCOL HANDLERS
            // ==========================================

            "NODE_HELLO" -> {
                val nodeId = json.optString("nodeId", session.deviceId ?: "anonymous_node")
                val nodeName = json.optString("nodeName", "Remote Node")
                val platformStr = json.optString("platform", "DESKTOP")
                val softwareVersion = json.optString("softwareVersion", "1.0.0")
                val protocolVersion = json.optInt("protocolVersion", 1)
                val fingerprint = json.optString("capabilityFingerprint", "")
                val clientHost = session.socket.inetAddress?.hostAddress ?: "127.0.0.1"

                val platform = try { NodePlatform.valueOf(platformStr.uppercase()) } catch (e: Exception) { NodePlatform.DESKTOP }
                session.deviceId = nodeId
                session.platform = platform

                val nodeManager = WastiNodeManager.getInstance()
                val existing = nodeManager.getNode(nodeId)
                val node = (existing ?: WastiNode(
                    nodeId = nodeId,
                    nodeName = nodeName,
                    platform = platform,
                    capabilities = emptySet(),
                    connectionState = NodeConnectionState.CONNECTED,
                    trustState = if (session.isAuthenticated) NodeTrustState.ACTIVE else NodeTrustState.PAIRING,
                    healthState = NodeHealthState.ONLINE,
                    isLocal = false,
                    networkAddress = clientHost
                )).copy(
                    nodeName = nodeName,
                    platform = platform,
                    softwareVersion = softwareVersion,
                    protocolVersion = protocolVersion,
                    capabilityFingerprint = fingerprint,
                    networkAddress = clientHost,
                    connectionState = NodeConnectionState.CONNECTED,
                    healthState = NodeHealthState.ONLINE,
                    lastPingTimestamp = System.currentTimeMillis()
                )
                nodeManager.registerNode(node)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_HELLO_ACK")
                    put("serverNodeId", "local_android_node")
                    put("serverSoftwareVersion", "1.0.0")
                    put("serverProtocolVersion", 1)
                    put("serverFingerprint", nodeManager.getNode("local_android_node")?.capabilityFingerprint ?: "")
                    put("isAuthenticated", session.isAuthenticated)
                    put("trustState", session.trustState.name)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "NODE_CAPABILITY_SNAPSHOT" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val softwareVersion = json.optString("softwareVersion", "1.0.0")
                val protocolVersion = json.optInt("protocolVersion", 1)
                val capsArray = json.optJSONArray("capabilities") ?: org.json.JSONArray()

                val snapList = mutableListOf<com.example.data.node.AdvertisedCapabilityInfo>()
                for (i in 0 until capsArray.length()) {
                    val cObj = capsArray.optJSONObject(i) ?: continue
                    val capId = cObj.optString("capabilityId", "")
                    if (capId.isBlank()) continue
                    val ver = cObj.optString("version", "1.0.0")
                    val stateStr = cObj.optString("realityState", "LIVE_CONNECTED")
                    val state = try { com.example.data.agent.runtime.CapabilityRealityState.valueOf(stateStr) } catch (e: Exception) { com.example.data.agent.runtime.CapabilityRealityState.LIVE_CONNECTED }
                    val provider = cObj.optString("provider", "Node[$nodeId]")
                    val reqs = cObj.optString("resourceRequirements", "LOW")

                    val opsList = mutableListOf<String>()
                    val opsArr = cObj.optJSONArray("supportedOperations")
                    if (opsArr != null) {
                        for (j in 0 until opsArr.length()) {
                            opsList.add(opsArr.optString(j))
                        }
                    }

                    val limList = mutableListOf<String>()
                    val limArr = cObj.optJSONArray("limitations")
                    if (limArr != null) {
                        for (j in 0 until limArr.length()) {
                            limList.add(limArr.optString(j))
                        }
                    }

                    snapList.add(
                        com.example.data.node.AdvertisedCapabilityInfo(
                            capabilityId = capId,
                            version = ver,
                            realityState = state,
                            provider = provider,
                            supportedOperations = opsList,
                            limitations = limList,
                            resourceRequirements = reqs,
                            isLocallyExecutable = cObj.optBoolean("isLocallyExecutable", true),
                            lastVerifiedTimestamp = System.currentTimeMillis()
                        )
                    )
                }

                val isSuccess = WastiNodeManager.getInstance().advertiseCapabilitySnapshot(
                    nodeId = nodeId,
                    snapshot = snapList,
                    softwareVersion = softwareVersion,
                    protocolVersion = protocolVersion
                )

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_CAPABILITY_SNAPSHOT_ACK")
                    put("nodeId", nodeId)
                    put("isSuccess", isSuccess)
                    put("capabilitiesCount", snapList.size)
                    put("fingerprint", WastiNodeManager.getInstance().getNode(nodeId)?.capabilityFingerprint ?: "")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "NODE_CAPABILITY_UPDATE" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val capObj = json.optJSONObject("capability")
                if (capObj != null) {
                    val capId = capObj.optString("capabilityId", "")
                    val ver = capObj.optString("version", "1.0.0")
                    val stateStr = capObj.optString("realityState", "LIVE_CONNECTED")
                    val state = try { com.example.data.agent.runtime.CapabilityRealityState.valueOf(stateStr) } catch (e: Exception) { com.example.data.agent.runtime.CapabilityRealityState.LIVE_CONNECTED }
                    val provider = capObj.optString("provider", "Node[$nodeId]")
                    val reqs = capObj.optString("resourceRequirements", "LOW")

                    val cap = com.example.data.node.AdvertisedCapabilityInfo(
                        capabilityId = capId,
                        version = ver,
                        realityState = state,
                        provider = provider,
                        resourceRequirements = reqs
                    )
                    val isUpdated = WastiNodeManager.getInstance().updateAdvertisedCapability(nodeId, cap)

                    sendTextFrame(session, JSONObject().apply {
                        put("type", "NODE_CAPABILITY_UPDATE_ACK")
                        put("nodeId", nodeId)
                        put("capabilityId", capId)
                        put("isSuccess", isUpdated)
                    }.toString())
                }
            }

            "NODE_CAPABILITY_REMOVE" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val capId = json.optString("capabilityId", "")
                val isRemoved = WastiNodeManager.getInstance().removeAdvertisedCapability(nodeId, capId)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_CAPABILITY_REMOVE_ACK")
                    put("nodeId", nodeId)
                    put("capabilityId", capId)
                    put("isSuccess", isRemoved)
                }.toString())
            }

            "NODE_HEARTBEAT" -> {
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val latencyMs = json.optLong("latencyMs", 0L)
                val load = json.optDouble("currentLoad", 0.0).toFloat()

                val recorded = WastiNodeManager.getInstance().recordHeartbeat(nodeId, latencyMs, load)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_HEARTBEAT_ACK")
                    put("nodeId", nodeId)
                    put("isSuccess", recorded)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "NODE_SYNC_REQUEST" -> {
                if (!session.isAuthenticated) {
                    sendTextFrame(session, JSONObject().apply {
                        put("type", "SECURITY_BLOCKED")
                        put("error", "Not authenticated.")
                    }.toString())
                    return
                }

                val localNode = WastiNodeManager.getInstance().getNode("local_android_node")
                val capsArr = org.json.JSONArray()
                localNode?.advertisedCapabilities?.values?.forEach { cap ->
                    capsArr.put(JSONObject().apply {
                        put("capabilityId", cap.capabilityId)
                        put("version", cap.version)
                        put("realityState", cap.realityState.name)
                        put("provider", cap.provider)
                    })
                }

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_SYNC_RESPONSE")
                    put("hostNodeId", "local_android_node")
                    put("fingerprint", localNode?.capabilityFingerprint ?: "")
                    put("capabilities", capsArr)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "NODE_TASK_ACCEPT" -> {
                val taskId = json.optString("taskId", "")
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val leaseExpiresAt = json.optLong("leaseExpiresAt", System.currentTimeMillis() + 30000L)

                com.example.data.di.WastiServiceLocator.agentEventBus.tryEmit(
                    AgentEvent.NodeTaskAccepted(
                        proactiveTaskId = taskId,
                        nodeId = nodeId,
                        leaseExpiresAt = leaseExpiresAt
                    )
                )
            }

            "NODE_TASK_REJECT" -> {
                val taskId = json.optString("taskId", "")
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val reason = json.optString("reason", "Rejected by remote node")

                com.example.data.di.WastiServiceLocator.agentEventBus.tryEmit(
                    AgentEvent.NodeTaskRejected(
                        proactiveTaskId = taskId,
                        nodeId = nodeId,
                        reason = reason
                    )
                )

                // Trigger immediate failover back to local
                com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine.failoverTask(taskId, "local_android_node")
            }

            "NODE_TASK_PROGRESS" -> {
                val taskId = json.optString("taskId", "")
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val progress = json.optString("progress", "")

                com.example.data.di.WastiServiceLocator.agentEventBus.tryEmit(
                    AgentEvent.NodeTaskProgress(
                        proactiveTaskId = taskId,
                        nodeId = nodeId,
                        progressSummary = progress
                    )
                )
            }

            "NODE_TASK_RESULT" -> {
                val taskId = json.optString("taskId", "")
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val isSuccess = json.optBoolean("isSuccess", true)
                val output = json.optString("output", "")
                val error = json.optString("error", "")

                // Complete proactive task if running under lease
                val engine = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine
                val task = engine.getTask(taskId)
                if (task != null && task.leaseOwnerNode == nodeId) {
                    if (isSuccess) {
                        engine.completeRunningTask(taskId, "Remote task executed by $nodeId: $output")
                    } else {
                        engine.failRunningTask(taskId, "Remote execution failed on $nodeId: $error")
                    }
                }
            }

            "NODE_TASK_LEASE_RENEW" -> {
                val taskId = json.optString("taskId", "")
                val nodeId = json.optString("nodeId", session.deviceId ?: "")
                val durationMs = json.optLong("durationMs", 30000L)

                val engine = com.example.data.di.WastiServiceLocator.proactiveAutonomousEngine
                val renewed = engine.acquireTaskLease(taskId, nodeId, durationMs)

                if (renewed) {
                    val task = engine.getTask(taskId)
                    com.example.data.di.WastiServiceLocator.agentEventBus.tryEmit(
                        AgentEvent.NodeLeaseRenewed(
                            proactiveTaskId = taskId,
                            nodeId = nodeId,
                            newExpiresAt = task?.leaseExpiresAt ?: 0L
                        )
                    )
                }

                sendTextFrame(session, JSONObject().apply {
                    put("type", "NODE_TASK_LEASE_RENEW_ACK")
                    put("taskId", taskId)
                    put("isSuccess", renewed)
                }.toString())
            }

            "AUDIO_STREAM_METADATA" -> {
                val sampleRate = json.optInt("sampleRate", 16000)
                val channels = json.optInt("channels", 1)
                val isSpeaking = json.optBoolean("isSpeaking", false)
                val lang = json.optString("language", "en-US")

                sendTextFrame(session, JSONObject().apply {
                    put("type", "AUDIO_STREAM_METADATA_ACK")
                    put("sampleRate", sampleRate)
                    put("channels", channels)
                    put("isSpeaking", isSpeaking)
                    put("language", lang)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "CONFIRMATION_RESOLVED" -> {
                val token = json.optString("confirmationToken", "")
                val approved = json.optBoolean("approved", false)
                val reason = json.optString("reason", "WebSocket remote resolution")

                val isResolved = if (context != null) {
                    com.example.data.conversation.UniversalConversationFabric.getInstance(context)
                        .resolveConfirmation(token, approved, reason)
                } else false

                sendTextFrame(session, JSONObject().apply {
                    put("type", "CONFIRMATION_RESOLVED_ACK")
                    put("token", token)
                    put("isResolved", isResolved)
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }

            "TASK_STATE_UPDATE" -> {
                val taskId = json.optString("taskId", "")
                val phaseStr = json.optString("phase", "EXECUTING")
                val desc = json.optString("description", "")
                val phase = try {
                    com.example.data.conversation.TaskTimelinePhase.valueOf(phaseStr)
                } catch (e: Exception) {
                    com.example.data.conversation.TaskTimelinePhase.EXECUTING
                }

                com.example.data.conversation.UniversalTaskTimeline.getInstance()
                    .appendPhase(taskId, phase, desc)

                sendTextFrame(session, JSONObject().apply {
                    put("type", "TASK_STATE_UPDATE_ACK")
                    put("taskId", taskId)
                    put("phase", phase.name)
                }.toString())
            }
        }
    }

    private fun handleIncomingBinaryFrame(session: WebSocketSession, payload: ByteArray) {
        serverScope.launch {
            try {
                val transport = com.example.data.mesh.WebSocketMeshTransport.getInstance()
                val clientAddress = session.socket.inetAddress?.hostAddress ?: "127.0.0.1"
                val result = transport.processIncomingBinaryFrame(payload, clientAddress)
                if (result.isSuccess) {
                    val responseEnvelope = result.getOrNull()
                    if (responseEnvelope != null) {
                        val responseBytes = com.example.data.mesh.WastiBinaryProtocolSerializer.serialize(responseEnvelope)
                        sendBinaryFrame(session, responseBytes)
                    }
                } else {
                    Log.w(TAG, "Failed to process incoming binary frame: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling binary WebSocket frame: ${e.message}", e)
            }
        }
    }

    /**
     * Stage 17: Dispatches a task offer to a remote node over WebSocket.
     */
    fun sendTaskOffer(
        nodeId: String,
        taskId: String,
        title: String,
        prompt: String,
        requiredCapabilities: List<String> = emptyList(),
        leaseDurationMs: Long = 30000L
    ): Boolean {
        val session = connectedSessions.find { it.deviceId == nodeId && it.isAuthenticated && !it.socket.isClosed } ?: return false
        val reqsArr = org.json.JSONArray()
        requiredCapabilities.forEach { reqsArr.put(it) }

        val offer = JSONObject().apply {
            put("type", "NODE_TASK_OFFER")
            put("taskId", taskId)
            put("title", title)
            put("prompt", prompt)
            put("requiredCapabilities", reqsArr)
            put("leaseDurationMs", leaseDurationMs)
            put("timestamp", System.currentTimeMillis())
        }

        sendTextFrame(session, offer.toString())
        com.example.data.di.WastiServiceLocator.agentEventBus.tryEmit(
            AgentEvent.NodeTaskOffered(
                proactiveTaskId = taskId,
                targetNodeId = nodeId,
                requiredCapabilities = requiredCapabilities
            )
        )
        return true
    }

    /**
     * Stage 17: Dispatches task cancellation to a remote node.
     */
    fun sendTaskCancel(nodeId: String, taskId: String, reason: String = "Cancelled by host"): Boolean {
        val session = connectedSessions.find { it.deviceId == nodeId && it.isAuthenticated && !it.socket.isClosed } ?: return false
        val msg = JSONObject().apply {
            put("type", "NODE_TASK_CANCEL")
            put("taskId", taskId)
            put("reason", reason)
            put("timestamp", System.currentTimeMillis())
        }
        sendTextFrame(session, msg.toString())
        return true
    }

    /**
     * Stage 17: Broadcasts emergency stop to all connected sessions across the mesh.
     */
    fun broadcastEmergencyStop(reason: String) {
        val msg = JSONObject().apply {
            put("type", "EMERGENCY_STOP")
            put("reason", reason)
            put("timestamp", System.currentTimeMillis())
        }
        broadcastText(msg.toString())
    }

    private fun startEventBroadcasting() {
        eventSubscriptionJob = serverScope.launch {
            com.example.data.di.WastiServiceLocator.agentEventBus.events.collect { event ->
                val envelope = serializeEventToEnvelope(event)
                broadcastText(envelope)
            }
        }
    }

    fun broadcastText(message: String) {
        for (session in connectedSessions) {
            if (session.isAuthenticated && !session.socket.isClosed) {
                sendTextFrame(session, message)
            }
        }
    }

    fun sendTextFrame(session: WebSocketSession, text: String) {
        try {
            synchronized(session.outputStream) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val out = session.outputStream

                out.write(0x81) // FIN=1, Text Opcode=0x1
                val length = bytes.size
                if (length <= 125) {
                    out.write(length)
                } else if (length <= 65535) {
                    out.write(126)
                    out.write((length shr 8) and 0xFF)
                    out.write(length and 0xFF)
                } else {
                    out.write(127)
                    for (i in 7 downTo 0) {
                        out.write(((length.toLong() shr (i * 8)) and 0xFF).toInt())
                    }
                }
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing frame to WebSocket session: ${e.message}")
        }
    }

    fun sendBinaryFrame(session: WebSocketSession, bytes: ByteArray) {
        try {
            synchronized(session.outputStream) {
                val out = session.outputStream
                out.write(0x82) // FIN=1, Binary Opcode=0x2
                val length = bytes.size
                if (length <= 125) {
                    out.write(length)
                } else if (length <= 65535) {
                    out.write(126)
                    out.write((length shr 8) and 0xFF)
                    out.write(length and 0xFF)
                } else {
                    out.write(127)
                    for (i in 7 downTo 0) {
                        out.write(((length.toLong() shr (i * 8)) and 0xFF).toInt())
                    }
                }
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error writing binary frame to WebSocket session: ${e.message}")
        }
    }

    fun sendBinaryFrameToNode(nodeId: String, bytes: ByteArray): Boolean {
        var sent = false
        for (session in connectedSessions) {
            if (session.deviceId == nodeId && !session.socket.isClosed) {
                sendBinaryFrame(session, bytes)
                sent = true
            }
        }
        return sent
    }

    fun broadcastBinaryFrame(bytes: ByteArray) {
        for (session in connectedSessions) {
            if (session.isAuthenticated && !session.socket.isClosed) {
                sendBinaryFrame(session, bytes)
            }
        }
    }

    private fun sendCloseFrame(out: OutputStream) {
        try {
            synchronized(out) {
                out.write(0x88) // FIN=1, Close Opcode=0x8
                out.write(0x00) // Payload 0
                out.flush()
            }
        } catch (ignored: Exception) {}
    }

    private fun sendPongFrame(out: OutputStream, payload: ByteArray) {
        try {
            synchronized(out) {
                out.write(0x8A) // FIN=1, Pong Opcode=0xA
                out.write(payload.size and 0x7F)
                out.write(payload)
                out.flush()
            }
        } catch (ignored: Exception) {}
    }

    private fun computeWebSocketAccept(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest((key + magic).toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun serializeEventToEnvelope(event: AgentEvent): String {
        val json = JSONObject().apply {
            put("type", "EVENT")
            put("eventType", event.javaClass.simpleName)
            put("eventId", event.eventId)
            put("taskId", event.taskId.value)
            put("timestamp", event.timestamp)
        }
        return json.toString()
    }

    fun getActiveSessionCount(): Int = connectedSessions.size

    companion object {
        private const val TAG = "WastiWebSocketServer"

        @Volatile
        private var instance: WastiWebSocketServer? = null

        fun getInstance(context: Context? = null): WastiWebSocketServer {
            return instance ?: synchronized(this) {
                instance ?: WastiWebSocketServer(context?.applicationContext).also { instance = it }
            }
        }
    }
}
