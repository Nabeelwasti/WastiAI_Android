package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.core.AppStartupManager
import com.example.data.core.ClientInvoiceItem
import com.example.data.core.ClientInvoiceManager
import com.example.data.core.InvoiceStatus
import com.example.data.core.LeadItemEntity
import com.example.data.core.LeadRadarRepository
import com.example.data.core.LeadStatus
import com.example.data.evaluation.AIEvaluationEngine
import com.example.data.evaluation.ProviderQualityScore
import com.example.data.ops.OperationsManager
import com.example.data.ops.ProviderHealthSummary
import com.example.data.tool.ToolRegistry
import com.example.data.worker.BackgroundTaskManager
import com.example.data.workflow.WorkflowEngine
import kotlinx.coroutines.launch

@Composable
fun OperationsDashboardScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val stats by OperationsManager.dashboardStatsFlow.collectAsStateWithLifecycle()
    val bgJobs by BackgroundTaskManager.jobsStateFlow.collectAsStateWithLifecycle()
    val workflowRules by WorkflowEngine.rulesStateFlow.collectAsStateWithLifecycle()
    val qualityScores by AIEvaluationEngine.qualityScoresFlow.collectAsStateWithLifecycle()
    val leads by LeadRadarRepository.leadsFlow.collectAsStateWithLifecycle()
    val invoices by ClientInvoiceManager.invoicesFlow.collectAsStateWithLifecycle()
    val tools = remember { ToolRegistry.getAllTools() }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Telemetry & Health", "Lead Radar & CRM", "Invoices & Ledger", "Background Maintenance", "AI Quality Scores", "Tools & Workflows")

    var customKeywordInput by remember { mutableStateOf("Video Editing & Graphic Design") }
    var isScanningLeads by remember { mutableStateOf(false) }
    var selectedKanbanFilter by remember { mutableStateOf<LeadStatus?>(null) }

    var newClientName by remember { mutableStateOf("") }
    var newMilestone by remember { mutableStateOf("") }
    var newAmount by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("operations_dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ops_header_card"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SYSTEM OPERATIONS & TELEMETRY",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { OperationsManager.refreshStats() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Telemetry",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Live Wasti AI OS Platform Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Real-time latency metrics, token consumption, daily USD costs, background maintenance jobs, and quality evaluation scores.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Key Metric Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricSummaryChip("Tokens", "${stats.totalTokensConsumed}", Icons.Default.DataUsage)
                        MetricSummaryChip("Est. Cost", "\$${"%.4f".format(stats.dailyCostEstimateUsd)}", Icons.Default.AttachMoney)
                        MetricSummaryChip("Active Jobs", "${bgJobs.size}", Icons.Default.Build)
                        MetricSummaryChip("Memories", "${stats.memoryStats.totalActiveMemories}", Icons.Default.Memory)
                    }
                }
            }
        }

        // Sub-Tab Navigation
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Provider Health & Latencies
                item {
                    Text(
                        text = "AI Intelligence Provider Health",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (stats.providerSummaries.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = "All systems active. No latency degradation recorded.",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(stats.providerSummaries) { provider ->
                        ProviderHealthCard(provider = provider)
                    }
                }

                // Memory Observability Section
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enterprise Memory Observability",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Vector Index Density:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${stats.memoryStats.totalVectorsIndexed} Vectors", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Knowledge Graph Nodes:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${stats.memoryStats.totalGraphNodes} Nodes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Knowledge Graph Edges:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${stats.memoryStats.totalGraphEdges} Edges", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Active Voice Provider:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stats.activeVoiceProvider.uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // Subsystem Cold-Start & Diagnostics
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Subsystem Cold-Start Diagnostics & Timings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                item {
                    val summary = remember { AppStartupManager.getDiagnosticSummary() }
                    StartupDiagnosticsCard(summary = summary)
                }
            }

            1 -> {
                // Lead Radar & CRM Kanban
                item {
                    Text(
                        text = "Lead Radar & Client CRM Kanban",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Custom Client Scraping Profile", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customKeywordInput,
                                    onValueChange = { customKeywordInput = it },
                                    label = { Text("Target Skill / Query", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        if (customKeywordInput.isNotBlank()) {
                                            isScanningLeads = true
                                            coroutineScope.launch {
                                                LeadRadarRepository.scanAndEvaluateLeads(context, customKeywordInput)
                                                isScanningLeads = false
                                            }
                                        }
                                    },
                                    enabled = !isScanningLeads,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isScanningLeads) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    } else {
                                        Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Scan", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                            // One-Tap Analytics & Data Export Toolbar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val csv = LeadRadarRepository.exportLeadsToCsv(leads)
                                        LeadRadarRepository.copyToClipboard(context, "Leads CSV Data", csv)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("CSV Export", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val json = LeadRadarRepository.exportLeadsToJson(leads)
                                        LeadRadarRepository.copyToClipboard(context, "Leads JSON Data", json)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("JSON Export", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val txt = LeadRadarRepository.exportProposalsToText(leads)
                                        LeadRadarRepository.copyToClipboard(context, "Proposals Export", txt)
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Proposals", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Kanban Stage Filter Row
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedKanbanFilter == null,
                                onClick = { selectedKanbanFilter = null },
                                label = { Text("All (${leads.size})", fontSize = 11.sp) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedKanbanFilter == LeadStatus.DISCOVERED,
                                onClick = { selectedKanbanFilter = LeadStatus.DISCOVERED },
                                label = { Text("🔍 Discovered (${leads.count { it.status == LeadStatus.DISCOVERED }})", fontSize = 11.sp) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedKanbanFilter == LeadStatus.PROPOSAL_SENT,
                                onClick = { selectedKanbanFilter = LeadStatus.PROPOSAL_SENT },
                                label = { Text("✉️ Sent (${leads.count { it.status == LeadStatus.PROPOSAL_SENT }})", fontSize = 11.sp) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedKanbanFilter == LeadStatus.NEGOTIATING,
                                onClick = { selectedKanbanFilter = LeadStatus.NEGOTIATING },
                                label = { Text("🤝 Negotiating (${leads.count { it.status == LeadStatus.NEGOTIATING }})", fontSize = 11.sp) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedKanbanFilter == LeadStatus.CLOSED,
                                onClick = { selectedKanbanFilter = LeadStatus.CLOSED },
                                label = { Text("✅ Closed (${leads.count { it.status == LeadStatus.CLOSED }})", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                val filteredLeads = if (selectedKanbanFilter != null) {
                    leads.filter { it.status == selectedKanbanFilter }
                } else leads

                if (filteredLeads.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = "No lead records in this CRM stage yet.",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    items(filteredLeads) { lead ->
                        KanbanLeadCard(lead = lead, context = context)
                    }
                }
            }

            2 -> {
                // Invoices & Ledger
                item {
                    Text(
                        text = "Automated Billing Ledger & Client Invoices",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Summary Row
                item {
                    val totalInvoiced = invoices.sumOf { it.amountUsd }
                    val totalPaid = invoices.filter { it.status == InvoiceStatus.PAID }.sumOf { it.amountUsd }
                    val totalPending = invoices.filter { it.status == InvoiceStatus.PENDING_PAYMENT || it.status == InvoiceStatus.INVOICED }.sumOf { it.amountUsd }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Total Revenue", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                Text("$${"%.0f".format(totalInvoiced)} USD", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981).copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Paid Received", fontSize = 10.sp, color = Color(0xFF047857))
                                Text("$${"%.0f".format(totalPaid)} USD", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF047857))
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B).copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Pending Due", fontSize = 10.sp, color = Color(0xFFB45309))
                                Text("$${"%.0f".format(totalPending)} USD", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFB45309))
                            }
                        }
                    }
                }

                // Create Invoice Form
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Draft New Client Invoice", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            OutlinedTextField(
                                value = newClientName,
                                onValueChange = { newClientName = it },
                                label = { Text("Client Name / Agency", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newMilestone,
                                onValueChange = { newMilestone = it },
                                label = { Text("Project Milestone / Deliverables", fontSize = 11.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newAmount,
                                    onValueChange = { newAmount = it },
                                    label = { Text("Amount ($ USD)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        val amt = newAmount.toDoubleOrNull() ?: 250.0
                                        if (newClientName.isNotBlank() && newMilestone.isNotBlank()) {
                                            ClientInvoiceManager.createInvoice(newClientName, newMilestone, amt)
                                            newClientName = ""
                                            newMilestone = ""
                                            newAmount = ""
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Log Invoice", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                items(invoices) { invoice ->
                    InvoiceLedgerCard(invoice = invoice, context = context)
                }
            }

            3 -> {
                // Background Maintenance Jobs
                item {
                    Text(
                        text = "BackgroundTaskManager Scheduled Jobs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(bgJobs) { job ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(job.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    "Type: ${job.type.name} • Runs: ${job.runCount}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (job.state.name) {
                                    "RUNNING" -> Color(0xFF3B82F6)
                                    "COMPLETED" -> Color(0xFF10B981)
                                    "FAILED" -> Color(0xFFEF4444)
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                            ) {
                                Text(
                                    text = job.state.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            4 -> {
                // AI Quality Scores
                item {
                    Text(
                        text = "AI Quality & Accuracy Benchmarks",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (qualityScores.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = "AIEvaluationEngine is continuously sampling model responses. Quality benchmarks will populate as requests complete.",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(qualityScores) { score ->
                        QualityScoreCard(score = score)
                    }
                }
            }

            5 -> {
                // Tools & Workflows
                item {
                    Text(
                        text = "Workflow Automation Rules (${workflowRules.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(workflowRules) { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(rule.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Text(
                                        rule.trigger.type.name,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(rule.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Registered System Tools (${tools.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                items(tools) { tool ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tool.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(tool.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricSummaryChip(label: String, value: String, icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ProviderHealthCard(provider: ProviderHealthSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(provider.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Latency: ${"%.1f".format(provider.averageLatencyMs)}ms • Success: ${"%.1f".format(provider.successRate)}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (provider.healthStatus == "HEALTHY" || provider.healthStatus == "UNKNOWN") Color(0xFF10B981) else Color(0xFFEF4444)
            ) {
                Text(
                    text = provider.healthStatus,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun QualityScoreCard(score: ProviderQualityScore) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(score.providerName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Score: ${"%.1f".format(score.compositeQualityIndex)}/100",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { score.compositeQualityIndex / 100.0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Accuracy: ${"%.1f".format(score.accuracyScorePercentage)}% • Avg Latency: ${"%.0f".format(score.averageLatencyMs)}ms • Evaluations: ${score.totalEvaluationsCount}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StartupDiagnosticsCard(summary: com.example.data.core.StartupDiagnostic) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cold-Start Duration",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${summary.totalStartupTimeMs} ms",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "Subsystem Stage Timings:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (summary.stageTimings.isEmpty()) {
                Text(
                    text = "Stage timing trace active...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                summary.stageTimings.forEach { (stage, timingMs) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (stage.isCritical) MaterialTheme.colorScheme.primary else Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stage.displayName,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "${timingMs} ms",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (summary.warnings.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Startup Degraded Warnings (${summary.warnings.size}):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                summary.warnings.forEach { warning ->
                    Text(
                        text = "• $warning",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun KanbanLeadCard(lead: LeadItemEntity, context: android.content.Context) {
    var expandedPitch by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    val cardOffsetAnim by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "KanbanDragOffset"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(cardOffsetAnim.roundToInt(), 0) }
            .pointerInput(lead.id, lead.status) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX > 120f) {
                            val nextStatus = when (lead.status) {
                                LeadStatus.DISCOVERED -> LeadStatus.PROPOSAL_SENT
                                LeadStatus.PROPOSAL_SENT -> LeadStatus.NEGOTIATING
                                LeadStatus.NEGOTIATING -> LeadStatus.CLOSED
                                LeadStatus.CLOSED -> LeadStatus.CLOSED
                            }
                            LeadRadarRepository.updateLeadStatus(context, lead.id, nextStatus)
                        } else if (offsetX < -120f) {
                            val prevStatus = when (lead.status) {
                                LeadStatus.CLOSED -> LeadStatus.NEGOTIATING
                                LeadStatus.NEGOTIATING -> LeadStatus.PROPOSAL_SENT
                                LeadStatus.PROPOSAL_SENT -> LeadStatus.DISCOVERED
                                LeadStatus.DISCOVERED -> LeadStatus.DISCOVERED
                            }
                            LeadRadarRepository.updateLeadStatus(context, lead.id, prevStatus)
                        }
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        offsetX = (offsetX + dragAmount).coerceIn(-300f, 300f)
                    }
                )
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (lead.status) {
                        LeadStatus.DISCOVERED -> Color(0xFF3B82F6)
                        LeadStatus.PROPOSAL_SENT -> Color(0xFF8B5CF6)
                        LeadStatus.NEGOTIATING -> Color(0xFFF59E0B)
                        LeadStatus.CLOSED -> Color(0xFF10B981)
                    }
                ) {
                    Text(
                        text = lead.status.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF047857), modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${lead.matchScore}% Match",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF047857)
                        )
                    }
                }
            }

            Text(
                text = lead.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = lead.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expandedPitch) 10 else 2
            )

            if (lead.matchedSkills.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    lead.matchedSkills.forEach { skill ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = skill,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            // 1-Tap Client Outreach Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { LeadRadarRepository.dispatchViaWhatsApp(context, lead.draftedPitch) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("WhatsApp", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { LeadRadarRepository.dispatchViaEmail(context, "Proposal: ${lead.title}", lead.draftedPitch, lead.clientEmail) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Email Pitch", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { expandedPitch = !expandedPitch },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(if (expandedPitch) "Hide Pitch" else "View Pitch", fontSize = 10.sp)
                }
            }

            if (expandedPitch && lead.draftedPitch.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Drafted Proposal Pitch:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(lead.draftedPitch, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Move Stage Toolbar
            Text("Move Stage:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                LeadStatus.values().forEach { st ->
                    FilterChip(
                        selected = lead.status == st,
                        onClick = { LeadRadarRepository.updateLeadStatus(lead.id, st) },
                        label = { Text(st.name.take(6), fontSize = 9.sp) },
                        modifier = Modifier.height(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InvoiceLedgerCard(invoice: ClientInvoiceItem, context: android.content.Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = invoice.clientName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (invoice.status) {
                        InvoiceStatus.DRAFT -> MaterialTheme.colorScheme.surfaceVariant
                        InvoiceStatus.INVOICED -> Color(0xFF3B82F6)
                        InvoiceStatus.PENDING_PAYMENT -> Color(0xFFF59E0B)
                        InvoiceStatus.PAID -> Color(0xFF10B981)
                    }
                ) {
                    Text(
                        text = invoice.status.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Text(
                text = invoice.projectMilestone,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Amount: $${String.format(java.util.Locale.US, "%.2f", invoice.amountUsd)} USD",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Due: ${invoice.dueDate}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { ClientInvoiceManager.copyInvoiceToClipboard(context, invoice) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Invoice", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = { ClientInvoiceManager.shareInvoiceViaEmail(context, invoice) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Email Client", fontSize = 10.sp)
                }

                if (invoice.status != InvoiceStatus.PAID) {
                    Button(
                        onClick = { ClientInvoiceManager.updateStatus(invoice.id, InvoiceStatus.PAID) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Text("Mark Paid", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
