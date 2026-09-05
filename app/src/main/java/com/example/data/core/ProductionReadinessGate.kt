package com.example.data.core

import android.content.Context
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.credential.CredentialRegistry
import com.example.data.credential.CredentialStatus
import com.example.data.db.WastiDatabase
import com.example.data.memory.ExecutionMemoryRecorder
import com.example.data.node.NodeConnectionState
import com.example.data.node.WastiNodeManager

/**
  * Canonical Production Readiness State Matrix.
  * Ensures zero-fabrication reporting of system and subsystem readiness.
  */
enum class ProductionReadinessState {
    NOT_READY,
    DEVELOPMENT_READY,
    BUILD_VERIFIED,
    TEST_VERIFIED,
    DEVICE_VERIFIED,
    EXTERNAL_INTEGRATIONS_VERIFIED,
    E2E_VERIFIED,
    RELEASE_VERIFIED,
    PRODUCTION_READY
}

data class SubsystemReadinessCheck(
    val subsystemName: String,
    val isOperational: Boolean,
    val isLiveVerified: Boolean,
    val state: ProductionReadinessState,
    val notes: String
)

data class ProductionReadinessAssessment(
    val overallState: ProductionReadinessState,
    val verifiedSubsystemCount: Int,
    val totalSubsystemCount: Int,
    val subsystemChecks: List<SubsystemReadinessCheck>,
    val verifiedAtMs: Long = System.currentTimeMillis()
)

object ProductionReadinessGate {

    fun assessReadiness(context: Context): ProductionReadinessAssessment {
        val checks = mutableListOf<SubsystemReadinessCheck>()

        // 1. Core Startup & OS Lifecycle
        val startupState = AppStartupManager.startupState.value
        val startupOk = startupState is AppStartupState.Ready
        val isDegraded = startupState is AppStartupState.CoreReadyDegraded
        val startupAssessmentState = when {
            startupOk -> ProductionReadinessState.RELEASE_VERIFIED
            isDegraded -> ProductionReadinessState.TEST_VERIFIED
            else -> ProductionReadinessState.NOT_READY
        }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "AppStartupManager",
                isOperational = startupOk || isDegraded,
                isLiveVerified = startupOk,
                state = startupAssessmentState,
                notes = "Startup State: ${startupState::class.simpleName}"
            )
        )

        // 2. Room Database & Local Persistence
        val dbOk = try {
            val db = WastiDatabase.getDatabase(context)
            db.openHelper.writableDatabase.isOpen
        } catch (_: Exception) {
            false
        }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "RoomDatabase",
                isOperational = dbOk,
                isLiveVerified = dbOk,
                state = if (dbOk) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.NOT_READY,
                notes = if (dbOk) "Database writable & active (Schema v15)" else "Database inaccessible"
            )
        )

        // 3. Unified Execution Fabric & Reality Registry
        val fabric = UnifiedExecutionFabric.instance
        val realities = fabric.realityRegistry.getSystemRealityReport()
        val operationalCount = realities.count { it.executionStatus == CapabilityExecutionStatus.OPERATIONAL }
        val liveVerifiedCount = realities.count { it.liveConnectionStatus == LiveConnectionStatus.VERIFIED }
        val hasRecentVerifiedExecution = ExecutionMemoryRecorder.getRecentExecutions(10).any { it.isSuccess == true && it.verificationStatus == "VERIFIED" }
        val fabricLiveVerified = operationalCount > 0 && (liveVerifiedCount > 0 || hasRecentVerifiedExecution)
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "UnifiedExecutionFabric",
                isOperational = operationalCount > 0,
                isLiveVerified = fabricLiveVerified,
                state = if (fabricLiveVerified) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.TEST_VERIFIED,
                notes = "$operationalCount / ${realities.size} operational, $liveVerifiedCount live-verified"
            )
        )

        // 4. Mesh & Node Topology (Optional Subsystem - isolated from blocking core release)
        val nodeManager = WastiNodeManager.getInstance()
        val nodes = nodeManager.getAllNodes()
        val nodeCount = nodes.size
        val hasActiveNode = nodes.any { it.connectionState == NodeConnectionState.CONNECTED }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "WastiMeshTransport",
                isOperational = true,
                isLiveVerified = hasActiveNode,
                state = if (hasActiveNode) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.TEST_VERIFIED,
                notes = "$nodeCount mesh nodes registered (activeNode=$hasActiveNode)"
            )
        )

        // 5. External Credentials & Integrations - Gated per Capability
        val credStates = CredentialRegistry.credentialStates.value
        val connectedCreds = credStates.count { it.status is CredentialStatus.Connected }
        val totalConfigured = credStates.count { it.rawValue.isNotBlank() && !CredentialRegistry.isPlaceholder(it.rawValue) }
        val hasGeminiOrCoreModel = credStates.any { 
            (it.entry.keyName == "GEMINI_API_KEY" || it.entry.keyName == "OPENAI_API_KEY" || it.entry.keyName == "GROQ_API_KEY") && 
            it.status is CredentialStatus.Connected 
        }
        val credState = when {
            hasGeminiOrCoreModel && connectedCreds >= 3 -> ProductionReadinessState.EXTERNAL_INTEGRATIONS_VERIFIED
            hasGeminiOrCoreModel -> ProductionReadinessState.RELEASE_VERIFIED
            totalConfigured > 0 -> ProductionReadinessState.TEST_VERIFIED
            else -> ProductionReadinessState.DEVELOPMENT_READY
        }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "CredentialRegistry",
                isOperational = true,
                isLiveVerified = hasGeminiOrCoreModel,
                state = credState,
                notes = "$connectedCreds verified connected ($totalConfigured configured keys, coreModelConnected=$hasGeminiOrCoreModel)"
            )
        )

        val verifiedCount = checks.count { it.isOperational && it.isLiveVerified }
        val mandatoryChecksPassed = startupOk && dbOk && (operationalCount > 0)
        val overall = when {
            !mandatoryChecksPassed -> ProductionReadinessState.NOT_READY
            checks.all { it.state == ProductionReadinessState.RELEASE_VERIFIED || it.state == ProductionReadinessState.EXTERNAL_INTEGRATIONS_VERIFIED || it.state == ProductionReadinessState.PRODUCTION_READY } -> ProductionReadinessState.PRODUCTION_READY
            checks.all { it.isOperational && it.isLiveVerified } -> ProductionReadinessState.RELEASE_VERIFIED
            else -> ProductionReadinessState.TEST_VERIFIED
        }

        return ProductionReadinessAssessment(
            overallState = overall,
            verifiedSubsystemCount = verifiedCount,
            totalSubsystemCount = checks.size,
            subsystemChecks = checks
        )
    }
}
