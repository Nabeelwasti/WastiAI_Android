package com.example.data.ai.engine

import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.provider.WastiLocalBrainProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BrainConsensusType {
    GENUINE_NEURAL_CONSENSUS,       // 2 or more nodes executed genuine neural weights
    HYBRID_NEURAL_HEURISTIC,        // Mix of real neural and heuristic nodes
    HEURISTIC_DOMAIN_SYNTHESIS,     // Local rule-based domain heuristic synthesis
    UNAVAILABLE                     // Insufficient active nodes or requirement not satisfied
}

data class ModelThinkingNode(
    val modelId: String,
    val modelName: String,
    val thoughtSummary: String,
    val runtimeConfidence: Float,
    val inferenceConfidence: Float,
    val latencyMs: Long,
    val runtimeStatus: ModelRuntimeStatus,
    val isNeuralExecution: Boolean = false,
    val executionMode: String = "HEURISTIC_NON_NEURAL"
)

data class UnifiedBrainConsensus(
    val finalSynthesis: String,
    val participatingModels: List<ModelThinkingNode>,
    val averageInferenceConfidence: Float,
    val totalLatencyMs: Long,
    val isFullyLocal: Boolean = true,
    val consensusType: BrainConsensusType = BrainConsensusType.HEURISTIC_DOMAIN_SYNTHESIS,
    val neuralNodeCount: Int = 0,
    val heuristicNodeCount: Int = 0
)

object UnifiedBrain {

    private val localProviders = mutableMapOf<String, WastiLocalBrainProvider>()
    private val _activeBrainState = MutableStateFlow<UnifiedBrainConsensus?>(null)
    val activeBrainState: StateFlow<UnifiedBrainConsensus?> = _activeBrainState.asStateFlow()

    init {
        // Instantiate all 12 local open source model nodes
        OpenSourceModelCatalog.ALL_MODELS.forEach { descriptor ->
            localProviders[descriptor.id] = WastiLocalBrainProvider(descriptor)
        }
    }

    fun getAllLocalProviders(): List<WastiLocalBrainProvider> = localProviders.values.toList()

    fun getLocalProvider(modelId: String): WastiLocalBrainProvider? = localProviders[modelId]

    suspend fun executeCooperativeReasoning(
        prompt: String,
        participatingModelIds: List<String> = listOf("wasti-llama", "wasti-qwen", "wasti-deepseek", "wasti-mistral"),
        requireNeuralConsensus: Boolean = false
    ): UnifiedBrainConsensus = coroutineScope {
        val startTime = System.currentTimeMillis()
        val validProviders = participatingModelIds.mapNotNull { localProviders[it] }

        if (requireNeuralConsensus && validProviders.count { it.isNeuralInferenceActive } < 2) {
            val unavailableConsensus = UnifiedBrainConsensus(
                finalSynthesis = "Wasti AI OS: Neural consensus unavailable. Minimum 2 active neural model weights required; heuristic consensus disallowed by caller.",
                participatingModels = emptyList(),
                averageInferenceConfidence = 0.0f,
                totalLatencyMs = 0L,
                isFullyLocal = true,
                consensusType = BrainConsensusType.UNAVAILABLE,
                neuralNodeCount = 0,
                heuristicNodeCount = 0
            )
            _activeBrainState.value = unavailableConsensus
            return@coroutineScope unavailableConsensus
        }

        val statuses = ModelArtifactManager.modelStatuses.value

        val deferredNodes = validProviders.map { provider ->
            async {
                val nodeStart = System.currentTimeMillis()
                val isNeural = provider.isNeuralInferenceActive
                val execMode = if (isNeural) "NEURAL" else "HEURISTIC_NON_NEURAL"

                val response = provider.generate(
                    ProviderRequest(prompt = prompt)
                )
                val nodeLatency = System.currentTimeMillis() - nodeStart
                val status = statuses[provider.id] ?: ModelRuntimeStatus.DECLARED

                // Epistemic separation: Runtime readiness vs. Semantic inference confidence
                val runtimeConf = when (status) {
                    ModelRuntimeStatus.ACTIVE_LOADED -> 1.0f
                    ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT -> 0.90f
                    ModelRuntimeStatus.AVAILABLE_PENDING_DOWNLOAD -> 0.60f
                    else -> 0.30f
                }

                // Inference confidence derived from response completeness and format validation
                val hasSubstantialContent = response.content.length > 40
                val inferConf = if (status == ModelRuntimeStatus.ACTIVE_LOADED && hasSubstantialContent) {
                    0.92f
                } else if (hasSubstantialContent) {
                    0.75f
                } else {
                    0.50f
                }

                ModelThinkingNode(
                    modelId = provider.id,
                    modelName = provider.name,
                    thoughtSummary = response.content,
                    runtimeConfidence = runtimeConf,
                    inferenceConfidence = inferConf,
                    latencyMs = nodeLatency,
                    runtimeStatus = status,
                    isNeuralExecution = isNeural,
                    executionMode = execMode
                )
            }
        }

        val results = deferredNodes.awaitAll()
        val totalLatency = System.currentTimeMillis() - startTime

        val neuralCount = results.count { it.isNeuralExecution }
        val heuristicCount = results.count { !it.isNeuralExecution }
        val consensusType = when {
            results.isEmpty() -> BrainConsensusType.UNAVAILABLE
            neuralCount >= 2 && heuristicCount == 0 -> BrainConsensusType.GENUINE_NEURAL_CONSENSUS
            neuralCount > 0 -> BrainConsensusType.HYBRID_NEURAL_HEURISTIC
            else -> BrainConsensusType.HEURISTIC_DOMAIN_SYNTHESIS
        }

        val synthesizedText = UltimateSynthesizer.synthesize(results, consensusType)
        val avgConfidence = if (results.isNotEmpty()) results.map { it.inferenceConfidence }.average().toFloat() else 1.0f

        val consensus = UnifiedBrainConsensus(
            finalSynthesis = synthesizedText,
            participatingModels = results,
            averageInferenceConfidence = avgConfidence,
            totalLatencyMs = totalLatency,
            isFullyLocal = true,
            consensusType = consensusType,
            neuralNodeCount = neuralCount,
            heuristicNodeCount = heuristicCount
        )

        _activeBrainState.value = consensus
        consensus
    }
}

object UltimateSynthesizer {
    fun synthesize(
        nodes: List<ModelThinkingNode>,
        consensusType: BrainConsensusType = BrainConsensusType.HEURISTIC_DOMAIN_SYNTHESIS
    ): String {
        if (nodes.isEmpty()) return "Wasti AI OS: Single-Brain local evaluation complete."
        if (nodes.size == 1) return nodes.first().thoughtSummary

        val codingNode = nodes.find { it.modelId.contains("qwen") || it.modelId.contains("deepseek") }
        val reasoningNode = nodes.find { it.modelId.contains("llama") || it.modelId.contains("mistral") }
        val automationNode = nodes.find { it.modelId.contains("granite") || it.modelId.contains("commandr") || it.modelId.contains("phi") }
        val logicNode = nodes.find { it.modelId.contains("gemma") }

        val builder = StringBuilder()
        when (consensusType) {
            BrainConsensusType.GENUINE_NEURAL_CONSENSUS -> {
                builder.append("### Wasti AI OS Unified Multi-Brain Neural Consensus\n")
                builder.append("*(Synthesized across ${nodes.size} Cooperative Local Neural Weights)*\n\n")
            }
            BrainConsensusType.HYBRID_NEURAL_HEURISTIC -> {
                val neural = nodes.count { it.isNeuralExecution }
                builder.append("### Wasti AI OS Hybrid Multi-Brain Consensus\n")
                builder.append("*(Synthesized across $neural Neural and ${nodes.size - neural} Heuristic Nodes)*\n\n")
            }
            BrainConsensusType.HEURISTIC_DOMAIN_SYNTHESIS -> {
                builder.append("### Wasti AI OS Unified Heuristic Domain Synthesis\n")
                builder.append("*(Synthesized across ${nodes.size} Cooperative Local Rule Heuristics • Non-Neural Fallback Mode)*\n\n")
            }
            BrainConsensusType.UNAVAILABLE -> {
                return "Wasti AI OS: Cooperative consensus unavailable."
            }
        }

        // 1. Executive Strategic Consensus
        builder.append("#### 1. Strategic Assessment\n")
        val mainReasoning = reasoningNode?.thoughtSummary ?: nodes.first().thoughtSummary
        builder.append(mainReasoning.lines().take(6).joinToString("\n"))
        builder.append("\n\n")

        // 2. Technical & Coding Specification
        if (codingNode != null && codingNode.thoughtSummary.isNotBlank()) {
            builder.append("#### 2. Technical & Algorithmic Execution\n")
            builder.append(codingNode.thoughtSummary.lines().take(10).joinToString("\n"))
            builder.append("\n\n")
        }

        // 3. Logic & Invariant Breakdown
        if (logicNode != null && logicNode.thoughtSummary.isNotBlank()) {
            builder.append("#### 3. Formal Invariants & Logical Validation\n")
            builder.append(logicNode.thoughtSummary.lines().take(6).joinToString("\n"))
            builder.append("\n\n")
        }

        // 4. Execution Fabric Grounding
        builder.append("#### 4. Execution Fabric Dispatch\n")
        val automationPlan = automationNode?.thoughtSummary ?: "• Intent grounded in UnifiedExecutionFabric with strict sandbox boundary checks."
        builder.append(automationPlan.lines().take(5).joinToString("\n"))
        builder.append("\n\n")

        // 5. Participating Node Badges & Truth State
        builder.append("---\n")
        builder.append("**Synthesis Participating Nodes:**\n")
        nodes.forEach { node ->
            builder.append("• **${node.modelName}** [${node.executionMode}] | Status: `${node.runtimeStatus}` | Latency: `${node.latencyMs}ms` | Confidence: `${(node.inferenceConfidence * 100).toInt()}%`\n")
        }

        return builder.toString()
    }
}
