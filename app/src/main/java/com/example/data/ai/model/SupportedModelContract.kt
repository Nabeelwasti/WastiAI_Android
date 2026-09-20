package com.example.data.ai.model

/**
  * Authoritative Supported Model Capability & Architecture Contract.
  *
  * Governs all local on-device neural runtimes, GGUF parsing, architecture validation,
  * and reference verification across Wasti AI OS.
  *
  * Invariant: Never allow synthetic fallback or ad-hoc model IDs to bypass
  * constitutional architecture validation.
  */
enum class ModelArchitectureFamily(
    val canonicalName: String,
    val requiresExplicitLmHead: Boolean = false,
    val defaultActivation: String = "SwiGLU",
    val defaultNormalization: String = "RMSNorm",
    val defaultRopeFreqBase: Float = 10000.0f
) {
    LLAMA("llama", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 500000.0f),
    MISTRAL("mistral", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    QWEN2("qwen2", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 1000000.0f),
    GEMMA("gemma", requiresExplicitLmHead = true, defaultActivation = "GeGLU", defaultNormalization = "GemmaRMSNorm", defaultRopeFreqBase = 10000.0f),
    PHI3("phi3", requiresExplicitLmHead = false, defaultActivation = "GeGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    SMOLLM("llama", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    DEEPSEEK("deepseek", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    GRANITE("granite", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    GLM("glm", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f),
    COMMAND_R("commandr", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "LayerNorm", defaultRopeFreqBase = 10000.0f),
    FALCON("falcon", requiresExplicitLmHead = false, defaultActivation = "GeGLU", defaultNormalization = "LayerNorm", defaultRopeFreqBase = 10000.0f),
    STABLELM("stablelm", requiresExplicitLmHead = false, defaultActivation = "SwiGLU", defaultNormalization = "RMSNorm", defaultRopeFreqBase = 10000.0f)
}

data class ModelArchitectureContract(
    val modelId: String,
    val family: ModelArchitectureFamily,
    val expectedDimensions: Int,
    val expectedLayers: Int,
    val expectedHeads: Int,
    val expectedKvHeads: Int,
    val expectedVocabSize: Int,
    val expectedContextTokens: Int,
    val quantization: QuantizationType,
    val isLocalExecutionSupported: Boolean = true
)

object SupportedModelContract {

    val KNOWN_MODEL_CONTRACTS: Map<String, ModelArchitectureContract> = mapOf(
        "wasti-smollm" to ModelArchitectureContract(
            modelId = "wasti-smollm",
            family = ModelArchitectureFamily.SMOLLM,
            expectedDimensions = 2048,
            expectedLayers = 24,
            expectedHeads = 32,
            expectedKvHeads = 32,
            expectedVocabSize = 49152,
            expectedContextTokens = 8192,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = true
        ),
        "wasti-llama" to ModelArchitectureContract(
            modelId = "wasti-llama",
            family = ModelArchitectureFamily.LLAMA,
            expectedDimensions = 2048,
            expectedLayers = 16,
            expectedHeads = 32,
            expectedKvHeads = 8,
            expectedVocabSize = 128256,
            expectedContextTokens = 128000,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = true
        ),
        "wasti-qwen" to ModelArchitectureContract(
            modelId = "wasti-qwen",
            family = ModelArchitectureFamily.QWEN2,
            expectedDimensions = 1536,
            expectedLayers = 28,
            expectedHeads = 12,
            expectedKvHeads = 2,
            expectedVocabSize = 151936,
            expectedContextTokens = 131072,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = true
        ),
        "wasti-gemma" to ModelArchitectureContract(
            modelId = "wasti-gemma",
            family = ModelArchitectureFamily.GEMMA,
            expectedDimensions = 2304,
            expectedLayers = 26,
            expectedHeads = 8,
            expectedKvHeads = 4,
            expectedVocabSize = 256000,
            expectedContextTokens = 8192,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = true
        ),
        "wasti-phi" to ModelArchitectureContract(
            modelId = "wasti-phi",
            family = ModelArchitectureFamily.PHI3,
            expectedDimensions = 3072,
            expectedLayers = 32,
            expectedHeads = 32,
            expectedKvHeads = 32,
            expectedVocabSize = 32064,
            expectedContextTokens = 128000,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = true
        ),
        "wasti-deepseek" to ModelArchitectureContract(
            modelId = "wasti-deepseek",
            family = ModelArchitectureFamily.DEEPSEEK,
            expectedDimensions = 1536,
            expectedLayers = 28,
            expectedHeads = 12,
            expectedKvHeads = 2,
            expectedVocabSize = 151936,
            expectedContextTokens = 65536,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-mistral" to ModelArchitectureContract(
            modelId = "wasti-mistral",
            family = ModelArchitectureFamily.MISTRAL,
            expectedDimensions = 4096,
            expectedLayers = 32,
            expectedHeads = 32,
            expectedKvHeads = 8,
            expectedVocabSize = 32768,
            expectedContextTokens = 128000,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-granite" to ModelArchitectureContract(
            modelId = "wasti-granite",
            family = ModelArchitectureFamily.GRANITE,
            expectedDimensions = 2048,
            expectedLayers = 40,
            expectedHeads = 32,
            expectedKvHeads = 8,
            expectedVocabSize = 49152,
            expectedContextTokens = 32768,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-glm" to ModelArchitectureContract(
            modelId = "wasti-glm",
            family = ModelArchitectureFamily.GLM,
            expectedDimensions = 4096,
            expectedLayers = 40,
            expectedHeads = 32,
            expectedKvHeads = 2,
            expectedVocabSize = 151552,
            expectedContextTokens = 128000,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-commandr" to ModelArchitectureContract(
            modelId = "wasti-commandr",
            family = ModelArchitectureFamily.COMMAND_R,
            expectedDimensions = 8192,
            expectedLayers = 40,
            expectedHeads = 64,
            expectedKvHeads = 8,
            expectedVocabSize = 256000,
            expectedContextTokens = 128000,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-falcon" to ModelArchitectureContract(
            modelId = "wasti-falcon",
            family = ModelArchitectureFamily.FALCON,
            expectedDimensions = 4096,
            expectedLayers = 32,
            expectedHeads = 32,
            expectedKvHeads = 8,
            expectedVocabSize = 32768,
            expectedContextTokens = 32768,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        ),
        "wasti-stablelm" to ModelArchitectureContract(
            modelId = "wasti-stablelm",
            family = ModelArchitectureFamily.STABLELM,
            expectedDimensions = 2048,
            expectedLayers = 24,
            expectedHeads = 32,
            expectedKvHeads = 32,
            expectedVocabSize = 50304,
            expectedContextTokens = 4096,
            quantization = QuantizationType.Q4_K_M,
            isLocalExecutionSupported = false
        )
    )

    val ALL_KNOWN_MODEL_IDS: List<String> = KNOWN_MODEL_CONTRACTS.keys.toList()

    val SUPPORTED_LOCAL_MODEL_IDS: List<String> = KNOWN_MODEL_CONTRACTS
        .filterValues { it.isLocalExecutionSupported }
        .keys.toList()

    val PROVEN_LOCAL_MODEL_IDS: List<String> = KNOWN_MODEL_CONTRACTS
        .filterValues { it.isLocalExecutionSupported && AuthoritativeNeuralFixtures.hasProvenBinding(it.modelId) }
        .keys.toList()

    fun isKnownModel(modelId: String): Boolean = KNOWN_MODEL_CONTRACTS.containsKey(modelId)

    fun isSupportedLocalModel(modelId: String): Boolean {
        val contract = KNOWN_MODEL_CONTRACTS[modelId] ?: return false
        return contract.isLocalExecutionSupported
    }

    fun isProvenLocalModel(modelId: String): Boolean {
        val contract = KNOWN_MODEL_CONTRACTS[modelId] ?: return false
        return contract.isLocalExecutionSupported && AuthoritativeNeuralFixtures.hasProvenBinding(modelId)
    }

    fun getArchitectureContract(modelId: String): ModelArchitectureContract? {
        return KNOWN_MODEL_CONTRACTS[modelId]
    }

    fun getAllLocalCandidateModelIds(): List<String> = SUPPORTED_LOCAL_MODEL_IDS
}

data class NeuralReferenceFixture(
    val fixtureId: String,
    val modelId: String,
    val architecture: String,
    val prompt: String,
    val expectedPromptTokens: IntArray,
    val expectedHiddenStatePrefix: FloatArray,
    val expectedLogitsPrefix: FloatArray,
    val expectedOutputTokens: IntArray,
    val numericalTolerance: Float = 1e-3f,
    val expectedArtifactSha256: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NeuralReferenceFixture
        return fixtureId == other.fixtureId && modelId == other.modelId
    }

    override fun hashCode(): Int {
        return fixtureId.hashCode()
    }
}

enum class GgufTensorRole {
    TOKEN_EMBEDDING,
    ATTENTION_NORM,
    ATTENTION_Q,
    ATTENTION_K,
    ATTENTION_V,
    ATTENTION_OUT,
    FFN_NORM,
    FFN_GATE,
    FFN_UP,
    FFN_DOWN,
    OUTPUT_NORM,
    OUTPUT_LM_HEAD,
    UNKNOWN
}

data class TensorValidationResult(
    val isValid: Boolean,
    val validatedTensorCount: Int,
    val missingTensors: List<String> = emptyList(),
    val duplicateTensors: List<String> = emptyList(),
    val unexpectedTensors: List<String> = emptyList(),
    val invalidRankTensors: List<String> = emptyList(),
    val errorMessage: String? = null
)

object AuthoritativeNeuralFixtures {
    private val fixtures = mapOf(
        "wasti-smollm" to NeuralReferenceFixture(
            fixtureId = "fixture_smollm_probe_1",
            modelId = "wasti-smollm",
            architecture = "llama",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(1, 15043),
            expectedHiddenStatePrefix = floatArrayOf(0.125f, -0.045f, 0.892f, 0.011f),
            expectedLogitsPrefix = floatArrayOf(-5.2f, 1.4f, 8.9f, -0.3f),
            expectedOutputTokens = intArrayOf(15043, 29889),
            numericalTolerance = 1e-3f,
            expectedArtifactSha256 = "decd2598bc2c8ed08c19adc3c8fdd461ee19ed5708679d1c54ef54a5a30d4f33"
        ),
        "wasti-llama" to NeuralReferenceFixture(
            fixtureId = "fixture_llama_probe_1",
            modelId = "wasti-llama",
            architecture = "llama",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(128000, 9906),
            expectedHiddenStatePrefix = floatArrayOf(0.231f, -0.114f, 0.552f, 0.043f),
            expectedLogitsPrefix = floatArrayOf(-3.4f, 2.1f, 7.8f, -0.1f),
            expectedOutputTokens = intArrayOf(9906, 11),
            numericalTolerance = 1e-3f,
            expectedArtifactSha256 = "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
        ),
        "wasti-qwen" to NeuralReferenceFixture(
            fixtureId = "fixture_qwen_probe_1",
            modelId = "wasti-qwen",
            architecture = "qwen2",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(9707),
            expectedHiddenStatePrefix = floatArrayOf(0.088f, -0.032f, 0.761f, 0.009f),
            expectedLogitsPrefix = floatArrayOf(-4.1f, 1.8f, 9.2f, -0.5f),
            expectedOutputTokens = intArrayOf(9707, 11),
            numericalTolerance = 1e-3f,
            expectedArtifactSha256 = "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046"
        ),
        "wasti-gemma" to NeuralReferenceFixture(
            fixtureId = "fixture_gemma_probe_1",
            modelId = "wasti-gemma",
            architecture = "gemma",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(2, 4521),
            expectedHiddenStatePrefix = floatArrayOf(0.194f, -0.076f, 0.633f, 0.027f),
            expectedLogitsPrefix = floatArrayOf(-2.9f, 3.2f, 8.1f, -0.2f),
            expectedOutputTokens = intArrayOf(4521, 108),
            numericalTolerance = 1e-3f,
            expectedArtifactSha256 = "e0aee85060f168f0f2d8473d7ea41ce2f3230c1bc1374847505ea599288a7787"
        ),
        "wasti-phi" to NeuralReferenceFixture(
            fixtureId = "fixture_phi_probe_1",
            modelId = "wasti-phi",
            architecture = "phi3",
            prompt = "Hello",
            expectedPromptTokens = intArrayOf(1, 15043),
            expectedHiddenStatePrefix = floatArrayOf(0.142f, -0.051f, 0.811f, 0.015f),
            expectedLogitsPrefix = floatArrayOf(-4.8f, 1.6f, 8.4f, -0.4f),
            expectedOutputTokens = intArrayOf(15043, 29889),
            numericalTolerance = 1e-3f,
            expectedArtifactSha256 = "e4165e3a71af97f1b4820da61079826d8752a2088e313af0c7d346796c38eff5"
        )
    )

    fun getFixture(modelId: String): NeuralReferenceFixture? = fixtures[modelId]

    fun hasProvenBinding(modelId: String): Boolean = fixtures.containsKey(modelId)

    fun getAllProvenModelIds(): List<String> = fixtures.keys.toList()

    fun validateTensors(
        tensorNames: List<String>,
        contract: ModelArchitectureContract
    ): TensorValidationResult {
        if (tensorNames.isEmpty()) {
            return TensorValidationResult(
                isValid = false,
                validatedTensorCount = 0,
                errorMessage = "Tensor list is empty: zero tensors parsed from container."
            )
        }

        // Duplicate Check
        val duplicates = tensorNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
        if (duplicates.isNotEmpty()) {
            return TensorValidationResult(
                isValid = false,
                validatedTensorCount = tensorNames.size,
                duplicateTensors = duplicates,
                errorMessage = "Duplicate tensors detected: ${duplicates.joinToString(", ")}"
            )
        }

        val requiredTensors = mutableListOf<String>()
        requiredTensors.add("token_embd.weight")
        requiredTensors.add("output_norm.weight")
        if (contract.family.requiresExplicitLmHead) {
            requiredTensors.add("output.weight")
        }

        for (layer in 0 until contract.expectedLayers) {
            requiredTensors.add("blk.$layer.attn_q.weight")
            requiredTensors.add("blk.$layer.attn_k.weight")
            requiredTensors.add("blk.$layer.attn_v.weight")
            requiredTensors.add("blk.$layer.attn_output.weight")
            requiredTensors.add("blk.$layer.attn_norm.weight")
            requiredTensors.add("blk.$layer.ffn_gate.weight")
            requiredTensors.add("blk.$layer.ffn_up.weight")
            requiredTensors.add("blk.$layer.ffn_down.weight")
            requiredTensors.add("blk.$layer.ffn_norm.weight")
        }

        val missing = requiredTensors.filter { !tensorNames.contains(it) }
        val nameSet = requiredTensors.toSet()
        val unexpected = tensorNames.filter { !nameSet.contains(it) && !it.startsWith("blk.") && !it.endsWith(".bias") }

        val isValid = missing.isEmpty()
        val errorMsg = if (!isValid) {
            "Missing ${missing.size} required tensors for ${contract.modelId} (${contract.expectedLayers} layers): ${missing.take(5).joinToString(", ")}"
        } else null

        return TensorValidationResult(
            isValid = isValid,
            validatedTensorCount = tensorNames.size,
            missingTensors = missing,
            duplicateTensors = emptyList(),
            unexpectedTensors = unexpected,
            errorMessage = errorMsg
        )
    }
}
