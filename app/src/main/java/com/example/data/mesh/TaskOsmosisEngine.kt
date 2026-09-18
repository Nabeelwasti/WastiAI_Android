package com.example.data.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class MeshNodeBodyType {
    ANDROID_MOBILE,
    LINUX_DESKTOP,
    WINDOWS_WORKSTATION,
    CLOUD_VM,
    HEADLESS_SERVER,
    WRE_CONTAINER
}

data class MeshNodeDescriptor(
    val nodeId: String = UUID.randomUUID().toString(),
    val name: String,
    val bodyType: MeshNodeBodyType,
    val ipAddress: String,
    val availableComputeTflops: Float,
    val isNearby: Boolean = true,
    val isOnline: Boolean = true
)

data class TaskOsmosisMigrationSnapshot(
    val migrationId: String = UUID.randomUUID().toString(),
    val sourceNode: String,
    val targetNode: String,
    val taskDescription: String,
    val transferredMemoryBytes: Long,
    val isSeamlessTransferSuccessful: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

object TaskOsmosisEngine {

    private val _registeredMeshNodes = MutableStateFlow(
        listOf(
            MeshNodeDescriptor(nodeId = "phone_01", name = "Wasti Primary Phone (Host)", bodyType = MeshNodeBodyType.ANDROID_MOBILE, ipAddress = "127.0.0.1", availableComputeTflops = 2.5f, isNearby = true)
        )
    )
    val registeredMeshNodes: StateFlow<List<MeshNodeDescriptor>> = _registeredMeshNodes.asStateFlow()

    private val _migrationHistory = MutableStateFlow<List<TaskOsmosisMigrationSnapshot>>(emptyList())
    val migrationHistory: StateFlow<List<TaskOsmosisMigrationSnapshot>> = _migrationHistory.asStateFlow()

    fun registerDiscoveredNode(node: MeshNodeDescriptor) {
        val current = _registeredMeshNodes.value.toMutableList()
        val index = current.indexOfFirst { it.nodeId == node.nodeId }
        if (index >= 0) {
            current[index] = node
        } else {
            current.add(node)
        }
        _registeredMeshNodes.value = current
    }

    fun migrateTaskToOptimalNode(taskDescription: String, memorySnapshotBytes: Long): TaskOsmosisMigrationSnapshot {
        val nodes = _registeredMeshNodes.value
        val remoteTarget = nodes.firstOrNull { it.nodeId != "phone_01" && it.isOnline }
        val target = remoteTarget ?: nodes.first()
        val isSuccess = remoteTarget != null && remoteTarget.isOnline

        val snapshot = TaskOsmosisMigrationSnapshot(
            sourceNode = "Wasti Primary Phone",
            targetNode = target.name,
            taskDescription = taskDescription,
            transferredMemoryBytes = if (isSuccess) memorySnapshotBytes else 0L,
            isSeamlessTransferSuccessful = isSuccess
        )

        val list = _migrationHistory.value.toMutableList()
        list.add(0, snapshot)
        _migrationHistory.value = list.take(20)
        return snapshot
    }
}
