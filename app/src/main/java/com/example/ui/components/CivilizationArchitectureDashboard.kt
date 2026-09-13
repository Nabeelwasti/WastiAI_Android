package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.council.AICouncilEngine
import com.example.data.architecture.*
import com.example.data.architecture.civilization.CapabilityCivilizationRegistry
import com.example.data.architecture.civilization.CapabilityLifecycleState
import com.example.data.architecture.observatory.CostIntelligenceEngine
import com.example.data.architecture.observatory.ObservatoryEngine
import com.example.data.architecture.observatory.ReliabilityEngine
import com.example.data.core.WastiExperienceMode
import kotlinx.coroutines.launch

/**
 * Universal Architecture & Civilization Dashboard.
 *
 * Implements Phase 1 to Phase 12 Architecture Intelligence System:
 * - 9-Layer Universal Hierarchy
 * - AI Council Role Deliberation & Identity Orbit
 * - Capability Civilization Registry (Health, Lifecycle, Metrics)
 * - World-Brain Runtime Topology (Mobile = Brain, Nearby = Muscle, Cloud = Cortex)
 * - Enterprise Observatory, Reliability & Sovereign Cost Intelligence
 * - Architecture Evolution Boundary Auditing
 */
@Composable
fun CivilizationArchitectureDashboard(
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val currentMode by WastiExperienceMode.currentMode.collectAsState()
    val observatoryMetrics by ObservatoryEngine.metricsState.collectAsState()
    val reliabilityStatus by ReliabilityEngine.reliabilityState.collectAsState()
    val costLedger by CostIntelligenceEngine.costLedgerState.collectAsState()
    val runtimeTopology by RuntimeTopologyMap.topologyState.collectAsState()

    val allNodes = remember { ArchitectureKnowledgeGraph.getAllNodes() }
    val allCapabilities = remember { CapabilityCivilizationRegistry.getAll() }

    var isRunningAudit by remember { mutableStateOf(false) }
    var auditReport by remember { mutableStateOf<ArchitectureEvolutionEngine.ArchitectureAuditReport?>(null) }
    var selectedLayerFilter by remember { mutableStateOf<ArchitectureLayer?>(null) }
    var expandedCouncilDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("civilization_architecture_dashboard"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // CARD 1: ARCHITECTURE COGNITION HEADER & MODE BADGE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Universal Hierarchy",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Wasti Civilization of Capabilities",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "9-Layer Universal Hierarchy & Architecture Knowledge Graph",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("experience_mode_badge")
                    ) {
                        Text(
                            text = currentMode.displayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Hierarchy Flow Diagram Summary
                Text(
                    text = "USER  ➔  BRAIN  ➔  KNOWLEDGE  ➔  CAPABILITY  ➔  EXECUTION  ➔  DEVICE  ➔  CLOUD  ➔  MESH  ➔  REALITY",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // CARD 2: AI COUNCIL & WASTI IDENTITY ORBIT (PHASE 11 & 12)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Council & Identity Orbit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    TextButton(onClick = { expandedCouncilDetails = !expandedCouncilDetails }) {
                        Text(if (expandedCouncilDetails) "Hide Roles" else "View Roles", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Identity Orbit Flow
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val stages = listOf("INTENT", "PLAN", "EXECUTE", "OBSERVE", "VERIFY", "REMEMBER", "IMPROVE")
                        stages.forEachIndexed { index, stage ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stage,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (index < stages.size - 1) {
                                Text("›", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = expandedCouncilDetails) {
                    Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CouncilRoleItem("Claude 3.5", "Architecture & Evolutionary Refactoring", "Anthropic / API")
                        CouncilRoleItem("GPT-4o", "Deep Multi-Perspective Reasoning", "OpenAI / API")
                        CouncilRoleItem("Gemini 1.5", "Web Grounding & Real-Time Research", "Google Cloud")
                        CouncilRoleItem("DeepSeek V3/R1", "Polyglot Code Synthesis & Sandbox Optimization", "Groq / DeepSeek")
                        CouncilRoleItem("SmolLM 360M", "100% Sovereign Offline Privacy & Zero-Latency", "Local Edge NPU")
                        CouncilRoleItem("Wasti Core", "Final Autonomous Decision & Governance Authority", "Sovereign Master")
                    }
                }
            }
        }

        // CARD 3: ENTERPRISE OBSERVATORY & SOVEREIGN COST LEDGER (PHASE 8)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enterprise Observatory & Sovereign Ledger", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricMiniCard(
                        title = "Health Score",
                        value = "${reliabilityStatus.overallHealthScorePercent.toInt()}%",
                        subtitle = reliabilityStatus.verdict.name,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        title = "Success Rate",
                        value = "${observatoryMetrics.successRatePercent.toInt()}%",
                        subtitle = "${observatoryMetrics.totalOperationsRecorded} ops",
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        title = "p95 Latency",
                        value = "${observatoryMetrics.p95LatencyMs}ms",
                        subtitle = "Avg ${observatoryMetrics.averageLatencyMs}ms",
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        title = "Saved USD",
                        value = "$${String.format("%.3f", costLedger.totalMoneySavedUsd)}",
                        subtitle = "${costLedger.localFreeInferencesExecuted} free",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // CARD 4: WORLD-BRAIN RUNTIME TOPOLOGY (PHASE 10)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeviceHub, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("World-Brain Runtime Topology", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TopologyNodeBadge(
                        role = "BRAIN",
                        device = "Mobile Phone (Local)",
                        status = if (runtimeTopology.primaryNode.isOnline) "Active" else "Offline",
                        isPrimary = true,
                        modifier = Modifier.weight(1f)
                    )
                    TopologyNodeBadge(
                        role = "MUSCLES",
                        device = "Nearby LAN Nodes (${runtimeTopology.muscleNodes.size})",
                        status = "Swarm Standby",
                        isPrimary = false,
                        modifier = Modifier.weight(1f)
                    )
                    TopologyNodeBadge(
                        role = "CORTEX",
                        device = "Cloud Clusters (${runtimeTopology.cortexNodes.size})",
                        status = "Available",
                        isPrimary = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // CARD 5: 9-LAYER HIERARCHY SUB-SYSTEM BROWSER (PHASE 1 & 6)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Subsystem Registry (${allNodes.size} Nodes)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (selectedLayerFilter != null) {
                        TextButton(onClick = { selectedLayerFilter = null }) {
                            Text("Show All", fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Layer Filter Chips
                val displayedNodes = if (selectedLayerFilter != null) {
                    allNodes.filter { it.layer == selectedLayerFilter }
                } else {
                    allNodes
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    displayedNodes.take(8).forEach { node ->
                        SubsystemNodeRow(node = node)
                    }
                    if (displayedNodes.size > 8) {
                        Text(
                            text = "+ ${displayedNodes.size - 8} more subsystems mapped in Universal Graph",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // CARD 6: ONE-TAP ARCHITECTURE SELF-AUDIT (PHASE 1 & 4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Architecture Boundary Audit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isRunningAudit = true
                                val report = ArchitectureEvolutionEngine.auditArchitectureBoundaries()
                                auditReport = report
                                isRunningAudit = false
                            }
                        },
                        enabled = !isRunningAudit,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isRunningAudit) "Auditing..." else "Run Self-Audit", fontSize = 11.sp)
                    }
                }

                auditReport?.let { report ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (report.isArchitectureCompliant) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (report.isArchitectureCompliant) "✓ 100% Architecture Compliant — Zero Violations" else "⚠ Boundary Violations Detected (${report.boundaryViolations.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (report.isArchitectureCompliant) Color(0xFF047857) else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Evaluated ${report.totalNodesEvaluated} nodes, ${report.totalEdgesEvaluated} relations • Sovereign offline integrity: ${String.format("%.1f", report.sovereignOfflineIntegrityPercent)}%",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouncilRoleItem(model: String, responsibility: String, origin: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(model, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
            Text(responsibility, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = origin,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MetricMiniCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun TopologyNodeBadge(role: String, device: String, status: String, isPrimary: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (isPrimary) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(role, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(device, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            Text(status, fontSize = 8.5.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SubsystemNodeRow(node: ArchitectureNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Text(node.responsibility, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (node.isSovereignOffline) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = if (node.isSovereignOffline) "Offline 100%" else "Hybrid Cloud",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (node.isSovereignOffline) Color(0xFF065F46) else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
}
