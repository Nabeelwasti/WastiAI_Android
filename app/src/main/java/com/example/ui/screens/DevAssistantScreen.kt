package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.core.WastiCore
import com.example.data.credential.CredentialRegistry
import com.example.ui.components.CodeBlockView
import kotlinx.coroutines.launch

/**
 * DevAssistantScreen: UI to compose dev prompts, inspect active code context, 
 * synthesize code patches via Wasti AI Multi-Tier Core Engine, preview results,
 * and apply patches directly into the workspace.
 */
@Composable
fun DevAssistantScreen(
    activeCodeContext: String = "fun main() {\n    println(\"Wasti OS Code Engine\")\n}",
    onCodeContextChange: (String) -> Unit = {},
    onSendMessageToChat: (prompt: String, codeContext: String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var promptInput by remember { mutableStateOf("") }
    var codeContext by remember(activeCodeContext) { mutableStateOf(activeCodeContext) }
    var statusMessage by remember { mutableStateOf("Ready to synthesize code patches") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var patchOutput by remember { mutableStateOf("") }
    var activeEngineLabel by remember { mutableStateOf("Wasti AI Code Engine") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("dev_assistant_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Dev Assistant",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Wasti Dev Assistant — Autonomous Patch Synthesizer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Synthesizes code fixes, Jetpack Compose UI, and logic refactors using DeepSeek V3/R1, Groq, and Gemini.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = codeContext,
                onValueChange = {
                    codeContext = it
                    onCodeContextChange(it)
                },
                label = { Text("Active Workspace Code Context") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .testTag("dev_assistant_code_context"),
                maxLines = 10
            )
        }

        item {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Describe code changes / feature implementation...") },
                placeholder = { Text("e.g. Add a search bar to filter memory items with smooth animation") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .testTag("dev_assistant_prompt_input"),
                maxLines = 5
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (promptInput.isBlank()) {
                            Toast.makeText(context, "Please enter a prompt first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        scope.launch {
                            isSynthesizing = true
                            statusMessage = "Analyzing workspace & synthesizing patch..."
                            try {
                                val systemPrompt = """
                                    You are Wasti AI Code Synthesis Engine.
                                    Synthesize a complete, clean, production-ready Kotlin/Compose or target code patch for the provided code context.
                                    Maintain modern Jetpack Compose, Material 3, and Kotlin best practices.
                                    Output clean code without markdown fluff or extra commentary.
                                """.trimIndent()

                                val fullUserPrompt = "Code Context:\n$codeContext\n\nTask Instruction:\n$promptInput"

                                val (resCode, engineName) = WastiCore.executeOrchestratedRequest(
                                    userPrompt = fullUserPrompt,
                                    systemInstruction = systemPrompt,
                                    activeAgentId = "coding_agent",
                                    fileContext = codeContext
                                )

                                patchOutput = resCode
                                activeEngineLabel = engineName
                                statusMessage = "Patch generated successfully via $engineName"
                            } catch (e: Exception) {
                                statusMessage = "Synthesis error: ${e.localizedMessage}"
                            } finally {
                                isSynthesizing = false
                            }
                        }
                    },
                    enabled = !isSynthesizing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("synthesize_patch_button")
                ) {
                    if (isSynthesizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Synthesizing...")
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Synthesize Patch")
                    }
                }

                OutlinedButton(
                    onClick = {
                        onSendMessageToChat(promptInput.ifBlank { "Refactor and optimize code" }, codeContext)
                    },
                    modifier = Modifier.testTag("send_to_chat_button")
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Chat Assistant")
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Status: $statusMessage",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (patchOutput.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Synthesized Code Patch ($activeEngineLabel)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Wasti Patch", patchOutput)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Patch copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Patch", modifier = Modifier.size(20.dp))
                                }

                                Button(
                                    onClick = {
                                        onCodeContextChange(patchOutput)
                                        codeContext = patchOutput
                                        Toast.makeText(context, "Patch applied to active workspace!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Apply Patch", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        CodeBlockView(code = patchOutput, language = "kotlin")
                    }
                }
            }
        }
    }
}
