package com.personal.lifelog.feature.sleep

import com.personal.lifelog.data.local.entity.SleepRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read-only projections. Never rewrite the persisted end-day archive used by Home/Review. */
internal fun sleepHistoryDate(record: SleepRecord): LocalDate = LocalDate.parse(record.archiveLocalDate)

data class SleepDaySegment(val recordId: String, val startMs: Long, val endMs: Long)

data class SleepDayAnalysis(
    val date: LocalDate,
    val zone: ZoneId,
    val startMs: Long,
    val endMs: Long,
    val segments: List<SleepDaySegment>,
    val openStarts: List<Long>,
) {
    /** null means no completed intervals, not a measured zero hours. */
    val durationMs: Long? get() = segments.takeIf { it.isNotEmpty() }?.sumOf { it.endMs - it.startMs }
    fun fraction(instant: Long): Float = if (endMs <= startMs) 0f else ((instant - startMs).toDouble() / (endMs - startMs))
        .coerceIn(0.0, 1.0).toFloat()
}

internal fun sleepDayAnalysis(records: List<SleepRecord>, date: LocalDate, zone: ZoneId): SleepDayAnalysis {
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val segments = records.mapNotNull { row ->
        val finish = row.endedAtUtcMs ?: return@mapNotNull null
        val from = maxOf(start, row.startedAtUtcMs)
        val to = minOf(end, finish)
        if (from < to) SleepDaySegment(row.id, from, to) else null
    }.sortedBy { it.startMs }
    val starts = records.filter { it.endedAtUtcMs == null && it.startedAtUtcMs >= start && it.startedAtUtcMs < end }
        .map { it.startedAtUtcMs }
    return SleepDayAnalysis(date, zone, start, end, segments, starts)
}
