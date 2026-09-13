package com.example.data.architecture

/**
 * File Ownership Engine.
 *
 * Implements Phase 1 Architecture Intelligence:
 * Maps files and packages to architectural layers, ownership subsystems,
 * and criticality tiers.
 *
 * Directly answers the four core questions for any file in Wasti AI OS:
 * 1. Why it exists (architectural purpose)
 * 2. What uses it (upstream consumers)
 * 3. What depends on it (downstream dependencies)
 * 4. What it breaks (systemic impact radius if modified or corrupted)
 */
object FileOwnershipEngine {

    enum class CriticalityTier {
        CRITICAL_SOVEREIGN,   // Core runtime, offline neural brain, encryption vault
        CORE_COGNITIVE,       // OmniBrain, AI Council, Capability Civilization
        EXECUTION_FABRIC,     // WRE polyglot sandbox, terminal, file system
        CONNECTORS_AND_MESH,  // Swarm mesh, cloud providers, external APIs
        ADAPTIVE_INTERFACE    // Compose screens, HUD, floating overlay
    }

    data class FileOwnershipRecord(
        val packageOrPath: String,
        val layer: ArchitectureLayer,
        val purpose: String,
        val primarySubsystemId: String,
        val criticality: CriticalityTier,
        val isCoreSovereign: Boolean
    )

    data class FileImpactAnalysis(
        val queryPath: String,
        val matchedRecord: FileOwnershipRecord?,
        val whyItExists: String,
        val ownerLayer: ArchitectureLayer,
        val subsystemId: String,
        val whatUsesIt: List<String>,
        val whatDependsOnIt: List<String>,
        val whatItBreaksIfRemoved: List<String>,
        val criticality: CriticalityTier
    )

    private val records = mutableMapOf<String, FileOwnershipRecord>()

    init {
        bootstrapOwnershipCatalog()
    }

    private fun bootstrapOwnershipCatalog() {
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.ai.engine.WastiOmniBrain",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                purpose = "Master cognitive engine that unifies multi-model perspectives into single sovereign consensus",
                primarySubsystemId = "omni_brain",
                criticality = CriticalityTier.CRITICAL_SOVEREIGN,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.ai.council.AICouncilEngine",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                purpose = "Specializes role assignment (Claude, GPT, Gemini, DeepSeek, Local) for multi-model governance",
                primarySubsystemId = "ai_council",
                criticality = CriticalityTier.CORE_COGNITIVE,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.architecture",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                purpose = "Self-inspection, dependency intelligence, capability civilization registry, and evolution pipeline",
                primarySubsystemId = "codebase_intelligence",
                criticality = CriticalityTier.CORE_COGNITIVE,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.wre",
                layer = ArchitectureLayer.EXECUTION_FABRIC_LAYER,
                purpose = "Polyglot native runtime sandbox for executing Python, JavaScript, and Shell securely on-device",
                primarySubsystemId = "wre_runtime",
                criticality = CriticalityTier.CRITICAL_SOVEREIGN,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.credential",
                layer = ArchitectureLayer.DEVICE_CONTROL_LAYER,
                purpose = "Hardware-backed EncryptedSharedPreferences vault for zero-leak API and secret credential management",
                primarySubsystemId = "credential_vault",
                criticality = CriticalityTier.CRITICAL_SOVEREIGN,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.mesh",
                layer = ArchitectureLayer.MESH_SWARM_LAYER,
                purpose = "Decentralized Wi-Fi Direct and LAN peer discovery and distributed task offloading",
                primarySubsystemId = "mesh_swarm",
                criticality = CriticalityTier.CONNECTORS_AND_MESH,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.device",
                layer = ArchitectureLayer.REALITY_INTEGRATION_LAYER,
                purpose = "Sensory telemetry, barometric/light/ambient perception, and BCI neural signal processing",
                primarySubsystemId = "reality_telemetry",
                criticality = CriticalityTier.CONNECTORS_AND_MESH,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.data.leadradar",
                layer = ArchitectureLayer.CAPABILITY_CIVILIZATION_LAYER,
                purpose = "Autonomous Upwork RSS ingestion, lead scoring, proposal drafting, and freelance contract pipeline",
                primarySubsystemId = "lead_radar",
                criticality = CriticalityTier.CORE_COGNITIVE,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.ui",
                layer = ArchitectureLayer.USER_INTERACTION_LAYER,
                purpose = "Adaptive Jetpack Compose UI reacting to Simple, Advanced, Architect, Developer, Autonomous modes",
                primarySubsystemId = "main_navigation_ui",
                criticality = CriticalityTier.ADAPTIVE_INTERFACE,
                isCoreSovereign = true
            )
        )
        registerRecord(
            FileOwnershipRecord(
                packageOrPath = "com.example.service.WastiFloatingService",
                layer = ArchitectureLayer.USER_INTERACTION_LAYER,
                purpose = "Always-accessible floating action bubble overlay with low-latency voice and quick prompt entry",
                primarySubsystemId = "floating_bubble",
                criticality = CriticalityTier.ADAPTIVE_INTERFACE,
                isCoreSovereign = true
            )
        )
    }

    @Synchronized
    fun registerRecord(record: FileOwnershipRecord) {
        records[record.packageOrPath] = record
    }

    @Synchronized
    fun lookupRecord(packageOrClass: String): FileOwnershipRecord? {
        records[packageOrClass]?.let { return it }
        return records.entries.firstOrNull { packageOrClass.startsWith(it.key) || it.key.startsWith(packageOrClass) }?.value
    }

    /**
     * Conducts deep impact analysis answering:
     * - Why the file exists
     * - What uses it
     * - What depends on it
     * - What it breaks if removed
     */
    fun inspectFileImpact(packageOrClass: String): FileImpactAnalysis {
        val record = lookupRecord(packageOrClass)
        val subsystemId = record?.primarySubsystemId ?: "unknown_subsystem"
        val ownerLayer = record?.layer ?: ArchitectureLayer.CAPABILITY_CIVILIZATION_LAYER
        val criticality = record?.criticality ?: CriticalityTier.CORE_COGNITIVE

        val why = record?.purpose ?: "Provides auxiliary runtime logic or domain models within $packageOrClass"

        val whatUsesIt = ArchitectureKnowledgeGraph.getDependents(subsystemId).map { it.name }
        val whatDependsOnIt = ArchitectureKnowledgeGraph.getDependencies(subsystemId).map { it.name }
        val whatItBreaks = ArchitectureKnowledgeGraph.calculateImpactRadius(subsystemId)

        return FileImpactAnalysis(
            queryPath = packageOrClass,
            matchedRecord = record,
            whyItExists = why,
            ownerLayer = ownerLayer,
            subsystemId = subsystemId,
            whatUsesIt = whatUsesIt,
            whatDependsOnIt = whatDependsOnIt,
            whatItBreaksIfRemoved = whatItBreaks,
            criticality = criticality
        )
    }

    @Synchronized
    fun getAllRecords(): List<FileOwnershipRecord> = records.values.toList()
}
