# ADR 0008: Enterprise Operations & Telemetry Dashboard Engine (Sprint 6)

## Status
Accepted & Implemented

## Context
As `Wasti AI` evolved into an AI Operating Platform, system operators required real-time observability across AI provider health, response latencies, token consumption, daily USD costs, memory & vector index density, and system events.

## Decision
We implemented `com.example.data.ops.OperationsManager`:

1. **Unified Observability Aggregator**:
   - Aggregates live telemetry from `HealthMonitor`, `CostTracker`, `TokenUsageTracker`, `CapabilityRegistry`, and `MemoryManager`.
   - Exposes `OperationsDashboardStats` state flow containing per-provider success rates, sliding window latency, daily cost estimates, total active vector embeddings, and knowledge graph node/edge counts.

2. **Quality Gates & Governance**:
   - Enforces 5 Quality Gates: Build Gate, Architecture Gate, Testing Gate, Documentation Gate, and Performance Gate.

## Consequences
- **Positive**: Complete real-time operational visibility into multi-cloud AI infrastructure, memory storage, and system health.
