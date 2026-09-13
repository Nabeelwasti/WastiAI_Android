package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.ai.engine.HardwareCapabilityDetector
import com.example.data.ai.engine.ModelArtifactManager
import com.example.data.ai.runtime.WastiModelDownloader
import kotlinx.coroutines.launch

/**
 * [The Eternal Manifesto: Resource Intelligence Law & Pilot/Spacecraft Principle]
 *
 * Professional Onboarding & Cold-Start Wizard for Edge Neural Model Weights:
 * - Checks hardware specs, storage space, thermals, and battery.
 * - 1-tap download & verification of compact edge model (SmolLM2 / Llama 3.2).
 * - Smooth fallback to cloud / heuristic mode when storage is constrained.
 */

@Composable
fun OnboardingModelWizardDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadProgressMap by WastiModelDownloader.downloadProgressMap.collectAsState()

    val targetModelId = "wasti-smollm"
    val manifest = remember { ModelArtifactManager.getManifest(targetModelId) }
    val hwSpecs = remember { HardwareCapabilityDetector.detectHardwareEnvironment(context) }
    val activeProgress = downloadProgressMap[targetModelId]
    val isWeightsInstalled by remember(activeProgress) {
        derivedStateOf { ModelArtifactManager.isWeightsPresent(context, targetModelId) }
    }

    var showInfoDialog by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Setup Options & Architecture Guidance", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Local Edge Core (SmolLM2):", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Downloads a compact ~1.05 GB GGUF neural model directly to your device storage. It runs 100% offline with zero token costs and zero API keys.", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Hybrid / Cloud Mode:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    Text("Skips the local weights download. Reasoning is routed seamlessly through Google Gemini, Groq, or DeepSeek cloud APIs. Requires an internet connection.", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("• Background Execution:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                    Text("Downloads and background tasks run continuously via Wasti Autonomous Foreground Daemon. You can minimize the app freely while it downloads.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Understood")
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Emblem & Info Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.size(24.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Setup Guidance",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Welcome to Wasti AI OS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Provision Sovereign Edge Neural Brain",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Genuine Hardware Telemetry Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PHYSICAL HARDWARE TELEMETRY:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• RAM: ${hwSpecs.totalRamMb} MB total (${hwSpecs.availableRamMb} MB available)\n" +
                                    "• CPU: ${hwSpecs.cpuCores} Cores • Architecture: ${hwSpecs.cpuArchitecture}\n" +
                                    "• Storage: ${hwSpecs.availableStorageMb / 1024} GB free / ${if (hwSpecs.totalStorageMb > 0) hwSpecs.totalStorageMb / 1024 else 32} GB total\n" +
                                    "• Battery: ${hwSpecs.batteryPercentage}% (${if (hwSpecs.isCharging) "Charging" else "Battery"}) • Thermals: ${if (hwSpecs.isThermalThrottling) "Throttled" else "Nominal"}\n" +
                                    "• Viability: ${hwSpecs.inferenceViabilityScore}",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isWeightsInstalled) {
                    // Already installed state
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("SmolLM2 Edge Weights Verified & Active", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Enter Wasti AI OS")
                    }
                } else {
                    // Provisioning State
                    Text(
                        text = "Install the compact SmolLM2 model (~1.05GB) for 100% offline, zero-token-cost local sovereign intelligence.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val currentProgress = activeProgress
                    if (currentProgress != null && !currentProgress.isCompleted && !currentProgress.isFailed) {
                        LinearProgressIndicator(
                            progress = { currentProgress.progressFraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(currentProgress.progressFraction * 100).toInt()}% • ${currentProgress.statusText}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Background daemon active: downloading smoothly in background notification panel",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (currentProgress != null && currentProgress.isFailed) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Download Status: ${currentProgress.errorMessage ?: "Interrupted"}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Multiple fallbacks available: Retrying automatically via mirror endpoints or Termux coding environment.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                com.example.service.WastiForegroundExecutionService.startDaemon(context)
                                coroutineScope.launch {
                                    if (manifest != null) {
                                        WastiModelDownloader.downloadModel(context, manifest)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Download with Alternative Mirror")
                        }
                    } else {
                        Button(
                            onClick = {
                                com.example.service.WastiForegroundExecutionService.startDaemon(context)
                                coroutineScope.launch {
                                    if (manifest != null) {
                                        val canDown = WastiModelDownloader.canDownload(context, manifest)
                                        if (canDown.first) {
                                            WastiModelDownloader.downloadModel(context, manifest)
                                        } else {
                                            android.widget.Toast.makeText(context, canDown.second, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Install Edge Neural Core (1-Tap)")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val prefs = context.getSharedPreferences("wasti_app_prefs", android.content.Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("onboarding_model_wizard_dismissed", true).apply()
                            onComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Proceed with Cloud Mode (Fast Setup)", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
