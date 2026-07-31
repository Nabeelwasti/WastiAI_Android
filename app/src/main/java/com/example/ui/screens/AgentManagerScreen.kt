package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newRoleTitle by remember { mutableStateOf("") }
    var newAgentType by remember { mutableStateOf("Custom") }
    var newInstruction by remember { mutableStateOf("") }
    var newCapabilities by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Deploy Custom Agent Node") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Agent Name") },
                        placeholder = { Text("e.g. Legal Compliance Agent") }
                    )
                    OutlinedTextField(
                        value = newRoleTitle,
                        onValueChange = { newRoleTitle = it },
                        label = { Text("Role Title") },
                        placeholder = { Text("e.g. Regulatory Advisor") }
                    )
                    OutlinedTextField(
                        value = newInstruction,
                        onValueChange = { newInstruction = it },
                        label = { Text("System Instruction") },
                        modifier = Modifier.height(100.dp)
                    )
                    OutlinedTextField(
                        value = newCapabilities,
                        onValueChange = { newCapabilities = it },
                        label = { Text("Capabilities (comma separated)") },
                        placeholder = { Text("e.g. Contract Review, Compliance Check") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newInstruction.isNotBlank()) {
                            onAddAgent(newName, newRoleTitle, newAgentType, newInstruction, newCapabilities)
                            newName = ""
                            newRoleTitle = ""
                            newInstruction = ""
                            newCapabilities = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Deploy Agent")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
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
        // Unified J.A.R.V.I.S. Super-Agent Banner
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
                        Column {
                            Text(
                                text = "J.A.R.V.I.S. Unified Master Agent",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "All specialized sub-agents combined into 1 Master AI Engine",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag("add_custom_agent_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Deploy Capability", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Rather than managing separate disconnected agents, J.A.R.V.I.S. unifies Coding, Business, Memory, Research, Automation, and Design into a single, cohesive, ultra-intelligent entity with natural voice speech.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AgentNodeTypeBadge("J.A.R.V.I.S. Core", Color(0xFF38BDF8))
                        AgentNodeTypeBadge("Code Engine", Color(0xFF818CF8))
                        AgentNodeTypeBadge("Memory System", Color(0xFFF59E0B))
                        AgentNodeTypeBadge("Voice Speech", Color(0xFF34D399))
                    }
                }
            }
        }

        item {
            Text(text = "Active Specialized Agents (${agents.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        items(agents) { agent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = agent.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(text = agent.roleTitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (agent.status == "Active") Color(0xFF34D399) else Color(0xFFF59E0B))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = agent.status, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = agent.systemInstruction,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        agent.capabilitiesCsv.split(",").forEach { cap ->
                            if (cap.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = cap.trim(),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { onSelectAgentForChat(agent.id) },
                            modifier = Modifier.testTag("chat_with_${agent.id}_button"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Launch Chat Session", fontSize = 12.sp)
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
