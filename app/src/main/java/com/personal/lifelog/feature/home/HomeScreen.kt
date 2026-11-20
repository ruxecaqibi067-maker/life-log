package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.feature.sleep.SleepHomeFeedback
import com.personal.lifelog.feature.sleep.SleepFinishConfirmation
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.timeline.TimelineUiState
import com.personal.lifelog.feature.timeline.timelineSection
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.theme.LogDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

/**
 * 今天: side-by-side summaries, four quick entries and the saved-data
 * timeline for the current local date. The timeline owns all four record types;
 * nothing is shown until Room has emitted for that date. The date itself follows
 * [HomeViewModel.today], which rolls over at local midnight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    today: LocalDate,
    timeline: TimelineUiState,
    photos: PhotoStorage,
    onOpenSettings: () -> Unit,
    onRecordMeal: () -> Unit,
    onOpenSleepHistory: () -> Unit,
    onRecordWorkout: () -> Unit,
    onOpenWorkoutHistory: () -> Unit,
    onOpenMeal: (String) -> Unit,
    onEditSleep: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onRetryTimeline: () -> Unit,
    sleepViewModel: SleepViewModel,
    homeViewModel: HomeViewModel,
    workViewModel: com.personal.lifelog.feature.work.WorkViewModel,
    workHomeViewModel: WorkHomeViewModel,
    onOpenWorkHistory: () -> Unit,
    onEditWork: (String) -> Unit,
    todoContent: @Composable () -> Unit = {},
    onQuickRecord: (Boolean) -> Unit = {},
) {
    val sleepStatus by sleepViewModel.status.collectAsStateWithLifecycle()
    val sleepBusy by sleepViewModel.busy.collectAsStateWithLifecycle()
    val activeSleep = sleepStatus.active
    val startId = remember(activeSleep?.id) { RecordIds.newId() }
    val sleepCard by homeViewModel.sleep.collectAsStateWithLifecycle()
    val goal by homeViewModel.goal.collectAsStateWithLifecycle()
    val workCard by workHomeViewModel.work.collectAsStateWithLifecycle()
    val workGoal by workHomeViewModel.goal.collectAsStateWithLifecycle()
    val workStatus by workViewModel.status.collectAsStateWithLifecycle()
    val workBusy by workViewModel.busy.collectAsStateWithLifecycle()
    val activeWork = workStatus.active
    val workStartId = remember(activeWork?.id) { RecordIds.newId() }
    val sleepElapsed by rememberActiveElapsed(activeSleep?.startedAtUtcMs, sleepViewModel.clock)
    val workElapsed by rememberActiveElapsed(activeWork?.startedAtUtcMs, workViewModel.clock)
    var quickExpanded by rememberSaveable { mutableStateOf(true) }
    DurationGoalDialog(workGoal, workHomeViewModel::goalHours, workHomeViewModel::goalMinutes,
        workHomeViewModel::cancelGoal, workHomeViewModel::saveGoal, R.string.home_work_goal_title,
        R.string.home_work_goal_hint, "work", workHomeViewModel::clearGoal)
    com.personal.lifelog.feature.work.WorkFinishConfirmation(workViewModel)

    SleepGoalDialog(goal, homeViewModel::goalHours, homeViewModel::goalMinutes,
        homeViewModel::cancelGoal, homeViewModel::saveGoal)
    SleepFinishConfirmation(sleepViewModel)

    Scaffold(
        topBar = {
            RecordTopBar(
                title = {
                    Column {
                        Text(text = stringResource(R.string.nav_today))
                        Text(
                            text = homeDateFormatter.format(today),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.open_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth().testTag("home-timeline"),
                contentPadding = PaddingValues(horizontal = LogDimens.page, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "duration-summaries") {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("duration-summaries"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SleepSummaryCard(sleepCard, onOpenSleepHistory, homeViewModel::editGoal, homeViewModel::retrySleep,
                            Modifier.weight(1f).fillMaxHeight(), activeDurationMs = sleepElapsed.takeIf { sleepCard.content?.hasActiveSleep == true })
                        WorkSummaryCard(workCard, onOpenWorkHistory, workHomeViewModel::editGoal, workHomeViewModel::retryWork,
                            Modifier.weight(1f).fillMaxHeight(), activeDurationMs = workElapsed.takeIf { workCard.content?.hasActiveWork == true })
                    }
                }
                item(key = "daily-todos") {
                    todoContent()
                }
                item(key = "sleep-feedback") {
                    SleepHomeFeedback(sleepViewModel, onEditSleep, {}, showRecordActions = false)
                }
                item(key = "work-feedback") {
                    com.personal.lifelog.feature.work.WorkHomeFeedback(workViewModel, onEditWork, {}, showRecordActions = false)
                }
                item(key = "timeline-header") {
                    TodayTimelineHeader(onOpenWorkoutHistory)
                }
                timelineSection(
                    state = timeline,
                    photos = photos,
                    onOpenMeal = onOpenMeal,
                    onOpenSleep = onEditSleep,
                    onOpenWorkout = onOpenWorkout,
                    onOpenWork = onEditWork,
                    onRetry = onRetryTimeline,
                )
            }
            ActionButton(onClick = { quickExpanded = !quickExpanded },
                modifier = Modifier.fillMaxWidth().testTag("home-quick-toggle")) {
                Icon(if (quickExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(20.dp))
                Text(stringResource(if (quickExpanded) R.string.home_quick_collapse else R.string.home_quick_expand))
            }
            if (quickExpanded) HomeQuickActions(
                sleepLabel = stringResource(when {
                    sleepBusy -> R.string.saving
                    !sleepStatus.loaded -> R.string.sleep_loading
                    activeSleep == null -> R.string.sleep_start
                    else -> R.string.sleep_finish
                }),
                workLabel = stringResource(when {
                    workBusy -> R.string.saving
                    !workStatus.loaded -> R.string.work_loading
                    activeWork == null -> R.string.work_start
                    else -> R.string.work_finish
                }),
                workActive = activeWork != null,
                sleepEnabled = sleepStatus.loaded && !sleepBusy,
                workEnabled = workStatus.loaded && !workStatus.failed && !workBusy && !workCard.loading && !workCard.failed,
                onSleep = { if (activeSleep == null) sleepViewModel.start(startId) else sleepViewModel.finish(activeSleep) },
                onWork = { if (activeWork == null) workViewModel.start(workStartId) else workViewModel.finish(activeWork) },
                onMeal = onRecordMeal,
                onWorkout = onRecordWorkout,
                onVoice = { onQuickRecord(true) },
                modifier = Modifier.padding(horizontal = LogDimens.page, vertical = 4.dp).testTag("home-quick-actions"),
            )
        }
    }
}

/** Four existing entries share an icon-only voice entry at their intersection. */
@Composable
internal fun HomeQuickActions(
    sleepLabel: String,
    workLabel: String,
    workActive: Boolean,
    sleepEnabled: Boolean,
    workEnabled: Boolean,
    onSleep: () -> Unit,
    onWork: () -> Unit,
    onMeal: () -> Unit,
    onWorkout: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reserve the inner edge of every card for the round button. Text can wrap at
    // large font sizes without entering the microphone's visual or touch bounds.
    val startCardPadding = PaddingValues(start = 12.dp, end = 32.dp, top = 8.dp, bottom = 8.dp)
    val endCardPadding = PaddingValues(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    Box(modifier.fillMaxWidth()) {
        Layout(modifier = Modifier.fillMaxWidth().testTag("home-voice-module"), content = {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard(sleepLabel, Icons.Filled.Bedtime, onSleep,
                    Modifier.weight(1f).fillMaxHeight().testTag("sleep-start-finish"), sleepEnabled, startCardPadding)
                QuickActionCard(workLabel, if (workActive) Icons.Filled.StopCircle else Icons.Filled.WorkOutline, onWork,
                    Modifier.weight(1f).fillMaxHeight().testTag("work-start-finish"), workEnabled, endCardPadding)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard(stringResource(R.string.home_quick_meal), Icons.Filled.Restaurant, onMeal,
                    Modifier.weight(1f).fillMaxHeight().testTag("home-quick-meal"), contentPadding = startCardPadding)
                QuickActionCard(stringResource(R.string.home_quick_workout), Icons.Filled.FitnessCenter, onWorkout,
                    Modifier.weight(1f).fillMaxHeight().testTag("home-quick-workout"), contentPadding = endCardPadding)
            }
            Surface(onClick = onVoice, modifier = Modifier.size(56.dp).testTag("home-voice-start"),
                shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(4.dp, MaterialTheme.colorScheme.background)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, stringResource(R.string.quick_voice_entry), Modifier.size(24.dp))
                }
            }
        }) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0)
            val top = measurables[0].measure(loose)
            val bottom = measurables[1].measure(loose)
            val microphone = measurables[2].measure(loose.copy(minWidth = 0))
            val gap = 8.dp.roundToPx()
            layout(top.width, top.height + gap + bottom.height) {
                top.placeRelative(0, 0)
                bottom.placeRelative(0, top.height + gap)
                // Anchor to the row boundary, including when large text makes the rows unequal.
                microphone.placeRelative((top.width - microphone.width) / 2, top.height + (gap - microphone.height) / 2)
            }
        }
    }
}

/** Compact thumb-friendly entry card; grows with text and keeps a 48dp touch target. */
@Composable
private fun QuickActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.minimumInteractiveComponentSize().padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center)
        }
    }
}
