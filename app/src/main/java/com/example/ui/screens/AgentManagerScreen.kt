package com.example.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AgentEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentManagerScreen(
    agents: List<AgentEntity>,
    onAddAgent: (String, String, String, String, String) -> Unit,
    onSelectAgentForChat: (String) -> Unit
) {
    var showAddAgentDialog by remember { mutableStateOf(false) }
    var newAgentName by remember { mutableStateOf("") }
    var newRoleTitle by remember { mutableStateOf("") }
    var newAgentType by remember { mutableStateOf("Specialist") }
    var newInstruction by remember { mutableStateOf("") }
    var newCapabilities by remember { mutableStateOf("reasoning,coding,search") }

    if (showAddAgentDialog) {
        AlertDialog(
            onDismissRequest = { showAddAgentDialog = false },
            title = { Text("Add Custom Autonomous Agent", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAgentName,
                        onValueChange = { newAgentName = it },
                        label = { Text("Agent Name") },
                        placeholder = { Text("e.g. Security Specialist") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRoleTitle,
                        onValueChange = { newRoleTitle = it },
                        label = { Text("Role Title") },
                        placeholder = { Text("e.g. Zero-Trust Security Auditor") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newAgentType,
                        onValueChange = { newAgentType = it },
                        label = { Text("Agent Type") },
                        placeholder = { Text("e.g. Specialist / Executive / Analyst") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newInstruction,
                        onValueChange = { newInstruction = it },
                        label = { Text("System Instruction") },
                        placeholder = { Text("e.g. Audit code boundaries and enforce fail-closed security.") },
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                    OutlinedTextField(
                        value = newCapabilities,
                        onValueChange = { newCapabilities = it },
                        label = { Text("Capabilities (comma separated)") },
                        placeholder = { Text("security_audit,keystore,zero_trust") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAgentName.isNotBlank()) {
                            onAddAgent(newAgentName.trim(), newRoleTitle.trim(), newAgentType.trim(), newInstruction.trim(), newCapabilities.trim())
                            newAgentName = ""
                            newRoleTitle = ""
                            newInstruction = ""
                            showAddAgentDialog = false
                        }
                    }
                ) {
                    Text("Register Agent")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAgentDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("agent_manager_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Single Unified Wasti AI Master Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = "Wasti AI Logo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Wasti AI — Unified Intelligent Engine",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Single Unified Master AI • Background Source & API Orchestration",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34D399))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "ACTIVE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Wasti AI operates as a single, cohesive, unified intelligence. Rather than exposing separate disconnected sub-agents, Wasti AI automatically selects the optimal reasoning models, voice pipelines, memory nodes, coding engines, and web search APIs in the background for each task.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AgentNodeTypeBadge("Unified Core", Color(0xFF38BDF8))
                        AgentNodeTypeBadge("Auto-Routing Engine", Color(0xFF818CF8))
                        AgentNodeTypeBadge("Vector Memory", Color(0xFFF59E0B))
                        AgentNodeTypeBadge("Neural Voice HD", Color(0xFF34D399))
                    }
                }
            }
        }

        // Unified Engine Capabilities
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Integrated Background Capabilities",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All modules execute automatically under Wasti AI's central control loop:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val capabilities = listOf(
                        "👨‍💻 Code Architecture & Kotlin/Python Debugging",
                        "💼 Executive Business Analysis & Strategic Planning",
                        "🔍 Real-Time Web Research & Fact Synthesis",
                        "🧠 Persistent Long-Term Vector Memory & Knowledge Base",
                        "🎙️ Real-Time Neural Speech & Voice Call Synthesis",
                        "🔐 Encrypted Credential Vault & Hardware Security",
                        "⚡ Automated Multi-Step Task & Workflow Planner"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        capabilities.forEach { cap ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = cap,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { onSelectAgentForChat("ceo_agent") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("launch_unified_wasti_chat_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Launch Wasti AI Unified Workspace")
                    }
                }
            }
        }

        // Background Processing Guarantee Banner
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Background Orchestration Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "API sources, model selection, and tool execution are evaluated dynamically behind the scenes for every prompt.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Agent Directory Header & Add Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Configured Agents Directory (${agents.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Button(
                    onClick = { showAddAgentDialog = true },
                    modifier = Modifier.testTag("add_custom_agent_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Agent", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Agent", fontSize = 12.sp)
                }
            }
        }

        // List of all agents
        items(agents, key = { it.id }) { agent ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_card_${agent.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (agent.agentType.uppercase()) {
                                        "CEO" -> Icons.Default.Work
                                        "DEVELOPER" -> Icons.Default.Terminal
                                        "RESEARCH" -> Icons.Default.Search
                                        else -> Icons.Default.SmartToy
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(agent.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(agent.roleTitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = agent.status.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    if (agent.systemInstruction.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = agent.systemInstruction,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Caps: ${agent.capabilitiesCsv.ifBlank { "universal" }}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedButton(
                            onClick = { onSelectAgentForChat(agent.id) },
                            modifier = Modifier.testTag("chat_with_agent_${agent.id}"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Chat with Agent", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentNodeTypeBadge(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
