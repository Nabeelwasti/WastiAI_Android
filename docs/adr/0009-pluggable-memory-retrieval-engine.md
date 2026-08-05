# ADR 0009: Pluggable 4D Memory Retrieval Engine & Provenance Explainability

## Status
Accepted & Implemented

## Context
Following the CTO Strategic Review, `Wasti AI` required a standalone `MemoryRetrievalEngine` to decouple hybrid search ranking from `MemoryManager`. Furthermore, memory context injected into AI prompts required explainability and provenance tracking to make retrieval decisions transparent and debuggable.

## Decision
We implemented `com.example.data.memory.retrieval.MemoryRetrievalEngine`:

1. **Standalone Subsystem Architecture**:
   - Extracted retrieval logic from `MemoryManager` into a dedicated `MemoryRetrievalEngine`.
   - `RetrievalPolicy`: Dynamic 4D multi-factor scoring algorithm taking vector similarity (50%), keyword frequency (20%), recency decay (15%), and importance score (15%).

2. **Explainable Provenance & Context Logging**:
   - Every search produces `RetrievalExplanation` objects recording scores breakdown and human-readable provenance reasons (e.g., `Sim: 0.92, Keyword: 0.80, Recency: 1.00`).
   - Prompt context formatter in `MemoryManager` includes provenance reasons in prompt context blocks.

## Consequences
- **Flexibility**: Search scoring weights can be tuned at runtime without modifying storage code.
- **Observability**: Developers and operators can inspect exact reasons why specific long-term memories were recalled.
