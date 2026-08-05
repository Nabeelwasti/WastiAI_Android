package com.example.data.ai.provider

import com.example.data.ai.model.ProviderCapability
import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.model.ProviderResponse
import kotlinx.coroutines.flow.Flow

interface AIProvider {
    val id: String
    val name: String
    val defaultModel: String
    val capabilities: Set<ProviderCapability>
    
    fun isAvailable(): Boolean

    suspend fun generate(request: ProviderRequest): ProviderResponse

    suspend fun stream(request: ProviderRequest): Flow<String>

    suspend fun embeddings(text: String): FloatArray {
        return FloatArray(0)
    }
}
