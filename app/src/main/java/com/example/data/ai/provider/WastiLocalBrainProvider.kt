package com.example.data.ai.provider

import com.example.data.ai.engine.HardwareCapabilityDetector
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.model.ModelRuntimeStatus
import com.example.data.ai.model.OpenSourceModelDescriptor
import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import com.example.data.ai.runtime.WastiLocalModelRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Local open-weight provider boundary.
 *
 * This provider is available only when all three conditions are true:
 * 1. the model artifact exists,
 * 2. its integrity metadata is trusted and verification passes, and
 * 3. a real native inference backend is linked and loadable.
 *
 * It intentionally never substitutes deterministic text for neural inference.
 */
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
        if (!WastiLocalModelRuntime.isNativeInferenceBackendAvailable()) return false
        if (!ModelArtifactManager.isWeightsPresent(appCtx, id)) return false
        return ModelArtifactManager.modelStatuses.value[id] == ModelRuntimeStatus.LOCAL_WEIGHTS_PRESENT ||
            ModelArtifactManager.modelStatuses.value[id] == ModelRuntimeStatus.ACTIVE_LOADED
    }

    fun isConfiguredOrDeclared(): Boolean = true

    override suspend fun generate(request: ProviderRequest): ProviderResponse {
        val startTime = System.currentTimeMillis()
        val appCtx = com.example.WastiApplication.instance
            ?: throw IllegalStateException("Wasti application context is unavailable")
        val manifest = ModelArtifactManager.getManifest(id)
            ?: throw IllegalStateException("No local model manifest registered for '$id'")

        val specs = HardwareCapabilityDetector.detectHardwareEnvironment(appCtx)
        val (hardwareReady, hardwareReason) = HardwareCapabilityDetector.canRunModelLocally(manifest, specs)
        if (!hardwareReady) {
            throw IllegalStateException("Local model '$id' is not executable: $hardwareReason")
        }
        if (!WastiLocalModelRuntime.isNativeInferenceBackendAvailable()) {
            throw UnsupportedOperationException(
                "Native local inference backend is not linked. GGUF presence alone is not neural inference."
            )
        }

        val modelFile = ModelArtifactManager.getModelFile(appCtx, id)
        if (!ModelArtifactManager.verifyModelIntegrity(modelFile, manifest.expectedSha256)) {
            ModelArtifactManager.updateStatus(id, ModelRuntimeStatus.FAILED_INITIALIZATION)
            throw SecurityException("Local model '$id' failed integrity verification")
        }

        ModelArtifactManager.updateStatus(id, ModelRuntimeStatus.VERIFIED_INTEGRITY)
        val runtime = WastiLocalModelRuntime(appCtx)
        val content = runtime.executeInference(
            modelId = id,
            prompt = request.prompt,
            systemInstruction = request.systemInstruction,
            maxTokens = request.maxTokens,
            temperature = request.temperature
        )
        ModelArtifactManager.updateStatus(id, ModelRuntimeStatus.ACTIVE_LOADED)

        return ProviderResponse(
            content = content,
            providerId = id,
            providerName = name,
            modelUsed = defaultModel,
            promptTokens = request.prompt.length / 4,
            completionTokens = content.length / 4,
            latencyMs = System.currentTimeMillis() - startTime,
            costUsd = 0.0
        )
    }

    override suspend fun stream(request: ProviderRequest): Flow<String> = flow {
        val response = generate(request)
        response.content.split(Regex("(?<=\\s)"))
            .filter { it.isNotEmpty() }
            .forEach {
                emit(it)
                delay(15)
            }
    }

    override suspend fun embeddings(text: String): FloatArray {
        throw UnsupportedOperationException(
            "Semantic embeddings are unavailable until a real local embedding model is linked."
        )
    }
}
