package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.DevBadge
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.sleep.SleepHomeStatus
import com.personal.lifelog.domain.RecordIds
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

/**
 * 今天 shell. The three quick actions are pinned just above the bottom navigation
 * bar (the natural thumb zone), while the timeline area fills the space above.
 * The timeline claim is data-derived via [hasRecordsToday]: nothing is asserted
 * before the first database read (null) and an empty day is only claimed when the
 * date index actually has no entries. The screen never fabricates records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    today: LocalDate,
    hasRecordsToday: Boolean?,
    onOpenSettings: () -> Unit,
    onRecordMeal: () -> Unit,
    onOpenSleep: () -> Unit,
    onRecordWorkout: () -> Unit,
    sleepViewModel: SleepViewModel,
    onEditSleep: (String) -> Unit,
) {
    val sleepStatus by sleepViewModel.status.collectAsStateWithLifecycle()
    val sleepBusy by sleepViewModel.busy.collectAsStateWithLifecycle()
    val activeSleep = sleepStatus.active
    val startId = remember(activeSleep?.id) { RecordIds.newId() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(R.string.nav_today))
                        Text(
                            text = homeDateFormatter.format(today),
                            style = MaterialTheme.typography.bodySmall,
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
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SleepHomeStatus(sleepViewModel, onOpenSleep, onEditSleep)
                Text(
                    text = stringResource(R.string.home_timeline_title),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    TimelineBody(hasRecordsToday = hasRecordsToday)
                }
            }
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionCard(
                    label = stringResource(R.string.home_quick_meal),
                    icon = Icons.Filled.Restaurant,
                    onClick = onRecordMeal,
                    modifier = Modifier.weight(1f),
                )
                QuickActionCard(
                    label = stringResource(when {
                        sleepBusy -> R.string.saving
                        !sleepStatus.loaded -> R.string.sleep_loading
                        activeSleep == null -> R.string.sleep_start
                        else -> R.string.sleep_finish
                    }),
                    icon = Icons.Filled.Bedtime,
                    onClick = { if (activeSleep == null) sleepViewModel.start(startId) else sleepViewModel.finish(activeSleep) },
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

@Composable
private fun TimelineBody(hasRecordsToday: Boolean?) {
    when (hasRecordsToday) {
        // First database read still pending: claim nothing.
        null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DevBadge(modifier = Modifier.padding(top = 24.dp))
            Text(
                text = stringResource(R.string.home_timeline_dev_note),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        true -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DevBadge(modifier = Modifier.padding(top = 24.dp))
            Text(
                text = stringResource(R.string.home_timeline_has_records_note),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        false -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                title = stringResource(R.string.empty_timeline_title),
                hint = stringResource(R.string.empty_timeline_hint),
                icon = Icons.AutoMirrored.Outlined.EventNote,
            )
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DevBadge()
                Text(
                    text = stringResource(R.string.home_timeline_dev_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
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
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(16.dp),
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
                modifier = Modifier.size(26.dp),
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
