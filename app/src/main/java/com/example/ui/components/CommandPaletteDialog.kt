package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class CommandOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val actionCommand: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onExecuteCommand: (String) -> Unit
) {
    if (!isOpen) return

    var query by remember { mutableStateOf("") }

    val allOptions = listOf(
        CommandOption("Chat Workspace", "Open Wasti AI natural chat interface", Icons.AutoMirrored.Filled.Chat, "Open Chat"),
        CommandOption("Executive Brain Dashboard", "View Wasti OS system state, metrics & active tasks", Icons.Default.Dashboard, "Open Dashboard"),
        CommandOption("Long-Term Memory & Knowledge", "Inspect vector memory records & knowledge base", Icons.Default.Memory, "Open Memory"),
        CommandOption("Wasti AI Engine Core", "View unified intelligent core status & capabilities", Icons.Default.Psychology, "Open Agents"),
        CommandOption("Projects & Task Roadmap", "View project Kanban, tasks & AI planner", Icons.Default.AccountTree, "Open Projects"),
        CommandOption("Code & Prompt Workspace", "Generate Kotlin/Python code & build prompt library", Icons.Default.Code, "Open Code"),
        CommandOption("Integrations & System Logs", "View service connectors, MCP server & system logs", Icons.Default.Extension, "Open Integrations"),
        CommandOption("Settings & AI Secrets", "Configure API keys, model parameters & theme", Icons.Default.Settings, "Open Settings"),
        CommandOption("Ask Wasti AI Research", "Delegate web research & document analysis to Wasti AI", Icons.AutoMirrored.Filled.ManageSearch, "Research: "),
        CommandOption("Ask Wasti AI Code", "Generate clean code or debug Kotlin/Python with Wasti AI", Icons.Default.Terminal, "Code: ")
    )

    val filteredOptions = if (query.isBlank()) {
        allOptions
    } else {
        allOptions.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.subtitle.contains(query, ignoreCase = true) ||
            it.actionCommand.contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("command_palette_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search command or enter prompt... (⌘K)", fontSize = 14.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("command_input_field"),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_command_palette")) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                if (query.isNotBlank() && filteredOptions.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExecuteCommand(query) }
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Execute prompt in AI Chat", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("\"$query\"", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(filteredOptions) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val cmd = if (option.actionCommand.endsWith(": ")) {
                                        option.actionCommand + query
                                    } else {
                                        option.actionCommand
                                    }
                                    onExecuteCommand(cmd)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = option.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.title,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = option.subtitle,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
