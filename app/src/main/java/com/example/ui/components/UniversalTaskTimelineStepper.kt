package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import com.example.data.conversation.TaskTimelineEntry
import com.example.data.conversation.TaskTimelinePhase
import com.example.data.conversation.TaskTimelineRecord
import com.example.data.conversation.UniversalTaskTimeline

@Composable
fun UniversalTaskTimelineStepper(
    modifier: Modifier = Modifier,
    activeTaskId: String? = null,
    timeline: UniversalTaskTimeline = UniversalTaskTimeline.getInstance()
) {
    var expanded by remember { mutableStateOf(false) }
    val latestEvents = remember { mutableStateListOf<TaskTimelineEntry>() }

    LaunchedEffect(Unit) {
        timeline.timelineEvents.collect { entry ->
            if (activeTaskId == null || entry.taskId == activeTaskId) {
                latestEvents.add(0, entry)
                if (latestEvents.size > 20) {
                    latestEvents.removeAt(latestEvents.lastIndex)
                }
            }
        }
    }

    val activeRecord: TaskTimelineRecord? = remember(activeTaskId, latestEvents.size) {
        if (activeTaskId != null) {
            timeline.getRecord(activeTaskId)
        } else {
            timeline.getAllRecords().firstOrNull { it.completedAt == null }
                ?: timeline.getAllRecords().lastOrNull()
        }
    }

    if (activeRecord == null && latestEvents.isEmpty()) {
        return
    }

    val currentPhase = activeRecord?.currentPhase ?: latestEvents.firstOrNull()?.phase ?: TaskTimelinePhase.RECEIVED
    val isSuccess = activeRecord?.isSuccessful ?: (currentPhase == TaskTimelinePhase.COMPLETED || currentPhase == TaskTimelinePhase.VERIFIED_POST_CORRECTION)
    val isFailed = currentPhase == TaskTimelinePhase.FAILED

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            // Header Row: Summary & Expand Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    val (icon, tint) = when {
                        isSuccess -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                        isFailed -> Icons.Default.Error to Color(0xFFE53935)
                        currentPhase == TaskTimelinePhase.AUTHORIZED || currentPhase == TaskTimelinePhase.CAPABILITY_CHECKED -> Icons.Default.Security to Color(0xFF2196F3)
                        currentPhase in listOf(TaskTimelinePhase.DIAGNOSED, TaskTimelinePhase.CORRECTED, TaskTimelinePhase.RETESTED) -> Icons.Default.Refresh to Color(0xFFFF9800)
                        else -> Icons.Default.PlayArrow to MaterialTheme.colorScheme.primary
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = "Timeline Status",
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Phase: ${currentPhase.name}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeRecord != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "[${activeRecord.originRoom}]",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Linear Progress / Phase Indicator
            Spacer(modifier = Modifier.height(6.dp))
            val phases = listOf(
                TaskTimelinePhase.RECEIVED,
                TaskTimelinePhase.UNDERSTOOD,
                TaskTimelinePhase.PLANNED,
                TaskTimelinePhase.CAPABILITY_CHECKED,
                TaskTimelinePhase.AUTHORIZED,
                TaskTimelinePhase.EXECUTING,
                TaskTimelinePhase.OBSERVING,
                TaskTimelinePhase.VERIFYING,
                TaskTimelinePhase.COMPLETED
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(phases) { phase ->
                    val isPastOrCurrent = currentPhase.ordinal >= phase.ordinal
                    val isCurrent = currentPhase == phase

                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(28.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    isPastOrCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                }
                            )
                    )
                }
            }

            // Expanded Detailed Timeline Log
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(8.dp))

                    val entriesToShow: List<TaskTimelineEntry> = activeRecord?.entries?.takeLast(10)?.reversed()
                        ?: latestEvents.take(10)

                    for (entry in entriesToShow) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (entry.phase) {
                                            TaskTimelinePhase.FAILED -> Color(0xFFE53935)
                                            TaskTimelinePhase.COMPLETED, TaskTimelinePhase.VERIFIED_POST_CORRECTION -> Color(0xFF4CAF50)
                                            TaskTimelinePhase.CORRECTED, TaskTimelinePhase.RETESTED -> Color(0xFFFF9800)
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.phase.name,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "+${entry.durationSinceStartMs}ms",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    text = entry.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
