package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.engine.UnifiedBrain
import com.example.data.ai.model.OpenSourceModelCatalog
import com.example.data.core.BciSignalProcessor
import com.example.data.core.BciSourceType
import com.example.data.core.BiologicalTelemetryInterface
import com.example.data.core.NeuralEmulationEngine
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
    val hhState by NeuralEmulationEngine.hodgkinHuxleyState.collectAsState()
    val annState by NeuralEmulationEngine.annState.collectAsState()
    val bciSnapshot by BciSignalProcessor.bciState.collectAsState()
    val bciSource by BciSignalProcessor.sourceType.collectAsState()

    var isReasoningRunning by remember { mutableStateOf(false) }
    var selectedSimulationTab by remember { mutableStateOf(0) } // 0: LIF Biology, 1: ANN MLP, 2: DIY BCI, 3: 12 Models, 4: Code Sandbox
    var stimulusSlider by remember { mutableStateOf(16.0f) }
    var rgResistorOhms by remember { mutableStateOf(500.0f) }

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
                            text = "Unified Brain & BCI Interface",
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
            // Truthful Reality Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Truth Boundary",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "⚠️ Reality & BCI Boundary: Distinguishes physical hardware streams (ESP32 / AD620 / BrainFlow) from mathematical simulation models (LIF, Hodgkin-Huxley, ANN). Battery power isolation mandatory for physical electrodes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Tab Selector
            item {
                ScrollableTabRow(
                    selectedTabIndex = selectedSimulationTab,
                    edgePadding = 0.dp
                ) {
                    Tab(
                        selected = selectedSimulationTab == 0,
                        onClick = { selectedSimulationTab = 0 },
                        text = { Text("LIF Biology") },
                        icon = { Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedSimulationTab == 1,
                        onClick = { selectedSimulationTab = 1 },
                        text = { Text("ANN MLP") },
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedSimulationTab == 2,
                        onClick = { selectedSimulationTab = 2 },
                        text = { Text("DIY BCI") },
                        icon = { Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedSimulationTab == 3,
                        onClick = { selectedSimulationTab = 3 },
                        text = { Text("12 Models") },
                        icon = { Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedSimulationTab == 4,
                        onClick = { selectedSimulationTab = 4 },
                        text = { Text("Code & Hardware") },
                        icon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }

            when (selectedSimulationTab) {
                0 -> {
                    // TAB 0: BIOLOGICAL LIF & HODGKIN-HUXLEY
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Leaky Integrate-and-Fire (LIF) 64-Neuron Network",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Membrane Time Constant τ = 20ms • Threshold = -55mV • Reset = -75mV",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Injected Current: ${stimulusSlider.toInt()} pA",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Slider(
                                        value = stimulusSlider,
                                        onValueChange = { stimulusSlider = it },
                                        valueRange = 5.0f..35.0f,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        NeuralEmulationEngine.stepNetwork(stimulusSlider)
                                        NeuralEmulationEngine.stepHodgkinHuxley(stimulusSlider.toDouble() * 0.5)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Step Biological Simulation")
                                }
                            }
                        }
                    }

                    // Neuron Grid Raster
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Neuron Spiking Activity Raster (64 Nodes)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val nodes = if (neuralState.isEmpty()) {
                                    List(64) { idx -> com.example.data.core.NeuronSpikeState(idx, -70.0f, false, 0) }
                                } else {
                                    neuralState
                                }

                                val chunkedNodes = nodes.chunked(16)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    chunkedNodes.forEach { row ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            row.forEach { neuron ->
                                                val color = when {
                                                    neuron.isSpiking -> Color(0xFFFF5252) // Bright Red Spike
                                                    neuron.membranePotentialMv > -60.0f -> Color(0xFFFFB74D) // Depolarized
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                val spikeCount = nodes.count { it.isSpiking }
                                Text(
                                    text = "Active Spikes in Step: $spikeCount | Synaptic Recurrence: Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Hodgkin-Huxley State
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Hodgkin-Huxley Conductance Channel Model",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Membrane Potential Vm: ${"%.2f".format(hhState.membraneVoltageMv)} mV",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Na+ (m): ${"%.3f".format(hhState.sodiumConductanceM)} • K+ (n): ${"%.3f".format(hhState.potassiumConductanceN)} • Inact (h): ${"%.3f".format(hhState.sodiumInactivationH)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // TAB 1: ARTIFICIAL NEURAL NETWORK (ANN)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Artificial Neural Network (Multi-Layer Perceptron)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Architecture: [Input: 4] → [Hidden 1: 8 ReLU] → [Hidden 2: 6 ReLU] → [Output: 3 Sigmoid]",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        NeuralEmulationEngine.executeAnnForwardPass()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Execute Forward Pass Calculation")
                                }
                            }
                        }
                    }

                    if (annState != null) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = "Forward Pass Results (Latency: ${annState!!.executionTimeMs}ms)",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Inputs: [${annState!!.inputValues.joinToString(", ") { "%.2f".format(it) }}]",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    annState!!.hiddenLayers.forEach { layer ->
                                        Text(
                                            text = "${layer.layerName} Activations (ReLU):\n[${layer.neuronValues.joinToString(", ") { "%.2f".format(it) }}]",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Output Predictions (Sigmoid):\n[${annState!!.outputValues.joinToString(", ") { "%.3f".format(it) }}]",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // TAB 2: DIY BRAIN-COMPUTER INTERFACE (BCI)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "DIY EEG Brain-Computer Interface",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Source: ${bciSource.name} • Protocol: 250Hz Microvolt Stream (Fp1-Fp2 Frontal)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Attention Gauge Card
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("Attention Level", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                text = "${bciSnapshot.attentionScorePercent}%",
                                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFF1976D2)
                                            )
                                            Text("Beta (12-30Hz)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                    }

                                    // Meditation Gauge Card
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("Meditation Level", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                text = "${bciSnapshot.meditationScorePercent}%",
                                                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFF388E3C)
                                            )
                                            Text("Alpha (8-12Hz)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        BciSignalProcessor.stepSimulation(focusLevel = 0.85f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Simulate / Ingest EEG Stream Pulse")
                                }
                            }
                        }
                    }

                    // EEG Band Power Spectrum Bars
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "EEG Spectral Band Power Decomposition",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                val bands = listOf(
                                    "Delta (0.5-4 Hz - Deep Sleep)" to bciSnapshot.bandPower.deltaPower,
                                    "Theta (4-8 Hz - Drowsiness)" to bciSnapshot.bandPower.thetaPower,
                                    "Alpha (8-12 Hz - Calm Focus)" to bciSnapshot.bandPower.alphaPower,
                                    "Beta (12-30 Hz - Active Cognition)" to bciSnapshot.bandPower.betaPower,
                                    "Gamma (30-45 Hz - Processing)" to bciSnapshot.bandPower.gammaPower
                                )

                                bands.forEach { (label, power) ->
                                    val progress = (power / 40.0f).coerceIn(0.05f, 1.0f)
                                    Column(modifier = Modifier.padding(vertical = 3.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall)
                                            Text("${"%.1f".format(power)} μV²", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // AD620 Circuit Gain Calculator
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "AD620 Instrumentation Amplifier Gain Calculator",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                val calculatedGain = BciSignalProcessor.calculateAd620Gain(rgResistorOhms)
                                Text(
                                    text = "Formula: Gain = 1 + (49.4 kΩ / Rg)\nResistor Rg = ${rgResistorOhms.toInt()} Ω  ➜  Amplification Gain = ${"%.1f".format(calculatedGain)}x",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Slider(
                                    value = rgResistorOhms,
                                    onValueChange = { rgResistorOhms = it },
                                    valueRange = 50.0f..1000.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                3 -> {
                    // TAB 3: 12 OPEN SOURCE LOCAL MODELS
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "One Brain • 12 Open-Source Local Models",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Cooperative reasoning across Llama, Qwen, Gemma, DeepSeek, Mistral, Phi, Granite, GLM, Command R, Falcon, SmolLM, StableLM.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isReasoningRunning = true
                                            UnifiedBrain.executeCooperativeReasoning("Analyze neural and BCI telemetry signals to optimize cognitive intent routing")
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
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Live Brain Consensus Synthesis",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = consensusState!!.finalSynthesis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
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
                }

                4 -> {
                    // TAB 4: CODE & HARDWARE BLUEPRINT (ESP32 C++ & BrainFlow Python)
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "ESP32 C++ Firmware (AD620 / GPIO 34 ADC Ingress)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Ready to flash via Arduino IDE / PlatformIO to stream 250Hz EEG into Wasti AI OS.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val espCode = remember { BciSignalProcessor.generateEsp32FirmwareCode() }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = espCode,
                                        color = Color(0xFFD4D4D4),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "BrainFlow Python Ingress Client (Synthetic / OpenBCI / Muse)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Free open-source multi-board client with Butterworth bandpass filtering.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val pyCode = remember { BciSignalProcessor.generateBrainFlowPythonCode() }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = pyCode,
                                        color = Color(0xFFD4D4D4),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
