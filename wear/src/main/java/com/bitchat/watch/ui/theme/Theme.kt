package com.bitchat.watch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val ResQMeshWearColorScheme = ColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF00ACC1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF004F54),
    onSecondaryContainer = Color(0xFFB2EBF2),
    tertiary = Color(0xFFFF9F0A),
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFF0E150E),
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF182118),
    onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF9AA69A),
    outline = Color(0xFF2A3A2A),
    outlineVariant = Color(0xFF1C271C),
    error = Color(0xFFD32F2F),
    onError = Color.White,
)

@Composable
fun ResQMeshWearTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalResQMeshPalette provides DarkResQMeshPalette) {
        MaterialTheme(
            colorScheme = ResQMeshWearColorScheme,
            typography = ResQMeshWearTypography,
            content = content
        )
    }
}
