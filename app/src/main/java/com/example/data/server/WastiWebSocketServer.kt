package com.example.data.server

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.agent.runtime.AgentEvent
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.WastiEmergencyStopController
import com.example.data.core.CommandOrigin
import com.example.data.core.WastiOSRuntime
import com.example.data.node.NodePlatform
import com.example.data.node.NodeTrustState
import com.example.data.node.WastiNodeManager
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

                // Read HTTP Upgrade Request Header
                val headerBytes = ByteArray(4096)
                val bytesRead = input.read(headerBytes)
                if (bytesRead <= 0) {
                    socket.close()
                    return@withContext
                }

                val headerStr = String(headerBytes, 0, bytesRead, Charsets.UTF_8)
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

            "PING" -> {
                sendTextFrame(session, JSONObject().apply {
                    put("type", "PONG")
                    put("timestamp", System.currentTimeMillis())
                }.toString())
            }
        }
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
