package com.personal.lifelog.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.theme.LogDimens

/** Presentation values only. Completed intervals are supplied by T18, never summed here. */
data class SleepCardContent(
    val targetMinutes: Int?,
    val recordedDurationMs: Long?,
    val completedSegmentCount: Int,
    val hasActiveSleep: Boolean,
)

data class SleepCardState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: SleepCardContent? = null,
)

/** Milliseconds remain intact until division; an unset target/total has no progress. */
internal fun durationProgress(recordedDurationMs: Long?, targetMinutes: Int?): Float? {
    if (recordedDurationMs == null || targetMinutes == null || targetMinutes !in 1..1440) return null
    return (recordedDurationMs.toDouble() / (targetMinutes * 60_000L)).coerceIn(0.0, 1.0).toFloat()
}

internal fun sleepProgress(recordedDurationMs: Long?, targetMinutes: Int?) = durationProgress(recordedDurationMs, targetMinutes)

@Composable
internal fun durationMinutesText(minutes: Long): String = when {
    minutes % 60 == 0L -> stringResource(R.string.home_sleep_hours, minutes / 60)
    minutes < 60 -> stringResource(R.string.home_sleep_minutes, minutes)
    else -> stringResource(R.string.home_sleep_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
internal fun recordedDurationText(ms: Long): String = when {
    ms < 1_000 -> stringResource(R.string.home_sleep_under_second)
    ms < 60_000 -> stringResource(R.string.home_sleep_seconds, ms / 1_000)
    else -> durationMinutesText(ms / 60_000)
}

@Composable
fun SleepSummaryCard(
    state: SleepCardState,
    onOpenHistory: () -> Unit,
    onEditTarget: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = state.content
    val target = content?.targetMinutes?.let { durationMinutesText(it.toLong()) }
    val targetLabel = if (target == null) stringResource(R.string.home_sleep_target_unset)
        else stringResource(R.string.home_sleep_target, target)
    RecordCard(modifier.fillMaxWidth().testTag("sleep-summary-card")) {
        Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_sleep_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                com.personal.lifelog.ui.components.SecondaryButton(onClick = onEditTarget, enabled = content != null && !state.loading && !state.failed,
                    modifier = Modifier.testTag("sleep-target-edit")) {
                    Text(stringResource(if (target == null) R.string.home_sleep_set else R.string.home_sleep_change))
                }
            }
            if (content != null) Text(targetLabel, style = MaterialTheme.typography.bodyLarge)
            if (state.loading) Text(stringResource(R.string.home_sleep_loading))
            if (state.failed) ErrorState(stringResource(R.string.home_sleep_read_error), onRetry = onRetry)
            if (state.failed && content != null) Text(stringResource(R.string.home_sleep_stale), style = MaterialTheme.typography.bodyMedium)
            // History is available even before a first successful read or without a target.
            Surface(onClick = onOpenHistory, shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().testTag("sleep-summary-history")) {
                Column(Modifier.padding(LogDimens.group), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
                    if (content != null) {
                        val duration = content.recordedDurationMs?.let { recordedDurationText(it) }
                        val recordedLabel = duration?.let { stringResource(R.string.home_sleep_recorded_value, it) }
                            ?: stringResource(R.string.home_sleep_no_completed)
                        val activeLabel = if (content.hasActiveSleep) stringResource(R.string.home_sleep_active) else ""
                        val description = listOf(targetLabel, recordedLabel, activeLabel).filter { it.isNotEmpty() }.joinToString("，")
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.2f
                            val chart: @Composable () -> Unit = {
                                DurationProgressRing(if (state.failed || state.loading) null
                                    else sleepProgress(content.recordedDurationMs, content.targetMinutes), description)
                            }
                            val values: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (duration == null) Text(recordedLabel, style = MaterialTheme.typography.titleSmall)
                                    else {
                                        Text(stringResource(R.string.home_sleep_recorded), style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(duration, style = MaterialTheme.typography.titleLarge)
                                        Text(stringResource(R.string.home_sleep_segments, content.completedSegmentCount),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (content.hasActiveSleep) Text(activeLabel, color = MaterialTheme.colorScheme.primary)
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
                    Text(stringResource(R.string.home_sleep_source), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.personal.lifelog.ui.components.SecondaryButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_sleep_history), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DurationProgressRing(progress: Float?, description: String, icon: ImageVector = Icons.Filled.Bedtime, tag: String = "sleep-progress") {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(Modifier.size(112.dp).semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val diameter = size.minDimension - stroke
            drawArc(track, -90f, 360f, false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke))
            if (progress != null && progress > 0f) {
                drawArc(fill, -90f, progress * 360f, false, Offset(inset, inset), Size(diameter, diameter),
                    style = Stroke(stroke, cap = StrokeCap.Butt))
            }
        }
        Icon(icon, null, Modifier.size(28.dp), tint = if (progress == null) MaterialTheme.colorScheme.onSurfaceVariant else fill)
    }
}
