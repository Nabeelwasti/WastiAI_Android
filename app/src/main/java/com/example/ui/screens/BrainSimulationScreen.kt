package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.engine.UnifiedBrain
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.core.BiologicalTelemetryInterface
import com.example.data.core.NeuralEmulationEngine
import com.example.data.core.QuantumComputingAdapter
import com.example.data.device.EnvironmentalRealityEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainSimulationScreen() {
    val coroutineScope = rememberCoroutineScope()
    val consensusState by UnifiedBrain.activeBrainState.collectAsState()
    val envState by EnvironmentalRealityEngine.currentEnvironment.collectAsState()
    val bioState by BiologicalTelemetryInterface.telemetryStream.collectAsState()
    val neuralState by NeuralEmulationEngine.emulationActivity.collectAsState()

    var isReasoningRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "Unified Brain Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Unified Brain & Neural Mesh",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unified_brain_hero_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "One Brain • 12 Open-Source Local Models",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "100% Local Hardware Execution • Zero External API Keys • Infinite Evolution",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isReasoningRunning = true
                                    UnifiedBrain.executeCooperativeReasoning("Analyze system telemetry and optimize unified cognitive routing")
                                    NeuralEmulationEngine.stepNetwork(15.0f)
                                    isReasoningRunning = false
                                }
                            },
                            enabled = !isReasoningRunning,
                            modifier = Modifier.testTag("trigger_parallel_reasoning_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isReasoningRunning) "Synthesizing Consensus..." else "Run Cooperative Reasoning")
                        }
                    }
                }
            }

            if (consensusState != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Live Brain Synthesis",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = consensusState!!.finalSynthesis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Models: ${consensusState!!.participatingModels.size} | Latency: ${consensusState!!.totalLatencyMs}ms | Confidence: ${(consensusState!!.averageConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Active Local Open-Source Model Roster (12 Models)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            items(OpenSourceModelCatalog.ALL_MODELS) { model ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = model.familyName,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.brandDisplayName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Specialization: ${model.primarySpecialization.name} • Range: ${model.parameterRange}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active & Offline Ready",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Neural Emulation & Environmental Telemetry",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "LIF Spiking Neurons: ${neuralState.size} active nodes",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Ambient Temp: ${envState.ambientTempCelsius}°C • Humidity: ${envState.relativeHumidityPercent}% • Lux: ${envState.lightLevelLux}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Biological HRV: ${bioState.heartRateVariabilityMs}ms • Alpha Power: ${bioState.alphaWavePower}μV²",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
