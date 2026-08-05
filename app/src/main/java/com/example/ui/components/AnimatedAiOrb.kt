package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedAiOrb(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.tertiary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbPulsing")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (this.size.width <= 0f || this.size.height <= 0f) return@Canvas

            val maxRadius = (size.toPx() / 2f).coerceAtLeast(1f)
            val centerPoint = center
            val pulseRadius = ((maxRadius * 0.75f) * pulseScale).coerceAtLeast(1f)

            // Glow aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.4f),
                        secondaryColor.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = centerPoint,
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = centerPoint
            )

            // Outer animated ring
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(primaryColor, secondaryColor, primaryColor),
                    center = centerPoint
                ),
                radius = pulseRadius,
                center = centerPoint,
                style = Stroke(width = 3.dp.toPx())
            )

            // Core sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, primaryColor),
                    center = centerPoint,
                    radius = (pulseRadius * 0.6f).coerceAtLeast(1f)
                ),
                radius = pulseRadius * 0.5f,
                center = centerPoint
            )
        }
    }
}
