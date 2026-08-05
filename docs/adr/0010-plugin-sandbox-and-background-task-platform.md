# ADR 0010: Plugin Sandbox, Granular Permissions & Background Task Manager

## Status
Accepted & Implemented

## Context
In accordance with CTO Priority 1 directives, `Wasti AI` required a secure `PluginSandbox`, granular permission enforcement, and a centralized `BackgroundTaskManager` to execute background memory decay, telemetry sync, database maintenance, and analytics.

## Decision
We implemented `com.example.data.plugin` and `com.example.data.worker`:

1. **`PluginSandbox` & `PermissionManager`**:
   - `PermissionManager` controls granular scopes (`READ_MEMORY`, `WRITE_MEMORY`, `READ_PROJECTS`, `WRITE_PROJECTS`, `READ_ANALYTICS`, `READ_BUSINESS_DATA`, `SYNC`, `BACKGROUND_TASKS`, `AI_CHAT`, `VOICE`, `FILES`, `DEVICE_CONTROL`, `AUTOMATION`).
   - `PluginSandbox.executeSafely()` intercepts plugin calls, verifies permissions against the plugin manifest, and isolates exceptions.

2. **Centralized `BackgroundTaskManager`**:
   - Manages recurring and one-off background tasks (`MEMORY_CLEANUP`, `TELEMETRY_SYNC`, `EMBEDDING_GENERATION`, `GRAPH_REBUILD`, `DATABASE_MAINTENANCE`, `PLUGIN_UPDATES`, `ANALYTICS`).
   - Publishes task completion and system alert events over `WastiEventBus`.

3. **Formalized `ToolRegistry`**:
   - Exposes clean tool definitions and invocation contracts (`MemorySearchTool`, `DeviceControlTool`).

## Consequences
- **Security**: Plugins operate inside a permission-checked sandbox avoiding unauthorized data access.
- **Asynchronous Health**: Heavy maintenance operations run smoothly off the main UI thread.
