package com.example.data.ai.provider

import com.example.data.ai.engine.HardwareCapabilityDetector
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.OpenSourceModelDescriptor
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.ai.runtime.WastiEmbeddingRuntime
import com.example.data.ai.runtime.WastiLocalModelRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class WastiLocalBrainProvider(
    val modelDescriptor: OpenSourceModelDescriptor
) : AIProvider {
    override val id: String = modelDescriptor.id
    override val name: String = modelDescriptor.brandDisplayName
    override val defaultModel: String = modelDescriptor.defaultVersion
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.STREAMING,
        ProviderCapability.MULTI_TURN
    )

    override fun isAvailable(): Boolean {
        val appCtx = com.example.WastiApplication.instance ?: return false
        val manifest = ModelArtifactManager.getManifest(id) ?: return false
        val hasWeights = ModelArtifactManager.isWeightsPresent(appCtx, id)
        if (!hasWeights) return false

        val specs = HardwareCapabilityDetector.detectHardwareEnvironment(appCtx)
        return specs.totalRamMb >= manifest.minRamRequiredMb
    }

    fun isConfiguredOrDeclared(): Boolean = true

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        val appCtx = com.example.WastiApplication.instance
        val specs = HardwareCapabilityDetector.detectHardwareEnvironment(appCtx)
        val manifest = ModelArtifactManager.getManifest(id)

        val hasWeights = appCtx?.let { ModelArtifactManager.isWeightsPresent(it, id) } ?: false
        
        val content = if (appCtx != null && hasWeights) {
            ModelArtifactManager.updateStatus(id, ModelRuntimeStatus.ACTIVE_LOADED)
            val runtime = WastiLocalModelRuntime(appCtx)
            runtime.executeInference(
                modelId = id,
                prompt = request.prompt,
                systemInstruction = request.systemInstruction
            )
        } else {
            val reqText = if (manifest != null) {
                "Model '${modelDescriptor.brandDisplayName}' is declared in catalog (Format: ${manifest.quantization}, Min RAM: ${manifest.minRamRequiredMb}MB, System RAM: ${specs.totalRamMb}MB). Local weights are pending download via Model Manager."
            } else {
                "Model '${modelDescriptor.brandDisplayName}' is registered in Wasti Local Brain Catalog. Backend: ${modelDescriptor.defaultBackend}."
            }
            "[LOCAL_INFERENCE_PENDING]: $reqText\nPrompt: \"${request.prompt.take(100)}\""
        }

        val latency = System.currentTimeMillis() - startTime

        return ProviderResponse(
            content = content,
            providerId = id,
            providerName = name,
            modelUsed = defaultModel,
            promptTokens = request.prompt.length / 4,
            completionTokens = content.length / 4,
            latencyMs = latency,
            costUsd = 0.0
        )
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val response = generate(request)
        val tokens = response.content.split(" ")
        for (token in tokens) {
            emit("$token ")
            delay(15)
        }
    }

    override suspend fun embeddings(text: String): FloatArray {
        return WastiEmbeddingRuntime.encode(text)
    }
}
