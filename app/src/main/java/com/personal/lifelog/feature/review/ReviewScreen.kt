package com.personal.lifelog.feature.review

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.feature.timeline.RecordedDatesState
import com.personal.lifelog.feature.timeline.timelineSection
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reviewDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

/**
 * 回顾: date navigation plus the same unified timeline used by 今天. The query is
 * keyed by the stored local date, so past and empty days are shown distinctly,
 * and a record edited to another date leaves this list and appears there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    photos: PhotoStorage,
    onOpenSettings: () -> Unit,
    onOpenMeal: (String) -> Unit,
    onOpenSleep: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    val selected by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val recorded by viewModel.recordedDates.collectAsStateWithLifecycle()
    var showDates by rememberSaveable { mutableStateOf(false) }
    val context = com.personal.lifelog.ui.components.rememberPickerContext()
    val dateLabel = reviewDateFormatter.format(selected)

    Scaffold(
        topBar = {
            RecordTopBar(
                title = { Text(text = stringResource(R.string.nav_review)) },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::previousDay) {
                    Icon(
                        imageVector = Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.review_previous_day),
                    )
                }
                SecondaryButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                viewModel.selectDate(LocalDate.of(year, month + 1, dayOfMonth))
                            },
                            selected.year,
                            selected.monthValue - 1,
                            selected.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                    )
                    Text(
                        text = dateLabel,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                IconButton(onClick = viewModel::nextDay) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.review_next_day),
                    )
                }
            }
            FlowRow(
                modifier = Modifier.padding(horizontal = LogDimens.page),
                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
            ) {
                ActionButton(onClick = viewModel::goToToday) {
                    Text(stringResource(R.string.review_today))
                }
                ActionButton(onClick = { showDates = true }) {
                    Text(stringResource(R.string.review_recorded_dates))
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = LogDimens.page, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                timelineSection(
                    state = day,
                    photos = photos,
                    onOpenMeal = onOpenMeal,
                    onOpenSleep = onOpenSleep,
                    onOpenWorkout = onOpenWorkout,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    if (showDates) {
        RecordedDatesDialog(
            state = recorded,
            selected = selected,
            onSelect = {
                viewModel.selectDate(it)
                showDates = false
            },
            onRetry = viewModel::retry,
            onDismiss = { showDates = false },
        )
    }
}

@Composable
private fun RecordedDatesDialog(
    state: RecordedDatesState,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = state.dates
    val dates = stored?.mapNotNull { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_recorded_dates_title)) },
        text = {
            when {
                state.failed -> Column {
                    Text(stringResource(R.string.review_dates_failed))
                    ActionButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }

                stored == null -> Text(stringResource(R.string.review_dates_loading))

                dates.isEmpty() -> Text(stringResource(R.string.review_dates_empty))

                else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(items = dates, key = { it.toString() }) { date ->
                        ActionButton(
                            onClick = { onSelect(date) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("recorded-date-$date"),
                        ) {
                            Text(
                                text = reviewDateFormatter.format(date),
                                color = if (date == selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ActionButton(onClick = onDismiss) { Text(stringResource(R.string.review_close)) }
        },
    )
}
