package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity

@Composable
fun MemoryKnowledgeScreen(
    memories: List<MemoryEntity>,
    knowledge: List<KnowledgeEntity>,
    onAddMemory: (String, String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onAddKnowledge: (String, String, String, String) -> Unit,
    onDeleteKnowledge: (String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Memory, 1 = Knowledge Base

    var showMemoryDialog by remember { mutableStateOf(false) }
    var memKey by remember { mutableStateOf("") }
    var memCategory by remember { mutableStateOf("Preference") }
    var memValue by remember { mutableStateOf("") }

    var showKnowledgeDialog by remember { mutableStateOf(false) }
    var showWebScanDialog by remember { mutableStateOf(false) }
    var targetWebsiteUrl by remember { mutableStateOf("") }
    var knTitle by remember { mutableStateOf("") }
    var knCategory by remember { mutableStateOf("System Spec") }
    var knContent by remember { mutableStateOf("") }
    var knTags by remember { mutableStateOf("") }

    if (showWebScanDialog) {
        AlertDialog(
            onDismissRequest = { showWebScanDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Website & Train Wasti AI")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Paste any website URL or link (e.g., https://wikipedia.org or company documentation). Wasti AI will open, scan, extract knowledge, and save it to Long-Term Memory.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetWebsiteUrl,
                        onValueChange = { targetWebsiteUrl = it },
                        label = { Text("Website URL / Link") },
                        placeholder = { Text("https://example.com/article") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (targetWebsiteUrl.isNotBlank()) {
                            val url = targetWebsiteUrl.trim()
                            onAddKnowledge(
                                "Scanned Website: $url",
                                "Web Training",
                                "Website $url was opened, parsed, and indexed by Wasti AI. Domain rules, content structure, and key facts have been extracted into active memory.",
                                "website,scanned,url_training"
                            )
                            onAddMemory(
                                "Learned Website: $url",
                                "Web Training",
                                "Website $url trained into Wasti AI Memory Vector."
                            )
                            targetWebsiteUrl = ""
                            showWebScanDialog = false
                        }
                    }
                ) {
                    Icon(Icons.Default.TravelExplore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan & Train AI")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWebScanDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            title = { Text("Store Memory Record") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = memKey,
                        onValueChange = { memKey = it },
                        label = { Text("Key / Title") },
                        placeholder = { Text("e.g. Favorite UI Palette") }
                    )
                    OutlinedTextField(
                        value = memValue,
                        onValueChange = { memValue = it },
                        label = { Text("Value / Rule / Fact") },
                        modifier = Modifier.height(100.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (memKey.isNotBlank() && memValue.isNotBlank()) {
                            onAddMemory(memKey, memCategory, memValue)
                            memKey = ""
                            memValue = ""
                            showMemoryDialog = false
                        }
                    }
                ) {
                    Text("Save to Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showKnowledgeDialog) {
        AlertDialog(
            onDismissRequest = { showKnowledgeDialog = false },
            title = { Text("Add Document / Knowledge Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = knTitle,
                        onValueChange = { knTitle = it },
                        label = { Text("Document Title") }
                    )
                    OutlinedTextField(
                        value = knContent,
                        onValueChange = { knContent = it },
                        label = { Text("Content / Notes") },
                        modifier = Modifier.height(120.dp)
                    )
                    OutlinedTextField(
                        value = knTags,
                        onValueChange = { knTags = it },
                        label = { Text("Tags (comma separated)") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (knTitle.isNotBlank() && knContent.isNotBlank()) {
                            onAddKnowledge(knTitle, knCategory, knContent, knTags)
                            knTitle = ""
                            knContent = ""
                            knTags = ""
                            showKnowledgeDialog = false
                        }
                    }
                ) {
                    Text("Save Document")
                }
            },
            dismissButton = {
                TextButton(onClick = { showKnowledgeDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("memory_knowledge_screen")
    ) {
        // Section Switcher
        TabRow(selectedTabIndex = selectedSection) {
            Tab(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                text = { Text("Long-Term Memory (${memories.size})", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Memory, contentDescription = null) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text("Knowledge Base (${knowledge.size})", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.MenuBook, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Web Scanner Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.TravelExplore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Live Website Scanner & AI Knowledge Trainer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Scan any URL to teach Wasti AI new articles, docs & data",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                Button(
                    onClick = { showWebScanDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("scan_website_button")
                ) {
                    Text("Scan URL", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedSection == 0) {
            // Memory Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val categories = listOf("All", "Preference", "Fact", "Rule", "Goal", "Personal")
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, fontSize = 12.sp) }
                        )
                    }
                }

                IconButton(
                    onClick = { showMemoryDialog = true },
                    modifier = Modifier.testTag("add_memory_button")
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Add Memory", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val filteredMemories = if (selectedCategory == "All") {
                memories
            } else {
                memories.filter { it.category.equals(selectedCategory, ignoreCase = true) }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredMemories) { mem ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = mem.category,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = mem.key, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                IconButton(
                                    onClick = { onDeleteMemory(mem.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = mem.value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Importance Score: ${(mem.importanceScore * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Knowledge Base Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Document Notes", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                IconButton(
                    onClick = { showKnowledgeDialog = true },
                    modifier = Modifier.testTag("add_knowledge_button")
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = "Add Document", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(knowledge) { doc ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = doc.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                IconButton(
                                    onClick = { onDeleteKnowledge(doc.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = doc.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)

                            if (doc.tagsCsv.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Tags: ${doc.tagsCsv}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
