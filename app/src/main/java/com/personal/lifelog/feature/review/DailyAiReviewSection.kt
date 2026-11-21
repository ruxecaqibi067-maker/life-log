package com.personal.lifelog.feature.review

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.domain.DailyReviewIntervalSection
import com.personal.lifelog.domain.DailyReviewPayload
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.LoadingState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.components.SectionHeader
import com.personal.lifelog.ui.components.TonalCard
import com.personal.lifelog.ui.theme.LogDimens
import com.personal.lifelog.ui.theme.TabularTextStyle
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val timeOnlyFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
internal fun DailyAiReviewPreviewCard(
    selectedDate: LocalDate,
    state: DailyReviewPreviewState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    supportsSend: Boolean = false,
    hasApiKey: Boolean = false,
    sendState: DailyReviewSendState = DailyReviewSendState.Idle,
    onRequestSend: () -> Unit = {},
    onConfirmSend: () -> Unit = {},
    onCancelSend: () -> Unit = {},
    onRetrySend: () -> Unit = {},
    onOpenAiReviewTest: () -> Unit = {},
) {
    if (state is DailyReviewPreviewState.Idle) return

    RecordCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("review-ai-preview-card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LogDimens.card),
            verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader(title = stringResource(R.string.review_ai_preview_title))
                    Text(
                        text = stringResource(R.string.review_ai_preview_date, selectedDate.toString()),
                        style = MaterialTheme.typography.bodySmall.merge(TabularTextStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("review-ai-preview-close"),
                ) {
                    Text(stringResource(R.string.review_ai_preview_close))
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )

            when (state) {
                DailyReviewPreviewState.Idle -> Unit
                is DailyReviewPreviewState.Loading -> {
                    LoadingState(
                        message = stringResource(R.string.review_ai_preview_loading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("review-ai-preview-loading"),
                    )
                }
                is DailyReviewPreviewState.Failed -> {
                    val messageRes = when (state.error.code) {
                        ErrorCode.STORAGE_FULL -> R.string.review_ai_preview_failed_full
                        ErrorCode.STORAGE_UNAVAILABLE -> R.string.review_ai_preview_failed_unavailable
                        else -> R.string.review_ai_preview_failed
                    }
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("review-ai-preview-error"),
                    )
                    SecondaryButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("review-ai-preview-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is DailyReviewPreviewState.Stale -> {
                    Text(
                        text = stringResource(R.string.review_ai_preview_stale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("review-ai-preview-stale"),
                    )
                    SecondaryButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("review-ai-preview-regenerate"),
                    ) {
                        Text(stringResource(R.string.review_ai_preview_regenerate))
                    }
                }
                is DailyReviewPreviewState.Ready -> {
                    val snapshot = state.snapshot
                    val payload = snapshot.payload
                    // Memory-only toggle; never persisted via rememberSaveable.
                    var jsonExpanded by remember(snapshot.rawJson) { mutableStateOf(false) }

                    Text(
                        text = stringResource(
                            R.string.review_ai_preview_meta,
                            formatClockLabel(payload.snapshotAt, payload.selectedDate),
                            payload.timeZone,
                            payload.schemaVersion,
                        ),
                        style = MaterialTheme.typography.bodySmall.merge(TabularTextStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ReadableSummaryBlock(payload)

                    Text(
                        text = stringResource(R.string.review_ai_preview_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SecondaryButton(
                        onClick = { jsonExpanded = !jsonExpanded },
                        modifier = Modifier.testTag("review-ai-preview-toggle-json"),
                    ) {
                        Text(
                            stringResource(
                                if (jsonExpanded) {
                                    R.string.review_ai_preview_collapse_json
                                } else {
                                    R.string.review_ai_preview_expand_json
                                },
                            ),
                        )
                    }

                    if (jsonExpanded) {
                        TonalCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = snapshot.rawJson,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp)
                                    .testTag("review-ai-preview-json"),
                            )
                        }
                    }

                    if (supportsSend) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
                        DailyReviewSendSection(
                            ready = state,
                            hasApiKey = hasApiKey,
                            sendState = sendState,
                            onRequestSend = onRequestSend,
                            onConfirmSend = onConfirmSend,
                            onCancelSend = onCancelSend,
                            onRetrySend = onRetrySend,
                            onOpenAiReviewTest = onOpenAiReviewTest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyReviewSendSection(
    ready: DailyReviewPreviewState.Ready,
    hasApiKey: Boolean,
    sendState: DailyReviewSendState,
    onRequestSend: () -> Unit,
    onConfirmSend: () -> Unit,
    onCancelSend: () -> Unit,
    onRetrySend: () -> Unit,
    onOpenAiReviewTest: () -> Unit,
) {
    // Only render send states belonging to this exact preview generation and date.
    val matchingSendState = when (sendState) {
        DailyReviewSendState.Idle -> DailyReviewSendState.Idle
        is DailyReviewSendState.Confirming ->
            if (sendState.generationId == ready.generationId && sendState.date == ready.date) sendState else DailyReviewSendState.Idle
        is DailyReviewSendState.Sending ->
            if (sendState.generationId == ready.generationId && sendState.date == ready.date) sendState else DailyReviewSendState.Idle
        is DailyReviewSendState.Succeeded ->
            if (sendState.generationId == ready.generationId && sendState.date == ready.date) sendState else DailyReviewSendState.Idle
        is DailyReviewSendState.Failed ->
            if (sendState.generationId == ready.generationId && sendState.date == ready.date) sendState else DailyReviewSendState.Idle
    }

    when (matchingSendState) {
        DailyReviewSendState.Idle -> {
            if (!hasApiKey) {
                Text(
                    text = stringResource(R.string.review_ai_send_need_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryButton(
                    onClick = onOpenAiReviewTest,
                    modifier = Modifier.testTag("review-ai-open-key"),
                ) {
                    Text(stringResource(R.string.review_ai_send_open_key))
                }
            } else {
                PrimaryButton(
                    onClick = onRequestSend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("review-ai-send-open"),
                ) {
                    Text(stringResource(R.string.review_ai_send_action))
                }
            }
        }
        is DailyReviewSendState.Confirming -> {
            PrimaryButton(
                onClick = onRequestSend,
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review-ai-send-open"),
            ) {
                Text(stringResource(R.string.review_ai_send_action))
            }
            AlertDialog(
                onDismissRequest = onCancelSend,
                title = { Text(stringResource(R.string.review_ai_send_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.review_ai_send_confirm_date, ready.date.toString()),
                            style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle),
                        )
                        Text(
                            text = stringResource(R.string.review_ai_send_confirm_recipient),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.review_ai_send_confirm_cost),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    PrimaryButton(
                        onClick = onConfirmSend,
                        modifier = Modifier.testTag("review-ai-send-confirm"),
                    ) {
                        Text(stringResource(R.string.review_ai_send_confirm_action))
                    }
                },
                dismissButton = {
                    ActionButton(
                        onClick = onCancelSend,
                        modifier = Modifier.testTag("review-ai-send-cancel"),
                    ) {
                        Text(stringResource(R.string.ai_review_cancel))
                    }
                },
            )
        }
        is DailyReviewSendState.Sending -> {
            LoadingState(
                message = stringResource(R.string.review_ai_send_loading),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review-ai-send-loading"),
            )
        }
        is DailyReviewSendState.Succeeded -> {
            TonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review-ai-send-result"),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.review_ai_send_result_title, ready.date.toString()),
                        style = MaterialTheme.typography.labelLarge.merge(TabularTextStyle),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = matchingSendState.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        is DailyReviewSendState.Failed -> {
            Text(
                text = stringResource(deepSeekErrorMessageRes(matchingSendState.error)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("review-ai-send-error"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
            ) {
                if (matchingSendState.error == DeepSeekError.MISSING_API_KEY ||
                    matchingSendState.error == DeepSeekError.INVALID_API_KEY ||
                    matchingSendState.error == DeepSeekError.UNAUTHORIZED
                ) {
                    SecondaryButton(
                        onClick = onOpenAiReviewTest,
                        modifier = Modifier.testTag("review-ai-open-key"),
                    ) {
                        Text(stringResource(R.string.review_ai_send_open_key))
                    }
                }
                if (matchingSendState.error != DeepSeekError.BAD_REQUEST) {
                    SecondaryButton(
                        onClick = onRetrySend,
                        enabled = hasApiKey,
                        modifier = Modifier.testTag("review-ai-send-retry"),
                    ) {
                        Text(stringResource(R.string.review_ai_send_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadableSummaryBlock(payload: DailyReviewPayload) {
    TonalCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review-ai-preview-summary"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!payload.hasAnyRecord) {
                Text(
                    text = stringResource(R.string.review_ai_preview_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val sleepGoal = payload.sleep.targetMinutes?.let {
                    stringResource(R.string.review_ai_preview_minutes, it)
                } ?: stringResource(R.string.review_ai_preview_goal_unset)
                val workGoal = payload.work.targetMinutes?.let {
                    stringResource(R.string.review_ai_preview_minutes, it)
                } ?: stringResource(R.string.review_ai_preview_goal_unset)
                SummaryLine(
                    label = stringResource(R.string.review_ai_preview_goals_label),
                    value = stringResource(R.string.review_ai_preview_goals_summary, sleepGoal, workGoal),
                )
            } else {
                SummaryLine(
                    label = stringResource(R.string.review_ai_preview_sleep_label),
                    value = formatIntervalSummary(payload.sleep, payload.selectedDate),
                )
                SummaryLine(
                    label = stringResource(R.string.review_ai_preview_work_label),
                    value = formatIntervalSummary(payload.work, payload.selectedDate),
                )
                SummaryLine(
                    label = stringResource(R.string.review_ai_preview_meals_label),
                    value = if (payload.meals.recordCount == 0) {
                        stringResource(R.string.review_ai_preview_not_recorded)
                    } else {
                        stringResource(
                            R.string.review_ai_preview_meals_summary,
                            payload.meals.recordCount,
                            payload.meals.photoOnlyRecordCount,
                            payload.meals.totalPhotoCount,
                        )
                    },
                )
                SummaryLine(
                    label = stringResource(R.string.review_ai_preview_workouts_label),
                    value = if (payload.workouts.sessionCount == 0) {
                        stringResource(R.string.review_ai_preview_not_recorded)
                    } else {
                        val base = stringResource(
                            R.string.review_ai_preview_workouts_summary,
                            payload.workouts.sessionCount,
                            payload.workouts.itemCount,
                        )
                        val names = payload.workouts.sessions
                            .flatMap { it.items }
                            .map { it.name }
                            .joinToString("、")
                        if (names.isEmpty()) base else "$base（$names）"
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun formatIntervalSummary(section: DailyReviewIntervalSection, selectedDate: String): String {
    val activeLabel = section.activeStartedAt?.let { formatClockLabel(it, selectedDate) }
    val body = when {
        section.completedSegmentCount > 0 && section.completedDurationMinutes != null -> {
            val completed = stringResource(
                R.string.review_ai_preview_interval_completed,
                section.completedSegmentCount,
                section.completedDurationMinutes,
            )
            if (section.hasActiveSegment && activeLabel != null) {
                completed + stringResource(R.string.review_ai_preview_interval_active_extra, activeLabel)
            } else {
                completed
            }
        }
        section.hasActiveSegment && activeLabel != null -> {
            stringResource(R.string.review_ai_preview_interval_active_only, activeLabel)
        }
        else -> stringResource(R.string.review_ai_preview_not_recorded)
    }
    val goalSuffix = section.targetMinutes?.let {
        stringResource(R.string.review_ai_preview_goal_suffix, it)
    }.orEmpty()
    return body + goalSuffix
}

private fun formatClockLabel(isoOffset: String, selectedDate: String): String =
    runCatching {
        val parsed = OffsetDateTime.parse(isoOffset)
        if (parsed.toLocalDate().toString() == selectedDate) {
            timeOnlyFormatter.format(parsed)
        } else {
            dateTimeFormatter.format(parsed)
        }
    }.getOrDefault(isoOffset)
