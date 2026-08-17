package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The executive brain's current operational state, shown in the header. */
enum class ExecutiveBrainStatus {
    ONLINE,
    THINKING,
    DEGRADED,
    OFFLINE
}

/**
 * Accessible, responsive header for the one-brain / many-capabilities shell.
 *
 * [activeTaskCount] represents independent work currently coordinated by the
 * executive brain; it does not indicate duplicate work. Existing callers keep
 * the same behaviour because all new parameters have safe defaults.
 */
@Composable
fun ExecutiveBrainHeader(
    activeAgentName: String = "Wasti AI",
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenVoiceCall: (() -> Unit)? = null,
    status: ExecutiveBrainStatus = ExecutiveBrainStatus.ONLINE,
    activeTaskCount: Int = 0,
    modifier: Modifier = Modifier,
    onOpenStatus: (() -> Unit)? = null
) {
    val safeTaskCount = activeTaskCount.coerceAtLeast(0)
    val resolvedAgentName = activeAgentName.ifBlank { "Wasti AI" }
    val statusLabel = status.label()
    val headerDescription = buildString {
        append("Wasti OS Executive Brain. ")
        append("$statusLabel. Active agent: $resolvedAgentName.")
        if (safeTaskCount > 0) append(" $safeTaskCount active ${taskLabel(safeTaskCount)}.")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("executive_brain_header")
            .semantics {
                contentDescription = headerDescription
                stateDescription = statusLabel
            },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val compactLayout = maxWidth < COMPACT_HEADER_WIDTH
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExecutiveIdentity(
                            activeAgentName = resolvedAgentName,
                            status = status,
                            activeTaskCount = safeTaskCount,
                            onOpenStatus = onOpenStatus
                        )
                        HeaderActions(
                            isCompact = true,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = onToggleTheme,
                            onOpenCommandPalette = onOpenCommandPalette,
                            onOpenVoiceCall = onOpenVoiceCall
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ExecutiveIdentity(
                            activeAgentName = resolvedAgentName,
                            status = status,
                            activeTaskCount = safeTaskCount,
                            modifier = Modifier.weight(1f, fill = false),
                            onOpenStatus = onOpenStatus
                        )
                        HeaderActions(
                            isCompact = false,
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = onToggleTheme,
                            onOpenCommandPalette = onOpenCommandPalette,
                            onOpenVoiceCall = onOpenVoiceCall
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        }
    }
}

@Composable
private fun ExecutiveIdentity(
    activeAgentName: String,
    status: ExecutiveBrainStatus,
    activeTaskCount: Int,
    modifier: Modifier = Modifier,
    onOpenStatus: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = "Wasti OS",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "WASTI OS",
                    modifier = Modifier.semantics { heading() },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                OperationalStatusBadge(
                    status = status,
                    onOpenStatus = onOpenStatus
                )
            }
            Text(
                text = buildString {
                    append("Executive Brain · ")
                    append(activeAgentName.ifBlank { "Wasti AI" })
                    if (activeTaskCount > 0) append(" · $activeTaskCount active ${taskLabel(activeTaskCount)}")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OperationalStatusBadge(
    status: ExecutiveBrainStatus,
    onOpenStatus: (() -> Unit)?
) {
    val statusColor = status.color()
    val label = status.label()
    val statusModifier = Modifier
        .testTag("executive_brain_status")
        .semantics {
            contentDescription = if (onOpenStatus == null) {
                "Executive Brain status: $label"
            } else {
                "Executive Brain status: $label. Open system status."
            }
        }
        .then(
            if (onOpenStatus == null) {
                Modifier
            } else {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = "Open system status",
                    onClick = onOpenStatus
                )
            }
        )
    Surface(
        modifier = statusModifier,
        shape = RoundedCornerShape(50),
        color = statusColor.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = statusColor
            )
        }
    }
}

@Composable
private fun HeaderActions(
    isCompact: Boolean,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenVoiceCall: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onOpenVoiceCall != null) {
            HeaderActionPill(
                label = "Live voice chat",
                compactLabel = "Voice",
                showLabel = !isCompact,
                icon = { Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = onOpenVoiceCall,
                modifier = Modifier.testTag("header_voice_call_button")
            )
        }

        HeaderActionPill(
            label = "Open command palette",
            compactLabel = "Commands",
            showLabel = !isCompact,
            icon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = onOpenCommandPalette,
            modifier = Modifier.testTag("header_search_bar")
        )

        IconButton(
            onClick = onToggleTheme,
            modifier = Modifier
                .testTag("theme_toggle_button")
                .semantics {
                    contentDescription = if (isDarkTheme) "Switch to light theme" else "Switch to dark theme"
                }
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HeaderActionPill(
    label: String,
    compactLabel: String,
    showLabel: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 48.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick
            )
            .semantics { contentDescription = label },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            if (showLabel) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = compactLabel,
                    maxLines = 1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExecutiveBrainStatus.color(): Color = when (this) {
    ExecutiveBrainStatus.ONLINE -> Color(0xFF198754)
    ExecutiveBrainStatus.THINKING -> MaterialTheme.colorScheme.primary
    ExecutiveBrainStatus.DEGRADED -> Color(0xFFB26A00)
    ExecutiveBrainStatus.OFFLINE -> MaterialTheme.colorScheme.error
}

private fun ExecutiveBrainStatus.label(): String = when (this) {
    ExecutiveBrainStatus.ONLINE -> "Online"
    ExecutiveBrainStatus.THINKING -> "Thinking"
    ExecutiveBrainStatus.DEGRADED -> "Degraded"
    ExecutiveBrainStatus.OFFLINE -> "Offline"
}

private fun taskLabel(taskCount: Int): String = if (taskCount == 1) "task" else "tasks"

private val COMPACT_HEADER_WIDTH = 420.dp

