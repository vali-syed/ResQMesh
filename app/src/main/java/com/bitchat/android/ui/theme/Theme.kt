package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Standard UI semantics live in Material so stock components and custom ResQMesh composables
// share one source of truth. LocalResQMeshPalette below only supplies app-specific extra colors.
internal val DarkResQMeshColorScheme = darkColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFF00ACC1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF004F54),
    onSecondaryContainer = Color(0xFFB2EBF2),
    tertiary = DarkResQMeshPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF0E150E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF182118),
    onSurfaceVariant = Color(0xFF9AA69A),
    outline = Color(0xFF2A3A2A),
    outlineVariant = Color(0xFF1C271C),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

internal val LightResQMeshColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF00ACC1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2EBF2),
    onSecondaryContainer = Color(0xFF002021),
    tertiary = LightResQMeshPalette.accentOrange,
    onTertiary = Color.Black,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF131A13),
    surface = Color(0xFFF2F6F2),
    onSurface = Color(0xFF131A13),
    surfaceVariant = Color(0xFFE7EDE7),
    onSurfaceVariant = Color(0xFF4C574C),
    outline = Color(0xFFCBD6CB),
    outlineVariant = Color(0xFFDEE6DE),
    error = Color(0xFFD32F2F),
    onError = Color.White
)

@Composable
fun ResQMeshTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkResQMeshColorScheme else LightResQMeshColorScheme
    val palette = if (shouldUseDark) DarkResQMeshPalette else LightResQMeshPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalResQMeshPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
