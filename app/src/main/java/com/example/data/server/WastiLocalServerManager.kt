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
            server.createContext("/api/nodes", NodeHandler())

            server.start()
            httpServer = server

            val updatedInfo = LocalServerInfo(
                state = LocalServerState.RUNNING,
                port = selectedPort,
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
                        "transport_gateway", "terminal_gateway", "events_stream"
                    ),
                    limitations = listOf("Bound to local interface 127.0.0.1:$selectedPort"),
                    realityState = CapabilityRealityState.NATIVE
                )
            )

            Log.i(TAG, "Wasti Embedded Server successfully started on port $selectedPort")
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
            val authToken = exchange.requestHeaders.getFirst("X-Wasti-Auth-Token")

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
                    authToken = authToken
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
