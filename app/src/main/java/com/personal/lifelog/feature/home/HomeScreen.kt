package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.feature.sleep.SleepHomeStatus
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.timeline.TimelineUiState
import com.personal.lifelog.feature.timeline.timelineSection
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.theme.LogDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

/**
 * 今天: three quick entries, the global active-sleep status and the saved-data
 * timeline for the current local date. The timeline owns all three record types;
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
) {
    val sleepStatus by sleepViewModel.status.collectAsStateWithLifecycle()
    val sleepBusy by sleepViewModel.busy.collectAsStateWithLifecycle()
    val activeSleep = sleepStatus.active
    val startId = remember(activeSleep?.id) { RecordIds.newId() }

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
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = LogDimens.page, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "sleep-status") {
                    SleepHomeStatus(sleepViewModel, onOpenSleepHistory, onEditSleep)
                }
                item(key = "timeline-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.home_timeline_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        ActionButton(onClick = onOpenWorkoutHistory) {
                            Text(stringResource(R.string.workout_history))
                        }
                    }
                }
                timelineSection(
                    state = timeline,
                    photos = photos,
                    onOpenMeal = onOpenMeal,
                    onOpenSleep = onEditSleep,
                    onOpenWorkout = onOpenWorkout,
                    onRetry = onRetryTimeline,
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = LogDimens.page, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(LogDimens.group),
            ) {
                QuickActionCard(
                    label = stringResource(R.string.home_quick_meal),
                    icon = Icons.Filled.Restaurant,
                    onClick = onRecordMeal,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    label = stringResource(
                        when {
                            sleepBusy -> R.string.saving
                            !sleepStatus.loaded -> R.string.sleep_loading
                            activeSleep == null -> R.string.sleep_start
                            else -> R.string.sleep_finish
                        },
                    ),
                    icon = Icons.Filled.Bedtime,
                    onClick = {
                        if (activeSleep == null) sleepViewModel.start(startId) else sleepViewModel.finish(activeSleep)
                    },
                    enabled = sleepStatus.loaded && !sleepBusy,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    label = stringResource(R.string.home_quick_workout),
                    icon = Icons.Filled.FitnessCenter,
                    onClick = onRecordWorkout,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Large thumb-friendly entry card; at least 48dp touch target enforced explicitly. */
@Composable
private fun QuickActionCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 88.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
