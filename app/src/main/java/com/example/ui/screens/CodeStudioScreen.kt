package com.example.ui.screens

import androidx.compose.runtime.Composable
import com.example.data.wre.WreManager
import com.example.ui.viewmodel.WastiViewModel

/**
 * Unified CodeStudioScreen:
 * Merged directly into TerminalWorkspaceScreen with mode [TerminalWorkspaceMode.CODE_STUDIO].
 * Preserves 100% API compatibility for all navigation routes and callers while eliminating
 * duplicate execution paths across the sovereign terminal and code editor.
 */
@Composable
fun CodeStudioScreen(
    viewModel: WastiViewModel? = null,
    wreManager: WreManager,
    activeCodeContext: String = "fun main() {\n    println(\"Wasti OS Code Engine\")\n}",
    onCodeContextChange: (String) -> Unit = {},
    onSendMessageToChat: (prompt: String, codeContext: String) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit = {}
) {
    TerminalWorkspaceScreen(
        wreManager = wreManager,
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        initialMode = TerminalWorkspaceMode.CODE_STUDIO,
        activeCodeContext = activeCodeContext,
        onCodeContextChange = onCodeContextChange,
        onSendMessageToChat = onSendMessageToChat
    )
}
