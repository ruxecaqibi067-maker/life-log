package com.personal.lifelog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.personal.lifelog.domain.ThemeMode

// Brand green derived from the launcher background color; the rest of each scheme
// keeps the Material 3 baseline tones so both schemes stay legible and familiar.
private val LightColors = lightColorScheme(
    primary = Color(0xFF365E50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9DDCE),
    onPrimaryContainer = Color(0xFF0F2A21),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCFBE),
    onPrimary = Color(0xFF0F2A21),
    primaryContainer = Color(0xFF24483D),
    onPrimaryContainer = Color(0xFFB9DDCE),
)

// Default Material typography: system font, sp-based sizes, so the system
// font-size setting scales all text without per-screen work.
private val LifelogTypography = Typography()

/** Resolves the effective dark/light choice for a stored [ThemeMode]. */
@Composable
fun resolvedDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/**
 * App theme for all screens. [themeMode] comes from the persisted Room AppSettings
 * row (see SettingsRepository); SYSTEM follows the device setting.
 */
@Composable
fun LifeLogTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (resolvedDarkTheme(themeMode)) DarkColors else LightColors,
        typography = LifelogTypography,
        content = content,
    )
}
