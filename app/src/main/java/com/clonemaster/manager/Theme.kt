package com.clonemaster.manager

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF168BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF153D91),
    onPrimaryContainer = Color(0xFFE6F0FF),
    secondary = Color(0xFF9B55FF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF33205F),
    onSecondaryContainer = Color(0xFFF0E5FF),
    tertiary = Color(0xFF20D6B2),
    onTertiary = Color(0xFF002E26),
    background = Color(0xFF070D1B),
    onBackground = Color(0xFFF1F5FF),
    surface = Color(0xFF0C1629),
    onSurface = Color(0xFFF1F5FF),
    surfaceVariant = Color(0xFF101F36),
    onSurfaceVariant = Color(0xFFA9B9D6),
    outline = Color(0xFF263C60),
    outlineVariant = Color(0xFF1B2C48),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A06),
    errorContainer = Color(0xFF4A1C1A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp)
)

@Composable
fun CloneMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, shapes = AppShapes, content = content)
}
