package com.example.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wasti Experience Modes.
 *
 * Implements Phase 9 User Experience Revolution:
 * Solves feature overload without removing or splitting any capabilities.
 *
 * All modes operate on the exact same 100% unified WastiOmniBrain and
 * Capability Civilization—differing only in exposure and technical granularity.
 */
enum class WastiExperienceMode(
    val displayName: String,
    val description: String,
    val showTechnicalLedger: Boolean,
    val showArchitectureGraph: Boolean,
    val showTerminalFab: Boolean,
    val autoExecuteSafeActions: Boolean
) {
    SIMPLE_MODE(
        displayName = "Simple Mode",
        description = "Clean, distraction-free conversational experience for everyday assistance",
        showTechnicalLedger = false,
        showArchitectureGraph = false,
        showTerminalFab = false,
        autoExecuteSafeActions = true
    ),
    ADVANCED_MODE(
        displayName = "Advanced Mode",
        description = "Displays model citations, consensus sources, and confidence metrics",
        showTechnicalLedger = true,
        showArchitectureGraph = false,
        showTerminalFab = false,
        autoExecuteSafeActions = true
    ),
    ARCHITECT_MODE(
        displayName = "Architect Mode",
        description = "Visualizes Architecture Knowledge Graph, layer topology, and subsystem impact radius",
        showTechnicalLedger = true,
        showArchitectureGraph = true,
        showTerminalFab = true,
        autoExecuteSafeActions = false
    ),
    DEVELOPER_MODE(
        displayName = "Developer Mode",
        description = "Direct access to WRE polyglot sandbox, terminal, logs, and raw function dispatch",
        showTechnicalLedger = true,
        showArchitectureGraph = true,
        showTerminalFab = true,
        autoExecuteSafeActions = false
    ),
    AUTONOMOUS_MODE(
        displayName = "Autonomous Mode",
        description = "Full sovereign agent capability with background self-evolution and auto-recovery",
        showTechnicalLedger = true,
        showArchitectureGraph = true,
        showTerminalFab = true,
        autoExecuteSafeActions = true
    );

    companion object {
        private val _currentMode = MutableStateFlow(DEVELOPER_MODE)
        val currentMode: StateFlow<WastiExperienceMode> = _currentMode.asStateFlow()

        fun setMode(mode: WastiExperienceMode) {
            _currentMode.value = mode
        }
    }
}
