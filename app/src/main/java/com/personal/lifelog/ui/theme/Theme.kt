package com.personal.lifelog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.personal.lifelog.domain.ThemeMode

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
        typography = LogTypography,
        shapes = LogShapes,
        content = content,
    )
}
