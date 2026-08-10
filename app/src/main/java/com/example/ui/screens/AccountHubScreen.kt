package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.credential.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountHubScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialStates by CredentialRegistry.credentialStates.collectAsState()

    var showAddCustomDialog by remember { mutableStateOf(false) }
    var newCustomKeyName by remember { mutableStateOf("") }
    var newCustomKeyValue by remember { mutableStateOf("") }

    var selectedCategoryFilter by remember { mutableStateOf<CredentialCategory?>(null) }

    LaunchedEffect(Unit) {
        CredentialRegistry.refreshAll(context)
    }

    val filteredList = remember(credentialStates, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) {
            credentialStates
        } else {
            credentialStates.filter { it.entry.category == selectedCategoryFilter }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Account Hub & Security Vault",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Thrivebridge Growth Solutions • AES-256 Encrypted at Rest",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("account_hub_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                CredentialRegistry.testAllCredentials(context)
                            }
                        },
                        modifier = Modifier.testTag("account_hub_test_all_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Test All Credentials", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddCustomDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("account_hub_add_custom_key_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Custom Secret")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface)
                .testTag("account_hub_screen")
        ) {
            // Security Badge Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "AES-256 Vault",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Encrypted Hardware Credential Vault",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Google AI, LinkedIn OAuth, ElevenLabs, and Upwork RSS keys are securely stored using Android EncryptedSharedPreferences.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Category Filter Row
            ScrollableTabRow(
                selectedTabIndex = if (selectedCategoryFilter == null) 0 else CredentialCategory.values().indexOf(selectedCategoryFilter) + 1,
                edgePadding = 16.dp,
                divider = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedCategoryFilter == null,
                    onClick = { selectedCategoryFilter = null },
                    text = { Text("All Secrets (${credentialStates.size})", fontSize = 12.sp) }
                )
                CredentialCategory.values().forEach { category ->
                    val count = credentialStates.count { it.entry.category == category }
                    Tab(
                        selected = selectedCategoryFilter == category,
                        onClick = { selectedCategoryFilter = category },
                        text = { Text("${category.title} ($count)", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Credentials List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredList, key = { it.entry.keyName }) { state ->
                    AccountHubKeyCard(
                        state = state,
                        onSave = { newValue ->
                            scope.launch {
                                CredentialRegistry.saveCredential(state.entry.keyName, newValue, context)
                            }
                        },
                        onTest = {
                            scope.launch {
                                CredentialRegistry.testSingleCredential(state.entry.keyName, context)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddCustomDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomDialog = false },
            title = { Text("Add Custom Secret / API Token") },
            text = {
                Column {
                    Text("Enter key identifier (e.g., LINKEDIN_CLIENT_SECRET, ELEVENLABS_VOICE_ID):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newCustomKeyName,
                        onValueChange = { newCustomKeyName = it },
                        label = { Text("Key Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newCustomKeyValue,
                        onValueChange = { newCustomKeyValue = it },
                        label = { Text("Secret Value") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCustomKeyName.isNotBlank() && newCustomKeyValue.isNotBlank()) {
                            scope.launch {
                                CredentialRegistry.addCustomKey(newCustomKeyName, newCustomKeyValue, context)
                                showAddCustomDialog = false
                                newCustomKeyName = ""
                                newCustomKeyValue = ""
                            }
                        }
                    }
                ) {
                    Text("Save Secret")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountHubKeyCard(
    state: CredentialState,
    onSave: (String) -> Unit,
    onTest: () -> Unit
) {
    var editValue by remember(state.rawValue) { mutableStateOf(state.rawValue) }
    var isMasked by remember { mutableStateOf(true) }
    var isEditing by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("account_hub_card_${state.entry.keyName}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.entry.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = state.entry.keyName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                StatusBadge(status = state.status)
            }

            if (state.entry.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.entry.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = editValue,
                onValueChange = {
                    editValue = it
                    isEditing = true
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (isMasked) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    IconButton(onClick = { isMasked = !isMasked }) {
                        Icon(
                            imageVector = if (isMasked) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Visibility"
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTest,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection", fontSize = 11.sp)
                }

                if (isEditing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave(editValue)
                            isEditing = false
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Secret", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: CredentialStatus) {
    val (bgColor, textColor, labelText) = when (status) {
        is CredentialStatus.Connected -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), status.message)
        is CredentialStatus.Testing -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "Testing Connection...")
        is CredentialStatus.Error -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), status.message)
        CredentialStatus.NotConfigured -> Triple(Color(0xFFF3F4F6), Color(0xFF4B5563), "Not Configured")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Text(
            text = labelText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
