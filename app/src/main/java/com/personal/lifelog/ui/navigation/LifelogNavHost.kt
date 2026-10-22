package com.personal.lifelog.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.personal.lifelog.R
import com.personal.lifelog.feature.home.HomeScreen
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.review.ReviewScreen
import com.personal.lifelog.feature.settings.SettingsScreen
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.sleep.SleepEditorViewModel
import com.personal.lifelog.feature.sleep.SleepEditorScreen
import com.personal.lifelog.feature.sleep.SleepHistoryScreen

/**
 * Single-activity navigation shell. The bottom bar only shows on the two tab
 * destinations; settings and editors are pushed full-screen, so system back and
 * the top-bar back arrow both return to the tab that opened them.
 *
 * Insets: the outer Scaffold contributes only the bottom bar height (its top inset
 * is left to each screen's own Scaffold/TopAppBar, which consumes the status bar).
 * Future form screens handle the keyboard with Modifier.imePadding() inside their
 * own Scaffold content, combined with the activity's windowSoftInputMode=adjustResize.
 */
@Composable
fun LifelogApp(
    homeViewModel: HomeViewModel,
    settingsViewModel: SettingsViewModel,
    sleepViewModel: SleepViewModel,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val today by homeViewModel.today.collectAsStateWithLifecycle()
    val hasRecordsToday by homeViewModel.hasRecordsToday.collectAsStateWithLifecycle()
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val themeError by settingsViewModel.themeError.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (currentRoute == LifelogRoutes.TODAY || currentRoute == LifelogRoutes.REVIEW) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == LifelogRoutes.TODAY,
                        onClick = { navController.navigateTopLevel(LifelogRoutes.TODAY) },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == LifelogRoutes.TODAY) {
                                    Icons.Filled.Home
                                } else {
                                    Icons.Outlined.Home
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_today)) },
                    )
                    NavigationBarItem(
                        selected = currentRoute == LifelogRoutes.REVIEW,
                        onClick = { navController.navigateTopLevel(LifelogRoutes.REVIEW) },
                        icon = {
                            Icon(
                                imageVector = if (currentRoute == LifelogRoutes.REVIEW) {
                                    Icons.Filled.DateRange
                                } else {
                                    Icons.Outlined.DateRange
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.nav_review)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = LifelogRoutes.TODAY,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            composable(LifelogRoutes.TODAY) {
                HomeScreen(
                    today = today,
                    hasRecordsToday = hasRecordsToday,
                    onOpenSettings = { navController.navigateSingle(LifelogRoutes.SETTINGS) },
                    onRecordMeal = { navController.navigate(LifelogRoutes.mealEditor()) },
                    onOpenSleep = { navController.navigateSingle(LifelogRoutes.SLEEP_HISTORY) },
                    onRecordWorkout = { navController.navigate(LifelogRoutes.workoutEditor()) },
                    sleepViewModel = sleepViewModel,
                    onEditSleep = { navController.navigate(LifelogRoutes.sleepEditor(it)) },
                )
            }
            composable(LifelogRoutes.REVIEW) {
                ReviewScreen(
                    onOpenSettings = { navController.navigateSingle(LifelogRoutes.SETTINGS) },
                )
            }
            composable(LifelogRoutes.SETTINGS) {
                SettingsScreen(
                    themeMode = themeMode,
                    themeWriteFailed = themeError != null,
                    onThemeSelected = settingsViewModel::setTheme,
                    onBack = { navController.navigateUp() },
                )
            }
            composable(
                route = LifelogRoutes.MEAL_EDIT_PATTERN,
                arguments = listOf(LifelogRoutes.mealIdArgument),
            ) { entry ->
                val mealId = entry.arguments?.getString(LifelogRoutes.ARG_MEAL_ID)
                DevPlaceholderScreen(
                    title = stringResource(R.string.home_quick_meal),
                    route = LifelogRoutes.mealEditor(mealId),
                    onBack = { navController.navigateUp() },
                )
            }
            composable(LifelogRoutes.SLEEP_HISTORY) {
                SleepHistoryScreen(sleepViewModel,
                    onAdd = { navController.navigate(LifelogRoutes.sleepEditor()) },
                    onEdit = { navController.navigate(LifelogRoutes.sleepEditor(it)) },
                    onBack = { navController.navigateUp() })
            }
            composable(
                route = LifelogRoutes.SLEEP_EDIT_PATTERN,
                arguments = listOf(LifelogRoutes.sleepIdArgument),
            ) { entry ->
                val sleepId = entry.arguments?.getString(LifelogRoutes.ARG_SLEEP_ID)
                val editor: SleepEditorViewModel = viewModel {
                    SleepEditorViewModel(sleepViewModel.repository, sleepViewModel.clock,
                        sleepViewModel.zoneIdProvider, sleepId, createSavedStateHandle())
                }
                SleepEditorScreen(
                    viewModel = editor,
                    editing = sleepId != null,
                    onBack = { navController.navigateUp() },
                )
            }
            composable(
                route = LifelogRoutes.WORKOUT_EDIT_PATTERN,
                arguments = listOf(LifelogRoutes.workoutIdArgument),
            ) { entry ->
                val workoutId = entry.arguments?.getString(LifelogRoutes.ARG_WORKOUT_ID)
                DevPlaceholderScreen(
                    title = stringResource(R.string.home_quick_workout),
                    route = LifelogRoutes.workoutEditor(workoutId),
                    onBack = { navController.navigateUp() },
                )
            }
        }
    }
}

/** Tab switch: single top, keep per-tab saved state, pop nothing but the graph root. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateSingle(route: String) {
    navigate(route) { launchSingleTop = true }
}
