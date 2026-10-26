package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.SecondaryButton

/** Compact, shared visuals; each caller retains its own summary, goals and commands. */
@Composable
internal fun DurationSummaryCard(
    kind: DurationKind,
    targetMinutes: Int?,
    recordedDurationMs: Long?,
    active: Boolean,
    hasContent: Boolean,
    loading: Boolean,
    failed: Boolean,
    onOpenHistory: () -> Unit,
    onEditTarget: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    activeDurationMs: Long? = null,
) {
    val sleep = kind == DurationKind.SLEEP
    val prefix = if (sleep) "sleep" else "work"
    val target = targetMinutes?.let { durationMinutesText(it.toLong()) }
    val targetLabel = if (target == null) stringResource(if (sleep) R.string.home_sleep_target_unset else R.string.home_work_target_unset)
        else stringResource(if (sleep) R.string.home_sleep_target else R.string.home_work_target, target)
    val duration = recordedDurationMs?.let { recordedDurationText(it) }
    val isActive = active || activeDurationMs != null
    val activeLabel = if (isActive) stringResource(if (sleep) R.string.home_sleep_active else R.string.home_work_active) else null
    val elapsed = if (hasContent && !loading && !failed) activeDurationMs?.let { recordedDurationText(it) } else null
    val previewMs = previewDurationMs(recordedDurationMs, if (elapsed != null) activeDurationMs else null)
    val elapsedLabel = elapsed?.let { stringResource(R.string.home_active_elapsed, it) }
    val completedLabel = duration?.let { if (elapsed != null) stringResource(R.string.home_completed_duration, it) else it }
    val feedback = if (hasContent && !failed && !loading) durationFeedbackText(kind,
        durationFeedback(kind, recordedDurationMs, targetMinutes, isActive)) else null
    val description = listOfNotNull(targetLabel, completedLabel, activeLabel, elapsedLabel, feedback).joinToString("，")
    RecordCard(modifier.testTag("$prefix-summary-card")) {
        Column(Modifier.fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (sleep) R.string.home_sleep_title else R.string.home_work_title),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            val editLabel = stringResource(if (sleep) R.string.home_sleep_goal_title else R.string.home_work_goal_title)
            OutlinedButton(onClick = onEditTarget, enabled = hasContent && !loading && !failed,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("$prefix-target-edit")
                    .semantics { contentDescription = editLabel }) {
                Text(if (hasContent) targetLabel else stringResource(if (loading) R.string.home_duration_goal_loading
                    else R.string.home_duration_goal_unavailable), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
            }
            val historyLabel = stringResource(if (sleep) R.string.home_sleep_open_history else R.string.home_work_open_history)
            // The center is the record entry. The target above is a separate click target.
            Surface(onClick = onOpenHistory, color = Color.Transparent, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 48.dp).testTag("$prefix-summary-history")
                    .semantics { contentDescription = historyLabel }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationProgressRing(if (loading || failed) null else durationProgress(previewMs, targetMinutes),
                        description, if (sleep) Icons.Filled.Bedtime else Icons.Filled.WorkOutline, "$prefix-progress", 88.dp)
                    if (elapsedLabel != null) {
                        Text(activeLabel.orEmpty(), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        Text(elapsedLabel, Modifier.fillMaxWidth().testTag("$prefix-active-duration"),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        if (duration != null) Text(completedLabel.orEmpty(), Modifier.fillMaxWidth().testTag("$prefix-duration"),
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.home_active_preview_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    } else {
                        if (duration != null) Text(duration, Modifier.fillMaxWidth().testTag("$prefix-duration"),
                            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        if (activeLabel != null) Text(activeLabel, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    if (loading) Text(stringResource(if (sleep) R.string.home_sleep_loading else R.string.home_work_loading),
                        style = MaterialTheme.typography.bodyMedium)
                    if (hasContent && recordedDurationMs == null && elapsed == null && !loading && !failed) {
                        Text(stringResource(if (sleep) R.string.home_sleep_no_completed else R.string.home_work_no_completed),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (feedback != null) Text(feedback, Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("$prefix-feedback"),
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            if (failed) {
                Text(stringResource(if (sleep) R.string.home_sleep_read_error else R.string.home_work_read_error),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                if (hasContent) Text(stringResource(if (sleep) R.string.home_sleep_stale else R.string.home_work_stale),
                    style = MaterialTheme.typography.bodySmall)
                SecondaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
