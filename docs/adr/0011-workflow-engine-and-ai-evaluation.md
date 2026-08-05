# ADR 0011: Enterprise Workflow Automation & AI Quality Evaluation Engine

## Status
Accepted & Implemented

## Context
Following the CTO's Strategic Priority Matrix for Sprints 7–8, `Wasti AI` required an enterprise `WorkflowEngine` (conditional triggers and automation rules) and an `AIEvaluationEngine` (continuous benchmarking of provider accuracy, latency, cost efficiency, and hallucination metrics).

## Decision
We implemented `com.example.data.workflow` and `com.example.data.evaluation`:

1. **Enterprise `WorkflowEngine`**:
   - Condition-based rules engine mapping `TriggerType` (`ON_EVENT`, `ON_SCHEDULE`, `ON_MEMORY_STORED`, `ON_VOICE_SESSION`) to `ActionType` (`RUN_TOOL`, `SCHEDULE_TASK`, `EMIT_ALERT`, `STORE_MEMORY`, `NOTIFICATION`).
   - Supports primary actions with fallback action paths and execution telemetry.

2. **`AIEvaluationEngine`**:
   - Sliding-window benchmarking engine capturing accuracy rates, latency distributions, cost ratings, and user satisfaction metrics per provider.
   - Computes composite quality scores to guide dynamic model routing in `ProviderRouter`.

3. **Operations Integration**:
   - Both engines export state flows aggregated into `OperationsManager.OperationsDashboardStats` for real-time observability across all platform layers.

## Consequences
- **Automation**: Enables zero-touch event-driven automation pipelines across Wasti AI.
- **Quality Optimization**: Intelligent dynamic model routing based on empirical quality scores rather than static assumptions.
