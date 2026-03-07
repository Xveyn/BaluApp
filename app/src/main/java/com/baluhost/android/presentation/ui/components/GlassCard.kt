package com.baluhost.android.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baluhost.android.presentation.ui.theme.*

/**
 * Glassmorphism card component matching webapp's dark slate card design.
 *
 * Uses dark slate-tinted backgrounds with subtle borders instead of white-tinted glass.
 * Matches webapp's `.card` class: `bg-slate-900/55 border-slate-800/60`.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    intensity: GlassIntensity = GlassIntensity.Medium,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val backgroundColor = when (intensity) {
        GlassIntensity.Light -> Slate900.copy(alpha = 0.40f)
        GlassIntensity.Medium -> Slate900.copy(alpha = 0.55f)
        GlassIntensity.Heavy -> Slate900.copy(alpha = 0.70f)
    }

    val borderColor = when (intensity) {
        GlassIntensity.Light -> Slate800.copy(alpha = 0.50f)
        GlassIntensity.Medium -> Slate800.copy(alpha = 0.60f)
        GlassIntensity.Heavy -> Slate800.copy(alpha = 0.70f)
    }

    Card(
        modifier = modifier
            .border(
                width = 1.dp,
                color = borderColor,
                shape = shape
            ),
        onClick = onClick ?: {},
        enabled = enabled && onClick != null,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = if (onClick != null) 2.dp else 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content
        )
    }
}

/**
 * Glassmorphism intensity levels.
 */
enum class GlassIntensity {
    Light,   // Subtle transparency
    Medium,  // Standard glassmorphism (webapp default)
    Heavy    // More opaque for elevated content
}

/**
 * Gradient glass card with colorful border.
 */
@Composable
fun GradientGlassCard(
    modifier: Modifier = Modifier,
    gradient: Brush,
    borderWidth: Dp = 2.dp,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(gradient)
    ) {
        Column(
            modifier = Modifier
                .padding(borderWidth)
                .clip(shape)
                .background(Slate900.copy(alpha = 0.9f))
                .padding(padding),
            content = content
        )
    }
}

/**
 * Simple glass surface without Card wrapper.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    intensity: GlassIntensity = GlassIntensity.Medium,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = when (intensity) {
        GlassIntensity.Light -> Slate900.copy(alpha = 0.40f)
        GlassIntensity.Medium -> Slate900.copy(alpha = 0.55f)
        GlassIntensity.Heavy -> Slate900.copy(alpha = 0.70f)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = Slate800.copy(alpha = 0.60f),
                shape = shape
            ),
        content = content
    )
}

/**
 * Shared background gradient matching webapp's body gradient.
 * `linear-gradient(135deg, #0f172a, #1e1b4b, #1e293b, #0c0a1f, #020617)`
 */
@Composable
fun BaluBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Slate950,
                        Indigo950,
                        Slate900,
                        Color(0xFF0C0A1F),
                        Color(0xFF020617)
                    )
                )
            ),
        content = content
    )
}
