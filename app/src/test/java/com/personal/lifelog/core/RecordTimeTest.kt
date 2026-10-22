package com.personal.lifelog.core

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.ErrorCode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecordTimeTest {
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun crossDayMonthAndYearUseCompleteInstants() {
        listOf(
            "2026-10-21T15:50:00Z" to "2026-10-21T23:20:00Z",
            "2026-10-31T15:50:00Z" to "2026-10-31T23:20:00Z",
            "2026-12-31T15:50:00Z" to "2026-12-31T23:20:00Z",
        ).forEach { (start, end) -> assertEquals(27_000L, RecordTime.durationSeconds(utc(start), utc(end))) }
        assertEquals("2027-01-01", RecordTime.localDate(utc("2026-12-31T23:20:00Z"), "Asia/Shanghai"))
    }

    @Test fun archiveDateUsesRecordedEndZone() {
        val instant = utc("2026-01-01T00:30:00Z")
        assertEquals("2026-01-01", RecordTime.localDate(instant, "Asia/Shanghai"))
        assertEquals("2025-12-31", RecordTime.localDate(instant, "America/Los_Angeles"))
    }

    @Test fun daylightSavingGapRequiresCorrection() {
        val failure = assertThrows(DataViolation::class.java) {
            RecordTime.resolve(LocalDateTime.parse("2026-03-08T02:30"), "America/New_York")
        }
        assertEquals(ErrorCode.INVALID_LOCAL_TIME, failure.error.code)
    }

    @Test fun daylightSavingOverlapRequiresExplicitOffset() {
        val local = LocalDateTime.parse("2026-11-01T01:30")
        assertEquals(ErrorCode.AMBIGUOUS_LOCAL_TIME, assertThrows(DataViolation::class.java) {
            RecordTime.resolve(local, "America/New_York")
        }.error.code)
        val first = RecordTime.resolve(local, "America/New_York", ZoneOffset.ofHours(-4))
        val second = RecordTime.resolve(local, "America/New_York", ZoneOffset.ofHours(-5))
        assertEquals(3_600_000L, second - first)
        assertEquals(ErrorCode.INVALID_LOCAL_TIME, assertThrows(DataViolation::class.java) {
            RecordTime.resolve(local, "America/New_York", ZoneOffset.UTC)
        }.error.code)
    }

    @Test fun daylightSavingDurationIsElapsedTime() {
        val start = RecordTime.resolve(LocalDateTime.parse("2026-03-08T01:30"), "America/New_York")
        val end = RecordTime.resolve(LocalDateTime.parse("2026-03-08T03:30"), "America/New_York")
        assertEquals(3_600L, RecordTime.durationSeconds(start, end))
    }

    @Test fun adjacentIntervalsDoNotOverlap() {
        assertFalse(RecordTime.overlaps(10, 20, 20, 30))
        assertFalse(RecordTime.overlaps(20, 30, 10, 20))
        assertTrue(RecordTime.overlaps(10, 21, 20, 30))
        assertTrue(RecordTime.overlaps(20, null, 30, null))
        assertFalse(RecordTime.overlaps(20, null, 10, 20))
        assertTrue(RecordTime.overlaps(Long.MIN_VALUE, null, Long.MAX_VALUE, null))
    }

    @Test fun zeroAndNegativeIntervalsAreRejected() {
        assertThrows(DataViolation::class.java) { RecordTime.durationSeconds(100, 100) }
        assertThrows(DataViolation::class.java) { RecordTime.durationSeconds(101, 100) }
    }

    @Test fun longIntervalThresholdIncludesSubsecondBoundary() {
        assertFalse(RecordTime.isLongInterval(0, 86_399_999))
        assertFalse(RecordTime.isLongInterval(0, 86_400_000))
        assertTrue(RecordTime.isLongInterval(0, 86_400_001))
    }

    @Test fun fixedOffsetStringsDoNotReplaceIanaZoneIds() {
        assertThrows(DataViolation::class.java) { RecordTime.zone("+08:00") }
        assertThrows(DataViolation::class.java) { RecordTime.zone("not/a-zone") }
        assertEquals("UTC", RecordTime.zone("UTC").id)
    }
}
