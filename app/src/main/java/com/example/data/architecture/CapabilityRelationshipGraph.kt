package com.example.data.architecture

/**
 * Capability Relationship Graph.
 *
 * Implements Phase 1 & Phase 3 Universal Capability Civilization:
 * Maps rich semantic relationships between capabilities (ENHANCES, FALLBACK_FOR,
 * SUBSTITUTES, COOPERATES_WITH, REQUIRES, FEEDS_INTO).
 *
 * Enables intelligent runtime fallback routing: if any capability (e.g. cloud vision)
 * is degraded or offline, Wasti instantly discovers sovereign offline substitutes.
 */
object CapabilityRelationshipGraph {

    enum class CapabilityRelation {
        ENHANCES,
        FALLBACK_FOR,
        SUBSTITUTES,
        COOPERATES_WITH,
        REQUIRES,
        FEEDS_INTO
    }

    data class CapabilityLink(
        val sourceCapabilityId: String,
        val targetCapabilityId: String,
        val relation: CapabilityRelation,
        val reason: String = ""
    )

    private val links = mutableListOf<CapabilityLink>()
    private val capabilities = mutableSetOf<String>()

    init {
        bootstrapCapabilityLinks()
    }

    private fun bootstrapCapabilityLinks() {
        // Voice & STT
        registerLink("vosk_offline_stt", "cloud_whisper_stt", CapabilityRelation.FALLBACK_FOR, "Provides 100% offline speech recognition without internet")
        registerLink("cloud_whisper_stt", "vosk_offline_stt", CapabilityRelation.ENHANCES, "Provides multi-lingual precision transcription when cloud connected")

        // Brain & Inference
        registerLink("edge_smollm_inference", "cloud_ensemble_inference", CapabilityRelation.FALLBACK_FOR, "Provides zero-latency, sovereign on-device reasoning")
        registerLink("cloud_ensemble_inference", "edge_smollm_inference", CapabilityRelation.ENHANCES, "Provides 70B+ deep reasoning and multi-hop synthesis")
        registerLink("local_opencv_vision", "cloud_gemini_vision", CapabilityRelation.SUBSTITUTES, "Performs real-time frame and document processing locally")

        // Code & Execution
        registerLink("wre_polyglot_sandbox", "terminal_workspace", CapabilityRelation.REQUIRES, "Executes Python, JS, and Shell scripts in isolated native sandbox")
        registerLink("code_prompt_synthesizer", "wre_polyglot_sandbox", CapabilityRelation.COOPERATES_WITH, "Feeds synthesized patches directly into WRE sandbox for verification")

        // Telemetry & Reality
        registerLink("environmental_sensors", "executive_brain", CapabilityRelation.FEEDS_INTO, "Feeds ambient noise, light, barometric data into sensory context")
        registerLink("bci_signal_processor", "executive_brain", CapabilityRelation.FEEDS_INTO, "Feeds bio-telemetry and neural signals into intentionality loop")

        // Swarm & Mesh
        registerLink("mesh_swarm_discovery", "runtime_topology", CapabilityRelation.COOPERATES_WITH, "Discovers nearby compute muscle nodes to offload heavy workloads")
        registerLink("mesh_swarm_offloading", "edge_smollm_inference", CapabilityRelation.ENHANCES, "Offloads parallel batch tasks to neighboring peer nodes")
    }

    @Synchronized
    fun registerLink(
        sourceId: String,
        targetId: String,
        relation: CapabilityRelation,
        reason: String = ""
    ) {
        capabilities.add(sourceId)
        capabilities.add(targetId)
        links.add(CapabilityLink(sourceId, targetId, relation, reason))
    }

    @Synchronized
    fun getLinksForCapability(capabilityId: String): List<CapabilityLink> {
        return links.filter { it.sourceCapabilityId == capabilityId || it.targetCapabilityId == capabilityId }
    }

    /**
     * Resolves the highest-priority fallback capabilities if the specified capability fails.
     */
    @Synchronized
    fun findFallbackPath(capabilityId: String): List<String> {
        // Direct fallbacks (where other capabilities act as FALLBACK_FOR or SUBSTITUTES)
        val directFallbacks = links.filter {
            (it.relation == CapabilityRelation.FALLBACK_FOR && it.targetCapabilityId == capabilityId) ||
            (it.relation == CapabilityRelation.SUBSTITUTES && it.targetCapabilityId == capabilityId)
        }.map { it.sourceCapabilityId }

        if (directFallbacks.isNotEmpty()) {
            return directFallbacks.distinct()
        }

        // Secondary fallback search (reverse links where source can substitute)
        val secondary = links.filter {
            it.relation == CapabilityRelation.SUBSTITUTES && it.sourceCapabilityId == capabilityId
        }.map { it.targetCapabilityId }

        return secondary.distinct()
    }

    /**
     * Resolves capabilities that enhance the specified capability.
     */
    @Synchronized
    fun findEnhancements(capabilityId: String): List<String> {
        return links.filter {
            it.relation == CapabilityRelation.ENHANCES && it.targetCapabilityId == capabilityId
        }.map { it.sourceCapabilityId }.distinct()
    }

    /**
     * Resolves the synergistic cluster of all cooperatively linked capabilities.
     */
    @Synchronized
    fun getSynergisticCluster(capabilityId: String): Set<String> {
        val cluster = mutableSetOf(capabilityId)
        val queue = ArrayDeque<String>()
        queue.add(capabilityId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val cooperators = links.filter {
                (it.sourceCapabilityId == current && it.relation == CapabilityRelation.COOPERATES_WITH) ||
                (it.targetCapabilityId == current && it.relation == CapabilityRelation.COOPERATES_WITH)
            }.map { if (it.sourceCapabilityId == current) it.targetCapabilityId else it.sourceCapabilityId }

            for (neighbor in cooperators) {
                if (cluster.add(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }
        return cluster
    }

    @Synchronized
    fun getAllLinks(): List<CapabilityLink> = links.toList()

    @Synchronized
    fun getAllCapabilities(): Set<String> = capabilities.toSet()
}
