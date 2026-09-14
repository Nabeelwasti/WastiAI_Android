package com.example.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.core.WastiCore
import com.example.data.agent.runtime.UnifiedExecutionFabric
import com.example.data.agent.runtime.UnifiedExecutionRequest
import com.example.ui.components.CodeBlockView
import kotlinx.coroutines.launch

data class PromptTemplate(
    val title: String,
    val category: String,
    val promptText: String,
    val sampleCode: String
)

@Composable
fun CodePromptWorkspaceScreen(
    activeCodeContext: String = "fun main() {\n    println(\"Wasti OS Code Engine\")\n}",
    onCodeContextChange: (String) -> Unit,
    onSendMessageToChat: (prompt: String, codeContext: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Code Playground, 1 = Dev Assistant, 2 = Prompt Library
    var codeInput by remember(activeCodeContext) { mutableStateOf(activeCodeContext) }
    var codeOutput by remember { mutableStateOf("") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var isRunningCode by remember { mutableStateOf(false) }
    var executionOutput by remember { mutableStateOf("") }
    var selectedLanguage by remember { mutableStateOf("kotlin") }

    val promptTemplates = listOf(
        PromptTemplate(
            title = "Jetpack Compose MVVM Template",
            category = "Android Architecture",
            promptText = "Generate a complete Jetpack Compose screen with StateFlow and ViewModel for Wasti OS.",
            sampleCode = "```kotlin\n@Composable\nfun WastiScreen(viewModel: WastiViewModel) {\n    val state by viewModel.uiState.collectAsStateWithLifecycle()\n    // UI Implementation\n}\n```"
        ),
        PromptTemplate(
            title = "Room Database Entity & DAO",
            category = "Database",
            promptText = "Create a Room database Entity, DAO, and Repository for storing vector memory.",
            sampleCode = "```kotlin\n@Entity(tableName = \"vectors\")\ndata class VectorEntity(\n    @PrimaryKey val id: String,\n    val embeddingCsv: String\n)\n```"
        ),
        PromptTemplate(
            title = "Multi-Agent Delegation Protocol",
            category = "AI Agents",
            promptText = "Draft a system instruction protocol for inter-agent delegation in Wasti OS.",
            sampleCode = "```json\n{\n  \"protocol\": \"WastiAgentMesh\",\n  \"delegationOrder\": [\"CEO\", \"Research\", \"Coding\", \"Review\"]\n}\n```"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("code_prompt_workspace_screen")
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Code Generator", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Code, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Dev Assistant", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Terminal, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Prompt Library", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(text = "Code Workspace & AI Refactorer", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            onCodeContextChange(it)
                        },
                        label = { Text("Code / Specification Input (Open Workspace File)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .testTag("code_spec_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isSynthesizing = true
                                    try {
                                        val (resCode, _) = WastiCore.executeOrchestratedRequest(
                                            userPrompt = "Code Context:\n$codeInput\n\nTask: Refactor, complete, and optimize this code.",
                                            systemInstruction = "You are Wasti AI Code Synthesis Engine. Output clean, compilable, idiomatic code without unnecessary markdown fluff.",
                                            activeAgentId = "coding_agent",
                                            fileContext = codeInput
                                        )
                                        codeOutput = resCode.trim()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Synthesis error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isSynthesizing = false
                                    }
                                }
                            },
                            enabled = !isSynthesizing,
                            modifier = Modifier.testTag("generate_code_button")
                        ) {
                            if (isSynthesizing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Synthesizing...")
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Generate Code")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isRunningCode = true
                                    executionOutput = ""
                                    try {
                                        val req = UnifiedExecutionRequest(
                                            capabilityId = "TERMINAL",
                                            parameters = mapOf("command" to "echo '${codeInput.replace("'", "'\\''")}'")
                                        )
                                        val res = UnifiedExecutionFabric.instance.execute(req, context)
                                        executionOutput = res.output
                                    } catch (e: Exception) {
                                        executionOutput = "Execution error: ${e.message}"
                                    } finally {
                                        isRunningCode = false
                                    }
                                }
                            },
                            enabled = !isRunningCode,
                            modifier = Modifier.testTag("run_code_wre_button")
                        ) {
                            if (isRunningCode) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Running...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Run")
                            }
                        }

                        OutlinedButton(
                            onClick = { selectedTab = 1 }
                        ) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Dev Assistant")
                        }
                    }
                }

                if (codeOutput.isNotBlank()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Output Preview", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Button(
                                onClick = {
                                    codeInput = codeOutput
                                    onCodeContextChange(codeOutput)
                                    Toast.makeText(context, "Applied to workspace code!", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Apply to Workspace", fontSize = 11.sp)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        CodeBlockView(code = codeOutput, language = selectedLanguage)
                    }
                }

                if (executionOutput.isNotBlank()) {
                    item {
                        Text(text = "Execution Console Output", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = executionOutput,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        } else if (selectedTab == 1) {
            DevAssistantScreen(
                activeCodeContext = codeInput,
                onCodeContextChange = { newCode ->
                    codeInput = newCode
                    onCodeContextChange(newCode)
                },
                onSendMessageToChat = onSendMessageToChat
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(text = "Engineering Prompt Library", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                items(promptTemplates) { tmpl ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = tmpl.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        text = tmpl.category,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = tmpl.promptText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)

                            Spacer(modifier = Modifier.height(10.dp))
                            CodeBlockView(code = tmpl.sampleCode, language = "kotlin")

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val stripped = tmpl.sampleCode.removePrefix("```sh\n").removePrefix("```kotlin\n").removePrefix("```json\n").removeSuffix("\n```")
                                        codeInput = stripped
                                        onCodeContextChange(stripped)
                                        selectedTab = 0
                                    },
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Open in Editor", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { onSendMessageToChat(tmpl.promptText, codeInput) }
                                ) {
                                    Text("Use Prompt in Chat", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
