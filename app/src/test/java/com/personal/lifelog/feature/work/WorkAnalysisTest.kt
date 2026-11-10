package com.personal.lifelog.feature.work

import com.personal.lifelog.data.local.entity.WorkRecord
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

internal fun workAnalysisRecord(id: String, start: String, end: String?, zone: String = "Asia/Shanghai"): WorkRecord {
    val zoneId = ZoneId.of(zone)
    val from = LocalDateTime.parse(start).atZone(zoneId)
    val to = end?.let { LocalDateTime.parse(it).atZone(zoneId) }
    return WorkRecord(id, from.toInstant().toEpochMilli(), zone, to?.toInstant()?.toEpochMilli(),
        end?.let { zone }, (to ?: from).toLocalDate().toString(), if (end == null) 1 else null, 1, 1)
}

class WorkAnalysisTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 27)

    @Test fun endDateArchiveAndActualDayChartRemainSeparateAndKeepMilliseconds() {
        val night = workAnalysisRecord("night", "2026-10-26T23:00:00.123", "2026-10-27T01:00:00.456")
        val morning = workAnalysisRecord("morning", "2026-10-27T09:00", "2026-10-27T12:00")
        val afternoon = workAnalysisRecord("afternoon", "2026-10-27T14:00", "2026-10-27T16:30")
        val rows = listOf(night, morning, afternoon)
        assertEquals(date, workHistoryDate(night))
        assertEquals(3_599_877L, workDayAnalysis(rows, date.minusDays(1), zone).durationMs)
        val day = workDayAnalysis(rows, date, zone)
        assertEquals(23_400_456L, day.durationMs)
        assertEquals(3, day.segments.size)
        assertEquals(0f, day.fraction(day.segments.first().startMs), 0f)
        assertEquals("2026-10-27", night.archiveLocalDate)
    }

    @Test fun activeWorkOnlyMarksStartAndDoesNotCreateCompletedTime() {
        val row = workAnalysisRecord("active", "2026-10-26T23:00", null)
        assertEquals(date.minusDays(1), workHistoryDate(row))
        val start = workDayAnalysis(listOf(row), date.minusDays(1), zone)
        assertNull(start.durationMs)
        assertEquals(listOf(row.startedAtUtcMs), start.openStarts)
        val current = workDayAnalysis(listOf(row), date, zone)
        assertNull(current.durationMs)
        assertTrue(current.openStarts.isEmpty())
    }

    @Test fun timezoneChangesChartWithoutMovingSavedEndDate() {
        val row = workAnalysisRecord("travel", "2026-10-27T01:00", "2026-10-27T03:00")
        assertEquals(date, workHistoryDate(row))
        assertEquals(7_200_000L, workDayAnalysis(listOf(row), date.minusDays(1), ZoneId.of("America/New_York")).durationMs)
        assertNull(workDayAnalysis(listOf(row), date.minusDays(1), zone).durationMs)
    }
}
