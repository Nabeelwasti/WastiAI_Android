package com.example.data.agent.runtime

import java.util.concurrent.ConcurrentHashMap

enum class ModelCatalogState {
    MODEL_EXISTS_IN_CATALOG,
    MODEL_LIVE_VERIFIED,
    MODEL_CURRENTLY_AVAILABLE,
    MODEL_SUFFICIENT_FOR_TASK
}

enum class TaskCategory {
    FAST_CHAT,
    DEEP_REASONING,
    CODE_GENERATION,
    CODE_REPAIR,
    CODE_REVIEW,
    RESEARCH,
    WEB_RESEARCH,
    VISION,
    IMAGE_GENERATION,
    VIDEO_GENERATION,
    VOICE_INPUT,
    VOICE_OUTPUT,
    LIVE_CONVERSATION,
    TRANSLATION,
    EMBEDDING,
    COMPUTER_USE,
    PLANNING,
    DIAGNOSIS,
    SELF_CORRECTION,
    ARCHITECTURE,
    DOCUMENT_ANALYSIS
}

data class GeminiModelMetadata(
    val modelId: String,
    val displayName: String,
    val isPreview: Boolean,
    val isExperimental: Boolean,
    val contextWindowTokens: Int,
    val supportedTaskCategories: List<TaskCategory>,
    val catalogState: ModelCatalogState,
    val credentialRef: CredentialRef = CredentialRef("GEMINI_API_KEY"),
    val costPer1kInputTokens: Double = 0.0001,
    val costPer1kOutputTokens: Double = 0.0004
)

class GeminiModelCatalog {

    private val models = ConcurrentHashMap<String, GeminiModelMetadata>()

    init {
        registerVerifiedCatalog()
    }

    private fun registerVerifiedCatalog() {
        val defaultList = listOf(
            GeminiModelMetadata("gemini-2.5-flash", "Gemini 2.5 Flash", false, false, 1_000_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.PLANNING, TaskCategory.DIAGNOSIS), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-2.5-pro", "Gemini 2.5 Pro", false, false, 2_000_000, listOf(TaskCategory.DEEP_REASONING, TaskCategory.ARCHITECTURE, TaskCategory.CODE_GENERATION), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-flash-latest", "Gemini Flash Latest", false, false, 1_000_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.SELF_CORRECTION), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-flash-lite-latest", "Gemini Flash Lite Latest", false, false, 500_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.TRANSLATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-pro-latest", "Gemini Pro Latest", false, false, 2_000_000, listOf(TaskCategory.DEEP_REASONING, TaskCategory.CODE_REVIEW), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", false, false, 500_000, listOf(TaskCategory.FAST_CHAT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3-flash-preview", "Gemini 3 Flash Preview", true, false, 1_000_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.PLANNING), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", true, false, 2_000_000, listOf(TaskCategory.DEEP_REASONING, TaskCategory.ARCHITECTURE), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.1-pro-preview-customtools", "Gemini 3.1 Pro Preview Custom Tools", true, false, 2_000_000, listOf(TaskCategory.COMPUTER_USE, TaskCategory.CODE_GENERATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash Lite Preview", true, false, 500_000, listOf(TaskCategory.FAST_CHAT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", false, false, 500_000, listOf(TaskCategory.FAST_CHAT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.5-flash", "Gemini 3.5 Flash", false, false, 1_000_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.CODE_GENERATION, TaskCategory.CODE_REPAIR), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", false, false, 500_000, listOf(TaskCategory.FAST_CHAT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.6-flash", "Gemini 3.6 Flash", false, false, 1_000_000, listOf(TaskCategory.FAST_CHAT, TaskCategory.CODE_GENERATION, TaskCategory.PLANNING, TaskCategory.DIAGNOSIS, TaskCategory.SELF_CORRECTION), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-omni-flash-preview", "Gemini Omni Flash Preview", true, false, 1_000_000, listOf(TaskCategory.VISION, TaskCategory.VOICE_INPUT, TaskCategory.VOICE_OUTPUT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-2.5-computer-use-preview-10-2025", "Gemini 2.5 Computer Use", true, false, 1_000_000, listOf(TaskCategory.COMPUTER_USE), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("deep-research-preview-04-2026", "Deep Research Preview", true, false, 2_000_000, listOf(TaskCategory.RESEARCH, TaskCategory.WEB_RESEARCH), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("deep-research-pro-preview-12-2025", "Deep Research Pro Preview", true, false, 2_000_000, listOf(TaskCategory.RESEARCH, TaskCategory.WEB_RESEARCH), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("deep-research-max-preview-04-2026", "Deep Research Max Preview", true, false, 2_000_000, listOf(TaskCategory.RESEARCH, TaskCategory.WEB_RESEARCH), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-embedding-001", "Gemini Embedding 001", false, false, 8192, listOf(TaskCategory.EMBEDDING), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("gemini-embedding-2", "Gemini Embedding 2", false, false, 8192, listOf(TaskCategory.EMBEDDING), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("imagen-4.0-generate-001", "Imagen 4.0 Generate", false, false, 4096, listOf(TaskCategory.IMAGE_GENERATION), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("imagen-4.0-ultra-generate-001", "Imagen 4.0 Ultra Generate", false, false, 4096, listOf(TaskCategory.IMAGE_GENERATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("imagen-4.0-fast-generate-001", "Imagen 4.0 Fast Generate", false, false, 4096, listOf(TaskCategory.IMAGE_GENERATION), ModelCatalogState.MODEL_LIVE_VERIFIED),
            GeminiModelMetadata("veo-3.1-generate-preview", "Veo 3.1 Generate Preview", true, false, 4096, listOf(TaskCategory.VIDEO_GENERATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("veo-3.1-fast-generate-preview", "Veo 3.1 Fast Generate Preview", true, false, 4096, listOf(TaskCategory.VIDEO_GENERATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-2.5-flash-native-audio-latest", "Gemini 2.5 Flash Native Audio", false, false, 1_000_000, listOf(TaskCategory.VOICE_INPUT, TaskCategory.VOICE_OUTPUT), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live Preview", true, false, 1_000_000, listOf(TaskCategory.LIVE_CONVERSATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG),
            GeminiModelMetadata("gemini-3.5-live-translate-preview", "Gemini 3.5 Live Translate Preview", true, false, 1_000_000, listOf(TaskCategory.TRANSLATION, TaskCategory.LIVE_CONVERSATION), ModelCatalogState.MODEL_EXISTS_IN_CATALOG)
        )

        for (m in defaultList) {
            models[m.modelId] = m
        }
    }

    fun registerModel(metadata: GeminiModelMetadata) {
        models[metadata.modelId] = metadata
    }

    fun getModel(modelId: String): GeminiModelMetadata? = models[modelId]

    fun getAllModels(): List<GeminiModelMetadata> = models.values.toList()

    fun findBestModelForTask(taskCategory: TaskCategory): GeminiModelMetadata? {
        val candidates = models.values.filter { it.supportedTaskCategories.contains(taskCategory) }
        return candidates.firstOrNull { it.catalogState == ModelCatalogState.MODEL_LIVE_VERIFIED }
            ?: candidates.firstOrNull { !it.isPreview }
            ?: candidates.firstOrNull()
    }
}
