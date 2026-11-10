package com.personal.lifelog.core.time

import java.time.LocalDate
import java.time.ZoneId

/** Read-only time points shared by charts; never a persisted record or a daily archive. */
data class RecordedTimeInterval(val id: String, val startMs: Long, val endMs: Long?)

data class DayTimeSegment(val recordId: String, val startMs: Long, val endMs: Long)

data class DayTimeAnalysis(
    val date: LocalDate,
    val zone: ZoneId,
    val startMs: Long,
    val endMs: Long,
    val segments: List<DayTimeSegment>,
    val openStarts: List<Long>,
) {
    /** null means no completed intervals, not a measured zero hours. */
    val durationMs: Long? get() = segments.takeIf { it.isNotEmpty() }?.sumOf { it.endMs - it.startMs }
    fun fraction(instant: Long): Float = if (endMs <= startMs) 0f else ((instant - startMs).toDouble() / (endMs - startMs))
        .coerceIn(0.0, 1.0).toFloat()
}

fun dayTimeAnalysis(records: List<RecordedTimeInterval>, date: LocalDate, zone: ZoneId): DayTimeAnalysis {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val segments = records.mapNotNull { row ->
        val finish = row.endMs ?: return@mapNotNull null
        val from = maxOf(start, row.startMs)
        val to = minOf(end, finish)
        if (from < to) DayTimeSegment(row.id, from, to) else null
    }.sortedBy { it.startMs }
    val starts = records.filter { it.endMs == null && it.startMs >= start && it.startMs < end }
        .map { it.startMs }
    return DayTimeAnalysis(date, zone, start, end, segments, starts)
}
