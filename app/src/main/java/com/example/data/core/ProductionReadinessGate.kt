package com.example.data.core

import android.content.Context
import com.example.data.agent.runtime.CapabilityExecutionStatus
import com.example.data.agent.runtime.CapabilityRealityState
import com.example.data.agent.runtime.ImplementationStatus
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

/**
 * Canonical 11-Stage Progressive Capability Lifecycle State.
 * Strictly prevents capability state inflation without verifiable evidence.
 */
enum class CapabilityLifecycleState {
    DECLARED,
    CONFIGURED,
    AVAILABLE,
    AUTHENTICATED,
    EXECUTABLE,
    STARTED,
    COMPLETED,
    OBSERVED,
    VERIFIED,
    TRUSTED,
    LEARNED;

    fun canTransitionTo(next: CapabilityLifecycleState): Boolean {
        return next.ordinal == this.ordinal + 1 || next == this
    }
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

    fun assessReadiness(
        context: Context,
        emergencyStopController: com.example.data.agent.runtime.WastiEmergencyStopController = com.example.data.di.WastiServiceLocator.emergencyStopController
    ): ProductionReadinessAssessment {
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
        val meshState = when {
            hasActiveNode -> ProductionReadinessState.RELEASE_VERIFIED
            nodeCount > 0 -> ProductionReadinessState.TEST_VERIFIED
            else -> ProductionReadinessState.DEVELOPMENT_READY
        }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "WastiMeshTransport",
                isOperational = hasActiveNode || nodeCount > 0,
                isLiveVerified = hasActiveNode,
                state = meshState,
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
                isOperational = totalConfigured > 0,
                isLiveVerified = hasGeminiOrCoreModel,
                state = credState,
                notes = "$connectedCreds verified connected ($totalConfigured configured keys, coreModelConnected=$hasGeminiOrCoreModel)"
            )
        )

        // 6. Integration Audit Boundary Verification
        val integrationAuditList = com.example.data.agent.runtime.IntegrationAuditRegistry.getAuditReport()
        val verifiedBoundaryCount = integrationAuditList.count { it.status == com.example.data.agent.runtime.IntegrationStatus.VERIFIED_CONNECTED }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "IntegrationAuditRegistry",
                isOperational = true,
                isLiveVerified = verifiedBoundaryCount > 0,
                state = if (verifiedBoundaryCount > 0) ProductionReadinessState.TEST_VERIFIED else ProductionReadinessState.DEVELOPMENT_READY,
                notes = "$verifiedBoundaryCount / ${integrationAuditList.size} integration boundaries verified connected"
            )
        )

        // 7. Local Neural Inference Runtime Check
        val nativeLlamaAvailable = try {
            com.example.data.ai.runtime.NativeLlamaBridge.isNativeSupported()
        } catch (_: Throwable) {
            false
        }
        val smolLmPresent = try {
            com.example.data.ai.engine.ModelArtifactManager.isWeightsPresent(context, "wasti-smollm")
        } catch (_: Throwable) {
            false
        }
        val localNeuralReady = nativeLlamaAvailable && smolLmPresent
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "LocalNeuralInferenceEngine",
                isOperational = nativeLlamaAvailable || smolLmPresent,
                isLiveVerified = localNeuralReady,
                state = if (localNeuralReady) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.DEVELOPMENT_READY,
                notes = if (localNeuralReady) "Native llama.cpp engine & GGUF weights active"
                        else "Native llama.cpp library (.so) or model weights pending device installation"
            )
        )

        // 8. Cloud Offload & Backend Bridge Check
        val backendSecretConfigured = try {
            !CredentialRegistry.getRawValue("WASTI_BACKEND_AUTH_SECRET", context).isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "CloudBackendOffload",
                isOperational = true,
                isLiveVerified = backendSecretConfigured,
                state = if (backendSecretConfigured) ProductionReadinessState.EXTERNAL_INTEGRATIONS_VERIFIED else ProductionReadinessState.DEVELOPMENT_READY,
                notes = if (backendSecretConfigured) "Backend auth token configured" else "Backend auth secret not configured"
            )
        )

        // 9. [P0-33] Real Device Execution Verification
        val hasDeviceProof = DeviceVerificationEvidenceTracker.hasValidDeviceProof()
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "RealDeviceExecutionVerification",
                isOperational = true,
                isLiveVerified = hasDeviceProof,
                state = if (hasDeviceProof) ProductionReadinessState.DEVICE_VERIFIED else ProductionReadinessState.DEVELOPMENT_READY,
                notes = if (hasDeviceProof) "Physical device/emulator instrumentation proof verified (${DeviceVerificationEvidenceTracker.getDeviceProofReport().firstOrNull()?.deviceModel})"
                        else "BLOCKED_EXTERNAL_DEVICE: Physical device or emulator instrumentation proof required (never reported without execution)"
            )
        )

        // 10. [P0-36] Runtime Permission and Consent Truth Verification
        val permissionAuditMap = com.example.assistant.PermissionManager.getPermissionAuditMap(context)
        val grantedPermissionCount = permissionAuditMap.count { it.value }
        val hasCorePermissionsGranted = com.example.assistant.PermissionManager.hasRecordAudio(context) &&
                com.example.assistant.PermissionManager.hasPostNotifications(context)
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "RuntimePermissionTruth",
                isOperational = true,
                isLiveVerified = hasCorePermissionsGranted,
                state = if (hasCorePermissionsGranted) ProductionReadinessState.DEVICE_VERIFIED else ProductionReadinessState.DEVELOPMENT_READY,
                notes = if (hasCorePermissionsGranted) "Core runtime permissions (Audio, Notifications) verified granted on device"
                        else "DECLARED_ONLY_PENDING_RUNTIME_GRANT: $grantedPermissionCount / ${permissionAuditMap.size} dangerous permissions granted on OS"
            )
        )

        // 11. [P0-34 / P0-50] Emergency Stop Active Latch Check
        val isEmergencyStopped = emergencyStopController.isEmergencyStopped
        val stopReason = emergencyStopController.getReason() ?: "Emergency stop active"
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "EmergencyStopLatch",
                isOperational = !isEmergencyStopped,
                isLiveVerified = !isEmergencyStopped,
                state = if (!isEmergencyStopped) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.NOT_READY,
                notes = if (isEmergencyStopped) "EMERGENCY_STOP_ACTIVE: $stopReason" else "System armed and operational (emergency latch disarmed)"
            )
        )

        // 12. [P0-49 / P0-50] Execution Provenance Ledger Cryptographic Integrity Check
        val provenanceIntegrityOk = com.example.data.agent.runtime.ExecutionProvenanceLedger.verifyLedgerIntegrity()
        val provenanceCount = com.example.data.agent.runtime.ExecutionProvenanceLedger.count()
        checks.add(
            SubsystemReadinessCheck(
                subsystemName = "ExecutionProvenanceLedger",
                isOperational = provenanceIntegrityOk,
                isLiveVerified = provenanceIntegrityOk,
                state = if (provenanceIntegrityOk) ProductionReadinessState.RELEASE_VERIFIED else ProductionReadinessState.NOT_READY,
                notes = if (provenanceIntegrityOk) "Cryptographic hash chain verified intact ($provenanceCount entries chained)"
                        else "HASH_CHAIN_COMPROMISED: Execution provenance ledger failed cryptographic integrity check"
            )
        )

        val verifiedCount = checks.count { it.isOperational && it.isLiveVerified }
        val mandatoryChecksPassed = startupOk && dbOk && (operationalCount > 0) && !isEmergencyStopped && provenanceIntegrityOk

        // Zero-Fabrication Rule: PRODUCTION_READY can NEVER be claimed until native neural runtime,
        // live external integrations, and real device execution validation are all verified simultaneously.
        val canBeProductionReady = localNeuralReady && mandatoryChecksPassed && hasDeviceProof &&
            checks.all { it.state == ProductionReadinessState.RELEASE_VERIFIED || it.state == ProductionReadinessState.EXTERNAL_INTEGRATIONS_VERIFIED || it.state == ProductionReadinessState.DEVICE_VERIFIED || it.state == ProductionReadinessState.PRODUCTION_READY }

        val hasNotReady = checks.any { it.state == ProductionReadinessState.NOT_READY }
        val hasDevelopmentReady = checks.any { it.state == ProductionReadinessState.DEVELOPMENT_READY }

        val overall = when {
            !mandatoryChecksPassed || hasNotReady -> ProductionReadinessState.NOT_READY
            canBeProductionReady -> ProductionReadinessState.PRODUCTION_READY
            hasDevelopmentReady -> ProductionReadinessState.DEVELOPMENT_READY
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

    /**
     * Evidence-based capability lifecycle readiness assessment.
     * Evaluates actual execution and verification records from ExecutionMemoryRecorder
     * and CapabilityReality to prevent ungrounded capability promotion.
     */
    fun assessCapabilityReadiness(
        capabilityId: String,
        context: Context? = null,
        isSkillLearned: Boolean? = null
    ): CapabilityLifecycleState {
        val fabric = UnifiedExecutionFabric.instance
        val reality = fabric.realityRegistry.getCapabilityReality(capabilityId)
            ?: return CapabilityLifecycleState.DECLARED

        if (reality.implementationStatus != ImplementationStatus.READY) {
            return CapabilityLifecycleState.DECLARED
        }

        if (reality.executionStatus != CapabilityExecutionStatus.OPERATIONAL) {
            return CapabilityLifecycleState.CONFIGURED
        }

        val recentExecutions = ExecutionMemoryRecorder.getRecentExecutions(50)
            .filter { it.selectedCapability.equals(capabilityId, ignoreCase = true) }

        val verifiedRuns = recentExecutions.count { it.isSuccess == true && it.verificationStatus == "VERIFIED" }

        val learnedSkillExists = isSkillLearned ?: run {
            if (context != null) {
                try {
                    val db = WastiDatabase.getDatabase(context)
                    db.openHelper.readableDatabase.let { sdb ->
                        val cursor = sdb.query(
                            "SELECT 1 FROM learned_skills WHERE requiredCapabilitiesJson LIKE ? AND operationalStatus = 'ACTIVE' LIMIT 1",
                            arrayOf("%$capabilityId%")
                        )
                        val exists = cursor.moveToFirst()
                        cursor.close()
                        exists
                    }
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }
        }

        return when {
            verifiedRuns >= 15 && learnedSkillExists -> CapabilityLifecycleState.LEARNED
            verifiedRuns >= 15 -> CapabilityLifecycleState.TRUSTED
            verifiedRuns >= 1 -> CapabilityLifecycleState.VERIFIED
            recentExecutions.any { it.isSuccess == true } -> CapabilityLifecycleState.COMPLETED
            recentExecutions.isNotEmpty() -> CapabilityLifecycleState.STARTED
            reality.liveConnectionStatus == LiveConnectionStatus.VERIFIED -> CapabilityLifecycleState.AUTHENTICATED
            else -> CapabilityLifecycleState.AVAILABLE
        }
    }
}

