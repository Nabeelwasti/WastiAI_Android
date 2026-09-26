package com.example.ui.screens

import androidx.compose.runtime.Composable
import com.example.data.db.IntegrationEntity
import com.example.data.db.SystemLogEntity

/**
 * Backwards-compatible delegating surface to the unified ConnectionsAndVaultScreen.
 */
@Composable
fun IntegrationsLogsScreen(
    integrations: List<IntegrationEntity>,
    logs: List<SystemLogEntity>,
    onClearLogs: () -> Unit,
    onToggleIntegration: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit = {}
) {
    ConnectionsAndVaultScreen(
        integrations = integrations,
        logs = logs,
        onClearLogs = onClearLogs,
        onToggleIntegration = onToggleIntegration,
        onNavigateBack = onNavigateBack
    )
}
