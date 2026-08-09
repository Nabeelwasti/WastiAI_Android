package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.credential.CredentialCategory
import com.example.data.credential.CredentialRegistry
import com.example.data.credential.CredentialState
import com.example.data.credential.CredentialStatus
import com.example.data.db.WastiDatabase
import com.example.data.drive.DriveSyncEngine
import com.example.data.drive.DriveSyncStatus
import com.example.data.security.WastiSecurityManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.service.WastiFloatingService
import com.example.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    selectedModel: String = "groq-llama-3.3-70b",
    onSelectModel: (String) -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("wasti_prefs", Context.MODE_PRIVATE) }

    val credentialStates by settingsViewModel.credentialStates.collectAsState()
    val isTestingMap by settingsViewModel.isTestingMap.collectAsState()
    val statusMessageMap by settingsViewModel.statusMessageMap.collectAsState()

    var enableExtraVoiceModels by remember { mutableStateOf(prefs.getBoolean("enable_extra_voice_models", false)) }
    var searchQuery by remember { mutableStateOf("") }
    var isVaultUnlocked by remember { mutableStateOf(false) }
    var showPasscodeAuthDialog by remember { mutableStateOf(false) }
    var enteredPasscode by remember { mutableStateOf("") }
    var vaultAuthError by remember { mutableStateOf<String?>(null) }

    // Dialog for adding dynamic custom keys
    var showAddCustomKeyDialog by remember { mutableStateOf(false) }
    var newCustomKeyName by remember { mutableStateOf("") }
    var newCustomKeyValue by remember { mutableStateOf("") }

    // Hidden Developer Panel state
    var devTapCount by remember { mutableIntStateOf(0) }
    var showDeveloperPanel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settingsViewModel.refresh(context)
    }

    if (showDeveloperPanel) {
        DeveloperLogsDialog(
            onDismiss = { showDeveloperPanel = false }
        )
    }

    if (showPasscodeAuthDialog) {
        AlertDialog(
            onDismissRequest = { showPasscodeAuthDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vault Authentication")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter Mobile PIN / Password to unlock sensitive credentials and full API Key Vault.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = enteredPasscode,
                        onValueChange = { enteredPasscode = it },
                        label = { Text("Passcode / PIN") },
                        placeholder = { Text("e.g. 1234 or your PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (vaultAuthError != null) {
                        Text(text = vaultAuthError!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (WastiSecurityManager.verifyPasscode(context, enteredPasscode.trim())) {
                            isVaultUnlocked = true
                            showPasscodeAuthDialog = false
                            enteredPasscode = ""
                            vaultAuthError = null
                        } else {
                            vaultAuthError = "Incorrect PIN / Passcode. Try '1234' or your custom PIN."
                        }
                    }
                ) {
                    Text("Verify & Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasscodeAuthDialog = false
                        enteredPasscode = ""
                        vaultAuthError = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddCustomKeyDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomKeyDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Custom Secret / API Key")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Add new API keys dynamically without modifying app source code. Custom secrets are encrypted and instantly accessible across all Wasti AI agents.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newCustomKeyName,
                        onValueChange = { newCustomKeyName = it },
                        label = { Text("Key Name") },
                        placeholder = { Text("e.g. REPLICATE_API_KEY, PINECONE_KEY") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("custom_key_name_input")
                    )
                    OutlinedTextField(
                        value = newCustomKeyValue,
                        onValueChange = { newCustomKeyValue = it },
                        label = { Text("Secret Value / Token") },
                        placeholder = { Text("Paste secret string here...") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("custom_key_value_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCustomKeyName.isNotBlank()) {
                            settingsViewModel.addCustomKey(newCustomKeyName, newCustomKeyValue, context)
                            showAddCustomKeyDialog = false
                            newCustomKeyName = ""
                            newCustomKeyValue = ""
                            Toast.makeText(context, "Custom secret added to Encrypted Vault!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = newCustomKeyName.isNotBlank()
                ) {
                    Text("Save Custom Key")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Wasti OS Settings & Security Vault",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Encrypted key storage, AI model configuration, and system preferences",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggleTheme) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = "Toggle Theme"
                    )
                }
            }
        }

        // SECTION 1: PRIMARY AI PROVIDERS VAULT
        item {
            Text(
                text = "Primary AI & Voice Model Credentials",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure distinct primary API keys below. All keys are encrypted at rest with EncryptedSharedPreferences.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            PrimaryProvidersCard(
                credentialStates = credentialStates,
                isTestingMap = isTestingMap,
                statusMessageMap = statusMessageMap,
                onSaveKey = { key, valStr -> settingsViewModel.saveKey(key, valStr, context) },
                onTestKey = { key -> settingsViewModel.testKey(key, context) }
            )
        }

        // SECTION 2: ADD DYNAMIC CUSTOM KEYS & FULL VAULT ACCESS
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Secret Vault & Dynamic Integration Keys", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        IconButton(
                            onClick = { showAddCustomKeyDialog = true },
                            modifier = Modifier.testTag("add_custom_key_button")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Custom Key", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add custom API keys dynamically without updating code. Access 35+ supported services or create new ones.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showAddCustomKeyDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Custom Secret")
                        }

                        OutlinedButton(
                            onClick = {
                                if (!isVaultUnlocked) {
                                    showPasscodeAuthDialog = true
                                } else {
                                    isVaultUnlocked = false
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("unlock_vault_button")
                        ) {
                            Icon(
                                imageVector = if (isVaultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isVaultUnlocked) "Lock Vault" else "Unlock Full Vault")
                        }
                    }

                    if (isVaultUnlocked) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        // Search Bar for Credentials
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search 35+ keys (e.g. Stripe, DeepSeek, Notion...)") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val filtered = credentialStates.filter { state ->
                            searchQuery.isBlank() ||
                                    state.entry.displayName.contains(searchQuery, ignoreCase = true) ||
                                    state.entry.keyName.contains(searchQuery, ignoreCase = true)
                        }

                        if (filtered.isEmpty()) {
                            Text(
                                text = "No matching credentials found.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                filtered.forEach { state ->
                                    CredentialItemCard(
                                        state = state,
                                        isTesting = isTestingMap[state.entry.keyName] == true,
                                        statusMsg = statusMessageMap[state.entry.keyName],
                                        onSave = { valStr -> settingsViewModel.saveKey(state.entry.keyName, valStr, context) },
                                        onTest = { settingsViewModel.testKey(state.entry.keyName, context) },
                                        onDeleteCustom = { settingsViewModel.deleteCustomKey(state.entry.keyName, context) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // SECTION 2.5: GOOGLE DRIVE BACKUP & CLOUD RESTORE
        item {
            GoogleDriveBackupCard()
        }

        // SECTION 2.6: OMNI-PRESENT FLOATING ACTION BUBBLE
        item {
            WastiFloatingBubbleCard()
        }

        // SECTION 3: VOICE & SPEECH PERSONA SETTINGS
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Voice Synthesis & Speech Personas", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Primary Assistant Voice", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "⚡ Wasti Natural Speech Engine / ElevenLabs Neural Voice", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "ACTIVE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Activate Multi-Gender Voice Personas", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(
                                text = "Enable Wasti Male/Female/Girl/Boy voice switching.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enableExtraVoiceModels,
                            onCheckedChange = { isChecked ->
                                enableExtraVoiceModels = isChecked
                                prefs.edit().putBoolean("enable_extra_voice_models", isChecked).apply()
                            }
                        )
                    }
                }
            }
        }

        // SECTION 4: SYSTEM INFO & SECURITY NOTICE
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        devTapCount++
                        if (devTapCount >= 5) {
                            devTapCount = 0
                            showDeveloperPanel = true
                            Toast.makeText(context, "Developer Panel Unlocked", Toast.LENGTH_SHORT).show()
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Architecture & Security Verification", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        IconButton(
                            onClick = {
                                showDeveloperPanel = true
                                Toast.makeText(context, "Developer Panel Unlocked", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = "Developer Panel", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Wasti OS 1.0.0 Architecture: Clean MVVM • Room DB • EncryptedSharedPreferences (AES-256 GCM).\n" +
                                "• Zero Plaintext Storage: API keys are stored in hardware-backed encrypted storage.\n" +
                                "• Direct Provider Abstraction: AIManager routes calls exclusively through validated API provider implementations.\n" +
                                "(Tap version details 5 times or tap bug icon to view hidden Developer Logs)",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// COMPOSABLE 1: PRIMARY API PROVIDERS VAULT CARD
@Composable
fun PrimaryProvidersCard(
    credentialStates: List<CredentialState>,
    isTestingMap: Map<String, Boolean>,
    statusMessageMap: Map<String, String>,
    onSaveKey: (String, String) -> Unit,
    onTestKey: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val primaryKeys = listOf(
                "GEMINI_API_KEY" to "Google AI Studio Gemini API Key",
                "OPENAI_API_KEY" to "OpenAI GPT-4o / GPT-5 API Key",
                "GROQ_API_KEY" to "Groq Ultra-Fast Llama 3.3 70B Key",
                "ELEVENLABS_API_KEY" to "ElevenLabs Neural Voice API Key"
            )

            primaryKeys.forEach { (keyName, title) ->
                val state = credentialStates.find { it.entry.keyName == keyName }
                val rawVal = state?.rawValue ?: ""
                val status = state?.status ?: CredentialStatus.NotConfigured
                val isTesting = isTestingMap[keyName] == true
                val statusMsg = statusMessageMap[keyName]

                PrimaryApiKeyField(
                    keyName = keyName,
                    title = title,
                    currentRawValue = rawVal,
                    status = status,
                    isTesting = isTesting,
                    statusMsg = statusMsg,
                    onSave = { newVal -> onSaveKey(keyName, newVal) },
                    onTest = { onTestKey(keyName) }
                )
            }
        }
    }
}

// COMPOSABLE 2: DISTINCT PRIMARY API KEY INPUT FIELD
@Composable
fun PrimaryApiKeyField(
    keyName: String,
    title: String,
    currentRawValue: String,
    status: CredentialStatus,
    isTesting: Boolean,
    statusMsg: String?,
    onSave: (String) -> Unit,
    onTest: () -> Unit
) {
    val context = LocalContext.current
    var inputVal by remember(currentRawValue) { mutableStateOf(currentRawValue) }
    var isEditing by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = keyName, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            // Status Badge
            val (badgeColor, badgeText) = when (status) {
                is CredentialStatus.Connected -> MaterialTheme.colorScheme.primary to "✅ Verified Connected"
                is CredentialStatus.Error -> MaterialTheme.colorScheme.error to "❌ Connection Error"
                is CredentialStatus.Testing -> MaterialTheme.colorScheme.tertiary to "Testing..."
                is CredentialStatus.NotConfigured -> {
                    if (currentRawValue.isNotBlank()) MaterialTheme.colorScheme.primary to "Key Configured"
                    else MaterialTheme.colorScheme.onSurfaceVariant to "Not Configured"
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val displayText = if (!isEditing && currentRawValue.isNotBlank() && !isPasswordVisible) {
            CredentialRegistry.maskKey(currentRawValue)
        } else {
            inputVal
        }

        OutlinedTextField(
            value = if (isEditing || isPasswordVisible) inputVal else displayText,
            onValueChange = {
                inputVal = it
                isEditing = true
            },
            label = { Text("API Key String") },
            placeholder = { Text("Enter $keyName...") },
            visualTransformation = if (isPasswordVisible || isEditing) VisualTransformation.None else PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentRawValue.isNotBlank()) {
                        IconButton(onClick = {
                            isPasswordVisible = !isPasswordVisible
                            if (isPasswordVisible) isEditing = true
                        }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Visibility",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (isEditing && inputVal != currentRawValue) {
                        IconButton(onClick = {
                            inputVal = currentRawValue
                            isEditing = false
                        }) {
                            Icon(Icons.Default.Undo, contentDescription = "Cancel Edits", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("${keyName.lowercase()}_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onSave(inputVal)
                    isEditing = false
                    Toast.makeText(context, "$keyName saved securely!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).testTag("save_${keyName.lowercase()}_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save Key", fontSize = 11.sp)
            }

            OutlinedButton(
                onClick = {
                    if (inputVal != currentRawValue) {
                        onSave(inputVal)
                        isEditing = false
                    }
                    onTest()
                },
                enabled = !isTesting && (inputVal.isNotBlank() || currentRawValue.isNotBlank()),
                modifier = Modifier.weight(1f).testTag("test_${keyName.lowercase()}_button")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Testing...", fontSize = 11.sp)
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Connection", fontSize = 11.sp)
                }
            }
        }

        if (!statusMsg.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusMsg,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// COMPOSABLE 3: CREDENTIAL ITEM CARD FOR FULL VAULT
@Composable
fun CredentialItemCard(
    state: CredentialState,
    isTesting: Boolean,
    statusMsg: String?,
    onSave: (String) -> Unit,
    onTest: () -> Unit,
    onDeleteCustom: () -> Unit
) {
    val context = LocalContext.current
    var inputVal by remember(state.rawValue) { mutableStateOf(state.rawValue) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    val isCustomKey = state.entry.description == "Custom User API Secret / Integration Token"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = state.entry.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(text = state.entry.keyName, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }

                val (statusColor, statusText) = when (val st = state.status) {
                    is CredentialStatus.Connected -> MaterialTheme.colorScheme.primary to st.message
                    is CredentialStatus.Error -> MaterialTheme.colorScheme.error to st.message
                    is CredentialStatus.Testing -> MaterialTheme.colorScheme.tertiary to "Testing..."
                    is CredentialStatus.NotConfigured -> {
                        if (inputVal.isNotBlank()) MaterialTheme.colorScheme.primary to "Configured"
                        else MaterialTheme.colorScheme.onSurfaceVariant to "Not Configured"
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val displayText = if (!isPasswordVisible && inputVal.isNotBlank()) {
                CredentialRegistry.maskKey(inputVal)
            } else {
                inputVal
            }

            OutlinedTextField(
                value = if (isPasswordVisible) inputVal else displayText,
                onValueChange = { inputVal = it },
                label = { Text("Secret Value") },
                placeholder = { Text("Enter ${state.entry.keyName}...") },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Visibility",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSave(inputVal)
                        Toast.makeText(context, "${state.entry.keyName} Saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = {
                        if (inputVal != state.rawValue) {
                            onSave(inputVal)
                        }
                        onTest()
                    },
                    enabled = !isTesting && inputVal.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test", fontSize = 11.sp)
                    }
                }

                if (isCustomKey) {
                    IconButton(onClick = onDeleteCustom) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Custom Key", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (!statusMsg.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = statusMsg, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperLogsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val db = remember(context) { WastiDatabase.getDatabase(context) }
    val logs by db.developerLogDao().getAllLogs().collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Developer Panel - Provider Logs", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = "${logs.size} Logs",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                Text(
                    text = "Silent failure logs captured during background multi-lane AI provider orchestration.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No provider errors recorded. All AI engines functioning properly.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(logs) { log ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = log.providerId.uppercase(),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        val sdf = remember { java.text.SimpleDateFormat("HH:mm:ss - MMM dd", java.util.Locale.US) }
                                        Text(
                                            text = sdf.format(java.util.Date(log.timestamp)),
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = log.errorMessage,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    if (!log.details.isNullOrBlank() && log.details != log.errorMessage) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = log.details,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            if (logs.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            db.developerLogDao().clearLogs()
                            Toast.makeText(context, "Developer logs cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Logs", fontSize = 11.sp)
                }
            }
        }
    )
}

@Composable
fun GoogleDriveBackupCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val syncStatus by DriveSyncEngine.syncStatus.collectAsState()

    var tokenInput by remember { mutableStateOf(DriveSyncEngine.getAccessToken(context) ?: "") }
    var isBackupLoading by remember { mutableStateOf(false) }
    var isRestoreLoading by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Google Drive Cloud Backup", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                // Sync status badge
                val (badgeColor, badgeText) = when (val st = syncStatus) {
                    is DriveSyncStatus.Synced -> MaterialTheme.colorScheme.primary to "Synced (HTTP 200 OK)"
                    is DriveSyncStatus.Syncing -> MaterialTheme.colorScheme.tertiary to "Syncing..."
                    is DriveSyncStatus.Error -> MaterialTheme.colorScheme.error to "Sync Error"
                    is DriveSyncStatus.Connecting -> MaterialTheme.colorScheme.tertiary to "Connecting..."
                    is DriveSyncStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to "Ready"
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Securely back up chat history, AI memories, and app settings to your personal Google Drive (Scope: https://www.googleapis.com/auth/drive.file).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = tokenInput,
                onValueChange = {
                    tokenInput = it
                    DriveSyncEngine.saveAccessToken(context, it)
                },
                label = { Text("Google OAuth Access Token / Auth Key") },
                placeholder = { Text("Paste OAuth Access Token or Auth Token...") },
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (tokenInput.isNotBlank()) {
                        IconButton(onClick = {
                            tokenInput = ""
                            DriveSyncEngine.clearAccessToken(context)
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Token", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("google_drive_token_input")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Show Synced details if synced
            if (syncStatus is DriveSyncStatus.Synced) {
                val synced = syncStatus as DriveSyncStatus.Synced
                val sdf = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US) }
                val formattedTime = sdf.format(java.util.Date(synced.lastSyncTime))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup Verified on Google Drive", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("• Drive File ID: ${synced.fileId}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• Backup Size: ${synced.backupSizeBytes} bytes", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• Verified at: $formattedTime", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            } else if (syncStatus is DriveSyncStatus.Error) {
                val err = syncStatus as DriveSyncStatus.Error
                Text(
                    text = err.message,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isBackupLoading = true
                            val token = tokenInput.ifBlank { "google_oauth_drive_token_${System.currentTimeMillis()}" }
                            val res = DriveSyncEngine.performBackupToDrive(context, token)
                            isBackupLoading = false
                            if (res is DriveSyncStatus.Synced) {
                                Toast.makeText(context, "Google Drive Backup Successful! HTTP 200 OK", Toast.LENGTH_SHORT).show()
                            } else if (res is DriveSyncStatus.Error) {
                                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isBackupLoading,
                    modifier = Modifier.weight(1f).testTag("connect_google_drive_button")
                ) {
                    if (isBackupLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Backing up...", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Connect & Backup", fontSize = 11.sp)
                    }
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isRestoreLoading = true
                            val token = tokenInput.ifBlank { "google_oauth_drive_token_${System.currentTimeMillis()}" }
                            val (success, msg) = DriveSyncEngine.restoreFromDrive(context, token)
                            isRestoreLoading = false
                            restoreMessage = msg
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isRestoreLoading,
                    modifier = Modifier.weight(1f).testTag("restore_google_drive_button")
                ) {
                    if (isRestoreLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restoring...", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restore Data", fontSize = 11.sp)
                    }
                }
            }

            if (!restoreMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = restoreMessage!!,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun WastiFloatingBubbleCard() {
    val context = LocalContext.current
    var isFloatingActive by remember { mutableStateOf(WastiFloatingService.isRunning) }
    var hasOverlayPermission by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PictureInPicture,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Omni-Present Floating Action Bubble",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Control Wasti AI while inside other applications (like WhatsApp or Chrome). Tap the floating bubble anywhere on screen to execute background voice commands.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Overlay Bubble",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = if (hasOverlayPermission) "SYSTEM_ALERT_WINDOW Permission Active" else "Requires Overlay Permission",
                        fontSize = 11.sp,
                        color = if (hasOverlayPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Switch(
                    checked = isFloatingActive,
                    onCheckedChange = { checked ->
                        if (checked) {
                            WastiFloatingService.start(context)
                            isFloatingActive = WastiFloatingService.isRunning
                        } else {
                            WastiFloatingService.stop(context)
                            isFloatingActive = false
                        }
                    }
                )
            }

            if (!hasOverlayPermission) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Grant Overlay Permission in System Settings")
                }
            }
        }
    }
}

