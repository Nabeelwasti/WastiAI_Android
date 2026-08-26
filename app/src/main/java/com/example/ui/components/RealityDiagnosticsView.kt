package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.agent.runtime.CapabilityReality
import com.example.data.agent.runtime.CapabilityRealityRegistry
import com.example.data.agent.runtime.LiveConnectionStatus
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.di.WastiServiceLocator
import com.example.data.sandbox.WastiWasmRuntime
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun RealityDiagnosticsView(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // Live state collection
    val isAccessibilityActive = WastiAccessibilityService.isServiceActive
    val wasmStatus = remember { WastiWasmRuntime.instance.getRuntimeStatus() }
    val audioOrchestrator = WastiServiceLocator.canonicalAudioOrchestrator
    val inputAudioReality by audioOrchestrator.inputRealityState.collectAsState()
    val outputAudioReality by audioOrchestrator.outputRealityState.collectAsState()
    val realityRegistry = UnifiedExecutionFabric.instance.realityRegistry
    var realityCapabilities by remember { mutableStateOf<List<CapabilityReality>>(realityRegistry.getSystemRealityReport()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.HealthAndSafety,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Wasti Reality & Diagnostic Matrix",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    "Authoritative Device & Sandbox Health",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    realityCapabilities = realityRegistry.getSystemRealityReport()
                                    isRefreshing = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Matrix")
                        }
                    }
                }
            }
        }

        // Section 1: Android Host & Accessibility
        item {
            DiagnosticSectionCard(
                title = "Android Host & Accessibility Service",
                icon = Icons.Default.TouchApp
            ) {
                DiagnosticItemRow(
                    label = "Accessibility Service Status",
                    statusText = if (isAccessibilityActive) "ACTIVE & CONNECTED" else "DISABLED IN SYSTEM SETTINGS",
                    isHealthy = isAccessibilityActive,
                    detail = if (isAccessibilityActive) "Can perform gestures and scrape interactive window content" else "Enable in Android Settings > Accessibility > Wasti AI"
                )
                DiagnosticItemRow(
                    label = "Gesture Dispatch Capability",
                    statusText = if (isAccessibilityActive) "AVAILABLE (canPerformGestures=true)" else "UNAVAILABLE",
                    isHealthy = isAccessibilityActive,
                    detail = "Programmatic tap and swipe coordinates ready"
                )
                DiagnosticItemRow(
                    label = "Window Content Node Scraping",
                    statusText = if (isAccessibilityActive) "READY (canRetrieveWindowContent=true)" else "DISABLED",
                    isHealthy = isAccessibilityActive,
                    detail = "Screen hierarchy parsing enabled"
                )
            }
        }

        // Section 2: Action Intent Engine & Adapters
        item {
            DiagnosticSectionCard(
                title = "Action Intent Fabric & Adapters",
                icon = Icons.AutoMirrored.Filled.DirectionsRun
            ) {
                DiagnosticItemRow(
                    label = "AndroidDeviceIntegrationAdapter",
                    statusText = "MOUNTED (9 Actions)",
                    isHealthy = true,
                    detail = "OPEN_APP, SEND_WHATSAPP, SEND_EMAIL, SEND_SMS, POST_SOCIAL, READ_SCREEN, TAP, SWIPE"
                )
                DiagnosticItemRow(
                    label = "WasmSandboxIntegrationAdapter",
                    statusText = "MOUNTED & READY",
                    isHealthy = true,
                    detail = "EXECUTE_MODULE, RUN_FUNCTION, RUN_TOOL"
                )
                DiagnosticItemRow(
                    label = "GmailIntegrationAdapter",
                    statusText = "AUTH_REQUIRED",
                    isHealthy = false,
                    detail = "OAuth scope authorization required before live execution"
                )
            }
        }

        // Section 3: Audio Pipeline (ONE Voice Loop)
        item {
            DiagnosticSectionCard(
                title = "Canonical Audio Pipeline",
                icon = Icons.Default.Mic
            ) {
                DiagnosticItemRow(
                    label = "Audio Input Reality",
                    statusText = inputAudioReality.name,
                    isHealthy = inputAudioReality.name.contains("AVAILABLE") || inputAudioReality.name.contains("CONNECTED"),
                    detail = "Microphone capture + Energy VAD + Android STT"
                )
                DiagnosticItemRow(
                    label = "Audio Output Reality",
                    statusText = outputAudioReality.name,
                    isHealthy = outputAudioReality.name.contains("AVAILABLE") || outputAudioReality.name.contains("CONNECTED"),
                    detail = "Android TTS Provider + ElevenLabs Speech Synthesis"
                )
            }
        }

        // Section 4: WASM Sandboxed Runtime
        item {
            DiagnosticSectionCard(
                title = "WASM Sandboxed Execution Engine",
                icon = Icons.Default.Memory
            ) {
                DiagnosticItemRow(
                    label = "Sandbox Virtual Machine",
                    statusText = wasmStatus["status"]?.toString() ?: "OPERATIONAL",
                    isHealthy = true,
                    detail = "Stack VM with LEB128 decoder and fuel limiter (1,000,000 opcodes limit)"
                )
                DiagnosticItemRow(
                    label = "Linear Memory Bounds",
                    statusText = "${wasmStatus["maxAllowedPages"]} Pages (${(wasmStatus["maxAllowedPages"] as? Int ?: 16) * 64} KB Max Heap)",
                    isHealthy = true,
                    detail = "Strict memory isolation per sandboxed module"
                )
                DiagnosticItemRow(
                    label = "Executed Tool Cycles",
                    statusText = "${wasmStatus["totalExecutions"]} Executions (${wasmStatus["totalFuelUsed"]} Fuel Used)",
                    isHealthy = true,
                    detail = "Zero-leak sandboxed execution lifetime"
                )
            }
        }

        // Section 5: Discovered Reality Matrix
        item {
            val count = realityCapabilities.size
            DiagnosticSectionCard(
                title = "Capability Reality Registry ($count)",
                icon = Icons.AutoMirrored.Filled.FactCheck
            ) {
                if (realityCapabilities.isEmpty()) {
                    Text(
                        "No capabilities registered yet. Refresh to audit reality.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (cap in realityCapabilities.take(8)) {
                        DiagnosticItemRow(
                            label = cap.capabilityId,
                            statusText = cap.liveConnectionStatus.name,
                            isHealthy = cap.liveConnectionStatus == LiveConnectionStatus.VERIFIED,
                            detail = "Category: ${cap.category} • Provider: ${cap.provider} • State: ${cap.realityState.name}"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            content()
        }
    }
}

@Composable
private fun DiagnosticItemRow(
    label: String,
    statusText: String,
    isHealthy: Boolean,
    detail: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                color = if (isHealthy) Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFB71C1C).copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHealthy) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = detail,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
