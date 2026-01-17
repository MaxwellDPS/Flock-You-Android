package com.flockyou.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Custom color palette for surveillance detection theme
val md_theme_dark_primary = Color(0xFF00E676)          // Neon green for active scanning
val md_theme_dark_onPrimary = Color(0xFF003915)
val md_theme_dark_primaryContainer = Color(0xFF005222)
val md_theme_dark_onPrimaryContainer = Color(0xFF6AFF94)
val md_theme_dark_secondary = Color(0xFFFFB74D)        // Amber for warnings
val md_theme_dark_onSecondary = Color(0xFF422C00)
val md_theme_dark_secondaryContainer = Color(0xFF5E4100)
val md_theme_dark_onSecondaryContainer = Color(0xFFFFDDB6)
val md_theme_dark_tertiary = Color(0xFF64B5F6)         // Blue for info
val md_theme_dark_onTertiary = Color(0xFF003355)
val md_theme_dark_tertiaryContainer = Color(0xFF004477)
val md_theme_dark_onTertiaryContainer = Color(0xFFD1E4FF)
val md_theme_dark_error = Color(0xFFFF5252)            // Red for critical threats
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF0D1117)       // Dark background
val md_theme_dark_onBackground = Color(0xFFE1E3E1)
val md_theme_dark_surface = Color(0xFF161B22)          // Dark surface
val md_theme_dark_onSurface = Color(0xFFE1E3E1)
val md_theme_dark_surfaceVariant = Color(0xFF1F2937)
val md_theme_dark_onSurfaceVariant = Color(0xFFC0C8C4)
val md_theme_dark_outline = Color(0xFF8A938E)
val md_theme_dark_inverseOnSurface = Color(0xFF191C1A)
val md_theme_dark_inverseSurface = Color(0xFFE1E3E1)
val md_theme_dark_inversePrimary = Color(0xFF006D35)
val md_theme_dark_surfaceTint = Color(0xFF00E676)

// Threat level colors
val ThreatCritical = Color(0xFFFF1744)
val ThreatHigh = Color(0xFFFF5722)
val ThreatMedium = Color(0xFFFFB300)
val ThreatLow = Color(0xFF8BC34A)
val ThreatInfo = Color(0xFF64B5F6)

// Signal strength colors
val SignalExcellent = Color(0xFF00E676)
val SignalGood = Color(0xFF8BC34A)
val SignalMedium = Color(0xFFFFEB3B)
val SignalWeak = Color(0xFFFF9800)
val SignalVeryWeak = Color(0xFFFF5722)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    errorContainer = md_theme_dark_errorContainer,
    onError = md_theme_dark_onError,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inverseSurface = md_theme_dark_inverseSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint
)

@Composable
fun FlockYouTheme(
    darkTheme: Boolean = true, // Always dark for tactical appearance
    dynamicColor: Boolean = false, // Don't use dynamic colors
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
