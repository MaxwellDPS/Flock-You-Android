package com.flockyou.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.core.view.WindowCompat
import com.flockyou.R

/**
 * Resource-based color accessors for the surveillance detection theme.
 * All colors are loaded from colors.xml to allow OEM customization.
 */
object AppColors {
    // Theme colors - accessed via colorResource() in Composable context
    val Primary: Color @Composable get() = colorResource(R.color.md_theme_dark_primary)
    val OnPrimary: Color @Composable get() = colorResource(R.color.md_theme_dark_onPrimary)
    val PrimaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_primaryContainer)
    val OnPrimaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_onPrimaryContainer)
    val Secondary: Color @Composable get() = colorResource(R.color.md_theme_dark_secondary)
    val OnSecondary: Color @Composable get() = colorResource(R.color.md_theme_dark_onSecondary)
    val SecondaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_secondaryContainer)
    val OnSecondaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_onSecondaryContainer)
    val Tertiary: Color @Composable get() = colorResource(R.color.md_theme_dark_tertiary)
    val OnTertiary: Color @Composable get() = colorResource(R.color.md_theme_dark_onTertiary)
    val TertiaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_tertiaryContainer)
    val OnTertiaryContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_onTertiaryContainer)
    val Error: Color @Composable get() = colorResource(R.color.md_theme_dark_error)
    val ErrorContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_errorContainer)
    val OnError: Color @Composable get() = colorResource(R.color.md_theme_dark_onError)
    val OnErrorContainer: Color @Composable get() = colorResource(R.color.md_theme_dark_onErrorContainer)
    val Background: Color @Composable get() = colorResource(R.color.md_theme_dark_background)
    val OnBackground: Color @Composable get() = colorResource(R.color.md_theme_dark_onBackground)
    val Surface: Color @Composable get() = colorResource(R.color.md_theme_dark_surface)
    val OnSurface: Color @Composable get() = colorResource(R.color.md_theme_dark_onSurface)
    val SurfaceVariant: Color @Composable get() = colorResource(R.color.md_theme_dark_surfaceVariant)
    val OnSurfaceVariant: Color @Composable get() = colorResource(R.color.md_theme_dark_onSurfaceVariant)
    val Outline: Color @Composable get() = colorResource(R.color.md_theme_dark_outline)
    val InverseOnSurface: Color @Composable get() = colorResource(R.color.md_theme_dark_inverseOnSurface)
    val InverseSurface: Color @Composable get() = colorResource(R.color.md_theme_dark_inverseSurface)
    val InversePrimary: Color @Composable get() = colorResource(R.color.md_theme_dark_inversePrimary)
    val SurfaceTint: Color @Composable get() = colorResource(R.color.md_theme_dark_surfaceTint)

    // Threat level colors
    val ThreatCritical: Color @Composable get() = colorResource(R.color.threat_critical)
    val ThreatHigh: Color @Composable get() = colorResource(R.color.threat_high)
    val ThreatMedium: Color @Composable get() = colorResource(R.color.threat_medium)
    val ThreatLow: Color @Composable get() = colorResource(R.color.threat_low)
    val ThreatInfo: Color @Composable get() = colorResource(R.color.threat_info)

    // Signal strength colors
    val SignalExcellent: Color @Composable get() = colorResource(R.color.signal_excellent)
    val SignalGood: Color @Composable get() = colorResource(R.color.signal_good)
    val SignalMedium: Color @Composable get() = colorResource(R.color.signal_medium)
    val SignalWeak: Color @Composable get() = colorResource(R.color.signal_weak)
    val SignalVeryWeak: Color @Composable get() = colorResource(R.color.signal_very_weak)

    // Status colors for subsystems and indicators
    val StatusActive: Color @Composable get() = colorResource(R.color.status_active)
    val StatusError: Color @Composable get() = colorResource(R.color.status_error)
    val StatusWarning: Color @Composable get() = colorResource(R.color.status_warning)
    val StatusDisabled: Color @Composable get() = colorResource(R.color.status_disabled)
    val StatusInactive: Color @Composable get() = colorResource(R.color.status_inactive)

    // Network generation colors
    val Network5G: Color @Composable get() = colorResource(R.color.network_5g)
    val Network4G: Color @Composable get() = colorResource(R.color.network_4g)
    val Network3G: Color @Composable get() = colorResource(R.color.network_3g)
    val Network2G: Color @Composable get() = colorResource(R.color.network_2g)
}

// Legacy color aliases for backwards compatibility
// These are deprecated - use AppColors object instead
@Deprecated("Use AppColors.ThreatCritical instead", ReplaceWith("AppColors.ThreatCritical"))
val ThreatCritical: Color @Composable get() = AppColors.ThreatCritical
@Deprecated("Use AppColors.ThreatHigh instead", ReplaceWith("AppColors.ThreatHigh"))
val ThreatHigh: Color @Composable get() = AppColors.ThreatHigh
@Deprecated("Use AppColors.ThreatMedium instead", ReplaceWith("AppColors.ThreatMedium"))
val ThreatMedium: Color @Composable get() = AppColors.ThreatMedium
@Deprecated("Use AppColors.ThreatLow instead", ReplaceWith("AppColors.ThreatLow"))
val ThreatLow: Color @Composable get() = AppColors.ThreatLow
@Deprecated("Use AppColors.ThreatInfo instead", ReplaceWith("AppColors.ThreatInfo"))
val ThreatInfo: Color @Composable get() = AppColors.ThreatInfo
@Deprecated("Use AppColors.SignalExcellent instead", ReplaceWith("AppColors.SignalExcellent"))
val SignalExcellent: Color @Composable get() = AppColors.SignalExcellent
@Deprecated("Use AppColors.SignalGood instead", ReplaceWith("AppColors.SignalGood"))
val SignalGood: Color @Composable get() = AppColors.SignalGood
@Deprecated("Use AppColors.SignalMedium instead", ReplaceWith("AppColors.SignalMedium"))
val SignalMedium: Color @Composable get() = AppColors.SignalMedium
@Deprecated("Use AppColors.SignalWeak instead", ReplaceWith("AppColors.SignalWeak"))
val SignalWeak: Color @Composable get() = AppColors.SignalWeak
@Deprecated("Use AppColors.SignalVeryWeak instead", ReplaceWith("AppColors.SignalVeryWeak"))
val SignalVeryWeak: Color @Composable get() = AppColors.SignalVeryWeak
@Deprecated("Use AppColors.StatusActive instead", ReplaceWith("AppColors.StatusActive"))
val StatusActive: Color @Composable get() = AppColors.StatusActive
@Deprecated("Use AppColors.StatusError instead", ReplaceWith("AppColors.StatusError"))
val StatusError: Color @Composable get() = AppColors.StatusError
@Deprecated("Use AppColors.StatusWarning instead", ReplaceWith("AppColors.StatusWarning"))
val StatusWarning: Color @Composable get() = AppColors.StatusWarning
@Deprecated("Use AppColors.StatusDisabled instead", ReplaceWith("AppColors.StatusDisabled"))
val StatusDisabled: Color @Composable get() = AppColors.StatusDisabled
@Deprecated("Use AppColors.StatusInactive instead", ReplaceWith("AppColors.StatusInactive"))
val StatusInactive: Color @Composable get() = AppColors.StatusInactive
@Deprecated("Use AppColors.Network5G instead", ReplaceWith("AppColors.Network5G"))
val Network5G: Color @Composable get() = AppColors.Network5G
@Deprecated("Use AppColors.Network4G instead", ReplaceWith("AppColors.Network4G"))
val Network4G: Color @Composable get() = AppColors.Network4G
@Deprecated("Use AppColors.Network3G instead", ReplaceWith("AppColors.Network3G"))
val Network3G: Color @Composable get() = AppColors.Network3G
@Deprecated("Use AppColors.Network2G instead", ReplaceWith("AppColors.Network2G"))
val Network2G: Color @Composable get() = AppColors.Network2G

/**
 * Creates a dark color scheme from resource-based colors.
 * Must be called within a Composable context.
 */
@Composable
private fun createDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = colorResource(R.color.md_theme_dark_primary),
    onPrimary = colorResource(R.color.md_theme_dark_onPrimary),
    primaryContainer = colorResource(R.color.md_theme_dark_primaryContainer),
    onPrimaryContainer = colorResource(R.color.md_theme_dark_onPrimaryContainer),
    secondary = colorResource(R.color.md_theme_dark_secondary),
    onSecondary = colorResource(R.color.md_theme_dark_onSecondary),
    secondaryContainer = colorResource(R.color.md_theme_dark_secondaryContainer),
    onSecondaryContainer = colorResource(R.color.md_theme_dark_onSecondaryContainer),
    tertiary = colorResource(R.color.md_theme_dark_tertiary),
    onTertiary = colorResource(R.color.md_theme_dark_onTertiary),
    tertiaryContainer = colorResource(R.color.md_theme_dark_tertiaryContainer),
    onTertiaryContainer = colorResource(R.color.md_theme_dark_onTertiaryContainer),
    error = colorResource(R.color.md_theme_dark_error),
    errorContainer = colorResource(R.color.md_theme_dark_errorContainer),
    onError = colorResource(R.color.md_theme_dark_onError),
    onErrorContainer = colorResource(R.color.md_theme_dark_onErrorContainer),
    background = colorResource(R.color.md_theme_dark_background),
    onBackground = colorResource(R.color.md_theme_dark_onBackground),
    surface = colorResource(R.color.md_theme_dark_surface),
    onSurface = colorResource(R.color.md_theme_dark_onSurface),
    surfaceVariant = colorResource(R.color.md_theme_dark_surfaceVariant),
    onSurfaceVariant = colorResource(R.color.md_theme_dark_onSurfaceVariant),
    outline = colorResource(R.color.md_theme_dark_outline),
    inverseOnSurface = colorResource(R.color.md_theme_dark_inverseOnSurface),
    inverseSurface = colorResource(R.color.md_theme_dark_inverseSurface),
    inversePrimary = colorResource(R.color.md_theme_dark_inversePrimary),
    surfaceTint = colorResource(R.color.md_theme_dark_surfaceTint)
)

@Suppress("UNUSED_PARAMETER")
@Composable
fun FlockYouTheme(
    darkTheme: Boolean = true, // Always dark for tactical appearance
    dynamicColor: Boolean = false, // Don't use dynamic colors
    content: @Composable () -> Unit
) {
    // Note: darkTheme and dynamicColor are kept for API compatibility
    // but this app always uses the dark tactical theme
    val colorScheme = createDarkColorScheme()

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
