package com.example.data.ai.engine

import com.example.data.ai.model.ProviderRequest
import com.example.data.ai.provider.AIProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class StreamingEngine {
    suspend fun streamFromProvider(provider: AIProvider, request: ProviderRequest): Flow<String> {
        return provider.stream(request)
    }
}
