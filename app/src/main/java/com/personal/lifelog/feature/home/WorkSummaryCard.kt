package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.theme.LogDimens

/** Presentation values only. Completed intervals are supplied by T20, never summed here. */
data class WorkCardContent(
    val targetMinutes: Int?,
    val recordedDurationMs: Long?,
    val completedSegmentCount: Int,
    val hasActiveWork: Boolean,
)

data class WorkCardState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: WorkCardContent? = null,
)

@Composable
fun WorkSummaryCard(
    state: WorkCardState,
    onOpenHistory: () -> Unit,
    onEditTarget: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    val content = state.content
    val target = content?.targetMinutes?.let { durationMinutesText(it.toLong()) }
    val targetLabel = if (target == null) stringResource(R.string.home_work_target_unset)
        else stringResource(R.string.home_work_target, target)
    RecordCard(modifier.fillMaxWidth().testTag("work-summary-card")) {
        Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_work_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                com.personal.lifelog.ui.components.SecondaryButton(onClick = onEditTarget, enabled = content != null && !state.loading && !state.failed,
                    modifier = Modifier.testTag("work-target-edit")) {
                    Text(stringResource(if (target == null) R.string.home_work_set else R.string.home_work_change))
                }
            }
            if (content != null) Text(targetLabel, style = MaterialTheme.typography.bodyLarge)
            if (state.loading) Text(stringResource(R.string.home_work_loading))
            if (state.failed) ErrorState(stringResource(R.string.home_work_read_error), onRetry = onRetry)
            if (state.failed && content != null) Text(stringResource(R.string.home_work_stale), style = MaterialTheme.typography.bodyMedium)
            // History is available even before a first successful read or without a target.
            Surface(onClick = onOpenHistory, shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("work-summary-history")) {
                Column(Modifier.padding(LogDimens.group), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
                    if (content != null) {
                        val duration = content.recordedDurationMs?.let { recordedDurationText(it) }
                        val recordedLabel = duration?.let { stringResource(R.string.home_work_recorded_value, it) }
                            ?: stringResource(R.string.home_work_no_completed)
                        val activeLabel = if (content.hasActiveWork) stringResource(R.string.home_work_active) else ""
                        val description = listOf(targetLabel, recordedLabel, activeLabel).filter { it.isNotEmpty() }.joinToString("，")
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.2f
                            val chart: @Composable () -> Unit = {
                                DurationProgressRing(if (state.failed || state.loading) null
                                    else durationProgress(content.recordedDurationMs, content.targetMinutes), description, Icons.Filled.WorkOutline, "work-progress")
                            }
                            val values: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (duration == null) Text(recordedLabel, style = MaterialTheme.typography.titleSmall)
                                    else {
                                        Text(stringResource(R.string.home_work_recorded), style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(duration, style = MaterialTheme.typography.titleLarge)
                                        Text(stringResource(R.string.home_work_segments, content.completedSegmentCount),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (content.hasActiveWork) Text(activeLabel, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LogDimens.group),
                                horizontalAlignment = Alignment.CenterHorizontally) { chart(); values() }
                            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LogDimens.card)) {
                                chart()
                                Box(Modifier.weight(1f)) { values() }
                            }
                        }
                    }
                    Text(stringResource(R.string.home_work_source), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.personal.lifelog.ui.components.SecondaryButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_work_history), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            actions()
        }
    }
}
