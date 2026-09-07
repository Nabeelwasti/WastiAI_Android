package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
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
    val isWeightsInstalled = remember { ModelArtifactManager.isWeightsPresent(context, targetModelId) }

    val activeProgress = downloadProgressMap[targetModelId]

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
                // Header Emblem
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

                Spacer(modifier = Modifier.height(12.dp))

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

                Spacer(modifier = Modifier.height(16.dp))

                // Hardware Suitability Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DEVICE COMPATIBILITY:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• RAM: ${hwSpecs.totalRamMb} MB (Available: ${hwSpecs.availableRamMb} MB)\n" +
                                    "• Cores: ${hwSpecs.cpuCores} • Architecture: ARM64 Native\n" +
                                    "• Thermals: ${if (hwSpecs.isBatteryLowOrThermalsThrottling) "Constrained" else "Optimal"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isWeightsInstalled) {
                    // Already installed state
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SmolLM2 Edge Weights Verified & Active", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enter Wasti AI OS")
                    }
                } else {
                    // Provisioning State
                    Text(
                        text = "Install the ultra-compact SmolLM2 model (~1GB) for 100% offline, zero-token-cost local intelligence.",
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
                    } else {
                        Button(
                            onClick = {
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

                    TextButton(
                        onClick = {
                            onComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Continue with Hybrid / Cloud Mode", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
