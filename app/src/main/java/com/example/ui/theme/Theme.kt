package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemeMode(val key: String, val label: String) {
    AMOLED("amoled", "AMOLED Black"),
    DARK("dark", "Studio Dark"),
    LIGHT("light", "Studio Light");

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.key == key } ?: AMOLED
    }
}

private val AmoledColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = EmberCoral,
    onSecondary = OnEmberCoral,
    secondaryContainer = EmberCoralContainer,
    onSecondaryContainer = EmberCoral,
    tertiary = PulseViolet,
    onTertiary = Color.White,
    tertiaryContainer = PulseVioletContainer,
    onTertiaryContainer = PulseViolet,
    background = AmoledBlack,
    onBackground = InkWhite,
    surface = AmoledSurface,
    onSurface = InkWhite,
    surfaceVariant = AmoledSurfaceContainer,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = AmoledSurface,
    surfaceContainer = AmoledSurfaceContainer,
    surfaceContainerHigh = AmoledSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF1F2738),
    outline = AmoledOutline,
    outlineVariant = Color(0xFF1A2232)
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = EmberCoral,
    onSecondary = OnEmberCoral,
    secondaryContainer = EmberCoralContainer,
    onSecondaryContainer = EmberCoral,
    tertiary = PulseViolet,
    onTertiary = Color.White,
    tertiaryContainer = PulseVioletContainer,
    onTertiaryContainer = PulseViolet,
    background = StudioDarkBg,
    onBackground = InkWhite,
    surface = StudioDarkSurface,
    onSurface = InkWhite,
    surfaceVariant = StudioDarkSurfaceContainer,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = StudioDarkSurface,
    surfaceContainer = StudioDarkSurfaceContainer,
    surfaceContainerHigh = StudioDarkSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF2B384F),
    outline = StudioDarkOutline,
    outlineVariant = Color(0xFF243044)
)

private val StudioLightColorScheme = lightColorScheme(
    primary = StudioLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEFF5),
    onPrimaryContainer = Color(0xFF002026),
    secondary = StudioLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E0),
    onSecondaryContainer = Color(0xFF3E0011),
    tertiary = Color(0xFF5D3FD3),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9E2FF),
    onTertiaryContainer = Color(0xFF1D0D52),
    background = StudioLightBg,
    onBackground = StudioLightText,
    surface = StudioLightSurface,
    onSurface = StudioLightText,
    surfaceVariant = StudioLightSurfaceContainer,
    onSurfaceVariant = StudioLightMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = StudioLightSurfaceContainer,
    surfaceContainerHigh = StudioLightSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFFD2DEEC),
    outline = Color(0xFFB6C4D6),
    outlineVariant = Color(0xFFD7E1EE)
)

@Composable
fun OmniStreamTheme(
    themeMode: AppThemeMode = AppThemeMode.AMOLED,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        AppThemeMode.AMOLED -> AmoledColorScheme
        AppThemeMode.DARK -> StudioDarkColorScheme
        AppThemeMode.LIGHT -> StudioLightColorScheme
    }

    val isLight = themeMode == AppThemeMode.LIGHT
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OmniStreamTypography,
        content = content
    )
}
