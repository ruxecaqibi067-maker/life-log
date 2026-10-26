package com.personal.lifelog.feature.sleep

import com.personal.lifelog.data.local.entity.SleepRecord
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

internal fun analysisRecord(id: String, start: String, end: String?, zone: String = "Asia/Shanghai"): SleepRecord {
    val zoneId = ZoneId.of(zone)
    val from = java.time.LocalDateTime.parse(start).atZone(zoneId)
    val to = end?.let { java.time.LocalDateTime.parse(it).atZone(zoneId) }
    return SleepRecord(id, from.toInstant().toEpochMilli(), zone, to?.toInstant()?.toEpochMilli(),
        end?.let { zone }, (to ?: from).toLocalDate().toString(), if (end == null) 1 else null, 1, 1)
}

class SleepAnalysisTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 26)

    @Test fun crossNightListUsesWakeDateButChartSplitsAtMidnightWithoutRearchiving() {
        val record = analysisRecord("night", "2026-10-25T23:00", "2026-10-26T07:00")
        assertEquals(date, sleepHistoryDate(record))
        assertEquals("2026-10-26", record.archiveLocalDate)
        val previous = sleepDayAnalysis(listOf(record), date.minusDays(1), zone)
        val current = sleepDayAnalysis(listOf(record), date, zone)
        assertEquals(3_600_000L, previous.durationMs)
        assertEquals(25_200_000L, current.durationMs)
        assertEquals(28_800_000L, previous.durationMs!! + current.durationMs!!)
        assertEquals(23f / 24, previous.fraction(previous.segments.single().startMs), 0.000001f)
        assertEquals(7f / 24, current.fraction(current.segments.single().endMs), 0.000001f)
    }

    @Test fun napsAndSubMinuteRecordsRetainMilliseconds() {
        val rows = listOf(analysisRecord("a", "2026-10-26T01:00", "2026-10-26T08:00"),
            analysisRecord("b", "2026-10-26T13:00", "2026-10-26T13:20"),
            analysisRecord("c", "2026-10-26T18:00:00.123", "2026-10-26T18:00:00.456"))
        val day = sleepDayAnalysis(rows, date, zone)
        assertEquals(26_400_333L, day.durationMs)
        assertEquals(3, day.segments.size)
    }

    @Test fun openIntervalsOnlyMarkTheirStartAndNeverInventADuration() {
        val record = analysisRecord("open", "2026-10-25T23:00", null)
        val startDay = sleepDayAnalysis(listOf(record), date.minusDays(1), zone)
        val nextDay = sleepDayAnalysis(listOf(record), date, zone)
        assertNull(startDay.durationMs)
        assertEquals(listOf(record.startedAtUtcMs), startDay.openStarts)
        assertNull(nextDay.durationMs)
        assertTrue(nextDay.openStarts.isEmpty())
    }

    @Test fun midnightIsExclusiveAndLongIntervalsAreClippedToEachDay() {
        val midnight = analysisRecord("a", "2026-10-25T23:00", "2026-10-26T00:00")
        assertNull(sleepDayAnalysis(listOf(midnight), date, zone).durationMs)
        val long = analysisRecord("b", "2026-10-24T23:00", "2026-10-27T01:00")
        assertEquals(86_400_000L, sleepDayAnalysis(listOf(long), date, zone).durationMs)
        assertEquals(1f, sleepDayAnalysis(listOf(long), date, zone).fraction(long.endedAtUtcMs!!), 0f)
    }

    @Test fun chartUsesActual23And25HourDaysAcrossDaylightSaving() {
        val ny = ZoneId.of("America/New_York")
        listOf("2026-03-08" to 23L, "2026-11-01" to 25L).forEach { (value, hours) ->
            val day = LocalDate.parse(value)
            val row = analysisRecord("dst", "${day}T00:00", "${day.plusDays(1)}T00:00", ny.id)
            val chart = sleepDayAnalysis(listOf(row), day, ny)
            assertEquals(hours * 3_600_000L, chart.durationMs)
            assertEquals(hours * 3_600_000L, chart.endMs - chart.startMs)
            assertEquals(0f, chart.fraction(chart.startMs), 0f)
            assertEquals(1f, chart.fraction(chart.endMs), 0f)
        }
    }

    @Test fun timezoneProjectionDoesNotChangeTheSavedWakeDay() {
        val row = analysisRecord("travel", "2026-10-26T01:00", "2026-10-26T03:00")
        val nyDay = sleepDayAnalysis(listOf(row), date.minusDays(1), ZoneId.of("America/New_York"))
        assertEquals(date, sleepHistoryDate(row))
        assertEquals(7_200_000L, nyDay.durationMs)
        assertNull(sleepDayAnalysis(listOf(row), date.minusDays(1), zone).durationMs)
    }
}
