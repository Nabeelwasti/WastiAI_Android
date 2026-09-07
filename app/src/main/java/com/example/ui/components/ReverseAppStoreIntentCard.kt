package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * [The Eternal Manifesto: The Reverse App Store Principle]
 *
 * "Users describe outcomes, Wasti determines capabilities, execution path, body and verification."
 *
 * Visualizes the 5-stage Intent-to-Reality autonomous execution tree with ethical autonomy gating.
 */

enum class IntentStepStatus {
    PENDING,
    IN_PROGRESS,
    AWAITING_AUTHORIZATION,
    COMPLETED_VERIFIED,
    FAILED
}

data class IntentExecutionStep(
    val stepIndex: Int,
    val title: String,
    val description: String,
    val capabilityRequired: String,
    val status: IntentStepStatus,
    val verificationEvidence: String? = null
)

@Composable
fun ReverseAppStoreIntentCard(
    userIntent: String,
    steps: List<IntentExecutionStep>,
    isAuthorized: Boolean,
    onAuthorize: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Reverse App Store Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "REVERSE APP STORE PIPELINE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Autonomous Intent-to-Reality Compiler",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "SAFEGUARDED",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // User Goal
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("USER INTENT:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(userIntent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5-Stage Autonomous Execution Tree
            Text(
                text = "Autonomous Execution Tree:",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            steps.forEach { step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, color) = when (step.status) {
                        IntentStepStatus.COMPLETED_VERIFIED -> Icons.Default.CheckCircle to Color(0xFF2E7D32)
                        IntentStepStatus.IN_PROGRESS -> Icons.Default.PlayArrow to Color(0xFF1565C0)
                        IntentStepStatus.AWAITING_AUTHORIZATION -> Icons.Default.Lock to Color(0xFFE65100)
                        IntentStepStatus.FAILED -> Icons.Default.Error to Color(0xFFC62828)
                        IntentStepStatus.PENDING -> Icons.Default.HourglassEmpty to Color.Gray
                    }

                    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${step.stepIndex}. ${step.title}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Capability: [${step.capabilityRequired}] • ${step.description}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (step.verificationEvidence != null) {
                            Text(
                                text = "Evidence: ${step.verificationEvidence}",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
            }

            // Ethical Autonomy Gate (Action Buttons)
            val requiresAuth = steps.any { it.status == IntentStepStatus.AWAITING_AUTHORIZATION }
            if (requiresAuth && !isAuthorized) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Ethical Autonomy Law Checkpoint",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Consequential actions require explicit human authorization before execution.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onAuthorize,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Authorize Execution", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = onAbort,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Abort", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
