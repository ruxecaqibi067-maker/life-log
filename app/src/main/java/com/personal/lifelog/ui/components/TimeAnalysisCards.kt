package com.personal.lifelog.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.core.time.DayTimeAnalysis
import androidx.annotation.StringRes
import com.personal.lifelog.ui.theme.LogDimens
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TimeAnalysisLabels(
    @param:StringRes val dayTitle: Int,
    @param:StringRes val weekTitle: Int,
    @param:StringRes val openStarts: Int,
    @param:StringRes val explanation: Int,
    @param:StringRes val weekHint: Int,
    val tagPrefix: String,
)

val historyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
private val shortDate = DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE)
private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

@Composable
private fun durationText(ms: Long?): String = when {
    ms == null -> stringResource(R.string.time_analysis_no_completed)
    ms < 60_000 -> stringResource(R.string.time_analysis_under_minute)
    else -> stringResource(R.string.time_analysis_duration, ms / 3_600_000, ms % 3_600_000 / 60_000)
}

private fun DayTimeAnalysis.at(ms: Long): String {
    if (ms == endMs) return "24:00"
    val point = Instant.ofEpochMilli(ms).atZone(zone)
    // On a daylight-saving transition an offset disambiguates repeated local clock labels.
    return time.format(point) + if (endMs - startMs != 86_400_000L) " (${point.offset})" else ""
}

@Composable
fun DayTimeAnalysisCard(day: DayTimeAnalysis, labels: TimeAnalysisLabels) {
    RecordCard(Modifier.fillMaxWidth().testTag("${labels.tagPrefix}-day-analysis")) {
        Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
            Text(stringResource(labels.dayTitle), style = MaterialTheme.typography.titleMedium)
            Text(durationText(day.durationMs), style = MaterialTheme.typography.titleLarge)
            DayTimeBar(day, labels)
            if (day.segments.isNotEmpty()) Text(day.segments.joinToString("　") { "${day.at(it.startMs)}–${day.at(it.endMs)}" },
                style = MaterialTheme.typography.bodyMedium)
            if (day.openStarts.isNotEmpty()) Text(stringResource(labels.openStarts,
                day.openStarts.joinToString("、") { day.at(it) }), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(labels.explanation, day.zone.id), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WeekTimeAnalysisCard(days: List<DayTimeAnalysis>, labels: TimeAnalysisLabels, onSelectDate: (java.time.LocalDate) -> Unit) {
    if (days.isEmpty()) return
    RecordCard(Modifier.fillMaxWidth().testTag("${labels.tagPrefix}-week-analysis")) {
        Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
            Text(stringResource(labels.weekTitle), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.time_analysis_week_range,
                shortDate.format(days.first().date), shortDate.format(days.last().date)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            days.forEach { day ->
                RecordCard(onClick = { onSelectDate(day.date) }, modifier = Modifier.fillMaxWidth().testTag("${labels.tagPrefix}-analysis-${day.date}")) {
                    Column(Modifier.padding(LogDimens.group), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                            Text(shortDate.format(day.date), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(durationText(day.durationMs), Modifier.weight(1.5f), style = MaterialTheme.typography.bodyMedium)
                        }
                        DayTimeBar(day, labels)
                    }
                }
            }
            Text(stringResource(labels.weekHint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A completed interval is clipped to this real local day, including 23/25-hour DST days. */
@Composable
private fun DayTimeBar(day: DayTimeAnalysis, labels: TimeAnalysisLabels) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val duration = durationText(day.durationMs)
    val completed = day.segments.joinToString("，") { "${day.at(it.startMs)}至${day.at(it.endMs)}" }
    val open = if (day.openStarts.isEmpty()) "" else stringResource(labels.openStarts,
        day.openStarts.joinToString("、") { day.at(it) })
    val description = "${historyDateFormat.format(day.date)}，$duration，$completed，$open"
    val ticks = listOf(0, 6, 12, 18, 24).map { hour ->
        if (hour == 24) 1f else day.fraction(day.date.atTime(hour, 0).atZone(day.zone).toInstant().toEpochMilli())
    }
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).testTag("${labels.tagPrefix}-time-bar-${day.date}")) {
            drawRoundRect(track, cornerRadius = CornerRadius(4.dp.toPx()))
            ticks.forEach { fraction ->
                val x = (size.width * fraction).coerceIn(1f, size.width - 1f)
                drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            day.segments.forEach { segment ->
                val from = day.fraction(segment.startMs) * size.width
                val width = (day.fraction(segment.endMs) * size.width - from).coerceAtLeast(1.dp.toPx())
                    .coerceAtMost(size.width - from)
                drawRect(fill, Offset(from, 0f), Size(width, size.height))
            }
            day.openStarts.forEach { start ->
                val radius = 5.dp.toPx()
                val center = Offset((day.fraction(start) * size.width).coerceIn(radius, size.width - radius), size.height / 2)
                drawCircle(surface, radius, center)
                drawCircle(fill, radius, center, style = Stroke(2.dp.toPx()))
            }
        }
        Layout(content = {
            listOf("0", "6", "12", "18", "24时").forEach { label ->
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { measurables, constraints ->
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, labels.maxOf { it.height }) {
                labels.forEachIndexed { index, label ->
                    val x = (ticks[index] * constraints.maxWidth - label.width / 2).toInt()
                        .coerceIn(0, (constraints.maxWidth - label.width).coerceAtLeast(0))
                    label.placeRelative(x, 0)
                }
            }
        }
    }
}
