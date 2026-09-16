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

data class MeshDiscoveredNode(val nodeId: String,val nodeName: String,val platform: NodePlatform,val ipAddress: String,val port: Int,val advertisedCapabilities: Set<String>,val capabilityFingerprint: String,val lastSeenTimestamp: Long = System.currentTimeMillis())
data class MeshExecutionResult(val isSuccess: Boolean,val targetNodeId: String,val output: String,val executionLatencyMs: Long,val verificationEvidence: String)

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
    fun registerDiscoveredPeerForTesting(peer: MeshDiscoveredNode) { discoveredPeers[peer.nodeId] = peer }

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
                        val caps = localNode?.capabilities ?: setOf("terminal", "files", "device_control")
                        val beacon = JSONObject().apply {
                            put("header", "WASTI_MESH_BEACON")
                            put("version", 1)
                            put("nodeId", localNodeId)
                            put("nodeName", "Wasti Mobile Host (${android.os.Build.MODEL})")
                            put("platform", NodePlatform.ANDROID.name)
                            put("port", MESH_BROADCAST_PORT)
                            put("capabilities", JSONArray(caps.toList()))
                            put("timestamp", System.currentTimeMillis())
                        }
                        val data = beacon.toString().toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(data,data.size,InetAddress.getByName("255.255.255.255"),MESH_BROADCAST_PORT))
                    } catch (e: Exception) { Log.w(TAG, "Beacon broadcast tick warning: ${e.message}") }
                    delay(BEACON_INTERVAL_MS)
                }
            } catch (e: Exception) { Log.e(TAG, "Mesh beacon socket error: ${e.message}", e) } finally { socket?.close() }
        }

        listenerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(MESH_BROADCAST_PORT)
                val buffer = ByteArray(4096)
                while (isActive && isMeshActive) {
                    try {
                        val packet = DatagramPacket(buffer,buffer.size)
                        socket.receive(packet)
                        val obj = JSONObject(String(packet.data,0,packet.length,Charsets.UTF_8))
                        if (obj.optString("header") != "WASTI_MESH_BEACON") continue
                        val peerId = obj.getString("nodeId")
                        if (peerId == localNodeId) continue
                        val peerName = obj.optString("nodeName","Remote Wasti Node")
                        val platform = try { NodePlatform.valueOf(obj.optString("platform","DESKTOP")) } catch (_: Exception) { NodePlatform.DESKTOP }
                        val peerCaps = mutableSetOf<String>()
                        val cArray = obj.optJSONArray("capabilities") ?: JSONArray()
                        for (i in 0 until cArray.length()) peerCaps.add(cArray.getString(i))
                        val discovered = MeshDiscoveredNode(peerId,peerName,platform,packet.address.hostAddress ?: "127.0.0.1",obj.optInt("port",MESH_BROADCAST_PORT),peerCaps,computeFingerprint(peerCaps))
                        discoveredPeers[peerId] = discovered
                        val advertisedMap = peerCaps.associateWith { cap -> AdvertisedCapabilityInfo(cap,"1.0.0",CapabilityRealityState.IMPLEMENTED_NOT_LIVE_VERIFIED,peerName,false) }
                        WastiServiceLocator.nodeManager.registerNode(WastiNode(nodeId=peerId,nodeName=peerName,platform=platform,capabilities=peerCaps,advertisedCapabilities=advertisedMap,capabilityFingerprint=discovered.capabilityFingerprint,connectionState=NodeConnectionState.CONNECTED,trustState=NodeTrustState.PAIRED,isLocal=false,endpointUrl="http://${discovered.ipAddress}:${MESH_EXECUTION_PORT}",networkAddress=discovered.ipAddress,dataLocality=NodeDataLocality.TRUSTED_LAN))
                        Log.i(TAG,"Discovered peer '$peerName' ($peerId); capabilities remain unverified until authenticated execution evidence exists.")
                    } catch (e: Exception) { if (isMeshActive) Log.w(TAG,"Packet receive warning: ${e.message}") }
                }
            } catch (e: Exception) { Log.e(TAG,"Mesh listener socket error: ${e.message}",e) } finally { socket?.close() }
        }

        executionServerJob = scope.launch {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(MESH_EXECUTION_PORT)
                executionServerSocket = serverSocket
                while (isActive && isMeshActive) {
                    try { val client = serverSocket.accept(); launch { handleClientExecution(client) } }
                    catch (e: Exception) { if (isMeshActive) Log.w(TAG,"Mesh execution socket accept warning: ${e.message}") }
                }
            } catch (e: Exception) { Log.w(TAG,"Mesh execution server bind warning: ${e.message}") }
            finally { serverSocket?.close() }
        }
        Log.i(TAG,"Wasti Mesh Transport active on UDP $MESH_BROADCAST_PORT / TCP $MESH_EXECUTION_PORT.")
    }

    suspend fun handleClientExecution(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(),Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(),Charsets.UTF_8))
            val line = reader.readLine() ?: return@withContext
            val reqObj = JSONObject(line)
            val paramObj = reqObj.optJSONObject("parameters") ?: JSONObject()
            val paramMap = mutableMapOf<String,String>()
            paramObj.keys().forEach { k -> paramMap[k] = paramObj.getString(k) }
            val execReq = UnifiedExecutionRequest(taskId=reqObj.optString("taskId",UUID.randomUUID().toString()),actionId=reqObj.optString("actionId","mesh_remote_action"),capabilityId=reqObj.optString("capabilityId","general_computation"),parameters=paramMap,originatingNodeId=reqObj.optString("callerNodeId","mesh_remote"))
            val result = UnifiedExecutionFabric.instance.execute(execReq,null)
            val resObj = JSONObject().apply { put("taskId",result.taskId); put("actionId",result.actionId); put("capabilityId",result.capabilityId); put("status",result.status.name); put("output",result.output); put("error",result.error ?: ""); put("executor",result.executor); put("startedAt",result.startedAt); put("completedAt",result.completedAt); put("verificationStatus",result.verificationStatus.name); put("verificationEvidence",result.verificationEvidence ?: "") }
            writer.write(resObj.toString()); writer.newLine(); writer.flush()
        } catch (e: Exception) { Log.w(TAG,"Error handling client execution: ${e.message}") }
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    fun stopMesh() {
        isMeshActive=false; beaconJob?.cancel(); listenerJob?.cancel(); executionServerJob?.cancel()
        try { executionServerSocket?.close() } catch (_: Exception) {}
        executionServerSocket=null; discoveredPeers.clear()
    }

    suspend fun dispatchRemoteTask(targetNodeId: String,request: UnifiedExecutionRequest): UnifiedExecutionResult = withContext(Dispatchers.IO) {
        val startTime=System.currentTimeMillis()
        val peer=discoveredPeers[targetNodeId] ?: WastiServiceLocator.nodeManager.getNode(targetNodeId)?.let { node -> MeshDiscoveredNode(node.nodeId,node.nodeName,node.platform,node.networkAddress?.ifBlank { "127.0.0.1" } ?: "127.0.0.1",MESH_EXECUTION_PORT,node.capabilities,node.capabilityFingerprint) }
        if (peer==null) return@withContext UnifiedExecutionResult(request.taskId,request.actionId,request.capabilityId,UnifiedExecutionStatus.FAILED,"Remote node '$targetNodeId' not found in active mesh.","NODE_UNREACHABLE","WastiMeshTransportEngine",startTime,System.currentTimeMillis(),UnifiedVerificationStatus.FAILED,"No discovered peer evidence")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.ipAddress,MESH_EXECUTION_PORT),3000)
                socket.soTimeout=10_000
                val writer=BufferedWriter(OutputStreamWriter(socket.getOutputStream(),Charsets.UTF_8))
                val reader=BufferedReader(InputStreamReader(socket.getInputStream(),Charsets.UTF_8))
                val reqObj=JSONObject().apply { put("taskId",request.taskId); put("actionId",request.actionId); put("capabilityId",request.capabilityId); put("callerNodeId","wasti_mobile_client"); put("parameters",JSONObject().apply { request.parameters.forEach { (k,v) -> put(k,v) } }) }
                writer.write(reqObj.toString()); writer.newLine(); writer.flush()
                val responseLine=reader.readLine()
                if (responseLine.isNullOrBlank()) throw IllegalStateException("EMPTY_REMOTE_RESPONSE")
                val resObj=JSONObject(responseLine)
                val status=try { UnifiedExecutionStatus.valueOf(resObj.optString("status")) } catch (_: Exception) { UnifiedExecutionStatus.FAILED }
                val remoteVerification=try { UnifiedVerificationStatus.valueOf(resObj.optString("verificationStatus","UNVERIFIED")) } catch (_: Exception) { UnifiedVerificationStatus.UNVERIFIED }
                return@withContext UnifiedExecutionResult(taskId=resObj.optString("taskId",request.taskId),actionId=resObj.optString("actionId",request.actionId),capabilityId=resObj.optString("capabilityId",request.capabilityId),status=status,output=resObj.optString("output",""),error=resObj.optString("error").takeIf { it.isNotBlank() },executor=resObj.optString("executor","MeshPeer_${peer.nodeId}"),startedAt=resObj.optLong("startedAt",startTime),completedAt=resObj.optLong("completedAt",System.currentTimeMillis()),verificationStatus=if (remoteVerification==UnifiedVerificationStatus.VERIFIED) UnifiedVerificationStatus.UNVERIFIED else remoteVerification,verificationEvidence=resObj.optString("verificationEvidence","Remote execution evidence received; canonical verification not established"))
            }
        } catch (e: Exception) {
            Log.w(TAG,"Direct TCP execution on ${peer.ipAddress}:$MESH_EXECUTION_PORT failed: ${e.message}")
            UnifiedExecutionResult(taskId=request.taskId,actionId=request.actionId,capabilityId=request.capabilityId,status=UnifiedExecutionStatus.FAILED,output="",error="REMOTE_EXECUTION_FAILED:${e.message ?: "unknown"}",executor="WastiMeshTransportEngine",startedAt=startTime,completedAt=System.currentTimeMillis(),verificationStatus=UnifiedVerificationStatus.UNVERIFIED,verificationEvidence="No independently verified remote execution evidence")
        }
    }

    private fun computeFingerprint(capabilities:Set<String>):String { val digest=MessageDigest.getInstance("SHA-256"); return digest.digest(capabilities.sorted().joinToString(";").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
}
