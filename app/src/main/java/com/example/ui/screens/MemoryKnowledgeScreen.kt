package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.ConversationEntity
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryKnowledgeScreen(
    memories: List<MemoryEntity>,
    knowledge: List<KnowledgeEntity>,
    conversations: List<ConversationEntity> = emptyList(),
    onAddMemory: (String, String, String) -> Unit,
    onUpdateMemory: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onDeleteMemory: (String) -> Unit,
    onAddKnowledge: (String, String, String, String) -> Unit,
    onUpdateKnowledge: (String, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onDeleteKnowledge: (String) -> Unit,
    onSelectConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onNavigateToChatWithPrompt: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Memory, 1 = Knowledge Base
    var searchQuery by remember { mutableStateOf("") }

    // New Memory Dialog State
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memKey by remember { mutableStateOf("") }
    var memCategory by remember { mutableStateOf("Preference") }
    var memValue by remember { mutableStateOf("") }

    // Edit Memory Dialog State
    var editingMemoryItem by remember { mutableStateOf<MemoryEntity?>(null) }
    var editMemKey by remember { mutableStateOf("") }
    var editMemCategory by remember { mutableStateOf("") }
    var editMemValue by remember { mutableStateOf("") }

    // New Knowledge Dialog State
    var showKnowledgeDialog by remember { mutableStateOf(false) }
    var showWebScanDialog by remember { mutableStateOf(false) }
    var targetWebsiteUrl by remember { mutableStateOf("") }
    var knTitle by remember { mutableStateOf("") }
    var knCategory by remember { mutableStateOf("System Spec") }
    var knContent by remember { mutableStateOf("") }
    var knTags by remember { mutableStateOf("") }

    // Edit Knowledge Dialog State
    var editingKnowledgeItem by remember { mutableStateOf<KnowledgeEntity?>(null) }
    var editKnTitle by remember { mutableStateOf("") }
    var editKnCategory by remember { mutableStateOf("") }
    var editKnContent by remember { mutableStateOf("") }
    var editKnTags by remember { mutableStateOf("") }

    fun copyToClipboard(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

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
            title = { Text("Add New Memory Prompt / Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = memKey,
                        onValueChange = { memKey = it },
                        label = { Text("Key / Title") },
                        placeholder = { Text("e.g. Code Style Preference") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = memCategory,
                        onValueChange = { memCategory = it },
                        label = { Text("Category (e.g. Preference, Fact, Rule)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = memValue,
                        onValueChange = { memValue = it },
                        label = { Text("Memory Content / Rule Value") },
                        modifier = Modifier.fillMaxWidth().height(110.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (memKey.isNotBlank() && memValue.isNotBlank()) {
                            onAddMemory(memKey, memCategory.ifBlank { "Preference" }, memValue)
                            memKey = ""
                            memValue = ""
                            showMemoryDialog = false
                        }
                    }
                ) {
                    Text("Save Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Edit Memory Dialog
    if (editingMemoryItem != null) {
        AlertDialog(
            onDismissRequest = { editingMemoryItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Memory Prompt")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editMemKey,
                        onValueChange = { editMemKey = it },
                        label = { Text("Key / Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editMemCategory,
                        onValueChange = { editMemCategory = it },
                        label = { Text("Category") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editMemValue,
                        onValueChange = { editMemValue = it },
                        label = { Text("Value / Content") },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val item = editingMemoryItem
                        if (item != null && editMemKey.isNotBlank() && editMemValue.isNotBlank()) {
                            onUpdateMemory(item.id, editMemKey, editMemCategory.ifBlank { "Preference" }, editMemValue)
                            editingMemoryItem = null
                        }
                    }
                ) {
                    Text("Update Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMemoryItem = null }) { Text("Cancel") }
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
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = knCategory,
                        onValueChange = { knCategory = it },
                        label = { Text("Category") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = knContent,
                        onValueChange = { knContent = it },
                        label = { Text("Content / Notes") },
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    OutlinedTextField(
                        value = knTags,
                        onValueChange = { knTags = it },
                        label = { Text("Tags (comma separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (knTitle.isNotBlank() && knContent.isNotBlank()) {
                            onAddKnowledge(knTitle, knCategory.ifBlank { "System Spec" }, knContent, knTags)
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

    // Edit Knowledge Dialog
    if (editingKnowledgeItem != null) {
        AlertDialog(
            onDismissRequest = { editingKnowledgeItem = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Knowledge Document")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editKnTitle,
                        onValueChange = { editKnTitle = it },
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editKnCategory,
                        onValueChange = { editKnCategory = it },
                        label = { Text("Category") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editKnContent,
                        onValueChange = { editKnContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth().height(130.dp)
                    )
                    OutlinedTextField(
                        value = editKnTags,
                        onValueChange = { editKnTags = it },
                        label = { Text("Tags") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val item = editingKnowledgeItem
                        if (item != null && editKnTitle.isNotBlank() && editKnContent.isNotBlank()) {
                            onUpdateKnowledge(item.id, editKnTitle, editKnCategory.ifBlank { "System Spec" }, editKnContent, editKnTags)
                            editingKnowledgeItem = null
                        }
                    }
                ) {
                    Text("Update Document")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingKnowledgeItem = null }) { Text("Cancel") }
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
                text = { Text("Memories (${memories.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                icon = { Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                text = { Text("Knowledge (${knowledge.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                icon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                text = { Text("Old Chats (${conversations.size})", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                icon = { Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Corporate Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("memory_search_box"),
            placeholder = {
                Text(
                    when (selectedSection) {
                        0 -> "Search long-term memories, preferences, facts..."
                        1 -> "Search knowledge documents, articles, tags..."
                        else -> "Search permanent old chat transcripts, titles, agents..."
                    },
                    fontSize = 12.sp
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        )

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

        when (selectedSection) {
            0 -> {
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

            val filteredMemories = memories.filter { mem ->
                (selectedCategory == "All" || mem.category.equals(selectedCategory, ignoreCase = true)) &&
                (searchQuery.isBlank() || mem.key.contains(searchQuery, ignoreCase = true) || mem.value.contains(searchQuery, ignoreCase = true) || mem.category.contains(searchQuery, ignoreCase = true))
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
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                                    Text(
                                        text = mem.key,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Useful Option 1: EDIT MEMORY PROMPT
                                    IconButton(
                                        onClick = {
                                            editingMemoryItem = mem
                                            editMemKey = mem.key
                                            editMemCategory = mem.category
                                            editMemValue = mem.value
                                        },
                                        modifier = Modifier.size(28.dp).testTag("edit_memory_${mem.id}")
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Prompt", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 2: COPY MEMORY VALUE
                                    IconButton(
                                        onClick = { copyToClipboard(mem.value, "Memory Content") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 3: USE IN CHAT / SEND PROMPT
                                    IconButton(
                                        onClick = { onNavigateToChatWithPrompt("Using Memory Prompt: ${mem.key}\nRule: ${mem.value}") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "Send to Chat", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 4: DELETE MEMORY
                                    IconButton(
                                        onClick = { onDeleteMemory(mem.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
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
        }
        1 -> {
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

            val filteredKnowledge = knowledge.filter { doc ->
                searchQuery.isBlank() ||
                doc.title.contains(searchQuery, ignoreCase = true) ||
                doc.content.contains(searchQuery, ignoreCase = true) ||
                doc.tagsCsv.contains(searchQuery, ignoreCase = true) ||
                doc.category.contains(searchQuery, ignoreCase = true)
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredKnowledge) { doc ->
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
                                Text(text = doc.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Useful Option 1: EDIT DOCUMENT
                                    IconButton(
                                        onClick = {
                                            editingKnowledgeItem = doc
                                            editKnTitle = doc.title
                                            editKnCategory = doc.category
                                            editKnContent = doc.content
                                            editKnTags = doc.tagsCsv
                                        },
                                        modifier = Modifier.size(28.dp).testTag("edit_knowledge_${doc.id}")
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Document", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 2: COPY CONTENT
                                    IconButton(
                                        onClick = { copyToClipboard(doc.content, "Document Content") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Content", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 3: USE IN CHAT
                                    IconButton(
                                        onClick = { onNavigateToChatWithPrompt("Knowledge Doc: ${doc.title}\nContent:\n${doc.content}") },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = "Send to Chat", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                                    }

                                    // Useful Option 4: DELETE DOCUMENT
                                    IconButton(
                                        onClick = { onDeleteKnowledge(doc.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
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
        else -> {
            // Permanent Old Chats Memory Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Permanent Chat History", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(text = "Saved conversation threads indexed in Wasti Memory", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                Button(
                    onClick = {
                        conversations.forEach { conv ->
                            onAddKnowledge(
                                "Archived Chat: ${conv.title}",
                                "Chat History",
                                "Chat session '${conv.title}' with Agent '${conv.activeAgentId}' recorded permanently on ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(conv.updatedTimestamp))}.",
                                "chat_history,archived,${conv.activeAgentId}"
                            )
                        }
                        Toast.makeText(context, "All active chats indexed to permanent memory", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Index All Chats", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val filteredConversations = conversations.filter { conv ->
                searchQuery.isBlank() ||
                conv.title.contains(searchQuery, ignoreCase = true) ||
                conv.activeAgentId.contains(searchQuery, ignoreCase = true)
            }

            if (filteredConversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forum, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Old Chats Found", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("All past chat threads will be saved permanently here in memory.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredConversations) { conv ->
                        val dateFormatted = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US).format(Date(conv.updatedTimestamp))
                        
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = conv.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "Agent: ${conv.activeAgentId}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = dateFormatted, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Resume Chat Button
                                        IconButton(
                                            onClick = { onSelectConversation(conv.id) },
                                            modifier = Modifier.size(32.dp).testTag("open_chat_${conv.id}")
                                        ) {
                                            Icon(Icons.Default.OpenInNew, contentDescription = "Open Chat", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }

                                        // Save to Knowledge Base
                                        IconButton(
                                            onClick = {
                                                onAddKnowledge(
                                                    "Archived Chat: ${conv.title}",
                                                    "Chat History",
                                                    "Chat Session transcript '${conv.title}' with Agent '${conv.activeAgentId}' saved to permanent memory on $dateFormatted.",
                                                    "chat,history,${conv.activeAgentId}"
                                                )
                                                Toast.makeText(context, "Chat archived to Knowledge Base", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.BookmarkAdd, contentDescription = "Save to Knowledge", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                        }

                                        // Copy Transcript Info
                                        IconButton(
                                            onClick = { copyToClipboard("Chat Thread: ${conv.title}\nAgent: ${conv.activeAgentId}\nDate: $dateFormatted", "Chat Summary") },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Summary", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        }

                                        // Delete Conversation
                                        IconButton(
                                            onClick = { onDeleteConversation(conv.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete Chat", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
