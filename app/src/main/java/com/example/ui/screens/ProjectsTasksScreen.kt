package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.AgentEntity
import com.example.data.db.ProjectEntity
import com.example.data.db.TaskEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectsTasksScreen(
    projects: List<ProjectEntity>,
    allTasks: List<TaskEntity>,
    agents: List<AgentEntity>,
    onAddProject: (String, String, String) -> Unit,
    onAddTask: (String, String, String, String, String) -> Unit,
    onToggleTaskStatus: (String, Boolean) -> Unit
) {
    var selectedProjectId by remember { mutableStateOf<String?>(projects.firstOrNull()?.id) }

    var showProjectDialog by remember { mutableStateOf(false) }
    var projName by remember { mutableStateOf("") }
    var projDesc by remember { mutableStateOf("") }
    var projPriority by remember { mutableStateOf("High") }

    var showTaskDialog by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf("") }
    var taskDesc by remember { mutableStateOf("") }
    var taskAgentId by remember { mutableStateOf("ceo_agent") }

    if (showProjectDialog) {
        AlertDialog(
            onDismissRequest = { showProjectDialog = false },
            title = { Text("Create New Project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = projName,
                        onValueChange = { projName = it },
                        label = { Text("Project Name") }
                    )
                    OutlinedTextField(
                        value = projDesc,
                        onValueChange = { projDesc = it },
                        label = { Text("Description") },
                        modifier = Modifier.height(80.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (projName.isNotBlank()) {
                            onAddProject(projName, projDesc, projPriority)
                            projName = ""
                            projDesc = ""
                            showProjectDialog = false
                        }
                    }
                ) { Text("Create Project") }
            },
            dismissButton = {
                TextButton(onClick = { showProjectDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTaskDialog) {
        AlertDialog(
            onDismissRequest = { showTaskDialog = false },
            title = { Text("Add AI Task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text("Task Title") }
                    )
                    OutlinedTextField(
                        value = taskDesc,
                        onValueChange = { taskDesc = it },
                        label = { Text("Task Instructions") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pId = selectedProjectId ?: projects.firstOrNull()?.id ?: return@TextButton
                        if (taskTitle.isNotBlank()) {
                            onAddTask(pId, taskTitle, taskDesc, taskAgentId, "High")
                            taskTitle = ""
                            taskDesc = ""
                            showTaskDialog = false
                        }
                    }
                ) { Text("Add Task") }
            },
            dismissButton = {
                TextButton(onClick = { showTaskDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("projects_tasks_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Banner
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
                        Text(text = "Projects & AI Planner", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        IconButton(
                            onClick = { showProjectDialog = true },
                            modifier = Modifier.testTag("add_project_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "New Project", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = "Manage multi-agent projects and task execution graphs.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Projects Cards
        item {
            Text(text = "Active Projects (${projects.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        items(projects) { proj ->
            val isSelected = proj.id == selectedProjectId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedProjectId = proj.id },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = proj.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = proj.priority,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = proj.description,
                        fontSize = 13.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Tasks List for Selected Project
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Project Tasks", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(
                    onClick = { showTaskDialog = true },
                    modifier = Modifier.testTag("add_task_button")
                ) {
                    Icon(Icons.Default.AddTask, contentDescription = "Add Task", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val projectTasks = if (selectedProjectId != null) {
            allTasks.filter { it.projectId == selectedProjectId }
        } else {
            allTasks
        }

        items(projectTasks) { task ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = task.isCompleted,
                        onCheckedChange = { onToggleTaskStatus(task.id, task.isCompleted) },
                        modifier = Modifier.testTag("task_checkbox_${task.id}")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                            color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (task.description.isNotBlank()) {
                            Text(
                                text = task.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = task.assignedAgentId,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
