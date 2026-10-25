package com.personal.lifelog.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R

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
    DurationSummaryCard(
        kind = DurationKind.SLEEP,
        targetMinutes = state.content?.targetMinutes,
        recordedDurationMs = state.content?.recordedDurationMs,
        active = state.content?.hasActiveSleep == true,
        hasContent = state.content != null,
        loading = state.loading,
        failed = state.failed,
        onOpenHistory = onOpenHistory,
        onEditTarget = onEditTarget,
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
internal fun DurationProgressRing(progress: Float?, description: String, icon: ImageVector = Icons.Filled.Bedtime, tag: String = "sleep-progress", diameterDp: androidx.compose.ui.unit.Dp = 112.dp) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(Modifier.size(diameterDp).semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (if (diameterDp < 100.dp) 10.dp else 12.dp).toPx()
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
