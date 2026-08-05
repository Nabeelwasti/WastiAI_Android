# ADR 0006: Enterprise Memory Engine & Data Safety Platform (Sprint 4)

## Status
Accepted & Implemented

## Context
Following the Sprint 3 CTO Executive Review, `Wasti AI` required an **Enterprise Memory Engine** to transform from an AI app into an **AI Operating Platform**. Additionally, database schema upgrades required replacing destructive Room migrations (`dropAllTables = true`) with explicit, data-safe migration classes (`MIGRATION_1_2`).

## Decision
We implemented a complete **Enterprise Memory Engine** under `com.example.data.memory` and updated the Room database platform:

1. **Phase A — Data Safety & Explicit Room Migrations**:
   - Upgraded Room Database version from `1` to `2`.
   - Created explicit `MIGRATION_1_2` creating `vector_embeddings`, `knowledge_graph_nodes`, and `knowledge_graph_edges` tables.
   - Replaced destructive migration with `.addMigrations(MIGRATION_1_2)` and `.fallbackToDestructiveMigrationOnDowngrade()`.

2. **Phase B — Enterprise Memory Architecture**:
   - `EmbeddingService`: Provider-agnostic embedding interface with dynamic vector schema (`providerId`, `modelName`, `vectorLength`, `FloatArray`). Supports on-device 768-dim deterministic semantic hashing as offline fallback.
   - `VectorIndex`: On-device vector store computing Cosine Similarity for topK nearest neighbor retrieval across multi-provider embeddings.
   - `KnowledgeGraphEngine`: Entity-relationship graph layer (`USER`, `PROJECT`, `TASK`, `PERSON`, `COMPANY`, `DOCUMENT`, `CONVERSATION`, `GOAL`) supporting relational queries.
   - `MemoryPolicyEngine`: Enforces retention policies, auto-archival thresholds, deduplication, and importance score adjustments.
   - `MemoryManager`: Central coordinator combining hybrid retrieval (vector similarity + text keyword matching) and knowledge graph context into `retrieveRelevantContextPrompt()`.

3. **Phase C — Decoupled Platform Bus & Plugin SDK Permissions**:
   - `WastiEventBus`: Thread-safe SharedFlow event bus for platform events (`MemoryUpdated`, `ProviderHealthChanged`, `VoiceSessionChanged`, `DatabaseMigrationCompleted`, `SyncCompleted`, `PluginInstalled`).
   - `PluginManifest` & `PluginPermission`: Granular permission scopes (`AI_CHAT`, `VOICE`, `FILES`, `NETWORK`, `CAMERA`, `CONTACTS`, `DEVICE_CONTROL`, `AUTOMATION`).

4. **WastiCore Context Pipeline Integration**:
   - Modified `WastiCore.executeOrchestratedRequest()` to automatically query `MemoryManager` and enrich AI prompt system instructions with relevant long-term memory and knowledge graph context before dispatching to `AIManager`.

## Consequences
- **Data Preservation**: User data, conversation histories, and settings persist safely across database upgrades.
- **Context Awareness**: All AI model responses now automatically benefit from relevant long-term memories and graph relationships.
- **Observability**: `MemoryManager.getObservabilityStats()` exposes active memories, vector counts, graph node/edge metrics, and storage usage.
