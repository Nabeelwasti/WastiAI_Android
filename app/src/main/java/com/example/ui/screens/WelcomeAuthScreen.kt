package com.example.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.GoogleAuthClient
import com.example.data.auth.GoogleAuthResult
import com.example.data.sync.CloudSyncManager
import com.example.security.BiometricSecurityManager
import com.example.security.findFragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun WelcomeAuthScreen(
    onLaunchWorkspace: () -> Unit,
    onOpenAccountHub: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var startAnimation by remember { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    // Task 45B: One-Tap Biometric states
    var isBiometricEnabled by remember { mutableStateOf(BiometricSecurityManager.isBiometricLoginEnabled(context)) }
    var showPostSignInBiometricCard by remember { mutableStateOf(false) }
    var enableBiometricCheckbox by remember { mutableStateOf(true) }
    var userDisplayName by remember { mutableStateOf("Leader") }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val backgroundPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_phase"
    )

    LaunchedEffect(Unit) {
        delay(150)
        startAnimation = true

        // Task 45B: On subsequent app launches, if biometric login is enabled, trigger BiometricPrompt immediately
        if (isBiometricEnabled) {
            val activity = context.findFragmentActivity()
            if (activity != null) {
                BiometricSecurityManager.authenticate(
                    activity = activity,
                    title = "Wasti OS One-Tap Login",
                    subtitle = "Scan thumbprint to access Operations Dashboard",
                    onSuccess = {
                        Toast.makeText(context, "Fingerprint authorized! Welcome back.", Toast.LENGTH_SHORT).show()
                        onLaunchWorkspace()
                    },
                    onError = { err ->
                        Log.w("WelcomeAuthScreen", "Biometric launch error: $err")
                    }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_auth_screen"),
        contentAlignment = Alignment.Center
    ) {
        RelaxingBackgroundCanvas(phase = backgroundPhase)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(800)) + slideInVertically(initialOffsetY = { -60 })
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            RoundedCornerShape(24.dp)
                        )
                        .testTag("thrivebridge_branding_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Business,
                            contentDescription = "Thrivebridge Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "THRIVEBRIDGE GROWTH SOLUTIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(1000)) + scaleIn(initialScale = 0.8f)
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                        .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Wasti AI Engine",
                        tint = Color.White,
                        modifier = Modifier.size(60.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(1200)) + slideInVertically(initialOffsetY = { 40 })
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Wasti AI Operating System",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Autonomous Intelligence • Biometric One-Tap • Firestore Cloud Sync",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(1400))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Core Subsystems Active",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        FeatureRow(
                            icon = Icons.Default.Fingerprint,
                            title = "One-Tap Biometric Security",
                            subtitle = "Instant thumbprint access to operations & draft approvals"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FeatureRow(
                            icon = Icons.Default.CloudSync,
                            title = "Firestore Cloud Backup & Sync",
                            subtitle = "Automatic restore of CRM prospects, invoices, and AI memories"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FeatureRow(
                            icon = Icons.Default.AutoMode,
                            title = "Multi-Agent Neural Swarm",
                            subtitle = "Coordinated autonomous agents for lead scraping, invoicing, and dev workflow"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (authErrorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = authErrorMessage ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Task 45B: Post Sign-In Card offering One-Tap Biometric Login preference
            if (showPostSignInBiometricCard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                        .testTag("post_signin_biometric_preference_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    )
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = "Biometric Setup",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Welcome $userDisplayName!",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Would you like to enable One-Tap Biometric Login for instant access on future sessions?",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { enableBiometricCheckbox = !enableBiometricCheckbox }
                        ) {
                            Checkbox(
                                checked = enableBiometricCheckbox,
                                onCheckedChange = { enableBiometricCheckbox = it },
                                modifier = Modifier.testTag("enable_biometric_login_checkbox")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Enable One-Tap Biometric Login for future sessions.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                BiometricSecurityManager.setBiometricLoginEnabled(context, enableBiometricCheckbox)
                                isBiometricEnabled = enableBiometricCheckbox
                                showPostSignInBiometricCard = false
                                Toast.makeText(context, "Preferences saved! Entering Wasti OS...", Toast.LENGTH_SHORT).show()
                                onLaunchWorkspace()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("confirm_biometric_pref_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Continue to Operations Dashboard", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = startAnimation && !showPostSignInBiometricCard,
                enter = fadeIn(animationSpec = tween(1600)) + slideInVertically(initialOffsetY = { 50 })
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Task 45B: One-Tap Biometric Login Button if enabled
                    if (isBiometricEnabled) {
                        Button(
                            onClick = {
                                val activity = context.findFragmentActivity()
                                if (activity != null) {
                                    BiometricSecurityManager.authenticate(
                                        activity = activity,
                                        title = "Wasti OS One-Tap Login",
                                        subtitle = "Scan thumbprint to enter Operations Dashboard",
                                        onSuccess = {
                                            Toast.makeText(context, "Fingerprint authorized!", Toast.LENGTH_SHORT).show()
                                            onLaunchWorkspace()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, "Biometric failed: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    onLaunchWorkspace()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("one_tap_biometric_login_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = "One-Tap Fingerprint", modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("One-Tap Biometric Login", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Task 44B: Prominent "Sign in with Google" Button
                    Button(
                        onClick = {
                            if (!isAuthenticating) {
                                scope.launch {
                                    isAuthenticating = true
                                    authErrorMessage = null
                                    val client = GoogleAuthClient(context)
                                    when (val result = client.signIn()) {
                                        is GoogleAuthResult.Success -> {
                                            try {
                                                CloudSyncManager.syncToLocal(context, result.user.uid)
                                            } catch (e: Exception) {
                                                Log.w("WelcomeAuthScreen", "Post-sign-in sync error: ${e.message}")
                                            }
                                            isAuthenticating = false
                                            userDisplayName = result.user.displayName ?: "Leader"
                                            showPostSignInBiometricCard = true
                                        }
                                        is GoogleAuthResult.Error -> {
                                            isAuthenticating = false
                                            authErrorMessage = result.message
                                            Toast.makeText(context, "Auth Error: ${result.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("google_sign_in_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (isAuthenticating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Authenticating with Google...", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Google Logo", modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Sign in with Google", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Secondary guest workspace entry
                    OutlinedButton(
                        onClick = onLaunchWorkspace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("welcome_launch_workspace_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Continue as Offline Guest", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = onOpenAccountHub,
                        modifier = Modifier.testTag("welcome_open_account_hub_button")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Account Hub & Credential Vault", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RelaxingBackgroundCanvas(phase: Float) {
    val primaryColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val tertiaryColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawRect(color = surfaceColor)

        val offsetX1 = width * (0.3f + 0.15f * sin(phase))
        val offsetY1 = height * (0.3f + 0.15f * sin(phase + 1f))
        drawCircle(
            color = primaryColor,
            radius = width * 0.45f,
            center = Offset(offsetX1, offsetY1)
        )

        val offsetX2 = width * (0.7f - 0.20f * sin(phase * 0.8f))
        val offsetY2 = height * (0.7f + 0.10f * sin(phase * 0.8f + 2f))
        drawCircle(
            color = tertiaryColor,
            radius = width * 0.50f,
            center = Offset(offsetX2, offsetY2)
        )

        val numParticles = 12
        for (i in 0 until numParticles) {
            val pOffset = i * (2f * Math.PI.toFloat() / numParticles)
            val pX = (width * (0.1f + 0.8f * ((i * 37) % 100 / 100f)) + 30f * sin(phase + pOffset))
            val pY = (height * (0.1f + 0.8f * ((i * 53) % 100 / 100f)) - 40f * sin(phase * 0.5f + pOffset))
            val alpha = 0.2f + 0.15f * sin(phase * 2f + pOffset)
            drawCircle(
                color = primaryColor.copy(alpha = alpha.coerceIn(0.05f, 0.4f)),
                radius = 6.dp.toPx() + (i % 4).dp.toPx(),
                center = Offset(pX, pY)
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
