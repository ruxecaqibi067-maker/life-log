package com.personal.lifelog.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.feature.exercise.ExerciseLibraryScreen
import com.personal.lifelog.feature.exercise.ExerciseLibraryViewModel
import com.personal.lifelog.feature.home.HomeScreen
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.meal.MealDetailScreen
import com.personal.lifelog.feature.meal.MealDetailViewModel
import com.personal.lifelog.feature.meal.MealEditorScreen
import com.personal.lifelog.feature.meal.MealEditorViewModel
import com.personal.lifelog.feature.review.ReviewScreen
import com.personal.lifelog.feature.review.ReviewViewModel
import com.personal.lifelog.feature.settings.SettingsRoute
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.BedtimeScreen
import com.personal.lifelog.feature.sleep.SleepEditorScreen
import com.personal.lifelog.feature.sleep.SleepEditorViewModel
import com.personal.lifelog.feature.sleep.SleepHistoryScreen
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.work.*
import com.personal.lifelog.feature.home.WorkHomeViewModel
import com.personal.lifelog.feature.workout.*
import java.time.Clock
import java.time.ZoneId

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
    records: RecordRepository,
    photos: PhotoStorage,
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
    sleepViewModel: SleepViewModel,
    workoutRepository: WorkoutRepository,
    timelineRepository: TimelineRepository,
    workViewModel: WorkViewModel,
    workHomeViewModel: WorkHomeViewModel,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isBedtime = currentRoute == LifelogRoutes.BEDTIME
    com.personal.lifelog.ui.components.SyncLegacySystemBarBackground(isBedtime)
    val bedtimeRequest by sleepViewModel.bedtimeRequest.collectAsStateWithLifecycle()
    LaunchedEffect(bedtimeRequest, currentRoute) {
        bedtimeRequest?.let { id ->
            sleepViewModel.consumeBedtimeRequest(id)
            // A write finishing after the user left Today must not interrupt another form.
            if (currentRoute == LifelogRoutes.TODAY) navController.navigateSingle(LifelogRoutes.BEDTIME)
        }
    }
    val today by homeViewModel.today.collectAsStateWithLifecycle()
    val timeline by homeViewModel.timeline.collectAsStateWithLifecycle()

    // T04's contract: pages that resume with draft tickets call maintain. The shell
    // performs the general abandoned-ticket sweep once per app composition; failures
    // surface through the photo operations that actually need the files.
    LaunchedEffect(photos) {
        photos.maintain()
    }

    Scaffold(
        containerColor = if (isBedtime) Color.Black else MaterialTheme.colorScheme.background,
        contentWindowInsets = if (isBedtime) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (currentRoute == LifelogRoutes.TODAY || currentRoute == LifelogRoutes.REVIEW) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
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
        // The bottom bar already includes the gesture/navigation safe area. Let
        // child Scaffolds retain top insets without adding that bottom area again.
        val bottomPadding = if (isBedtime) 0.dp else innerPadding.calculateBottomPadding()
        NavHost(
            navController = navController,
            startDestination = LifelogRoutes.TODAY,
            modifier = Modifier.padding(bottom = bottomPadding)
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding)),
        ) {
            composable(LifelogRoutes.BEDTIME) {
                BedtimeScreen(clock, zoneIdProvider, sleepViewModel, onBack = {
                    if (navController.currentDestination?.route == LifelogRoutes.BEDTIME) navController.popBackStack()
                })
            }
            composable(LifelogRoutes.TODAY) {
                HomeScreen(
                    today = today,
                    timeline = timeline,
                    photos = photos,
                    onOpenSettings = { navController.navigateSingle(LifelogRoutes.SETTINGS) },
                    onRecordMeal = { navController.navigate(LifelogRoutes.mealEditor()) },
                    onOpenSleepHistory = { navController.navigateSingle(LifelogRoutes.SLEEP_HISTORY) },
                    onRecordWorkout = { navController.navigate(LifelogRoutes.workoutEditor()) },
                    onOpenWorkoutHistory = { navController.navigate(LifelogRoutes.WORKOUT_HISTORY) },
                    onOpenMeal = { id -> navController.navigate(LifelogRoutes.mealDetail(id)) },
                    onEditSleep = { navController.navigate(LifelogRoutes.sleepEditor(it)) },
                    onOpenWorkout = { id -> navController.navigate(LifelogRoutes.workoutDetail(id)) },
                    onRetryTimeline = homeViewModel::retry,
                    sleepViewModel = sleepViewModel,
                    homeViewModel = homeViewModel,
                    workViewModel = workViewModel,
                    workHomeViewModel = workHomeViewModel,
                    onOpenWorkHistory = { navController.navigateSingle(LifelogRoutes.WORK_HISTORY) },
                    onEditWork = { navController.navigate(LifelogRoutes.workEditor(it)) },
                )
            }
            composable(LifelogRoutes.REVIEW) {
                val reviewViewModel: ReviewViewModel = viewModel {
                    ReviewViewModel(timelineRepository, createSavedStateHandle(), clock, zoneIdProvider)
                }
                ReviewScreen(
                    viewModel = reviewViewModel,
                    photos = photos,
                    onOpenSettings = { navController.navigateSingle(LifelogRoutes.SETTINGS) },
                    onOpenMeal = { id -> navController.navigate(LifelogRoutes.mealDetail(id)) },
                    onOpenSleep = { id -> navController.navigate(LifelogRoutes.sleepEditor(id)) },
                    onOpenWork = { id -> navController.navigate(LifelogRoutes.workEditor(id)) },
                    onOpenWorkout = { id -> navController.navigate(LifelogRoutes.workoutDetail(id)) },
                )
            }
            composable(LifelogRoutes.SETTINGS) {
                SettingsRoute(
                    viewModel = settingsViewModel,
                    onOpenExercises = { navController.navigateSingle(LifelogRoutes.EXERCISES) },
                    onBack = { navController.navigateUp() },
                )
            }
            composable(LifelogRoutes.EXERCISES) {
                val exerciseViewModel: ExerciseLibraryViewModel = viewModel {
                    ExerciseLibraryViewModel(workoutRepository, createSavedStateHandle())
                }
                ExerciseLibraryScreen(exerciseViewModel, onBack = { navController.navigateUp() })
            }
            composable(
                route = LifelogRoutes.MEAL_EDIT_PATTERN,
                arguments = listOf(LifelogRoutes.mealIdArgument),
            ) { entry ->
                val mealId = entry.arguments?.getString(LifelogRoutes.ARG_MEAL_ID)
                val editorViewModel: MealEditorViewModel = viewModel {
                    MealEditorViewModel(
                        mealId = mealId,
                        savedStateHandle = createSavedStateHandle(),
                        records = records,
                        photos = photos,
                        clock = clock,
                        zoneIdProvider = zoneIdProvider,
                    )
                }
                MealEditorScreen(
                    viewModel = editorViewModel,
                    onFinished = { navController.popBackStack() },
                )
            }
            composable(LifelogRoutes.WORK_HISTORY) {
                WorkHistoryScreen(workViewModel,
                    onAdd = { navController.navigate(LifelogRoutes.workEditor()) },
                    onEdit = { navController.navigate(LifelogRoutes.workEditor(it)) },
                    onBack = { navController.navigateUp() })
            }
            composable(LifelogRoutes.WORK_EDIT_PATTERN, arguments = listOf(LifelogRoutes.workIdArgument)) { entry ->
                val id = entry.arguments?.getString(LifelogRoutes.ARG_WORK_ID)
                val editor: WorkEditorViewModel = viewModel {
                    WorkEditorViewModel(workViewModel.repository, clock, zoneIdProvider, id,
                        createSavedStateHandle(), workViewModel::deleted)
                }
                WorkEditorScreen(editor, editing = id != null, onBack = { navController.navigateUp() })
            }
            composable(LifelogRoutes.SLEEP_HISTORY) {
                SleepHistoryScreen(sleepViewModel,
                    onAdd = { navController.navigate(LifelogRoutes.sleepEditor()) },
                    onEdit = { navController.navigate(LifelogRoutes.sleepEditor(it)) },
                    onBack = { navController.navigateUp() })
            }
            composable(
                route = LifelogRoutes.MEAL_DETAIL_PATTERN,
                arguments = listOf(LifelogRoutes.mealIdArgument),
            ) { entry ->
                val mealId = entry.arguments?.getString(LifelogRoutes.ARG_MEAL_ID)
                if (mealId == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val detailViewModel: MealDetailViewModel = viewModel {
                        MealDetailViewModel(
                            mealId = mealId,
                            records = records,
                            photos = photos,
                        )
                    }
                    MealDetailScreen(
                        viewModel = detailViewModel,
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate(LifelogRoutes.mealEditor(id)) },
                    )
                }
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
                val editor: WorkoutEditorViewModel = viewModel {
                    WorkoutEditorViewModel(workoutRepository, createSavedStateHandle(), clock, zoneIdProvider, workoutId)
                }
                val exercises: ExerciseLibraryViewModel = viewModel(key = "workout-picker") {
                    ExerciseLibraryViewModel(workoutRepository, createSavedStateHandle(), selecting = true)
                }
                WorkoutEditorScreen(editor, editing = workoutId != null,
                    onFinished = { navController.popBackStack() },
                    picker = { onBack, onSelected -> ExerciseLibraryScreen(exercises, onBack, onSelected) },
                )
            }
            composable(LifelogRoutes.WORKOUT_HISTORY) {
                val history: WorkoutDayViewModel = viewModel {
                    WorkoutDayViewModel(workoutRepository, createSavedStateHandle(), clock, zoneIdProvider)
                }
                WorkoutHistoryScreen(history, onBack = { navController.navigateUp() },
                    onOpen = { navController.navigate(LifelogRoutes.workoutDetail(it)) },
                    onAdd = { navController.navigate(LifelogRoutes.workoutEditor()) })
            }
            composable(LifelogRoutes.WORKOUT_DETAIL_PATTERN, arguments = listOf(LifelogRoutes.workoutIdArgument)) { entry ->
                val id = entry.arguments?.getString(LifelogRoutes.ARG_WORKOUT_ID)
                if (id == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val detail: WorkoutDetailViewModel = viewModel { WorkoutDetailViewModel(workoutRepository, id) }
                    WorkoutDetailScreen(detail, onBack = { navController.popBackStack() },
                        onEdit = { navController.navigate(LifelogRoutes.workoutEditor(it)) })
                }
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
