package com.example.ui.screens

import androidx.compose.runtime.Composable

/**
 * Backwards-compatible delegating surface to the unified ConnectionsAndVaultScreen.
 */
@Composable
fun AccountHubScreen(
    onNavigateBack: () -> Unit
) {
    ConnectionsAndVaultScreen(
        onNavigateBack = onNavigateBack
    )
}
