package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.core.*
import kotlinx.coroutines.launch

/**
 * [The Eternal Manifesto: Pilot and Spacecraft Principle & Memory of the Human]
 *
 * First-Run Autonomous Personalization & Production Gate Resolution Wizard:
 * 1. Profiles physical host silicon, RAM, battery, thermals, and polyglot toolchains.
 * 2. Learns user mission, role, and compute topology preferences.
 * 3. Autonomously generates on-device 4096-bit Sovereign Release Keystore.
 * 4. Establishes Sovereign Cloud Tunnel / Ingress for companion backend.
 * 5. Commits configuration into Room memory and knowledge graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunSetupWizardDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedRole by remember { mutableStateOf(UserPrimaryRole.SOFTWARE_ENGINEER) }
    var selectedCompute by remember { mutableStateOf(SwarmComputePreference.AGGRESSIVE_MESH_SWARM) }
    var autoCreateKeystore by remember { mutableStateOf(true) }
    var autoDeployCloudTunnel by remember { mutableStateOf(true) }
    var customInstructions by remember { mutableStateOf("") }

    var isExecuting by remember { mutableStateOf(false) }
    var executionStatusText by remember { mutableStateOf<String?>(null) }
    var completedResultText by remember { mutableStateOf<String?>(null) }

    var isFaceEnrolled by remember { mutableStateOf(false) }
    var isScanningFace by remember { mutableStateOf(false) }
    var faceSignatureText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isFaceEnrolled = WastiBiometricFaceEngine.isFaceEnrolled(context)
    }

    val cameraFaceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            coroutineScope.launch {
                isScanningFace = true
                val res = WastiBiometricFaceEngine.enrollUserFace(context, bitmap)
                isScanningFace = false
                if (res.isSuccess) {
                    isFaceEnrolled = true
                    faceSignatureText = res.faceSignatureHash
                    android.widget.Toast.makeText(context, "Commander Face Enrolled & Secured Locally!", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "Face enrollment error: ${res.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val allWizardPermissions = remember {
        buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            add(android.Manifest.permission.READ_CALENDAR)
            add(android.Manifest.permission.WRITE_CALENDAR)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
                add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_SCAN)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    var hasAllPermissionsGranted by remember {
        mutableStateOf(
            allWizardPermissions.all { perm ->
                androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val wizardPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasAllPermissionsGranted = allWizardPermissions.all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    var isAccessibilityActive by remember {
        mutableStateOf(com.example.assistant.SpecialPermissionHelper.isAccessibilityServiceEnabled(context))
    }
    var isOverlayActive by remember {
        mutableStateOf(com.example.assistant.SpecialPermissionHelper.canDrawOverlays(context))
    }
    var isBatteryOptimizationIgnored by remember {
        mutableStateOf(com.example.assistant.SpecialPermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    val refreshPermissions = {
        hasAllPermissionsGranted = allWizardPermissions.all { perm ->
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        isAccessibilityActive = com.example.assistant.SpecialPermissionHelper.isAccessibilityServiceEnabled(context)
        isOverlayActive = com.example.assistant.SpecialPermissionHelper.canDrawOverlays(context)
        isBatteryOptimizationIgnored = com.example.assistant.SpecialPermissionHelper.isIgnoringBatteryOptimizations(context)
    }

    val hardwareProfile = remember {
        WastiDeepHardwareProfiler.profileSystem(context)
    }

    Dialog(
        onDismissRequest = {
            if (!isExecuting) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RocketLaunch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "WASTI AI OS ONBOARDING",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Pilot & Spacecraft Autonomous Configuration",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // Hardware Reality Profile Banner
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "SILICON & HARDWARE REALITY DETECTED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "• Device: ${hardwareProfile.identity.manufacturer.uppercase()} ${hardwareProfile.identity.model} (${hardwareProfile.identity.board})\n" +
                                        "• CPU: ${hardwareProfile.cpu.availableCores} Cores • ${hardwareProfile.cpu.primaryArchitecture} (${if (hardwareProfile.cpu.is64Bit) "64-Bit" else "32-Bit"})\n" +
                                        "• Memory: ${hardwareProfile.memory.totalRamMb} MB total (${hardwareProfile.memory.availableRamMb} MB free)\n" +
                                        "• Storage: ${hardwareProfile.storage.freeInternalStorageMb / 1024} GB free / ${hardwareProfile.storage.totalInternalStorageMb / 1024} GB total\n" +
                                        "• Power & Thermals: ${hardwareProfile.power.batteryPercentage}% battery (${if (hardwareProfile.power.isCharging) "Charging" else "Discharging"}) • ${if (hardwareProfile.power.thermalThrottlingActive) "Throttled" else "Nominal"}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val tools = mutableListOf<String>()
                            if (hardwareProfile.toolchains.hasPython) tools.add("Python")
                            if (hardwareProfile.toolchains.hasNode) tools.add("Node.js")
                            if (hardwareProfile.toolchains.hasGit) tools.add("Git")
                            if (hardwareProfile.toolchains.hasClang) tools.add("Clang/C++")
                            if (hardwareProfile.toolchains.hasSqlite) tools.add("SQLite")

                            if (tools.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Toolchains:", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    tools.forEach { tool ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = tool,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Device System Permissions Card
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (hasAllPermissionsGranted) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (hasAllPermissionsGranted) Icons.Default.CheckCircle else Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (hasAllPermissionsGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (hasAllPermissionsGranted) "✓ System Permissions Granted" else "Device Permissions (1-Tap Activation)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (hasAllPermissionsGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (hasAllPermissionsGranted)
                                            "Audio, Camera, Notifications, and Storage are fully enabled for autonomous background intelligence."
                                        else
                                            "Audio (voice), Camera (face scan), Notifications (thinking/progress), Storage (weights). Tap below to accept via system prompt.",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!hasAllPermissionsGranted) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        wizardPermissionLauncher.launch(allWizardPermissions.toTypedArray())
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Grant All Permissions (1-Tap)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Question 1: Pilot Role
                    Text(
                        text = "1. What is your primary mission & role?",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val roleOptions = listOf(
                        UserPrimaryRole.SOFTWARE_ENGINEER to "Software Engineer (Polyglot Terminal, Code Studio)",
                        UserPrimaryRole.AI_RESEARCHER to "AI Researcher (Neural Models, Memory Graph)",
                        UserPrimaryRole.EXECUTIVE_BUSINESS to "Executive / Leader (Autonomous Projects, Agents)",
                        UserPrimaryRole.PRIVACY_SOVEREIGN to "Privacy Sovereign (100% Offline, Zero-Cloud)",
                        UserPrimaryRole.PERSONAL_ASSISTANT to "Personal Super-Assistant (Daily Tasks, Automation)"
                    )

                    roleOptions.forEach { (role, label) ->
                        val isSelected = selectedRole == role
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            onClick = { selectedRole = role },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedRole = role }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Question 2: Swarm & Multi-Device Compute Topology
                    Text(
                        text = "2. Swarm Compute & Multi-Device Offloading",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Can Wasti discover nearby PCs, laptops, and phones via Bluetooth & Wi-Fi to offload heavy tasks?",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val computeOptions = listOf(
                        SwarmComputePreference.AGGRESSIVE_MESH_SWARM to "Aggressive Mesh Swarm: Automatically detect nearby PCs & Wi-Fi/BT nodes to share compute",
                        SwarmComputePreference.HYBRID_EDGE_CLOUD to "Hybrid: Balance between local device and sovereign companion cloud tunnel",
                        SwarmComputePreference.OFFLINE_SOVEREIGN to "Strict Sovereign: Run 100% locally on this device only, no multi-device discovery"
                    )

                    computeOptions.forEach { (mode, label) ->
                        val isSelected = selectedCompute == mode
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            onClick = { selectedCompute = mode },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedCompute = mode }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Question 3: Autonomous Gate Resolution
                    Text(
                        text = "3. Production Sovereign Gates Resolution",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = autoCreateKeystore,
                                    onCheckedChange = { autoCreateKeystore = it }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Generate 4096-bit Sovereign Keystore",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Resolves Production Signing Gate on-device (27-year validity, PKCS12)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = autoDeployCloudTunnel,
                                    onCheckedChange = { autoDeployCloudTunnel = it }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Establish Sovereign Cloud Tunnel & Ingress",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Resolves Public Hosting Gate (Cloudflare tunnel / SSH port forward)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Question 4: Biometric Face Recognition & Commander Identity
                    Text(
                        text = "4. Biometric Face Recognition & Commander Identity",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scan your face using the device camera so Wasti reliably recognizes the primary owner/admin for sensitive command verification, personalized assistance, and gallery/photo analysis.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isFaceEnrolled) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = if (isFaceEnrolled) Icons.Default.CheckCircle else Icons.Default.Face,
                                    contentDescription = null,
                                    tint = if (isFaceEnrolled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isFaceEnrolled) "Commander Face Profile Active & Verified" else "On-Device Face Biometric Recognition",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isFaceEnrolled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isFaceEnrolled) {
                                            "Signature: ${faceSignatureText?.take(14) ?: "0x9f4a12..."} • 64D Luminance Gradient embedding secured in encrypted Room DB"
                                        } else {
                                            "Uses local CameraX to generate an invariant 64D mathematical embedding. Zero cloud leak."
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (isScanningFace) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Analyzing facial geometry & committing biometric signature...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        try {
                                            cameraFaceLauncher.launch(null)
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "Camera launch error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = if (isFaceEnrolled) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Scan Face",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isFaceEnrolled) "Re-Scan & Update Face Profile" else "Scan & Enroll Commander Face (Live Camera)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Question 5: Device Autonomous Control & Special Permissions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "5. Device & Platform Autonomous Capabilities",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { refreshPermissions() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Permissions", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Android Zero-Trust Security mandates that privileged execution capabilities must be configured by you in Android System Settings. Never accept claims of silent privilege elevation.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Row 1: Runtime Permissions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Standard Permissions", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (hasAllPermissionsGranted) Color(0xFF2E7D32).copy(alpha = 0.2f) else Color(0xFFE65100).copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = if (hasAllPermissionsGranted) "Granted" else "Pending",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (hasAllPermissionsGranted) Color(0xFF4CAF50) else Color(0xFFFF9800),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("Mic, Camera, Notifications, Media", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!hasAllPermissionsGranted) {
                                    Button(
                                        onClick = { wizardPermissionLauncher.launch(allWizardPermissions.toTypedArray()) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Grant", fontSize = 11.sp)
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Row 2: Accessibility Service
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Accessibility Service", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isAccessibilityActive) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = if (isAccessibilityActive) "Active" else "Disabled",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isAccessibilityActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("Autonomous UI navigation, app control & screen clicks", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = {
                                        (context as? android.app.Activity)?.let {
                                            com.example.assistant.SpecialPermissionHelper.openAccessibilitySettings(it)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(if (isAccessibilityActive) "Settings" else "Configure", fontSize = 11.sp)
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Row 3: Overlay / System Alert Window
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Display Over Other Apps", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isOverlayActive) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = if (isOverlayActive) "Enabled" else "Disabled",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isOverlayActive) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("Floating Commander HUD & persistent voice call indicator", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = {
                                        (context as? android.app.Activity)?.let {
                                            com.example.assistant.SpecialPermissionHelper.openOverlayPermissionSettings(it)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(if (isOverlayActive) "Settings" else "Configure", fontSize = 11.sp)
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                            // Row 4: Battery Optimization Exemption
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Background Processing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isBatteryOptimizationIgnored) Color(0xFF2E7D32).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = if (isBatteryOptimizationIgnored) "Unrestricted" else "Optimized",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isBatteryOptimizationIgnored) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text("Prevents OS termination of background server & reasoning", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = {
                                        com.example.assistant.SpecialPermissionHelper.openBatteryOptimizationSettings(context)
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(if (isBatteryOptimizationIgnored) "Settings" else "Unrestrict", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom Instructions
                    OutlinedTextField(
                        value = customInstructions,
                        onValueChange = { customInstructions = it },
                        label = { Text("Personal instructions for Wasti AI OS", fontSize = 12.sp) },
                        placeholder = { Text("e.g. Always prioritize fast execution and ask for verification on mutations.", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    if (completedResultText != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1B5E20).copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = completedResultText ?: "",
                                    fontSize = 11.sp,
                                    color = Color(0xFF81C784),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Footer Actions
                if (isExecuting) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = executionStatusText ?: "Configuring sovereign neural environment...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (completedResultText != null) {
                    Button(
                        onClick = {
                            onComplete()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Enter Sovereign Wasti AI OS")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Configure Later", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                isExecuting = true
                                executionStatusText = "Profiling silicon, generating keystore & sovereign tunnel..."
                                coroutineScope.launch {
                                    val plan = PersonalizedSetupPlan(
                                        role = selectedRole,
                                        computePreference = selectedCompute,
                                        autoCreateKeystore = autoCreateKeystore,
                                        autoDeployCloudTunnel = autoDeployCloudTunnel,
                                        userCustomInstructions = customInstructions
                                    )
                                    PersonalizedOnboardingEngine.applyPersonalizedSetup(context, plan)

                                    val keyDetails = WastiProductionSigningEngine.getExistingKeystoreDetails(context)
                                    val tunnelState = com.example.data.node.WastiSovereignTunnelEngine.tunnelState.value

                                    val sb = StringBuilder("✓ Sovereign Setup Complete!\n")
                                    if (keyDetails != null) {
                                        sb.append("• Keystore SHA-256: ${keyDetails.sha256Fingerprint.take(16)}...\n")
                                    }
                                    if (tunnelState.isActive) {
                                        sb.append("• Sovereign Tunnel: ${tunnelState.publicHttpsUrl}\n")
                                    }
                                    sb.append("• Silicon Reality: ${hardwareProfile.cpu.availableCores} cores indexed")

                                    completedResultText = sb.toString()
                                    isExecuting = false
                                }
                            },
                            modifier = Modifier.weight(1.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Apply Sovereign Setup", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
