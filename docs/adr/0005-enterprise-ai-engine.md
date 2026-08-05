# ADR 0005: Enterprise AI Engine Architecture (Sprint 3)

## Status
Accepted & Implemented

## Context
Previously, `WastiCore` invoked provider APIs (`GeminiClient`, `GroqClient`, `OpenAIClient`, `XAIClient`, `DeepSeekClient`, `OpenRouterClient`) directly with hardcoded fallback chains. This created tight coupling, lacked health and latency metrics, offered no centralized token or cost tracking, and lacked standardized retry/streaming/tool-calling interfaces.

## Decision
We implemented a complete modular **Enterprise AI Engine** architecture under `com.example.data.ai`:

1. **Shared Polymorphic Provider Model**:
   - `AIProvider` Interface: Common interface implemented by `GeminiProvider`, `GroqProvider`, `OpenAIProvider`, `XAIProvider`, `DeepSeekProvider`, `OpenRouterProvider`, and `OfflineProvider`.
   - `ProviderCapability` Enum: `TEXT_GENERATION`, `VISION`, `STREAMING`, `EMBEDDINGS`, `TOOL_CALLING`, `MULTI_TURN`.

2. **Core AI Engine Platform Components**:
   - `CapabilityRegistry`: Dynamic registration and capability-based provider lookup.
   - `HealthMonitor`: Real-time health status tracking (`HEALTHY`, `DEGRADED`, `UNHEALTHY`), sliding window latency tracking, and success/failure statistics.
   - `TokenUsageTracker`: Tracks prompt and completion tokens per provider.
   - `CostTracker`: Calculates USD cost based on per-model pricing tiers and exposes live daily cost StateFlow.
   - `RetryManager`: Exponential backoff execution for transient network errors (HTTP 429 rate limit, 503 unavailable, timeouts).
   - `StreamingEngine`: Standardized token chunk streaming flow.
   - `ToolCallingEngine`: Schema-based tool registration and execution framework.
   - `ConversationCoordinator`: Context enrichment, workspace context injection, and conversation history transcript formatting.
   - `ProviderRouter`: Capability matching, health-based prioritization, retry execution, token & cost tracking, and automatic failover cascade.
   - `AIManager`: High-level singleton orchestrator bringing together all components.

3. **`WastiCore` Migration**:
   - Refactored `WastiCore` to delegate request execution to `AIManager.execute()`, preserving backwards compatibility while routing calls through the enterprise telemetry and failover pipeline.

## Consequences
- **Positive**: Complete decoupling of provider integrations from application business logic.
- **Positive**: Real-time observability over model health, response latency, token consumption, and daily cost.
- **Positive**: Automated retries and failover across multi-cloud provider models (Google Gemini, Groq, OpenAI, xAI Grok, DeepSeek, OpenRouter, and Local Offline fallback).
