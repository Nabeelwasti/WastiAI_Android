package com.example.data.ai.model

enum class ModelSpecialization {
    GENERAL_REASONING,
    DEEP_CODING,
    MATHEMATICS_LOGIC,
    CREATIVE_WRITING,
    MULTILINGUAL_TRANSLATION,
    LIGHTWEIGHT_EDGE_EXECUTION,
    SYSTEM_AUTOMATION,
    RESEARCH_SYNTHESIS
}

enum class LocalExecutionBackend {
    MOBILE_NPU_CPU_TENSOR,
    LOCAL_OLLAMA_SERVER,
    LLAMA_CPP_EMBEDDED,
    ONNX_RUNTIME_ACCELERATED,
    WASTI_MESH_FEDERATION
}

data class OpenSourceModelDescriptor(
    val id: String,
    val familyName: String,
    val brandDisplayName: String,
    val defaultVersion: String,
    val parameterRange: String,
    val primarySpecialization: ModelSpecialization,
    val maxContextTokens: Int,
    val isLocalExecutionSupported: Boolean = true,
    val requiresExternalApiKey: Boolean = false,
    val defaultBackend: LocalExecutionBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR,
    val systemPromptTemplate: String = "You are Wasti AI OS, operating locally on device."
)

object OpenSourceModelCatalog {
    val ALL_MODELS = listOf(
        OpenSourceModelDescriptor(
            id = "wasti-llama",
            familyName = "Llama",
            brandDisplayName = "Wasti Llama Local (Meta Family)",
            defaultVersion = "Llama-3.2-1B-Instruct",
            parameterRange = "1B - 3B (Edge Quantized Q4_K_M)",
            primarySpecialization = ModelSpecialization.GENERAL_REASONING,
            maxContextTokens = 128000,
            isLocalExecutionSupported = true,
            defaultBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR
        ),
        OpenSourceModelDescriptor(
            id = "wasti-qwen",
            familyName = "Qwen",
            brandDisplayName = "Wasti Qwen Local (Alibaba Family)",
            defaultVersion = "Qwen2.5-Coder-1.5B",
            parameterRange = "0.5B - 1.5B (Edge Quantized Q4_K_M)",
            primarySpecialization = ModelSpecialization.DEEP_CODING,
            maxContextTokens = 131072,
            isLocalExecutionSupported = true,
            defaultBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        ),
        OpenSourceModelDescriptor(
            id = "wasti-gemma",
            familyName = "Gemma",
            brandDisplayName = "Wasti Gemma Local (Google Open Family)",
            defaultVersion = "Gemma-2-2B-IT",
            parameterRange = "2B (Edge Quantized Q4_K_M)",
            primarySpecialization = ModelSpecialization.MATHEMATICS_LOGIC,
            maxContextTokens = 8192,
            isLocalExecutionSupported = true,
            defaultBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR
        ),
        OpenSourceModelDescriptor(
            id = "wasti-deepseek",
            familyName = "DeepSeek",
            brandDisplayName = "Wasti DeepSeek Local (DeepSeek Family)",
            defaultVersion = "DeepSeek-R1-Distill-Qwen-8B",
            parameterRange = "1.5B - 671B (MoE / Distilled)",
            primarySpecialization = ModelSpecialization.DEEP_CODING,
            maxContextTokens = 65536,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        ),
        OpenSourceModelDescriptor(
            id = "wasti-mistral",
            familyName = "Mistral",
            brandDisplayName = "Wasti Mistral Local (Mistral Family)",
            defaultVersion = "Mistral-Nemo-12B-Instruct",
            parameterRange = "7B - 8x22B",
            primarySpecialization = ModelSpecialization.RESEARCH_SYNTHESIS,
            maxContextTokens = 128000,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        ),
        OpenSourceModelDescriptor(
            id = "wasti-phi",
            familyName = "Phi",
            brandDisplayName = "Wasti Phi Local (Microsoft Family)",
            defaultVersion = "Phi-3.5-mini-instruct-3.8B",
            parameterRange = "1.5B - 3.8B (Edge Quantized Q4_K_M)",
            primarySpecialization = ModelSpecialization.LIGHTWEIGHT_EDGE_EXECUTION,
            maxContextTokens = 128000,
            isLocalExecutionSupported = true,
            defaultBackend = LocalExecutionBackend.LLAMA_CPP_EMBEDDED
        ),
        OpenSourceModelDescriptor(
            id = "wasti-granite",
            familyName = "Granite",
            brandDisplayName = "Wasti Granite Local (IBM Family)",
            defaultVersion = "Granite-3.0-8B-Instruct",
            parameterRange = "2B - 34B",
            primarySpecialization = ModelSpecialization.SYSTEM_AUTOMATION,
            maxContextTokens = 32768,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        ),
        OpenSourceModelDescriptor(
            id = "wasti-glm",
            familyName = "GLM",
            brandDisplayName = "Wasti GLM Local (Zhipu AI Family)",
            defaultVersion = "GLM-4-9B-Chat",
            parameterRange = "6B - 130B",
            primarySpecialization = ModelSpecialization.MULTILINGUAL_TRANSLATION,
            maxContextTokens = 128000,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        ),
        OpenSourceModelDescriptor(
            id = "wasti-commandr",
            familyName = "Command R",
            brandDisplayName = "Wasti Command R Local (Cohere Open Family)",
            defaultVersion = "Command-R-35B",
            parameterRange = "7B - 35B (Requires Mesh / Server)",
            primarySpecialization = ModelSpecialization.SYSTEM_AUTOMATION,
            maxContextTokens = 128000,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.WASTI_MESH_FEDERATION
        ),
        OpenSourceModelDescriptor(
            id = "wasti-falcon",
            familyName = "Falcon",
            brandDisplayName = "Wasti Falcon Local (TII Family)",
            defaultVersion = "Falcon-Mamba-7B",
            parameterRange = "7B - 180B",
            primarySpecialization = ModelSpecialization.RESEARCH_SYNTHESIS,
            maxContextTokens = 32768,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        ),
        OpenSourceModelDescriptor(
            id = "wasti-smollm",
            familyName = "SmolLM",
            brandDisplayName = "Wasti SmolLM Ultra-Edge (Hugging Face Family)",
            defaultVersion = "SmolLM2-1.7B-Instruct",
            parameterRange = "135M - 1.7B (Edge Quantized Q4_K_M)",
            primarySpecialization = ModelSpecialization.LIGHTWEIGHT_EDGE_EXECUTION,
            maxContextTokens = 8192,
            isLocalExecutionSupported = true,
            defaultBackend = LocalExecutionBackend.MOBILE_NPU_CPU_TENSOR
        ),
        OpenSourceModelDescriptor(
            id = "wasti-stablelm",
            familyName = "StableLM",
            brandDisplayName = "Wasti StableLM Local (Stability Family)",
            defaultVersion = "StableLM-2-12B-Chat",
            parameterRange = "1.6B - 12B",
            primarySpecialization = ModelSpecialization.CREATIVE_WRITING,
            maxContextTokens = 4096,
            isLocalExecutionSupported = false,
            defaultBackend = LocalExecutionBackend.LOCAL_OLLAMA_SERVER
        )
    )

    fun getModelById(id: String): OpenSourceModelDescriptor? = ALL_MODELS.find { it.id == id || it.familyName.equals(id, ignoreCase = true) }
}
