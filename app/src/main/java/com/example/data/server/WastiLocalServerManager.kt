package com.example.data.server

import android.content.Context
import android.util.Log
import com.example.data.action.WastiAppAction
import com.example.data.action.WastiAppActionBus
import com.example.data.agent.runtime.*
import com.example.data.core.CommandOrigin
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiCore
import com.example.data.core.WastiOSRuntime
import com.example.data.transport.WastiCommandTransport
import com.example.data.wre.ExecutionRequest
import com.example.data.wre.WreManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.concurrent.Executors

enum class LocalServerState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPING,
    STOPPED,
    FAILED
}

data class LocalServerInfo(
    val state: LocalServerState = LocalServerState.NOT_STARTED,
    val port: Int = 8080,
    val wsPort: Int = 8081,
    val host: String = "127.0.0.1",
    val startedAt: Long = 0L,
    val requestsHandled: Long = 0L,
    val lastError: String? = null
)

/**
 * Stage 10: Canonical Wasti Embedded Local Server Daemon.
 * Exposes a multi-threaded, non-blocking HTTP and event streaming gateway into WastiBrain.
 * Connects Web Companions, external tools, local scripts, and distributed nodes into WastiOSRuntime.
 */
class WastiLocalServerManager(
    private val context: Context? = null
) {
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: HttpServer? = null
    private var executor = Executors.newFixedThreadPool(4)

    private val _serverInfo = MutableStateFlow(LocalServerInfo())
    val serverInfo: StateFlow<LocalServerInfo> = _serverInfo.asStateFlow()

    private val wreManager: WreManager by lazy {
        val ctx = context ?: com.example.WastiApplication.instance
        if (ctx != null) WreManager.getInstance(ctx) else WreManager(com.example.WastiApplication.instance ?: throw IllegalStateException("Context required for WreManager"))
    }

    @Synchronized
    fun startServer(preferredPort: Int = 8080): Result<LocalServerInfo> {
        if (_serverInfo.value.state == LocalServerState.RUNNING) {
            return Result.success(_serverInfo.value)
        }

        _serverInfo.value = _serverInfo.value.copy(
            state = LocalServerState.STARTING,
            port = preferredPort,
            lastError = null
        )

        var selectedPort = preferredPort
        var server: HttpServer? = null
        var lastException: Exception? = null

        // Try preferred port, fallback to sequential ports up to +5
        for (offset in 0..5) {
            try {
                val candidatePort = preferredPort + offset
                val address = InetSocketAddress("127.0.0.1", candidatePort)
                server = HttpServer.create(address, 0)
                selectedPort = candidatePort
                break
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (server == null) {
            val errMsg = "Failed to bind local server on ports $preferredPort..${preferredPort + 5}: ${lastException?.message}"
            Log.e(TAG, errMsg, lastException)
            _serverInfo.value = _serverInfo.value.copy(
                state = LocalServerState.FAILED,
                lastError = errMsg
            )
            return Result.failure(lastException ?: IllegalStateException(errMsg))
        }

        return try {
            executor = Executors.newFixedThreadPool(4)
            server.executor = executor

            // 1. Health endpoint
            server.createContext("/health", HealthHandler())

            // 2. Status & Telemetry endpoints
            val statusHandler = StatusHandler()
            server.createContext("/status", statusHandler)
            server.createContext("/api/status", statusHandler)

            // 3. Capabilities Reality endpoints
            val capabilitiesHandler = CapabilitiesHandler()
            server.createContext("/capabilities", capabilitiesHandler)
            server.createContext("/api/capabilities", capabilitiesHandler)

            // 4. Execution State & Cancellation endpoints
            val executionHandler = ExecutionHandler(serverScope)
            server.createContext("/execution", executionHandler)
            server.createContext("/api/execution", executionHandler)

            // 5. Canonical Command Transport endpoints
            val commandHandler = TransportHandler(serverScope)
            server.createContext("/command", commandHandler)
            server.createContext("/api/command", commandHandler)
            server.createContext("/api/transport", commandHandler)

            // 6. Emergency Stop endpoints
            val emergencyStopHandler = EmergencyStopHandler()
            server.createContext("/emergency-stop", emergencyStopHandler)
            server.createContext("/api/emergency-stop", emergencyStopHandler)

            // 7. Live Events Polling & Streaming endpoints
            val eventsHandler = EventsHandler()
            server.createContext("/events", eventsHandler)
            server.createContext("/api/events", eventsHandler)

            // 8. Brain Chat Gateway
            server.createContext("/api/chat", ChatHandler(serverScope))

            // 9. Unified Execution Fabric Gateway
            server.createContext("/api/execute", ExecuteHandler(serverScope, context))

            // 10. Semantic App Actions Gateway
            server.createContext("/api/actions", ActionHandler(serverScope))

            // 11. Native Terminal Execution Endpoint
            server.createContext("/api/terminal", TerminalHandler(serverScope))

            // 12. Node Registry Endpoint
            val nodeHandler = NodeHandler()
            server.createContext("/nodes", nodeHandler)
            server.createContext("/api/nodes", nodeHandler)

            // 13. Stage 11: Multi-Device Pairing Endpoints
            server.createContext("/api/pairing/request", PairingRequestHandler())
            server.createContext("/api/pairing/verify", PairingVerifyHandler())
            server.createContext("/api/pairing/devices", PairingDevicesHandler())
            server.createContext("/api/pairing/revoke", PairingRevokeHandler())

            // 14. Stage 11/12: Real-time Event Stream Snapshot
            val streamHandler = StreamHandler()
            server.createContext("/stream", streamHandler)
            server.createContext("/api/stream", streamHandler)

            // 15. Stage 12: Web Companion Lightweight Dashboard UI
            val webCompanionHandler = WebCompanionDashboardHandler()
            server.createContext("/", webCompanionHandler)
            server.createContext("/web", webCompanionHandler)
            server.createContext("/companion", webCompanionHandler)

            server.start()
            httpServer = server

            // Stage 13: Canonical RFC 6455 WebSocket Server Startup
            val wsPort = selectedPort + 1
            val wsResult = WastiWebSocketServer.getInstance(context).start(wsPort)
            val actualWsPort = wsResult.getOrDefault(wsPort)

            val updatedInfo = LocalServerInfo(
                state = LocalServerState.RUNNING,
                port = selectedPort,
                wsPort = actualWsPort,
                host = "127.0.0.1",
                startedAt = System.currentTimeMillis(),
                requestsHandled = 0L,
                lastError = null
            )
            _serverInfo.value = updatedInfo

            // Register in Capability Reality Registry
            UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
                CapabilityReality(
                    capabilityId = "LOCAL_SERVER",
                    category = "TRANSPORT",
                    implementationStatus = ImplementationStatus.READY,
                    liveConnectionStatus = LiveConnectionStatus.VERIFIED,
                    executionStatus = CapabilityExecutionStatus.OPERATIONAL,
                    authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                    provider = "WastiLocalServerManager",
                    supportedOperations = listOf(
                        "start_server", "stop_server", "get_status", "http_gateway",
                        "transport_gateway", "terminal_gateway", "events_stream", "websocket_fabric"
                    ),
                    limitations = listOf("Bound to local interface 127.0.0.1:$selectedPort (HTTP) & $actualWsPort (WS)"),
                    realityState = CapabilityRealityState.NATIVE
                )
            )

            Log.i(TAG, "Wasti Embedded Server successfully started on port $selectedPort (WS: $actualWsPort)")

            // Stage 12: Network Service Discovery (mDNS) registration
            val ctx = context ?: com.example.WastiApplication.instance
            if (ctx != null) {
                com.example.data.node.WastiNodeDiscoveryManager.getInstance(ctx).registerService(selectedPort)
            }

            Result.success(updatedInfo)
        } catch (e: Exception) {
            val errMsg = "Failed to start local server on port $selectedPort: ${e.message}"
            Log.e(TAG, errMsg, e)
            _serverInfo.value = _serverInfo.value.copy(
                state = LocalServerState.FAILED,
                lastError = errMsg
            )
            Result.failure(e)
        }
    }

    @Synchronized
    fun stopServer(reason: String = "Normal shutdown"): Result<Unit> {
        if (_serverInfo.value.state != LocalServerState.RUNNING && _serverInfo.value.state != LocalServerState.STARTING) {
            return Result.success(Unit)
        }

        _serverInfo.value = _serverInfo.value.copy(state = LocalServerState.STOPPING)
        return try {
            httpServer?.stop(0)
            httpServer = null
            executor.shutdown()

            // Stage 13: Stop WebSocket Server
            WastiWebSocketServer.getInstance(context).stop()

            // Stage 12: Network Service Discovery unregistration
            val ctx = context ?: com.example.WastiApplication.instance
            if (ctx != null) {
                com.example.data.node.WastiNodeDiscoveryManager.getInstance(ctx).unregisterService()
            }

            _serverInfo.value = _serverInfo.value.copy(
                state = LocalServerState.STOPPED,
                lastError = null
            )

            UnifiedExecutionFabric.instance.realityRegistry.updateCapabilityReality(
                CapabilityReality(
                    capabilityId = "LOCAL_SERVER",
                    category = "TRANSPORT",
                    implementationStatus = ImplementationStatus.READY,
                    liveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
                    executionStatus = CapabilityExecutionStatus.UNAVAILABLE,
                    authenticationStatus = CapabilityAuthStatus.NOT_REQUIRED,
                    provider = "WastiLocalServerManager",
                    supportedOperations = listOf("start_server", "stop_server", "get_status"),
                    limitations = listOf("Server stopped: $reason"),
                    realityState = CapabilityRealityState.UNAVAILABLE
                )
            )

            Log.i(TAG, "Wasti Embedded Server stopped ($reason)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping local server", e)
            _serverInfo.value = _serverInfo.value.copy(
                state = LocalServerState.FAILED,
                lastError = e.message
            )
            Result.failure(e)
        }
    }

    private fun incrementRequestCount() {
        val curr = _serverInfo.value
        _serverInfo.value = curr.copy(requestsHandled = curr.requestsHandled + 1)
    }

    // --- Inner HTTP Handlers ---

    private inner class HealthHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val responseJson = JSONObject().apply {
                put("status", "UP")
                put("os", "WastiAI OS")
                put("brain", "OPERATIONAL")
                put("serverState", _serverInfo.value.state.name)
                put("port", _serverInfo.value.port)
                put("uptimeMs", System.currentTimeMillis() - _serverInfo.value.startedAt)
                put("requestsHandled", _serverInfo.value.requestsHandled)
            }.toString()

            sendJsonResponse(exchange, 200, responseJson)
        }
    }

    private inner class StatusHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val realityReport = UnifiedExecutionFabric.instance.realityRegistry.getSystemRealityReport()
            val reportArray = org.json.JSONArray()
            for (cap in realityReport) {
                reportArray.put(JSONObject().apply {
                    put("id", cap.capabilityId)
                    put("category", cap.category)
                    put("status", cap.executionStatus.name)
                    put("reality", cap.realityState.name)
                    put("provider", cap.provider)
                })
            }

            val runtimeContext = WastiOSRuntime.getInstance(context).activeContext.value
            val isEmergencyStopped = com.example.data.di.WastiServiceLocator.emergencyStopController.isEmergencyStopped

            val responseJson = JSONObject().apply {
                put("system", "WastiAI OS")
                put("server", JSONObject().apply {
                    put("state", _serverInfo.value.state.name)
                    put("port", _serverInfo.value.port)
                })
                put("runtime", JSONObject().apply {
                    put("isBusy", runtimeContext.isBusy)
                    put("activeTask", runtimeContext.activeTaskId)
                    put("activeTool", runtimeContext.activeTool)
                    put("progressMessage", runtimeContext.progressMessage)
                    put("isEmergencyStopped", isEmergencyStopped)
                })
                put("capabilities", reportArray)
            }.toString()

            sendJsonResponse(exchange, 200, responseJson)
        }
    }

    private inner class CapabilitiesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val realityReport = UnifiedExecutionFabric.instance.realityRegistry.getSystemRealityReport()
            val reportArray = org.json.JSONArray()
            for (cap in realityReport) {
                reportArray.put(JSONObject().apply {
                    put("id", cap.capabilityId)
                    put("category", cap.category)
                    put("status", cap.executionStatus.name)
                    put("reality", cap.realityState.name)
                    put("provider", cap.provider)
                    put("supportedOperations", org.json.JSONArray(cap.supportedOperations))
                    put("limitations", org.json.JSONArray(cap.limitations))
                })
            }

            val responseJson = JSONObject().apply {
                put("system", "WastiAI OS")
                put("totalCapabilities", realityReport.size)
                put("capabilities", reportArray)
            }.toString()

            sendJsonResponse(exchange, 200, responseJson)
        }
    }

    private inner class ExecutionHandler(private val scope: CoroutineScope) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()

            if (method == "POST" && (path.endsWith("/cancel") || path.contains("/cancel"))) {
                // Cancel active execution
                val body = InputStreamReader(exchange.requestBody).readText()
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val reason = json.optString("reason", "Cancelled via HTTP execution endpoint")
                val cancelled = WastiCommandTransport.getInstance(context).cancelActiveExecution(reason)

                val responseJson = JSONObject().apply {
                    put("success", cancelled)
                    put("message", if (cancelled) "Execution cancelled successfully" else "No active execution to cancel")
                }.toString()
                sendJsonResponse(exchange, 200, responseJson)
                return
            }

            // GET active execution status
            val runtimeContext = WastiOSRuntime.getInstance(context).activeContext.value
            val responseJson = JSONObject().apply {
                put("isBusy", runtimeContext.isBusy)
                put("activeTaskId", runtimeContext.activeTaskId)
                put("activeCommand", runtimeContext.activeCommand)
                put("activeTool", runtimeContext.activeTool)
                put("progressMessage", runtimeContext.progressMessage)
                put("origin", runtimeContext.currentOrigin?.name ?: "IDLE")
                put("iteration", runtimeContext.currentIteration)
                put("lastResultSummary", runtimeContext.lastResultSummary)
                put("lastError", runtimeContext.lastError)
            }.toString()

            sendJsonResponse(exchange, 200, responseJson)
        }
    }

    private inner class EmergencyStopHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val method = exchange.requestMethod.uppercase()
            val controller = com.example.data.di.WastiServiceLocator.emergencyStopController

            if (method == "POST") {
                val body = InputStreamReader(exchange.requestBody).readText()
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                val reason = json.optString("reason", "Emergency stop triggered via HTTP API")

                WastiCommandTransport.getInstance(context).triggerEmergencyStop(reason)

                val responseJson = JSONObject().apply {
                    put("success", true)
                    put("isEmergencyStopped", true)
                    put("reason", reason)
                    put("message", "Emergency stop engaged across all Wasti subsystems")
                }.toString()
                sendJsonResponse(exchange, 200, responseJson)
            } else {
                val isStopped = controller.isEmergencyStopped
                val responseJson = JSONObject().apply {
                    put("isEmergencyStopped", isStopped)
                    put("activeContext", WastiOSRuntime.getInstance(context).activeContext.value.isBusy)
                }.toString()
                sendJsonResponse(exchange, 200, responseJson)
            }
        }
    }

    private inner class ChatHandler(private val scope: CoroutineScope) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val prompt = json.optString("prompt", "")

            if (prompt.isBlank()) {
                sendJsonResponse(exchange, 400, JSONObject().put("error", "Missing 'prompt' field").toString())
                return
            }

            scope.launch {
                try {
                    val (reply, model) = WastiCore.executeOrchestratedRequest(
                        userPrompt = prompt,
                        systemInstruction = "You are WastiAI OS Executive Brain operating through Local Server Gateway.",
                        activeAgentId = "local_server_client"
                    )
                    val res = JSONObject().apply {
                        put("success", true)
                        put("reply", reply)
                        put("provider", model)
                    }.toString()
                    sendJsonResponse(exchange, 200, res)
                } catch (e: Exception) {
                    val res = JSONObject().apply {
                        put("success", false)
                        put("error", e.message ?: "Chat execution failure")
                    }.toString()
                    sendJsonResponse(exchange, 500, res)
                }
            }
        }
    }

    private inner class ExecuteHandler(
        private val scope: CoroutineScope,
        private val targetContext: Context?
    ) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val capabilityId = json.optString("capabilityId", "")
            val paramsObj = json.optJSONObject("parameters") ?: JSONObject()
            val paramsMap = mutableMapOf<String, Any>()
            val keys = paramsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                paramsMap[k] = paramsObj.get(k)
            }

            if (capabilityId.isBlank()) {
                sendJsonResponse(exchange, 400, JSONObject().put("error", "Missing 'capabilityId'").toString())
                return
            }

            scope.launch {
                try {
                    val req = UnifiedExecutionRequest(
                        capabilityId = capabilityId,
                        parameters = paramsMap
                    )
                    val result = UnifiedExecutionFabric.instance.execute(req, targetContext)
                    val duration = result.completedAt - result.startedAt
                    val res = JSONObject().apply {
                        put("taskId", result.taskId)
                        put("status", result.status.name)
                        put("verification", result.verificationStatus.name)
                        put("output", result.output)
                        put("error", result.error)
                        put("executionDurationMs", duration)
                    }.toString()
                    sendJsonResponse(exchange, 200, res)
                } catch (e: Exception) {
                    val res = JSONObject().apply {
                        put("status", "FAILED")
                        put("error", e.message ?: "Execution failed")
                    }.toString()
                    sendJsonResponse(exchange, 500, res)
                }
            }
        }
    }

    private inner class TransportHandler(private val scope: CoroutineScope) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val command = json.optString("command", "")
            val originName = json.optString("origin", "LOCAL_SERVER")
            val targetAgent = json.optString("agentId", "ceo_agent")
            val authToken = exchange.requestHeaders.getFirst("X-Wasti-Auth-Token") ?: json.optString("authToken").takeIf { it.isNotBlank() }
            val deviceId = exchange.requestHeaders.getFirst("X-Wasti-Device-Id") ?: json.optString("deviceId").takeIf { it.isNotBlank() }
            val requestId = exchange.requestHeaders.getFirst("X-Wasti-Request-Id") ?: json.optString("requestId").takeIf { it.isNotBlank() }
            val correlationId = exchange.requestHeaders.getFirst("X-Wasti-Correlation-Id") ?: json.optString("correlationId").takeIf { it.isNotBlank() }

            val origin = try {
                CommandOrigin.valueOf(originName.uppercase())
            } catch (_: Exception) {
                CommandOrigin.LOCAL_SERVER
            }

            scope.launch {
                val result = WastiCommandTransport.getInstance(context).dispatchCommand(
                    command = command,
                    origin = origin,
                    executionMode = ExecutionMode.AUTONOMOUS,
                    targetAgentId = targetAgent,
                    clientHost = exchange.remoteAddress?.hostString ?: "127.0.0.1",
                    authToken = authToken,
                    deviceId = deviceId,
                    requestId = requestId,
                    correlationId = correlationId
                )

                when (result) {
                    is CommandSubmissionResult.Accepted -> {
                        val res = JSONObject().apply {
                            put("success", true)
                            put("commandId", result.commandId)
                            put("message", result.message)
                        }.toString()
                        sendJsonResponse(exchange, 200, res)
                    }
                    is CommandSubmissionResult.ImmediateSuccess -> {
                        val res = JSONObject().apply {
                            put("success", true)
                            put("commandId", result.commandId)
                            put("output", result.output)
                            put("evidence", result.verificationEvidence)
                        }.toString()
                        sendJsonResponse(exchange, 200, res)
                    }
                    is CommandSubmissionResult.Rejected -> {
                        val res = JSONObject().apply {
                            put("success", false)
                            put("reason", result.reason)
                        }.toString()
                        sendJsonResponse(exchange, 400, res)
                    }
                }
            }
        }
    }

    // ========================================================
    // STAGE 11: MULTI-DEVICE PAIRING & EVENT STREAMING HANDLERS
    // ========================================================

    private inner class PairingRequestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val deviceId = json.optString("deviceId", java.util.UUID.randomUUID().toString())
            val deviceName = json.optString("deviceName", "Remote Companion")
            val platformStr = json.optString("platform", "WEB")
            val platform = try {
                com.example.data.node.NodePlatform.valueOf(platformStr.uppercase())
            } catch (_: Exception) {
                com.example.data.node.NodePlatform.WEB
            }

            val challenge = WastiCommandTransport.getInstance(context).createPairingChallenge(
                deviceId = deviceId,
                deviceName = deviceName,
                platform = platform
            )

            val res = JSONObject().apply {
                put("success", true)
                put("code", challenge.code)
                put("deviceId", challenge.deviceId)
                put("deviceName", challenge.deviceName)
                put("platform", challenge.platform.name)
                put("expiresAt", challenge.expiresAt)
            }.toString()

            sendJsonResponse(exchange, 200, res)
        }
    }

    private inner class PairingVerifyHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val code = json.optString("code", "").trim()
            val deviceId = json.optString("deviceId", "").trim()
            val endpointUrl = json.optString("endpointUrl").takeIf { it.isNotBlank() }

            val paired = WastiCommandTransport.getInstance(context).verifyPairingChallenge(
                code = code,
                deviceId = deviceId,
                endpointUrl = endpointUrl
            )

            if (paired != null) {
                val res = JSONObject().apply {
                    put("success", true)
                    put("sessionToken", paired.sessionToken)
                    put("deviceId", paired.deviceId)
                    put("deviceName", paired.deviceName)
                    put("platform", paired.platform.name)
                    put("pairedAt", paired.pairedAt)
                }.toString()
                sendJsonResponse(exchange, 200, res)
            } else {
                val res = JSONObject().apply {
                    put("success", false)
                    put("error", "Invalid or expired pairing code.")
                }.toString()
                sendJsonResponse(exchange, 400, res)
            }
        }
    }

    private inner class PairingDevicesHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val devices = WastiCommandTransport.getInstance(context).getPairedDevices()
            val arr = org.json.JSONArray()
            for (dev in devices) {
                arr.put(JSONObject().apply {
                    put("deviceId", dev.deviceId)
                    put("deviceName", dev.deviceName)
                    put("platform", dev.platform.name)
                    put("pairedAt", dev.pairedAt)
                    put("lastSeenAt", dev.lastSeenAt)
                })
            }
            val res = JSONObject().apply {
                put("devices", arr)
                put("count", devices.size)
            }.toString()
            sendJsonResponse(exchange, 200, res)
        }
    }

    private inner class PairingRevokeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val deviceId = json.optString("deviceId", "")

            val revoked = WastiCommandTransport.getInstance(context).revokeDevice(deviceId)
            val res = JSONObject().apply {
                put("success", revoked)
                put("deviceId", deviceId)
            }.toString()
            sendJsonResponse(exchange, if (revoked) 200 else 404, res)
        }
    }

    private inner class StreamHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val currentContext = WastiOSRuntime.getInstance(context).activeContext.value
            val recentExecutions = com.example.data.memory.ExecutionMemoryRecorder.getRecentExecutions(10)

            val eventsArray = org.json.JSONArray()
            for (exec in recentExecutions) {
                eventsArray.put(JSONObject().apply {
                    put("taskId", exec.taskId)
                    put("intent", exec.interpretedIntent)
                    put("capability", exec.selectedCapability)
                    put("isSuccess", exec.isSuccess)
                    put("timestamp", exec.timestamp)
                })
            }

            val res = JSONObject().apply {
                put("type", "sync_state")
                put("activeTaskId", currentContext.activeTaskId)
                put("activeCommand", currentContext.activeCommand)
                put("isBusy", currentContext.isBusy)
                put("progressMessage", currentContext.progressMessage)
                put("recentEvents", eventsArray)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            sendJsonResponse(exchange, 200, res)
        }
    }

    private inner class WebCompanionDashboardHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Wasti AI OS — Web Companion</title>
                    <style>
                        :root {
                            --bg: #0D1117;
                            --card: #161B22;
                            --border: #30363D;
                            --text: #C9D1D9;
                            --accent: #58A6FF;
                            --success: #238636;
                            --danger: #DA3633;
                            --subtext: #8B949E;
                        }
                        body {
                            margin: 0;
                            padding: 24px;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                            background-color: var(--bg);
                            color: var(--text);
                        }
                        .container {
                            max-width: 800px;
                            margin: 0 auto;
                        }
                        header {
                            display: flex;
                            align-items: center;
                            justify-content: space-between;
                            border-bottom: 1px solid var(--border);
                            padding-bottom: 16px;
                            margin-bottom: 24px;
                        }
                        h1 { margin: 0; font-size: 24px; color: #FFF; }
                        .badge {
                            background: var(--success);
                            color: #FFF;
                            padding: 4px 10px;
                            border-radius: 12px;
                            font-size: 12px;
                            font-weight: bold;
                        }
                        .card {
                            background: var(--card);
                            border: 1px solid var(--border);
                            border-radius: 8px;
                            padding: 20px;
                            margin-bottom: 20px;
                        }
                        .input-group {
                            display: flex;
                            gap: 10px;
                            margin-top: 12px;
                        }
                        input[type="text"] {
                            flex: 1;
                            background: #090D13;
                            border: 1px solid var(--border);
                            color: #FFF;
                            padding: 10px 14px;
                            border-radius: 6px;
                            font-size: 14px;
                        }
                        button {
                            background: var(--accent);
                            color: #0D1117;
                            border: none;
                            border-radius: 6px;
                            padding: 10px 18px;
                            font-weight: 600;
                            cursor: pointer;
                        }
                        button.danger {
                            background: var(--danger);
                            color: #FFF;
                        }
                        pre {
                            background: #090D13;
                            padding: 12px;
                            border-radius: 6px;
                            overflow-x: auto;
                            font-size: 13px;
                            color: #7EE787;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <header>
                            <div>
                                <h1>Wasti AI OS — Web Room</h1>
                                <span style="font-size: 13px; color: var(--subtext);">One Brain &bull; Infinite Rooms &bull; Connected Node</span>
                            </div>
                            <span class="badge" id="connStatus">ONLINE</span>
                        </header>

                        <div class="card">
                            <h3>Companion Authentication & Pairing</h3>
                            <div class="input-group">
                                <input type="text" id="pairingCode" placeholder="Enter 6-digit Code (e.g. PAIR-123456)">
                                <button onclick="verifyPairing()">Pair Device</button>
                            </div>
                            <p id="authStatus" style="font-size: 13px; color: var(--subtext); margin-top: 8px;"></p>
                        </div>

                        <div class="card">
                            <h3>Command Dispatch</h3>
                            <div class="input-group">
                                <input type="text" id="commandInput" placeholder="Submit autonomous command to Wasti...">
                                <button onclick="sendCommand()">Send</button>
                                <button class="danger" onclick="emergencyStop()">STOP</button>
                            </div>
                        </div>

                        <div class="card">
                            <h3>Live Stream & Status</h3>
                            <pre id="streamOutput">Connecting to Wasti stream...</pre>
                        </div>
                    </div>

                    <script>
                        let sessionToken = localStorage.getItem("wasti_token") || "wasti-local-secure-token";
                        let deviceId = localStorage.getItem("wasti_dev_id") || "web_companion_" + Math.floor(Math.random()*10000);
                        localStorage.setItem("wasti_dev_id", deviceId);

                        async function verifyPairing() {
                            const code = document.getElementById("pairingCode").value.trim();
                            if (!code) return;
                            try {
                                const res = await fetch("/api/pairing/verify", {
                                    method: "POST",
                                    headers: { "Content-Type": "application/json" },
                                    body: JSON.stringify({ code: code, deviceId: deviceId, deviceName: "Web Companion Browser" })
                                });
                                const data = await res.json();
                                if (data.sessionToken) {
                                    sessionToken = data.sessionToken;
                                    localStorage.setItem("wasti_token", sessionToken);
                                    document.getElementById("authStatus").innerText = "Paired successfully as " + data.deviceName;
                                } else {
                                    document.getElementById("authStatus").innerText = "Pairing failed: " + (data.error || "Invalid code");
                                }
                            } catch(e) {
                                document.getElementById("authStatus").innerText = "Error: " + e.message;
                            }
                        }

                        async function sendCommand() {
                            const cmd = document.getElementById("commandInput").value.trim();
                            if (!cmd) return;
                            try {
                                const res = await fetch("/api/command", {
                                    method: "POST",
                                    headers: {
                                        "Content-Type": "application/json",
                                        "X-Wasti-Auth-Token": sessionToken,
                                        "X-Wasti-Device-Id": deviceId,
                                        "X-Wasti-Request-Id": "req_" + Date.now()
                                    },
                                    body: JSON.stringify({ command: cmd, origin: "WEB_COMPANION" })
                                });
                                const data = await res.json();
                                pollStream();
                            } catch(e) {
                                alert("Failed: " + e.message);
                            }
                        }

                        async function emergencyStop() {
                            await fetch("/api/emergency-stop", {
                                method: "POST",
                                headers: { "Content-Type": "application/json" },
                                body: JSON.stringify({ reason: "Triggered from Web Companion UI" })
                            });
                            pollStream();
                        }

                        async function pollStream() {
                            try {
                                const res = await fetch("/api/stream");
                                const data = await res.json();
                                document.getElementById("streamOutput").innerText = JSON.stringify(data, null, 2);
                            } catch(e) {
                                document.getElementById("streamOutput").innerText = "Stream offline: " + e.message;
                            }
                        }

                        let ws = null;
                        function connectWebSocket() {
                            try {
                                const wsPort = (parseInt(location.port) || 8080) + 1;
                                const wsUrl = "ws://" + location.hostname + ":" + wsPort + "/";
                                ws = new WebSocket(wsUrl);

                                ws.onopen = () => {
                                    document.getElementById("connStatus").innerText = "WS CONNECTED";
                                    document.getElementById("connStatus").style.background = "#238636";
                                    // Authenticate session
                                    ws.send(JSON.stringify({
                                        type: "AUTHENTICATE",
                                        token: sessionToken,
                                        deviceId: deviceId,
                                        platform: "WEB"
                                    }));
                                };

                                ws.onmessage = (event) => {
                                    try {
                                        const parsed = JSON.parse(event.data);
                                        document.getElementById("streamOutput").innerText = JSON.stringify(parsed, null, 2);
                                    } catch(e) {
                                        document.getElementById("streamOutput").innerText = event.data;
                                    }
                                };

                                ws.onclose = () => {
                                    document.getElementById("connStatus").innerText = "WS RECONNECTING";
                                    document.getElementById("connStatus").style.background = "#D29922";
                                    setTimeout(connectWebSocket, 3000);
                                };

                                ws.onerror = (err) => {
                                    document.getElementById("connStatus").innerText = "HTTP FALLBACK";
                                    document.getElementById("connStatus").style.background = "#8B949E";
                                };
                            } catch(e) {
                                console.warn("WebSocket init error:", e);
                            }
                        }

                        connectWebSocket();
                        setInterval(pollStream, 4000);
                    </script>
                </body>
                </html>
            """.trimIndent()

            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(bytes)
                os.flush()
            }
        }
    }

    private inner class TerminalHandler(private val scope: CoroutineScope) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val cmd = json.optString("command", "").trim()
            val workDir = json.optString("workingDirectory", "home/wasti")

            if (cmd.isBlank()) {
                sendJsonResponse(exchange, 400, JSONObject().put("error", "Missing 'command'").toString())
                return
            }

            scope.launch {
                try {
                    val req = ExecutionRequest(
                        command = cmd,
                        workingDirectory = workDir,
                        initiatedBy = "LocalServerHttpGateway"
                    )
                    val result = wreManager.execute(req)
                    val res = JSONObject().apply {
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("exitCode", result.exitCode)
                        put("status", result.status.name)
                        put("verified", result.verified)
                        put("evidence", result.verificationEvidence)
                        put("durationMs", result.durationMs)
                    }.toString()
                    sendJsonResponse(exchange, 200, res)
                } catch (e: Exception) {
                    sendJsonResponse(exchange, 500, JSONObject().put("error", e.message).toString())
                }
            }
        }
    }

    private inner class NodeHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val nodeManager = com.example.data.node.WastiNodeManager.getInstance()
            val allNodes = nodeManager.getAllNodes()
            val nodesArray = org.json.JSONArray()
            for (node in allNodes) {
                nodesArray.put(JSONObject().apply {
                    put("nodeId", node.nodeId)
                    put("nodeName", node.nodeName)
                    put("platform", node.platform.name)
                    put("connectionState", node.connectionState.name)
                    put("isLocal", node.isLocal)
                    put("endpointUrl", node.endpointUrl)
                })
            }
            val res = JSONObject().apply {
                put("nodes", nodesArray)
                put("count", allNodes.size)
            }.toString()
            sendJsonResponse(exchange, 200, res)
        }
    }

    private inner class ActionHandler(private val scope: CoroutineScope) : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                sendJsonResponse(exchange, 405, JSONObject().put("error", "Method not allowed. Use POST.").toString())
                return
            }

            val body = InputStreamReader(exchange.requestBody).readText()
            val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            val actionType = json.optString("action", "")

            scope.launch {
                try {
                    val action = when (actionType.lowercase()) {
                        "navigate" -> WastiAppAction.NavigateTo(json.optString("destinationId", "dashboard"))
                        "open_project" -> WastiAppAction.OpenProject(json.optString("projectId", ""))
                        "search_memory" -> WastiAppAction.SearchMemory(json.optString("query", ""))
                        "terminal" -> WastiAppAction.ExecuteTerminalCommand(json.optString("command", "pwd"))
                        "voice" -> WastiAppAction.TriggerVoiceModal()
                        "workflow" -> WastiAppAction.StartBackgroundWorkflow(json.optString("request", ""))
                        else -> null
                    }

                    if (action != null) {
                        WastiAppActionBus.dispatch(action)
                        val res = JSONObject().apply {
                            put("success", true)
                            put("action", actionType)
                            put("message", "Action dispatched to WastiAppActionBus")
                        }.toString()
                        sendJsonResponse(exchange, 200, res)
                    } else {
                        sendJsonResponse(exchange, 400, JSONObject().put("error", "Unknown action: $actionType").toString())
                    }
                } catch (e: Exception) {
                    sendJsonResponse(exchange, 500, JSONObject().put("error", e.message).toString())
                }
            }
        }
    }

    private inner class EventsHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            incrementRequestCount()
            val recentExecutions = com.example.data.memory.ExecutionMemoryRecorder.getRecentExecutions(20)
            val eventsArray = org.json.JSONArray()
            for (exec in recentExecutions) {
                eventsArray.put(JSONObject().apply {
                    put("taskId", exec.taskId)
                    put("intent", exec.interpretedIntent)
                    put("capability", exec.selectedCapability)
                    put("isSuccess", exec.isSuccess)
                    put("durationMs", exec.durationMs)
                    put("timestamp", exec.timestamp)
                })
            }

            val responseJson = JSONObject().apply {
                put("events", eventsArray)
                put("count", recentExecutions.size)
            }.toString()

            sendJsonResponse(exchange, 200, responseJson)
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { os ->
            os.write(bytes)
            os.flush()
        }
    }

    companion object {
        private const val TAG = "WastiLocalServer"

        @Volatile
        private var instance: WastiLocalServerManager? = null

        fun getInstance(context: Context? = null): WastiLocalServerManager {
            return instance ?: synchronized(this) {
                instance ?: WastiLocalServerManager(context).also { instance = it }
            }
        }
    }
}
