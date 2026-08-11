package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.evaluation.AIEvaluationEngine
import com.example.service.VoskModelDownloader
import com.example.service.WakeWordVoskService
import com.example.service.WakeWordVoskState
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordSettingsScreen(
    onNavigateBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val downloadState by VoskModelDownloader.downloadState.collectAsStateWithLifecycle()
    val isModelInstalled = remember(downloadState) { VoskModelDownloader.isModelInstalled(context) }

    val statusText by WakeWordVoskState.statusText.collectAsStateWithLifecycle()
    val isModelLoaded by WakeWordVoskState.isModelLoaded.collectAsStateWithLifecycle()
    val isListening by WakeWordVoskState.isListening.collectAsStateWithLifecycle()
    val lastDetectedWakeWord by WakeWordVoskState.lastDetectedWakeWord.collectAsStateWithLifecycle()
    val lastDetectedTimeMs by WakeWordVoskState.lastDetectedTimeMs.collectAsStateWithLifecycle()

    val voskCalibrationFactor by AIEvaluationEngine.voskCalibrationFactor.collectAsStateWithLifecycle()
    val voskListeningMode by AIEvaluationEngine.voskListeningMode.collectAsStateWithLifecycle()
    val voskLastCalibratedMs by AIEvaluationEngine.voskLastCalibratedMs.collectAsStateWithLifecycle()
    val voskIsCalibrating by AIEvaluationEngine.voskIsCalibrating.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vosk Wake-Word Engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Offline 'Hey Wasti' Speech Keyword Spotting", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Live Engine Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Engine Live Monitor",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        val statusBg = when {
                            isListening -> Color(0xFF10B981)
                            isModelLoaded -> Color(0xFFF59E0B)
                            statusText.startsWith("Error") -> Color(0xFFEF4444)
                            else -> Color(0xFF6B7280)
                        }

                        Surface(
                            color = statusBg.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusBg, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isListening) "LISTENING" else if (isModelLoaded) "STANDBY" else "STOPPED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusBg
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        text = "Status: $statusText",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Model Directory", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (isModelLoaded) "Loaded (vosk-model-small-en-us-0.15)" else "Prepared / Standby",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Background WakeLock", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (isListening) "PARTIAL_WAKE_LOCK Held" else "Released",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isListening) Color(0xFF10B981) else Color(0xFF6B7280)
                            )
                        }
                    }

                    if (lastDetectedWakeWord != null && lastDetectedTimeMs > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastDetectedTimeMs))
                                Text(
                                    text = "Last Detection: '$lastDetectedWakeWord' at $timeStr",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 2. Control Actions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Vosk Engine Service Controls", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    val isDownloading = downloadState is VoskModelDownloader.DownloadState.Downloading || downloadState is VoskModelDownloader.DownloadState.Extracting

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val success = VoskModelDownloader.downloadAndInstallModel(context)
                                if (success) {
                                    Toast.makeText(context, "Vosk Model installed successfully!", Toast.LENGTH_SHORT).show()
                                    WakeWordVoskService.startService(context)
                                } else {
                                    Toast.makeText(context, "Failed to install Vosk Model", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isModelInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isModelInstalled) Icons.Default.CheckCircle else Icons.Default.Download,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                isDownloading -> "Downloading / Extracting Model..."
                                isModelInstalled -> "Re-install Wake Word Model (~40MB)"
                                else -> "Install Wake Word Model (~40MB)"
                            }
                        )
                    }

                    when (val state = downloadState) {
                        is VoskModelDownloader.DownloadState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = if (state.totalBytes > 0) {
                                        val downloadedMb = state.bytesDownloaded / (1024f * 1024f)
                                        val totalMb = state.totalBytes / (1024f * 1024f)
                                        "Downloading: ${"%.1f".format(downloadedMb)} MB / ${"%.1f".format(totalMb)} MB (${(state.progress * 100).toInt()}%)"
                                    } else {
                                        "Downloading Vosk Model..."
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is VoskModelDownloader.DownloadState.Extracting -> {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = state.message,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is VoskModelDownloader.DownloadState.Error -> {
                            Text(
                                text = state.message,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        else -> {}
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                WakeWordVoskService.startService(context)
                                Toast.makeText(context, "Wake-Word Service Started", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isListening,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Start Listening")
                        }

                        Button(
                            onClick = {
                                WakeWordVoskService.stopService(context)
                                Toast.makeText(context, "Wake-Word Service Stopped", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = isListening,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Listening")
                        }
                    }
                }
            }

            // 3. Acoustic Sensitivity & Calibration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Acoustic Sensitivity & Listening Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Detection Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Continuous Standby", "Balanced", "High Accuracy", "Low Power").forEach { mode ->
                                FilterChip(
                                    selected = voskListeningMode.equals(mode, ignoreCase = true),
                                    onClick = { AIEvaluationEngine.updateVoskListeningMode(mode) },
                                    label = { Text(mode, fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sensitivity Multiplier", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${"%.1f".format(voskCalibrationFactor)}x", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.8f to "0.8x Quiet", 1.0f to "1.0x Normal", 1.2f to "1.2x High", 1.5f to "1.5x Loud").forEach { (factor, label) ->
                                FilterChip(
                                    selected = (voskCalibrationFactor == factor),
                                    onClick = { AIEvaluationEngine.updateVoskCalibrationFactor(factor) },
                                    label = { Text(label, fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mic Noise Floor Auto-Calibration", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            val lastCalibStr = if (voskLastCalibratedMs > 0) {
                                val secondsAgo = (System.currentTimeMillis() - voskLastCalibratedMs) / 1000
                                if (secondsAgo < 60) "Just now" else "${secondsAgo / 60}m ago"
                            } else "Never"
                            Text("Last Calibrated: $lastCalibStr", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Button(
                            onClick = {
                                AIEvaluationEngine.runVoskCalibration()
                                Toast.makeText(context, "Vosk Acoustic Sensor Calibration Complete!", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !voskIsCalibrating,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Calibrate", fontSize = 11.sp)
                        }
                    }
                }
            }

            // 4. Hardware Diagnostic Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Technical Specification & Keywords", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("• Target Keyword: 'Hey Wasti' or 'Wasti'", fontSize = 12.sp)
                    Text("• Audio Format: 16,000Hz PCM 16-Bit Mono", fontSize = 12.sp)
                    Text("• Service Architecture: START_STICKY Foreground Service + PowerManager WakeLock", fontSize = 12.sp)
                    Text("• Local Storage: ${context.filesDir.absolutePath}/vosk-model-small-en-us-0.15", fontSize = 12.sp)
                }
            }
        }
    }
}
