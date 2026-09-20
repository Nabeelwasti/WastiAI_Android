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

    val SUPPORTED_LOCAL_MODEL_IDS: List<String> = listOf(
        "wasti-smollm",
        "wasti-llama",
        "wasti-qwen",
        "wasti-gemma",
        "wasti-phi",
        "wasti-deepseek",
        "wasti-mistral",
        "wasti-granite",
        "wasti-glm",
        "wasti-commandr",
        "wasti-falcon",
        "wasti-stablelm"
    )

    private val contracts: Map<String, ModelArchitectureContract> = mapOf(
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

    fun isSupportedLocalModel(modelId: String): Boolean {
        return SUPPORTED_LOCAL_MODEL_IDS.contains(modelId) || contracts.containsKey(modelId)
    }

    fun getArchitectureContract(modelId: String): ModelArchitectureContract? {
        return contracts[modelId]
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
    val numericalTolerance: Float = 1e-3f
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
