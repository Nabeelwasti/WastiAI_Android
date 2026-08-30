package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.agent.runtime.*
import com.example.data.di.WastiServiceLocator
import com.example.data.sandbox.WastiWasmRuntime
import com.example.service.WastiAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun CapabilityCenterScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val realityRegistry = remember { UnifiedExecutionFabric.instance.realityRegistry }
    var realityList by remember { mutableStateOf(realityRegistry.getSystemRealityReport()) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>("ALL") }
    var isRefreshing by remember { mutableStateOf(false) }

    // Live Subsystems
    val isAccessibilityActive = WastiAccessibilityService.isServiceActive
    val wasmStatus = remember { WastiWasmRuntime.instance.getRuntimeStatus() }
    val audioOrchestrator = WastiServiceLocator.canonicalAudioOrchestrator
    val inputAudioReality by audioOrchestrator.inputRealityState.collectAsState()
    val outputAudioReality by audioOrchestrator.outputRealityState.collectAsState()

    // Interactive Action Console State
    var selectedAction by remember { mutableStateOf("read_screen") }
    var actionTargetParam by remember { mutableStateOf("") }
    var actionContentParam by remember { mutableStateOf("") }
    var isExecutingAction by remember { mutableStateOf(false) }
    var lastActionResult by remember { mutableStateOf<UnifiedExecutionResult?>(null) }

    val nodeManager = remember { WastiServiceLocator.nodeManager }
    val nodeTopology = remember(isRefreshing) { nodeManager.getTopologySnapshot() }

    val categories = remember {
        listOf("ALL", "NODES_MESH", "AUTOMATION", "EXECUTION", "STORAGE", "DEVELOPMENT", "BRIDGE", "SECURITY", "AI_PROVIDERS")
    }

    val actionOptions = listOf(
        "read_screen" to "Screen Node Scrape (Accessibility)",
        "simulate_tap" to "Simulate Tap (Target Text/ID)",
        "press_back" to "Press Back Navigation",
        "press_home" to "Press Home Navigation",
        "press_recents" to "Press Recents Navigation",
        "scroll_down" to "Scroll Down Container",
        "execute_wasm" to "WASM Stack VM Sandbox",
        "search_memory" to "Hybrid Memory Search",
        "search_web" to "Live Web Search (Google/DuckDuckGo)",
        "write_file" to "Write Workspace File",
        "read_file" to "Read Workspace File"
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
    ) {
        // Header Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capability_center_header"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.FactCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Capability & Device Center",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Authoritative Runtime Reality Matrix",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    realityList = realityRegistry.getSystemRealityReport()
                                    isRefreshing = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Matrix")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Host & Sandbox Summary Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isAccessibilityActive) Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFB71C1C).copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("ACCESSIBILITY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isAccessibilityActive) Color(0xFF2E7D32) else Color(0xFFC62828))
                                Text(if (isAccessibilityActive) "CONNECTED" else "INACTIVE", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0D47A1).copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("WASM ENGINE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                                Text("${wasmStatus["maxAllowedPages"]}p • READY", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF4A148C).copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("AUDIO LOOP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7B1FA2))
                                Text(inputAudioReality.name.take(9), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // Category Filter Tabs
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { category ->
                    val isSelected = (selectedCategoryFilter == category) || (category == "ALL" && selectedCategoryFilter == null)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedCategoryFilter = if (category == "ALL") null else category
                        },
                        label = { Text(category, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        // Interactive Live Capability Test Console
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action_test_console_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Live Action & Capability Dispatch Console",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Text(
                        "Execute actions directly against UnifiedExecutionFabric and observe real-time verification evidence.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Action Selector Dropdown
                    var actionDropdownExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { actionDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                actionOptions.firstOrNull { it.first == selectedAction }?.second ?: selectedAction,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }

                        DropdownMenu(
                            expanded = actionDropdownExpanded,
                            onDismissRequest = { actionDropdownExpanded = false }
                        ) {
                            actionOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontSize = 13.sp) },
                                    onClick = {
                                        selectedAction = key
                                        actionDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Dynamic Parameter Inputs
                    if (selectedAction in listOf("simulate_tap", "write_file", "read_file", "search_memory", "search_web")) {
                        OutlinedTextField(
                            value = actionTargetParam,
                            onValueChange = { actionTargetParam = it },
                            label = {
                                Text(
                                    when (selectedAction) {
                                        "simulate_tap" -> "Target Text / Button Label"
                                        "write_file", "read_file" -> "File Name (e.g. test.txt)"
                                        "search_memory" -> "Memory Query (e.g. Thrivebridge)"
                                        "search_web" -> "Web Query (e.g. AI News)"
                                        else -> "Target Parameter"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    if (selectedAction in listOf("write_file")) {
                        OutlinedTextField(
                            value = actionContentParam,
                            onValueChange = { actionContentParam = it },
                            label = { Text("File Content", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            minLines = 2
                        )
                    }

                    // Dispatch Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isExecutingAction = true
                                lastActionResult = null
                                try {
                                    val req = when (selectedAction) {
                                        "read_screen" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "read_screen")
                                        )
                                        "simulate_tap" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "simulate_tap", "targetElement" to actionTargetParam)
                                        )
                                        "press_back" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "press_back")
                                        )
                                        "press_home" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "press_home")
                                        )
                                        "press_recents" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "press_recents")
                                        )
                                        "scroll_down" -> UnifiedExecutionRequest(
                                            capabilityId = "device_control",
                                            parameters = mapOf("action" to "scroll", "direction" to "DOWN")
                                        )
                                        "execute_wasm" -> UnifiedExecutionRequest(
                                            capabilityId = "TERMINAL",
                                            parameters = mapOf("action" to "execute_code", "language" to "wasm")
                                        )
                                        "search_memory" -> UnifiedExecutionRequest(
                                            capabilityId = "MEMORY_SEARCH",
                                            parameters = mapOf("query" to actionTargetParam.ifBlank { "Wasti OS" })
                                        )
                                        "search_web" -> UnifiedExecutionRequest(
                                            capabilityId = "WEB_SEARCH",
                                            parameters = mapOf("query" to actionTargetParam.ifBlank { "Android Jetpack Compose" })
                                        )
                                        "write_file" -> UnifiedExecutionRequest(
                                            capabilityId = "FILES",
                                            parameters = mapOf("action" to "write_file", "path" to actionTargetParam.ifBlank { "test.txt" }, "content" to actionContentParam.ifBlank { "Hello Wasti AI OS" })
                                        )
                                        "read_file" -> UnifiedExecutionRequest(
                                            capabilityId = "FILES",
                                            parameters = mapOf("action" to "read_file", "path" to actionTargetParam.ifBlank { "test.txt" })
                                        )
                                        else -> UnifiedExecutionRequest(capabilityId = selectedAction)
                                    }

                                    val result = UnifiedExecutionFabric.instance.execute(req, context)
                                    lastActionResult = result
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Action error: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isExecutingAction = false
                                }
                            }
                        },
                        enabled = !isExecutingAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dispatch_test_action_button"),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isExecutingAction) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Executing & Verifying...")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Dispatch Action via UnifiedFabric")
                        }
                    }

                    // Result Display Box
                    lastActionResult?.let { res ->
                        val isVerified = res.verificationStatus == UnifiedVerificationStatus.VERIFIED || res.status == UnifiedExecutionStatus.COMPLETED
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isVerified) Color(0xFF1B5E20).copy(alpha = 0.08f) else Color(0xFFB71C1C).copy(alpha = 0.08f)
                            ),
                            border = BorderStroke(1.dp, if (isVerified) Color(0xFF2E7D32).copy(alpha = 0.5f) else Color(0xFFC62828).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "STATUS: ${res.status.name} (${res.verificationStatus.name})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isVerified) Color(0xFF2E7D32) else Color(0xFFC62828)
                                    )
                                    Text(
                                        "Took ${(res.completedAt - res.startedAt).coerceAtLeast(0)}ms • ${res.executor}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (!res.verificationEvidence.isNullOrBlank()) {
                                    Text(
                                        "Evidence: ${res.verificationEvidence}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Text(
                                    text = res.output.take(400) + if (res.output.length > 400) "..." else "",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Filtered Capability List or Node Mesh
        if (selectedCategoryFilter == "NODES_MESH") {
            item {
                Text(
                    "Federated Mesh Nodes (${nodeTopology.totalNodes})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            items(nodeTopology.nodes) { node ->
                NodeMeshDetailCard(node = node)
            }
        } else {
            val filteredList = if (selectedCategoryFilter == null || selectedCategoryFilter == "ALL") {
                realityList
            } else {
                realityList.filter { it.category.equals(selectedCategoryFilter, ignoreCase = true) }
            }

            item {
                Text(
                    "Registered Capabilities (${filteredList.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            items(filteredList) { cap ->
                CapabilityDetailCard(
                    cap = cap,
                    onTestPing = {
                        coroutineScope.launch {
                            val req = UnifiedExecutionRequest(
                                capabilityId = cap.capabilityId,
                                parameters = mapOf("ping" to "true")
                            )
                            val res = UnifiedExecutionFabric.instance.execute(req, context)
                            Toast.makeText(context, "${cap.capabilityId}: ${res.status.name} (${res.verificationStatus.name})", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NodeMeshDetailCard(node: com.example.data.node.WastiNode) {
    val isOnline = node.connectionState == com.example.data.node.NodeConnectionState.CONNECTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        node.nodeName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "ID: ${node.nodeId} • Platform: ${node.platform.name} • Trust: ${node.trustState.name}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (isOnline) Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFB71C1C).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = node.connectionState.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (node.capabilities.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    node.capabilities.take(4).forEach { cap ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                cap,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Text(
                text = "Locality: ${node.dataLocality.name} • Endpoint: ${node.endpointUrl ?: "IPC/Internal"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CapabilityDetailCard(
    cap: CapabilityReality,
    onTestPing: () -> Unit
) {
    val isVerified = cap.liveConnectionStatus == LiveConnectionStatus.VERIFIED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        cap.capabilityId,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Category: ${cap.category} • Provider: ${cap.provider}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = if (isVerified) Color(0xFF1B5E20).copy(alpha = 0.15f) else Color(0xFFB71C1C).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = cap.liveConnectionStatus.name,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isVerified) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Supported Ops
            if (cap.supportedOperations.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    cap.supportedOperations.take(3).forEach { op ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                op,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Limitations / Notes
            if (cap.limitations.isNotEmpty()) {
                Text(
                    text = "• ${cap.limitations.joinToString("; ")}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onTestPing,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ping Capability", fontSize = 11.sp)
                }
            }
        }
    }
}
