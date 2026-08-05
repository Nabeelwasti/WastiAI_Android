# ADR 0004: Enterprise AI Provider Abstraction Architecture (Sprint 3 Roadmap)

## Status
Accepted / In Planning for Sprint 3

## Context
Currently, `WastiCore` implements multi-tier routing (Fast Lane, Standard Lane, Deep Lane, Offline Lane) with automatic failover, but invokes provider API singletons (`GeminiClient`, `GroqClient`, `XAIClient`, `OpenAIClient`, `DeepSeekClient`, `OpenRouterClient`) directly. The system lacks a formal `AIProvider` interface abstraction.

## Decision
For Sprint 3 (Enterprise AI Engine), we will introduce a unified polymorphic provider layer:
1. **`AIProvider` Interface**:
```kotlin
interface AIProvider {
    val id: String
    val name: String
    val capabilities: Set<ProviderCapability>
    
    suspend fun chat(
        prompt: String,
        systemInstruction: String,
        history: List<GeminiContent> = emptyList()
    ): ProviderResponse

    suspend fun vision(
        prompt: String,
        imageBytes: ByteArray,
        mimeType: String
    ): ProviderResponse

    suspend fun embeddings(text: String): FloatArray
}
```
2. **`ProviderRouter` Component**:
Handles provider health monitoring, dynamic latency tracking, rate limit monitoring, cost estimation, streaming, retries, and automatic failover cascade across `AIProvider` implementations.
3. **`AIManager` Orchestrator**:
High-level entry point for ViewModels and domain layers.

## Consequences
- **Positive**: Clean separation of concerns adhering to Dependency Inversion (SOLID).
- **Positive**: Makes adding new AI providers (Claude, DeepSeek, Ollama, OpenRouter) plug-and-play without modifying core routing logic.
- **Positive**: Enables automated provider health monitoring and cost tracking.
