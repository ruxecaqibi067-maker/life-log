package com.personal.lifelog

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import com.personal.lifelog.ui.theme.resolvedDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (application as App).container
            val settingsViewModel: SettingsViewModel = viewModel {
                SettingsViewModel(container.settings)
            }
            val homeViewModel: HomeViewModel = viewModel {
                HomeViewModel(
                    container.clock,
                    container.zoneIdProvider,
                    container.records::datesWithRecords,
                    container.records::mealsOn,
                )
            }
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val sleepViewModel: SleepViewModel = viewModel {
                SleepViewModel(container.records, container.clock, container.zoneIdProvider)
            }
            val darkTheme = resolvedDarkTheme(themeMode)

            // Keep system bar icon contrast in sync with the stored theme choice,
            // including when the user picks a mode that differs from the system.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            LifeLogTheme(themeMode = themeMode) {
                LifelogApp(
                    homeViewModel = homeViewModel,
                    settingsViewModel = settingsViewModel,
                    sleepViewModel = sleepViewModel,
                    records = container.records,
                    photos = container.photos,
                    clock = container.clock,
                    zoneIdProvider = container.zoneIdProvider,
                )
            }
        }
    }
}
