package com.baluhost.android.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baluhost.android.presentation.ui.theme.*

/**
 * Button with gradient background matching web design.
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = defaultGradient()
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(
                brush = if (enabled) gradient else Brush.horizontalGradient(
                    listOf(Slate700, Slate600)
                )
            )
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

/**
 * Small gradient button for secondary actions.
 */
@Composable
fun GradientButtonSmall(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = secondaryGradient()
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(
                brush = if (enabled) gradient else Brush.horizontalGradient(
                    listOf(Slate700, Slate600)
                )
            )
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Default gradient: Sky → Indigo → Violet
 */
@Composable
fun defaultGradient(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            Sky400,
            Indigo500,
            Violet500
        )
    )
}

/**
 * Secondary gradient: Indigo → Violet
 */
@Composable
fun secondaryGradient(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            Indigo500,
            Violet500
        )
    )
}

/**
 * Success gradient: Green shades
 */
@Composable
fun successGradient(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            Green400,
            Green500,
            Green600
        )
    )
}

/**
 * Error gradient: Red shades
 */
@Composable
fun errorGradient(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            Red400,
            Red500,
            Red600
        )
    )
}

/**
 * Warning gradient: Yellow shades
 */
@Composable
fun warningGradient(): Brush {
    return Brush.horizontalGradient(
        colors = listOf(
            Yellow400,
            Yellow500,
            Yellow600
        )
    )
}
