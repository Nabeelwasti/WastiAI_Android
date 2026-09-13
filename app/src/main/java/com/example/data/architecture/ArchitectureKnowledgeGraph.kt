package com.example.data.architecture

/**
 * Wasti Architecture Intelligence System - Knowledge Graph.
 *
 * Implements the Universal Hierarchy:
 * User -> Wasti Brain -> Knowledge Layer -> Capability Layer ->
 * Execution Layer -> Device Layer -> Cloud Layer -> Mesh Layer -> Reality Layer
 *
 * Every subsystem, layer, and capability is mapped with explicit
 * ownership, dependencies, dependents, and impact radius.
 */
enum class ArchitectureLayer {
    USER_INTERACTION_LAYER,
    BRAIN_REASONING_LAYER,
    KNOWLEDGE_MEMORY_LAYER,
    CAPABILITY_CIVILIZATION_LAYER,
    EXECUTION_FABRIC_LAYER,
    DEVICE_CONTROL_LAYER,
    CLOUD_INTELLIGENCE_LAYER,
    MESH_SWARM_LAYER,
    REALITY_INTEGRATION_LAYER
}

enum class ArchitectureRelationType {
    USES,
    DEPENDS_ON,
    VERIFIES,
    EXECUTES,
    LEARNS,
    CREATES
}

data class ArchitectureNode(
    val id: String,
    val name: String,
    val layer: ArchitectureLayer,
    val responsibility: String,
    val filePackage: String,
    val dependencies: List<String> = emptyList(),
    val isSovereignOffline: Boolean = true
)

data class ArchitectureEdge(
    val sourceId: String,
    val targetId: String,
    val relationType: ArchitectureRelationType,
    val description: String = ""
)

object ArchitectureKnowledgeGraph {

    private val nodes = mutableMapOf<String, ArchitectureNode>()
    private val edges = mutableListOf<ArchitectureEdge>()

    init {
        bootstrapCoreTopology()
    }

    private fun bootstrapCoreTopology() {
        // 1. User Interaction Layer
        registerNode(
            ArchitectureNode(
                id = "floating_bubble",
                name = "Floating Action Bubble Service",
                layer = ArchitectureLayer.USER_INTERACTION_LAYER,
                responsibility = "System Alert Window overlay providing immediate voice and text access over any app",
                filePackage = "com.example.service.WastiFloatingService",
                dependencies = listOf("vosk_wake_word", "omni_brain", "stt_tts_engine")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "main_navigation_ui",
                name = "Wasti Multi-Workspace UI",
                layer = ArchitectureLayer.USER_INTERACTION_LAYER,
                responsibility = "Primary Jetpack Compose multi-screen interface (Chat, Agents, Memory, Tools, Settings)",
                filePackage = "com.example.ui",
                dependencies = listOf("omni_brain", "conversation_fabric", "wasti_runtime")
            )
        )

        // 2. Brain & Reasoning Layer
        registerNode(
            ArchitectureNode(
                id = "omni_brain",
                name = "Wasti OmniBrain Universal Orchestrator",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                responsibility = "Master cognitive engine coordinating multi-perspective consensus across sovereign models and cloud nodes",
                filePackage = "com.example.data.ai.engine.WastiOmniBrain",
                dependencies = listOf("unified_local_brain", "ai_council", "memory_manager", "resilience_governor")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "ai_council",
                name = "AI Council Consensus Engine",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                responsibility = "Coordinates role-specialized models (Architecture, Reasoning, Research, Optimization, Sovereign Privacy)",
                filePackage = "com.example.data.ai.council",
                dependencies = listOf("unified_local_brain", "ai_manager")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "resilience_governor",
                name = "System Resilience & Anti-Burden Governor",
                layer = ArchitectureLayer.BRAIN_REASONING_LAYER,
                responsibility = "Monitors memory pressure, limits concurrent inferences, enforces timeouts, shields crashes",
                filePackage = "com.example.data.core.WastiSystemResilienceGovernor",
                dependencies = emptyList()
            )
        )

        // 3. Knowledge & Memory Layer
        registerNode(
            ArchitectureNode(
                id = "memory_manager",
                name = "Long-Term Memory & Vector Store",
                layer = ArchitectureLayer.KNOWLEDGE_MEMORY_LAYER,
                responsibility = "Episodic, semantic, working memory, and cross-session knowledge storage with embeddings",
                filePackage = "com.example.data.memory.MemoryManager",
                dependencies = listOf("wasti_database", "learning_preserver")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "learning_preserver",
                name = "Agent Learning Preserver",
                layer = ArchitectureLayer.KNOWLEDGE_MEMORY_LAYER,
                responsibility = "Persists learned skills, verified patterns, and successful strategies across all Wasti apps",
                filePackage = "com.example.data.agent.runtime.WastiAgentLearningPreserver",
                dependencies = listOf("wasti_database")
            )
        )

        // 4. Capability Civilization Layer
        registerNode(
            ArchitectureNode(
                id = "capability_registry",
                name = "Capability Civilization Registry",
                layer = ArchitectureLayer.CAPABILITY_CIVILIZATION_LAYER,
                responsibility = "Maintains all system capabilities with health scores, versions, owners, and verification gates",
                filePackage = "com.example.data.agent.runtime.CapabilityRealityRegistry",
                dependencies = listOf("verification_engine")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "capability_evolution",
                name = "Capability Evolution Pipeline",
                layer = ArchitectureLayer.CAPABILITY_CIVILIZATION_LAYER,
                responsibility = "Autonomous 9-stage pipeline: Observe -> Identify Gap -> Research -> Design -> Prototype -> Test -> Verify -> Register -> Monitor",
                filePackage = "com.example.data.architecture.evolution",
                dependencies = listOf("capability_registry", "verification_engine", "wre_runtime")
            )
        )

        // 5. Execution Fabric Layer
        registerNode(
            ArchitectureNode(
                id = "execution_fabric",
                name = "Unified Execution Fabric",
                layer = ArchitectureLayer.EXECUTION_FABRIC_LAYER,
                responsibility = "Executes intents, tools, scripts, and workflows with strict cryptographic provenance",
                filePackage = "com.example.data.agent.runtime.UnifiedExecutionFabric",
                dependencies = listOf("wre_runtime", "device_controller", "verification_engine")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "wre_runtime",
                name = "Wasti Polyglot Runtime Environment (WRE)",
                layer = ArchitectureLayer.EXECUTION_FABRIC_LAYER,
                responsibility = "Native sandbox executing Kotlin, Python, Bash, Node.js, and WASM commands safely on device",
                filePackage = "com.example.data.wre",
                dependencies = emptyList()
            )
        )
        registerNode(
            ArchitectureNode(
                id = "verification_engine",
                name = "Honest Verification Engine",
                layer = ArchitectureLayer.EXECUTION_FABRIC_LAYER,
                responsibility = "Verifies real execution artifacts and prevents synthetic or mock success claims",
                filePackage = "com.example.data.agent.runtime.WastiVerificationEngine",
                dependencies = emptyList()
            )
        )

        // 6. Device Control Layer
        registerNode(
            ArchitectureNode(
                id = "device_controller",
                name = "Android Accessibility & Device Controller",
                layer = ArchitectureLayer.DEVICE_CONTROL_LAYER,
                responsibility = "Direct UI element interaction, screen layout extraction, and physical touch execution",
                filePackage = "com.example.data.device.WastiDeviceController",
                dependencies = emptyList()
            )
        )
        registerNode(
            ArchitectureNode(
                id = "vosk_wake_word",
                name = "Vosk Offline Wake-Word Service",
                layer = ArchitectureLayer.DEVICE_CONTROL_LAYER,
                responsibility = "Continuous background listening for 'Hey Wasti' with automatic acoustic model downloader",
                filePackage = "com.example.service.WakeWordVoskService",
                dependencies = emptyList()
            )
        )

        // 7. Cloud Intelligence Layer
        registerNode(
            ArchitectureNode(
                id = "ai_manager",
                name = "Cloud AI Multi-Provider Manager",
                layer = ArchitectureLayer.CLOUD_INTELLIGENCE_LAYER,
                responsibility = "Manages API routing across Gemini, Groq, DeepSeek, OpenAI, Anthropic, and xAI",
                filePackage = "com.example.data.ai.AIManager",
                dependencies = emptyList(),
                isSovereignOffline = false
            )
        )

        // 8. Mesh Swarm Layer
        registerNode(
            ArchitectureNode(
                id = "mesh_swarm",
                name = "Cross-Device Mesh & Hardware Offloader",
                layer = ArchitectureLayer.MESH_SWARM_LAYER,
                responsibility = "Discovers nearby compute nodes, offloads heavy workloads to PC/terminals, distributes compute",
                filePackage = "com.example.data.node",
                dependencies = listOf("wasti_local_server")
            )
        )
        registerNode(
            ArchitectureNode(
                id = "wasti_local_server",
                name = "Embedded Ktor / HTTP Server",
                layer = ArchitectureLayer.MESH_SWARM_LAYER,
                responsibility = "Local HTTP daemon exposing REST endpoints for external tooling and mesh federation",
                filePackage = "com.example.data.server.WastiLocalServerManager",
                dependencies = emptyList()
            )
        )

        // 9. Reality Integration Layer
        registerNode(
            ArchitectureNode(
                id = "reality_boundary",
                name = "Physical Reality & Hardware Sensors",
                layer = ArchitectureLayer.REALITY_INTEGRATION_LAYER,
                responsibility = "Hardware truth boundary validating physical sensors, battery, thermal, and touch hardware",
                filePackage = "com.example.data.core.HostRealityBoundary",
                dependencies = emptyList()
            )
        )

        // Establish Edges
        nodes.values.forEach { node ->
            node.dependencies.forEach { depId ->
                edges.add(ArchitectureEdge(sourceId = node.id, targetId = depId, relationType = ArchitectureRelationType.DEPENDS_ON))
            }
        }
    }

    @Synchronized
    fun registerNode(node: ArchitectureNode) {
        nodes[node.id] = node
    }

    @Synchronized
    fun registerEdge(edge: ArchitectureEdge) {
        edges.add(edge)
    }

    fun getNode(id: String): ArchitectureNode? = nodes[id]

    fun getAllNodes(): List<ArchitectureNode> = nodes.values.toList()

    fun getAllEdges(): List<ArchitectureEdge> = edges.toList()

    fun getNodesByLayer(layer: ArchitectureLayer): List<ArchitectureNode> =
        nodes.values.filter { it.layer == layer }

    fun getDependencies(nodeId: String): List<ArchitectureNode> {
        val targetIds = edges.filter { it.sourceId == nodeId && it.relationType == ArchitectureRelationType.DEPENDS_ON }.map { it.targetId }
        return targetIds.mapNotNull { nodes[it] }
    }

    fun getDependents(nodeId: String): List<ArchitectureNode> {
        val sourceIds = edges.filter { it.targetId == nodeId && it.relationType == ArchitectureRelationType.DEPENDS_ON }.map { it.sourceId }
        return sourceIds.mapNotNull { nodes[it] }
    }

    /**
     * Calculates impact radius: What other nodes will be affected if this node changes or fails?
     */
    fun calculateImpactRadius(nodeId: String): List<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(nodeId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val dependents = edges.filter { it.targetId == current }.map { it.sourceId }
            for (dep in dependents) {
                if (visited.add(dep)) {
                    queue.add(dep)
                }
            }
        }
        return visited.toList()
    }
}
