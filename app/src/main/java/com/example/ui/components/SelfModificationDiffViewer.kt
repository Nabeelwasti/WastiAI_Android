package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.agent.runtime.DiffLine
import com.example.data.agent.runtime.DiffLineType
import com.example.data.agent.runtime.ProposedModification
import com.example.data.agent.runtime.SelfModificationSafetyEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose Diff Viewer & Explicit Authorization Component.
 *
 * Adheres to Wasti AI OS Eternal Manifesto:
 * - Real human authorization for self-modifications
 * - Transparent, observable mutations
 * - Fail-closed safety invariants
 */
@Composable
fun SelfModificationDiffViewer(
    modifier: Modifier = Modifier
) {
    val pendingProposals by SelfModificationSafetyEngine.pendingProposals.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (pendingProposals.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Code Integrity Invariant Active",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Self-Modification Status: Invariants Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "No pending autonomous modifications. All code edits require explicit human authorization and verification.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            for (proposal in pendingProposals) {
                ProposalDiffCard(proposal = proposal)
            }
        }
    }
}

@Composable
private fun ProposalDiffCard(
    proposal: ProposedModification
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val formattedDate = remember(proposal.timestamp) { dateFormat.format(Date(proposal.timestamp)) }
    val diffLines = remember(proposal.originalContent, proposal.newContent) {
        SelfModificationSafetyEngine.computeDiff(proposal.originalContent, proposal.newContent)
    }

    var outcomeMessage by remember { mutableStateOf<String?>(null) }
    var isOutcomeError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Icon + Title + Protection Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = proposal.filePath.substringAfterLast('/'),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (proposal.isProtected) {
                    Surface(
                        color = Color(0xFFB71C1C).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFFC62828),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PROTECTED CORE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFC62828),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "STANDARD COMPONENT",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Path & Metadata
            Text(
                text = "Target: ${proposal.filePath}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Intent: ${proposal.reason}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Proposed: $formattedDate",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (proposal.contentHash.isNotEmpty()) {
                    Text(
                        text = "SHA-256: ${proposal.contentHash.take(12)}...",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Diff Viewer Box
            DiffContentBox(diffLines = diffLines)

            // Protected path warning if applicable
            if (proposal.isProtected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Protected core file. Authorization will execute strict staged rollback and verification.",
                        fontSize = 11.sp,
                        color = Color(0xFFE65100)
                    )
                }
            }

            // Outcome message if any
            outcomeMessage?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    color = if (isOutcomeError) Color(0xFFC62828) else Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        SelfModificationSafetyEngine.rejectProposal(proposal.id, "User rejected via Diff Viewer")
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject & Discard", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val outcome = SelfModificationSafetyEngine.authorizeAndApply(proposal.id)
                        if (outcome.status == com.example.data.agent.runtime.ModificationOutcomeStatus.APPLIED_VERIFIED ||
                            outcome.status == com.example.data.agent.runtime.ModificationOutcomeStatus.APPLIED_UNVERIFIED
                        ) {
                            outcomeMessage = "Applied successfully: ${outcome.status.name}"
                            isOutcomeError = false
                        } else {
                            outcomeMessage = "Action failed: ${outcome.status.name} (${outcome.errorDetails ?: "Blocked by policy"})"
                            isOutcomeError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Authorize & Apply", fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DiffContentBox(diffLines: List<DiffLine>) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
                .horizontalScroll(scrollState)
        ) {
            if (diffLines.isEmpty()) {
                Text(
                    text = "No textual changes detected between versions.",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                for (line in diffLines) {
                    DiffLineRow(line = line)
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val (bgColor, textColor, prefix) = when (line.type) {
        DiffLineType.ADDED -> Triple(Color(0x334CAF50), Color(0xFF1B5E20), "+ ")
        DiffLineType.DELETED -> Triple(Color(0x33F44336), Color(0xFFB71C1C), "- ")
        DiffLineType.UNCHANGED -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurface, "  ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val lineNum = (line.newLineNumber ?: line.oldLineNumber)?.toString() ?: ""
        Text(
            text = lineNum.padStart(4, ' '),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = prefix,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        Text(
            text = line.text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = textColor
        )
    }
}
