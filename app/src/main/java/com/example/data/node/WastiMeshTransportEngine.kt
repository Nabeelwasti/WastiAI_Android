package com.example.data.node

import android.content.Context
import android.util.Log
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.data.agent.runtime.UnifiedExecutionResult
import com.example.data.agent.runtime.UnifiedExecutionStatus
import com.example.data.agent.runtime.UnifiedVerificationStatus
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.data.mesh.WastiSovereignAddressResolver

data class MeshDiscoveredNode(
    val nodeId: String,
    val nodeName: String,
    val platform: NodePlatform,
    val ipAddress: String,
    val port: Int,
    val advertisedCapabilities: Set<String>,
    val capabilityFingerprint: String,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class MeshExecutionResult(
    val isSuccess: Boolean,
    val targetNodeId: String,
    val output: String,
    val executionLatencyMs: Long,
    val verificationEvidence: String
)

object WastiMeshTransportEngine {
    private const val TAG = "MeshTransportEngine"
    const val MESH_BROADCAST_PORT = 35260
    const val MESH_EXECUTION_PORT = 35261
    private const val BEACON_INTERVAL_MS = 10_000L

    private val discoveredPeers = ConcurrentHashMap<String, MeshDiscoveredNode>()
    private var beaconJob: Job? = null
    private var listenerJob: Job? = null
    private var executionServerJob: Job? = null
    private var executionServerSocket: ServerSocket? = null
    @Volatile private var isMeshActive = false

    fun getDiscoveredPeers(): List<MeshDiscoveredNode> = discoveredPeers.values.toList()

    fun registerDiscoveredPeerForTesting(peer: MeshDiscoveredNode) {
        discoveredPeers[peer.nodeId] = peer
    }

    fun startMesh(context: Context, localNodeId: String = "wasti_local_host") {
        if (isMeshActive) return
        isMeshActive = true
        val scope = CoroutineScope(Dispatchers.IO)

        beaconJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                while (isActive && isMeshActive) {
                    try {
                        val localNode = WastiServiceLocator.nodeManager.getNode("local_android_node")
                        val capabilities = localNode?.capabilities ?: setOf("terminal", "files", "device_control")
                        val beacon = JSONObject().apply {
                            put("header", "WASTI_MESH_BEACON")
                            put("version", 1)
                            put("nodeId", localNodeId)
                            put("nodeName", "Wasti Mobile Host (${android.os.Build.MODEL})")
                            put("platform", NodePlatform.ANDROID.name)
                            put("port", MESH_BROADCAST_PORT)
                            put("capabilities", JSONArray(capabilities.toList()))
                            put("timestamp", System.currentTimeMillis())
                        }
                        val data = beacon.toString().toByteArray(Charsets.UTF_8)
                        socket.send(
                            DatagramPacket(
                                data,
                                data.size,
                                InetAddress.getByName("255.255.255.255"),
                                MESH_BROADCAST_PORT
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Beacon broadcast warning: ${e.message}")
                    }
                    delay(BEACON_INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh beacon socket error: ${e.message}", e)
            } finally {
                socket?.close()
            }
        }

        listenerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MESH_BROADCAST_PORT)
                val buffer = ByteArray(4096)
                while (isActive && isMeshActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val payload = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        if (payload.optString("header") != "WASTI_MESH_BEACON") continue

                        val peerId = payload.getString("nodeId")
                        if (peerId == localNodeId) continue
                        val peerName = payload.optString("nodeName", "Remote Wasti Node")
                        val platform = parsePlatform(payload.optString("platform", "DESKTOP"))
                        val peerCapabilities = readCapabilities(payload.optJSONArray("capabilities"))
                        val ipAddress = packet.address.hostAddress ?: "127.0.0.1"
                        val fingerprint = computeFingerprint(peerCapabilities)

                        discoveredPeers[peerId] = MeshDiscoveredNode(
                            nodeId = peerId,
                            nodeName = peerName,
                            platform = platform,
                            ipAddress = ipAddress,
                            port = payload.optInt("port", MESH_BROADCAST_PORT),
                            advertisedCapabilities = peerCapabilities,
                            capabilityFingerprint = fingerprint
                        )

                        val advertised = peerCapabilities.associateWith { capability ->
                            AdvertisedCapabilityInfo(
                                capabilityId = capability,
                                version = "1.0.0",
                                realityState = CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,
                                provider = peerName,
                                supportedOperations = emptyList(),
                                isLocallyExecutable = false
                            )
                        }

                        WastiServiceLocator.nodeManager.registerNode(
                            WastiNode(
                                nodeId = peerId,
                                nodeName = peerName,
                                platform = platform,
                                capabilities = peerCapabilities,
                                advertisedCapabilities = advertised,
                                capabilityFingerprint = fingerprint,
                                connectionState = NodeConnectionState.CONNECTED,
                                trustState = NodeTrustState.PAIRED,
                                isLocal = false,
                                endpointUrl = "http://$ipAddress:$MESH_EXECUTION_PORT",
                                networkAddress = ipAddress,
                                dataLocality = NodeDataLocality.TRUSTED_LAN
                            )
                        )
                    } catch (e: Exception) {
                        if (isMeshActive) Log.w(TAG, "Packet receive warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mesh listener socket error: ${e.message}", e)
            } finally {
                socket?.close()
            }
        }

        executionServerJob = scope.launch {
            var server: ServerSocket? = null
            try {
                val channel = java.nio.channels.ServerSocketChannel.open()
                channel.socket().reuseAddress = true
                val loopbackEndpoint: InetSocketAddress = WastiSovereignAddressResolver.createLoopbackEndpoint(MESH_EXECUTION_PORT)
                channel.bind(loopbackEndpoint)
                server = channel.socket()
                executionServerSocket = server
                while (isActive && isMeshActive) {
                    try {
                        val client = server.accept()
                        launch { handleClientExecution(client) }
                    } catch (e: Exception) {
                        if (isMeshActive) Log.w(TAG, "Mesh accept warning: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mesh execution bind warning: ${e.message}")
            } finally {
                server?.close()
            }
        }
    }

    suspend fun handleClientExecution(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return@withContext
            val requestJson = JSONObject(line)
            val parametersJson = requestJson.optJSONObject("parameters") ?: JSONObject()
            val parameters = mutableMapOf<String, String>()
            parametersJson.keys().forEach { key -> parameters[key] = parametersJson.getString(key) }

            val execution = UnifiedExecutionFabric.instance.execute(
                UnifiedExecutionRequest(
                    taskId = requestJson.optString("taskId", UUID.randomUUID().toString()),
                    actionId = requestJson.optString("actionId", "mesh_remote_action"),
                    capabilityId = requestJson.optString("capabilityId", "general_computation"),
                    parameters = parameters,
                    originatingNodeId = requestJson.optString("callerNodeId", "mesh_remote")
                ),
                null
            )

            val response = JSONObject().apply {
                put("taskId", execution.taskId)
                put("actionId", execution.actionId)
                put("capabilityId", execution.capabilityId)
                put("status", execution.status.name)
                put("output", execution.output)
                put("error", execution.error ?: "")
                put("executor", execution.executor)
                put("startedAt", execution.startedAt)
                put("completedAt", execution.completedAt)
                put("verificationStatus", execution.verificationStatus.name)
                put("verificationEvidence", execution.verificationEvidence ?: "")
            }
            writer.write(response.toString())
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client execution: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    fun stopMesh() {
        isMeshActive = false
        beaconJob?.cancel()
        listenerJob?.cancel()
        executionServerJob?.cancel()
        try { executionServerSocket?.close() } catch (_: Exception) { }
        executionServerSocket = null
        discoveredPeers.clear()
    }

    suspend fun dispatchRemoteTask(
        targetNodeId: String,
        request: UnifiedExecutionRequest
    ): UnifiedExecutionResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val peer = discoveredPeers[targetNodeId]
            ?: WastiServiceLocator.nodeManager.getNode(targetNodeId)?.let { node ->
                MeshDiscoveredNode(
                    nodeId = node.nodeId,
                    nodeName = node.nodeName,
                    platform = node.platform,
                    ipAddress = node.networkAddress?.ifBlank { "127.0.0.1" } ?: "127.0.0.1",
                    port = MESH_EXECUTION_PORT,
                    advertisedCapabilities = node.capabilities,
                    capabilityFingerprint = node.capabilityFingerprint
                )
            }

        if (peer == null) {
            return@withContext UnifiedExecutionResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = UnifiedExecutionStatus.FAILED,
                output = "",
                error = "NODE_UNREACHABLE:$targetNodeId",
                executor = "WastiMeshTransportEngine",
                startedAt = start,
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED,
                verificationEvidence = "No discovered peer evidence"
            )
        }

        val inet = try {
            java.net.InetAddress.getByName(targetIp)
        } catch (_: Exception) {
            null
        }

        if (inet == null || inet.isAnyLocalAddress || targetIp.contains("/")) {
            return@withContext UnifiedExecutionResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = UnifiedExecutionStatus.FAILED,
                output = "",
                error = "INVALID_PEER_IP:$targetIp",
                executor = "WastiMeshTransportEngine",
                startedAt = start,
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED,
                verificationEvidence = "Invalid mesh peer network address"
            )
        }

        try {
            val endpoint: InetSocketAddress = WastiSovereignAddressResolver.createValidatedEndpoint(inet, MESH_EXECUTION_PORT)
            java.nio.channels.SocketChannel.open().socket().use { socket ->
                socket.connect(endpoint, 3000)
                socket.soTimeout = 10_000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestJson = JSONObject().apply {
                    put("taskId", request.taskId)
                    put("actionId", request.actionId)
                    put("capabilityId", request.capabilityId)
                    put("callerNodeId", "wasti_mobile_client")
                    put("parameters", JSONObject().apply {
                        request.parameters.forEach { (key, value) -> put(key, value) }
                    })
                }
                writer.write(requestJson.toString())
                writer.newLine()
                writer.flush()

                val responseLine = reader.readLine() ?: throw IllegalStateException("EMPTY_REMOTE_RESPONSE")
                val response = JSONObject(responseLine)
                val status = parseExecutionStatus(response.optString("status"))
                val remoteVerification = parseVerificationStatus(response.optString("verificationStatus", "UNVERIFIED"))

                return@withContext UnifiedExecutionResult(
                    taskId = response.optString("taskId", request.taskId),
                    actionId = response.optString("actionId", request.actionId),
                    capabilityId = response.optString("capabilityId", request.capabilityId),
                    status = status,
                    output = response.optString("output", ""),
                    error = response.optString("error").takeIf { it.isNotBlank() },
                    executor = response.optString("executor", "MeshPeer_${peer.nodeId}"),
                    startedAt = response.optLong("startedAt", start),
                    completedAt = response.optLong("completedAt", System.currentTimeMillis()),
                    verificationStatus = if (remoteVerification == UnifiedVerificationStatus.VERIFIED) {
                        UnifiedVerificationStatus.UNVERIFIED
                    } else {
                        remoteVerification
                    },
                    verificationEvidence = response.optString(
                        "verificationEvidence",
                        "Remote evidence received; canonical verification not established"
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote execution failed on ${peer.ipAddress}:$MESH_EXECUTION_PORT: ${e.message}")
            return@withContext UnifiedExecutionResult(
                taskId = request.taskId,
                actionId = request.actionId,
                capabilityId = request.capabilityId,
                status = UnifiedExecutionStatus.FAILED,
                output = "",
                error = "REMOTE_EXECUTION_FAILED:${e.message ?: "unknown"}",
                executor = "WastiMeshTransportEngine",
                startedAt = start,
                completedAt = System.currentTimeMillis(),
                verificationStatus = UnifiedVerificationStatus.UNVERIFIED,
                verificationEvidence = "No independently verified remote execution evidence"
            )
        }
    }

    private fun parsePlatform(value: String): NodePlatform =
        try { NodePlatform.valueOf(value) } catch (_: Exception) { NodePlatform.DESKTOP }

    private fun readCapabilities(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val result = mutableSetOf<String>()
        for (index in 0 until array.length()) result.add(array.getString(index))
        return result
    }

    private fun parseExecutionStatus(value: String): UnifiedExecutionStatus =
        try { UnifiedExecutionStatus.valueOf(value) } catch (_: Exception) { UnifiedExecutionStatus.FAILED }

    private fun parseVerificationStatus(value: String): UnifiedVerificationStatus =
        try { UnifiedVerificationStatus.valueOf(value) } catch (_: Exception) { UnifiedVerificationStatus.UNVERIFIED }

    private fun computeFingerprint(capabilities: Set<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(capabilities.sorted().joinToString(";").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
