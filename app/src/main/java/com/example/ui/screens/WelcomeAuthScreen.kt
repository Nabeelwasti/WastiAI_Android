package com.example.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_auth_screen"),
        contentAlignment = Alignment.Center
    ) {
        // Task 44B: Relaxing Continuous Ambient Background Animation
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
                // Thrivebridge Growth Solutions Branding Badge
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
                // Central Animated Wasti AI Logo Shield
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
                        text = "Autonomous Intelligence • Credential Manager Google Auth • Firestore Cloud Sync",
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
                            icon = Icons.Default.CloudSync,
                            title = "Firestore Cloud Backup & Sync",
                            subtitle = "Automatic restore of CRM prospects, invoices, and AI memories"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FeatureRow(
                            icon = Icons.Default.RecordVoiceOver,
                            title = "Vosk Wake-Word Engine",
                            subtitle = "Continuous offline 'Hey Wasti' KWS recognition running on background thread"
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

            Spacer(modifier = Modifier.height(32.dp))

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

            AnimatedVisibility(
                visible = startAnimation,
                enter = fadeIn(animationSpec = tween(1600)) + slideInVertically(initialOffsetY = { 50 })
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                                            Toast.makeText(
                                                context,
                                                "Welcome ${result.user.displayName ?: "Leader"}! Cloud synced.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onLaunchWorkspace()
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

/**
 * Task 44B: Continuous relaxing particle & pulsing gradient background canvas
 */
@Composable
private fun RelaxingBackgroundCanvas(phase: Float) {
    val primaryColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val tertiaryColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawRect(color = surfaceColor)

        // Pulsing ambient gradient 1
        val offsetX1 = width * (0.3f + 0.15f * sin(phase))
        val offsetY1 = height * (0.3f + 0.15f * sin(phase + 1f))
        drawCircle(
            color = primaryColor,
            radius = width * 0.45f,
            center = Offset(offsetX1, offsetY1)
        )

        // Pulsing ambient gradient 2
        val offsetX2 = width * (0.7f - 0.20f * sin(phase * 0.8f))
        val offsetY2 = height * (0.7f + 0.10f * sin(phase * 0.8f + 2f))
        drawCircle(
            color = tertiaryColor,
            radius = width * 0.50f,
            center = Offset(offsetX2, offsetY2)
        )

        // Floating relaxing particles
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
