package com.personal.lifelog.feature.review

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.personal.lifelog.ui.components.RecordDatePicker
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.LoadingState
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.components.TonalCard
import com.personal.lifelog.ui.theme.LogDimens
import com.personal.lifelog.ui.theme.TabularTextStyle
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
    onOpenWork: (String) -> Unit = {},
    onOpenAiReviewTest: () -> Unit = {},
) {
    val selected by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val recorded by viewModel.recordedDates.collectAsStateWithLifecycle()
    val aiPreview by viewModel.aiReviewPreview.collectAsStateWithLifecycle()
    val aiSend by viewModel.aiReviewSend.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasAiReviewApiKey.collectAsStateWithLifecycle()
    var showDates by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
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
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                            onClick = { pickingDate = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DateRange,
                                contentDescription = null,
                            )
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelLarge.merge(TabularTextStyle),
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
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ActionButton(onClick = viewModel::goToToday) {
                            Text(stringResource(R.string.review_today))
                        }
                        ActionButton(onClick = { showDates = true }) {
                            Text(stringResource(R.string.review_recorded_dates))
                        }
                        if (viewModel.supportsAiReviewPreview) {
                            ActionButton(
                                onClick = viewModel::previewAiReview,
                                modifier = Modifier.testTag("review-ai-preview-button"),
                            ) {
                                Text(stringResource(R.string.review_ai_preview_action))
                            }
                        }
                        if (viewModel.supportsAiReviewSend) {
                            ActionButton(
                                onClick = onOpenAiReviewTest,
                                modifier = Modifier.testTag("review-ai-test-entry"),
                            ) {
                                Text(stringResource(R.string.ai_review_test_entry))
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = LogDimens.page, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (aiPreview !is DailyReviewPreviewState.Idle) {
                    item(key = "ai-review-preview") {
                        DailyAiReviewPreviewCard(
                            selectedDate = selected,
                            state = aiPreview,
                            onRetry = viewModel::previewAiReview,
                            onDismiss = viewModel::dismissAiReviewPreview,
                            supportsSend = viewModel.supportsAiReviewSend,
                            hasApiKey = hasApiKey,
                            sendState = aiSend,
                            onRequestSend = viewModel::requestSendAiReview,
                            onConfirmSend = viewModel::confirmSendAiReview,
                            onCancelSend = viewModel::cancelSendAiReview,
                            onRetrySend = viewModel::retrySendAiReview,
                            onOpenAiReviewTest = onOpenAiReviewTest,
                        )
                    }
                }
                timelineSection(
                    state = day,
                    photos = photos,
                    onOpenMeal = onOpenMeal,
                    onOpenSleep = onOpenSleep,
                    onOpenWorkout = onOpenWorkout,
                    onOpenWork = onOpenWork,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    if (pickingDate) RecordDatePicker(R.string.picker_date_title, selected, { pickingDate = false }, {
        viewModel.selectDate(it); pickingDate = false
    })
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

                stored == null -> LoadingState(stringResource(R.string.review_dates_loading))

                dates.isEmpty() -> Text(stringResource(R.string.review_dates_empty))

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = dates, key = { it.toString() }) { date ->
                        val isSelected = date == selected
                        TonalCard(
                            onClick = { onSelect(date) },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = LogDimens.touch)
                                .testTag("recorded-date-$date"),
                        ) {
                            Text(
                                text = reviewDateFormatter.format(date),
                                style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
