package com.example.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VeoVideoResult(
    val videoId: String,
    val title: String,
    val videoUrl: String,
    val status: String
)

object VeoVideoClient {

    suspend fun generateShortVideo(prompt: String): VeoVideoResult = withContext(Dispatchers.IO) {
        val videoId = "veo_" + System.currentTimeMillis()
        VeoVideoResult(
            videoId = videoId,
            title = "Veo AI Generated Clip: ${prompt.take(30)}...",
            videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            status = "COMPLETED"
        )
    }
}
