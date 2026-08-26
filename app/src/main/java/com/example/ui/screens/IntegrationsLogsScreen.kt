package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.data.credential.CredentialCategory
import com.example.data.credential.CredentialRegistry
import com.example.data.credential.CredentialState
import com.example.data.credential.CredentialStatus
import com.example.data.db.IntegrationEntity
import com.example.data.db.SystemLogEntity
import kotlinx.coroutines.launch

@Composable
fun IntegrationsLogsScreen(
    integrations: List<IntegrationEntity>,
    logs: List<SystemLogEntity>,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Credentials Registry, 1 = Service Connectors, 2 = System Logs
    var selectedCategoryFilter by remember { mutableStateOf<CredentialCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isTestingAll by remember { mutableStateOf(false) }
    var selectedLogLevel by remember { mutableStateOf("ALL") }

    val credentialStates by CredentialRegistry.credentialStates.collectAsState()

    LaunchedEffect(Unit) {
        CredentialRegistry.refreshAll(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("integrations_logs_screen")
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Credentials", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.VpnKey, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Connectors (${integrations.size})", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Extension, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Audit Logs (${logs.size})", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Terminal, contentDescription = null) }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("Reality Matrix", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.HealthAndSafety, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Unified Ecosystem CredentialRegistry",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Centralized key management & live API health verification across models, design, payments, GitHub repos, and communications.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                    )
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isTestingAll = true
                                            CredentialRegistry.testAllCredentials(context)
                                            isTestingAll = false
                                        }
                                    },
                                    enabled = !isTestingAll,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.testTag("test_all_credentials_button")
                                ) {
                                    if (isTestingAll) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Testing...", fontSize = 12.sp)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test All Keys", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Search & Category Filters
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search API keys by name or provider...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("credential_search_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategoryFilter == null,
                                onClick = { selectedCategoryFilter = null },
                                label = { Text("ALL (${credentialStates.size})") }
                            )
                        }
                        items(CredentialCategory.values()) { category ->
                            val count = credentialStates.count { it.entry.category == category }
                            FilterChip(
                                selected = selectedCategoryFilter == category,
                                onClick = { selectedCategoryFilter = category },
                                label = { Text("${category.title} ($count)") }
                            )
                        }
                    }
                }

                val filteredItems = credentialStates.filter { item ->
                    val matchesCategory = selectedCategoryFilter == null || item.entry.category == selectedCategoryFilter
                    val matchesQuery = searchQuery.isBlank() ||
                            item.entry.keyName.contains(searchQuery, ignoreCase = true) ||
                            item.entry.displayName.contains(searchQuery, ignoreCase = true) ||
                            item.entry.description.contains(searchQuery, ignoreCase = true)
                    matchesCategory && matchesQuery
                }

                items(filteredItems, key = { it.entry.keyName }) { item ->
                    CredentialCardItem(
                        state = item,
                        onTestClick = {
                            coroutineScope.launch {
                                CredentialRegistry.testSingleCredential(item.entry.keyName, context)
                            }
                        }
                    )
                }
            }
        } else if (selectedTab == 1) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(text = "Connected Services & MCP Adapters", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Wasti OS connects to cloud tools, Canva design engine, GitHub code repositories, and Google AI Studio APIs.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(integrations) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = item.serviceName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(text = "Provider: ${item.provider} • Auth: ${item.authType}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(text = item.statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            }

                            Switch(
                                checked = item.isConnected,
                                onCheckedChange = { },
                                modifier = Modifier.testTag("integration_switch_${item.id}")
                            )
                        }
                    }
                }
            }
        } else if (selectedTab == 2) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Live System Audit Logs", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.testTag("clear_logs_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val filteredLogs = if (selectedLogLevel == "ALL") {
                    logs
                } else {
                    logs.filter { it.level.equals(selectedLogLevel, ignoreCase = true) }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredLogs) { log ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 1.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (log.level) {
                                                "ERROR" -> Color(0xFFF43F5E)
                                                "WARN" -> Color(0xFFF59E0B)
                                                "AGENT" -> Color(0xFF818CF8)
                                                else -> Color(0xFF38BDF8)
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "[${log.level}] ${log.source}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = log.message,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!log.details.isNullOrBlank()) {
                                        Text(
                                            text = log.details,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            com.example.ui.components.RealityDiagnosticsView(
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun CredentialCardItem(
    state: CredentialState,
    onTestClick: () -> Unit
) {
    val context = LocalContext.current
    val entry = state.entry
    val rawVal = state.rawValue

    var isRevealed by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var pendingAuthMode by remember { mutableStateOf<String?>(null) } // "EYE" or "COPY"
    var enteredPin by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }

    fun performCopy(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    val displayVal = when {
        rawVal.isBlank() || rawVal.startsWith("MY_") || rawVal.startsWith("YOUR_") -> "Not Configured (Placeholder Key)"
        isRevealed -> rawVal
        else -> "••••••••••••••••••••"
    }

    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = {
                showAuthDialog = false
                pendingAuthMode = null
                enteredPin = ""
                authError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sensitive Credential Verification")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter Mobile PIN / Passcode or Fingerprint to ${if (pendingAuthMode == "COPY") "copy" else "view"} ${entry.displayName}.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { enteredPin = it },
                        label = { Text("Passcode / PIN") },
                        placeholder = { Text("e.g. 1234 or your PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (authError != null) {
                        Text(text = authError!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (com.example.data.security.WastiSecurityManager.verifyPasscode(context, enteredPin.trim())) {
                            if (pendingAuthMode == "EYE") {
                                isRevealed = true
                            } else if (pendingAuthMode == "COPY") {
                                performCopy(rawVal, entry.displayName)
                            }
                            showAuthDialog = false
                            pendingAuthMode = null
                            enteredPin = ""
                            authError = null
                        } else {
                            authError = "Incorrect PIN / Passcode. Try '1234' or your custom PIN."
                        }
                    }
                ) {
                    Text("Verify & Authenticate")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAuthDialog = false
                        pendingAuthMode = null
                        enteredPin = ""
                        authError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("credential_card_${entry.keyName}"),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (entry.isDefaultActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "ACTIVE DEFAULT",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Key Name: ${entry.keyName}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Chip
                when (val status = state.status) {
                    is CredentialStatus.Connected -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("CONNECTED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                            }
                        }
                    }
                    is CredentialStatus.Error -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("ERROR / UNCONFIGURED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    is CredentialStatus.Testing -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("TESTING...", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    is CredentialStatus.NotConfigured -> {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("NOT TESTED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.description,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = displayVal,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (rawVal.isNotBlank()) {
                                    pendingAuthMode = "COPY"
                                    showAuthDialog = true
                                } else {
                                    Toast.makeText(context, "No key value set", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Secret", modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                if (!isRevealed) {
                                    pendingAuthMode = "EYE"
                                    showAuthDialog = true
                                } else {
                                    isRevealed = false
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Secret Visibility",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onTestClick,
                    modifier = Modifier.testTag("test_key_button_${entry.keyName}"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test", fontSize = 11.sp)
                }
            }

            // Status message detail
            when (val status = state.status) {
                is CredentialStatus.Connected -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Status: ${status.message}",
                        fontSize = 11.sp,
                        color = Color(0xFF047857),
                        fontFamily = FontFamily.Monospace
                    )
                }
                is CredentialStatus.Error -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Status: ${status.message}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> {}
            }
        }
    }
}

