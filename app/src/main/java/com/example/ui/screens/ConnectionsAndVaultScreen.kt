package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.learning.PromotionLifecycleState
import com.example.data.ai.learning.WastiCloudLearningManager
import com.example.data.auth.AuthProviderType
import com.example.data.auth.GoogleAuthClient
import com.example.data.auth.GoogleAuthResult
import com.example.data.auth.WastiIdentityManager
import com.example.data.credential.CredentialCategory
import com.example.data.credential.CredentialEntry
import com.example.data.credential.CredentialRegistry
import com.example.data.credential.CredentialState
import com.example.data.credential.CredentialStatus
import com.example.data.db.IntegrationEntity
import com.example.data.db.SystemLogEntity
import com.example.data.security.WastiIntentSafetyAuditor
import com.example.security.BiometricSecurityManager
import com.example.security.findFragmentActivity
import kotlinx.coroutines.launch

/**
 * Unified Connections & Secret Vault Screen.
 * 
 * Securely unifies:
 * 1. Account Hub & Multi-Provider Identity (Google, Microsoft, GitHub, Meta, etc.)
 * 2. Service Connectors & Integrations
 * 3. Hardware Keystore Secret Vault (Zero-Leakage doctrine)
 * 4. Multi-Tenant Cloud Learning & Skill Promotion Layer
 * 5. Owner Admin Security Alerts & Policy Enforcement
 * 
 * Exposed through Developer Mode with biometric/PIN authorization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsAndVaultScreen(
    integrations: List<IntegrationEntity> = emptyList(),
    logs: List<SystemLogEntity> = emptyList(),
    onClearLogs: () -> Unit = {},
    onToggleIntegration: (String, Boolean) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Identity, 1: Connectors, 2: Secret Vault, 3: Cloud Learning, 4: Admin Alerts & Logs
    var ghostTapCount by remember { mutableIntStateOf(0) }
    var isDevModeUnlocked by remember { mutableStateOf(BiometricSecurityManager.isDevModeUnlocked(context)) }
    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var isAuthenticating by remember { mutableStateOf(false) }

    val profile by WastiIdentityManager.currentProfile.collectAsState()
    val credentialStates by CredentialRegistry.credentialStates.collectAsState()
    val learningProposals by WastiCloudLearningManager.pendingProposals.collectAsState()
    val globalSkills by WastiCloudLearningManager.canonicalGlobalSkills.collectAsState()
    val safetyAlerts by WastiIntentSafetyAuditor.pendingAlerts.collectAsState()

    var selectedCategoryFilter by remember { mutableStateOf<CredentialCategory?>(null) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    var newCustomKeyName by remember { mutableStateOf("") }
    var newCustomKeyValue by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        WastiIdentityManager.initialize(context)
        CredentialRegistry.refreshAll(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            ghostTapCount++
                            if (ghostTapCount >= 5 && !isDevModeUnlocked) {
                                ghostTapCount = 0
                                showPinDialog = true
                            }
                        }
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDevModeUnlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isDevModeUnlocked) Icons.Default.VpnKey else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isDevModeUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Connections & Secret Vault",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (profile?.isVerifiedOwner == true) "👑 Verified Owner • Developer Mode" else "Operator • Sovereign Vault",
                                fontSize = 10.sp,
                                color = if (profile?.isVerifiedOwner == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isDevModeUnlocked) {
                        TextButton(
                            onClick = { showPinDialog = true },
                            modifier = Modifier.testTag("unlock_dev_mode_btn")
                        ) {
                            Text("Unlock Dev", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        IconButton(onClick = {
                            isDevModeUnlocked = false
                            BiometricSecurityManager.setDevModeUnlocked(context, false)
                            Toast.makeText(context, "Developer Mode locked.", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.LockClock, contentDescription = "Lock Dev Mode")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("connections_and_vault_screen")
        ) {
            // Tab Header Row
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {}
            ) {
                listOf(
                    "Identity & Auth" to Icons.Default.Person,
                    "Connectors (${integrations.count { it.isConnected }})" to Icons.Default.Extension,
                    "Secret Vault" to Icons.Default.VpnKey,
                    "Cloud Learning" to Icons.Default.Hub,
                    "Admin Alerts (${safetyAlerts.count { !it.isResolved }})" to Icons.Default.Security
                ).forEachIndexed { idx, (label, icon) ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(label, fontSize = 11.sp, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.testTag("vault_tab_$idx")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            when (selectedTab) {
                0 -> IdentityTabContent(
                    profile = profile,
                    isAuthenticating = isAuthenticating,
                    onGoogleSignIn = {
                        scope.launch {
                            isAuthenticating = true
                            val client = GoogleAuthClient(context)
                            val res = client.signIn()
                            isAuthenticating = false
                            when (res) {
                                is GoogleAuthResult.Success -> {
                                    val u = res.user
                                    WastiIdentityManager.onAuthenticated(
                                        context = context,
                                        userId = u.uid,
                                        email = u.email,
                                        displayName = u.displayName,
                                        photoUrl = u.photoUrl?.toString(),
                                        provider = AuthProviderType.GOOGLE
                                    )
                                    Toast.makeText(context, "Welcome ${u.displayName ?: "Leader"}!", Toast.LENGTH_SHORT).show()
                                }
                                is GoogleAuthResult.Error -> {
                                    Toast.makeText(context, "Sign-in failed: ${res.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onSignOut = {
                        scope.launch {
                            GoogleAuthClient(context).signOut()
                            WastiIdentityManager.signOut(context)
                            Toast.makeText(context, "Signed out.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> ConnectorsTabContent(
                    integrations = integrations,
                    onToggle = onToggleIntegration
                )
                2 -> SecretVaultTabContent(
                    credentialStates = credentialStates,
                    categoryFilter = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it },
                    isDevUnlocked = isDevModeUnlocked,
                    onAddCustom = { showAddCustomDialog = true }
                )
                3 -> CloudLearningTabContent(
                    pendingProposals = learningProposals,
                    globalSkills = globalSkills,
                    isOwner = profile?.isVerifiedOwner == true
                )
                4 -> AdminAlertsAndLogsTabContent(
                    alerts = safetyAlerts,
                    logs = logs,
                    isOwner = profile?.isVerifiedOwner == true,
                    onClearLogs = onClearLogs
                )
            }
        }
    }

    // Dev Mode PIN Unlock Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Enter Developer PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!BiometricSecurityManager.isPinConfigured(context)) {
                        Text(
                            "No Developer PIN enrolled. Authenticate via Biometrics to enroll your PIN and unlock.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Enter Developer PIN (or authenticate via Biometrics) to access privileged development tools.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = { if (it.length <= 10 && it.all { ch -> ch.isDigit() }) enteredPin = it },
                        label = { Text(if (!BiometricSecurityManager.isPinConfigured(context)) "New PIN (4-10 digits)" else "Developer PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (!BiometricSecurityManager.isPinConfigured(context)) {
                        Toast.makeText(context, "PIN not enrolled. Use Biometric button to enroll PIN.", Toast.LENGTH_SHORT).show()
                    } else if (BiometricSecurityManager.verifyPin(context, enteredPin)) {
                        isDevModeUnlocked = true
                        BiometricSecurityManager.setDevModeUnlocked(context, true)
                        showPinDialog = false
                        enteredPin = ""
                        Toast.makeText(context, "Developer Mode unlocked (15-min privileged session).", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid PIN.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(if (!BiometricSecurityManager.isPinConfigured(context)) "Enroll (Use Biometric)" else "Unlock")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val act = context.findFragmentActivity()
                    if (act != null) {
                        BiometricSecurityManager.authenticate(
                            activity = act,
                            title = "Developer Authentication",
                            subtitle = "Verify thumbprint to unlock Developer Mode",
                            onSuccess = {
                                if (!BiometricSecurityManager.isPinConfigured(context) && enteredPin.length in 4..10 && enteredPin.all { ch -> ch.isDigit() }) {
                                    BiometricSecurityManager.enrollPinWithAuthorization(context, enteredPin, true)
                                    Toast.makeText(context, "PIN enrolled with biometric authorization.", Toast.LENGTH_SHORT).show()
                                }
                                isDevModeUnlocked = true
                                BiometricSecurityManager.setDevModeUnlocked(context, true)
                                showPinDialog = false
                                enteredPin = ""
                                Toast.makeText(context, "Biometric verified! Dev Mode unlocked (15-min session).", Toast.LENGTH_SHORT).show()
                            },
                            onError = { err ->
                                Toast.makeText(context, "Authentication failed: $err", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }) {
                    Text("Biometric")
                }
            }
        )
    }

    // Add Custom Credential Dialog
    if (showAddCustomDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomDialog = false },
            title = { Text("Store Secret in Android Keystore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Secrets are encrypted via AES-256-GCM in hardware-backed Keystore. Never logged or leaked in APK assets.", fontSize = 11.sp)
                    OutlinedTextField(
                        value = newCustomKeyName,
                        onValueChange = { newCustomKeyName = it },
                        label = { Text("Key Name (e.g. CUSTOM_API_KEY)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newCustomKeyValue,
                        onValueChange = { newCustomKeyValue = it },
                        label = { Text("Secret Value") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newCustomKeyName.isNotBlank() && newCustomKeyValue.isNotBlank()) {
                        scope.launch {
                            CredentialRegistry.saveCredential(newCustomKeyName.trim().uppercase(), newCustomKeyValue.trim(), context)
                            showAddCustomDialog = false
                            newCustomKeyName = ""
                            newCustomKeyValue = ""
                            Toast.makeText(context, "Secret securely persisted in Keystore!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) {
                    Text("Encrypt & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun IdentityTabContent(
    profile: com.example.data.auth.WastiIdentityProfile?,
    isAuthenticating: Boolean,
    onGoogleSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(profile?.displayName ?: "Guest Explorer", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(profile?.email ?: "No email linked", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (profile?.isVerifiedOwner == true) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = if (profile?.isVerifiedOwner == true) "👑 Verified Wasti Owner" else "Role: ${profile?.role?.name ?: "GUEST"}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (profile?.isVerifiedOwner == true) Color(0xFF047857) else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Device Keystore Identity Fingerprint:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(profile?.publicKeyBase64?.take(32) ?: "Initializing...", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onGoogleSignIn,
                            enabled = !isAuthenticating,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isAuthenticating) "Connecting..." else "Sign in with Google", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Sign Out", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item {
            Text("Pluggable Enterprise & Social Providers", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Connect additional identity providers to bind cryptographic capabilities to your unified Wasti ID:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val providers = listOf(
            AuthProviderType.MICROSOFT to "Active Directory / Azure OIDC",
            AuthProviderType.GITHUB to "Developer fine-grained PAT & OAuth",
            AuthProviderType.META to "Meta Business & Graph API",
            AuthProviderType.INSTAGRAM to "Content & Creator Automation",
            AuthProviderType.WHATSAPP to "WhatsApp Cloud Business Webhook",
            AuthProviderType.TIKTOK to "Creator Studio & Video Dispatch",
            AuthProviderType.SNAPCHAT to "Snap Kit & Camera Kit Integration"
        )

        items(providers) { (prov, desc) ->
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(prov.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(
                        onClick = { /* Ready for provider OAuth flow */ },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("Connect", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorsTabContent(
    integrations: List<IntegrationEntity>,
    onToggle: (String, Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Active Service Connectors", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Wasti links with external platforms through strictly scoped client credentials:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        items(integrations) { item ->
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.serviceName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(item.provider, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = item.isConnected,
                        onCheckedChange = { onToggle(item.id, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecretVaultTabContent(
    credentialStates: List<CredentialState>,
    categoryFilter: CredentialCategory?,
    onSelectCategory: (CredentialCategory?) -> Unit,
    isDevUnlocked: Boolean,
    onAddCustom: () -> Unit
) {
    val filtered = remember(credentialStates, categoryFilter) {
        if (categoryFilter == null) credentialStates else credentialStates.filter { it.entry.category == categoryFilter }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Hardware Keystore Vault", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(if (isDevUnlocked) "Unlocked: AES-256 Encrypted" else "Locked: Read-Only Status", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isDevUnlocked) {
                    Button(onClick = onAddCustom, shape = RoundedCornerShape(10.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Secret", fontSize = 11.sp)
                    }
                }
            }
        }

        // Category Filter Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = categoryFilter == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All (${credentialStates.size})", fontSize = 11.sp) }
                    )
                }
                items(CredentialCategory.values()) { cat ->
                    val count = credentialStates.count { it.entry.category == cat }
                    FilterChip(
                        selected = categoryFilter == cat,
                        onClick = { onSelectCategory(cat) },
                        label = { Text("${cat.title} ($count)", fontSize = 11.sp) }
                    )
                }
            }
        }

        items(filtered) { state ->
            val entry: CredentialEntry = state.entry
            val isPersisted = state.status is CredentialStatus.Connected || (state.rawValue.isNotBlank() && !CredentialRegistry.isPlaceholder(state.rawValue))
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(entry.keyName, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (isPersisted) "🔒 Persisted in Keystore" else "⚪ Unconfigured (Fail-Closed)",
                            fontSize = 10.sp,
                            color = if (isPersisted) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = if (isPersisted) Color(0xFF10B981).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPersisted) Icons.Default.Check else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isPersisted) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudLearningTabContent(
    pendingProposals: List<com.example.data.ai.learning.PromotionProposal>,
    globalSkills: List<com.example.data.ai.learning.PromotionProposal>,
    isOwner: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Multi-Tenant Cloud Knowledge Architecture", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Promotes verified skills & autonomous improvements while strictly isolating personal memory & private data.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Text("Pending Local Proposals (${pendingProposals.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        if (pendingProposals.isEmpty()) {
            item {
                Text("No pending capability promotion proposals. Verified execution outcomes will appear here automatically.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(pendingProposals) { prop ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(prop.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(prop.state.name, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(prop.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Provenance Hash: ${prop.provenanceHash.take(16)}...", fontSize = 9.sp, fontFamily = FontFamily.Monospace)

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!prop.userConsentGranted) {
                                Button(
                                    onClick = { WastiCloudLearningManager.grantUserConsent(prop.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Grant Consent", fontSize = 11.sp)
                                }
                            } else if (prop.state == PromotionLifecycleState.CONSENT_GRANTED) {
                                Button(
                                    onClick = { WastiCloudLearningManager.verifyPolicy(prop.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Verify Policy", fontSize = 11.sp)
                                }
                            } else if (prop.state == PromotionLifecycleState.POLICY_VERIFIED && isOwner) {
                                Button(
                                    onClick = { WastiCloudLearningManager.approveAndPromoteGlobally(prop.id) },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("👑 Owner Promote", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(10.dp))
            Text("Canonical Global Skills (${globalSkills.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        items(globalSkills) { skill ->
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(skill.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("✔ Distributed Canonical Asset (Signed by Wasti Owner)", fontSize = 10.sp, color = Color(0xFF10B981))
                }
            }
        }
    }
}

@Composable
private fun AdminAlertsAndLogsTabContent(
    alerts: List<com.example.data.security.OwnerAdminSafetyAlert>,
    logs: List<SystemLogEntity>,
    isOwner: Boolean,
    onClearLogs: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Owner Admin Safety Escalations", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Real-time safety evaluations for high-risk operations requiring explicit Owner authorization:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (alerts.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("✔ All operations within verified safety boundaries. Zero pending alerts.", fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                }
            }
        } else {
            items(alerts) { alert ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("⚠ ${alert.evaluation.summary}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text(alert.resolutionStatus, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Intent: \"${alert.intentText}\"", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        alert.evaluation.suggestedScopeConstraint?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Suggested Scope: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                        }

                        if (!alert.isResolved && isOwner) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { WastiIntentSafetyAuditor.resolveAlert(alert.id, "ACCEPTED") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Accept", fontSize = 10.sp)
                                }
                                Button(
                                    onClick = { WastiIntentSafetyAuditor.resolveAlert(alert.id, "SCOPED", alert.evaluation.suggestedScopeConstraint) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Scope Limit", fontSize = 10.sp)
                                }
                                Button(
                                    onClick = { WastiIntentSafetyAuditor.resolveAlert(alert.id, "REJECTED") },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Reject", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("System Logs (${logs.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClearLogs) { Text("Clear Logs", fontSize = 11.sp) }
            }
        }

        items(logs.take(30)) { log ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "[${log.level}] ${log.source}: ${log.message}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
