package com.personal.lifelog.feature.sleep

import com.personal.lifelog.data.local.entity.SleepRecord
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SleepWheelModelTest {
    @Test fun monthChangesClampDayIncludingLeapYears() {
        assertEquals(LocalDateTime.parse("2026-02-28T23:50"), wheelDateTime(2026, 2, 31, 23, 50))
        assertEquals(29, wheelDateTime(2024, 2, 31, 7, 10).dayOfMonth)
        assertEquals(30, wheelDateTime(2026, 4, 31, 7, 10).dayOfMonth)
    }

    private fun source() = SleepRecord("fixture", Instant.parse("2026-11-01T05:30:19.123Z").toEpochMilli(), "America/New_York",
        Instant.parse("2026-11-01T06:30:48.987Z").toEpochMilli(), "America/New_York", "2026-11-01", null, 1, 1)

    @Test fun untouchedAndUnchangedConfirmationPreserveBothInstantsOffsetsAndMillisecondsAcrossZones() {
        val record = source()
        val systemZone = ZoneId.of("Asia/Kolkata")
        val form = SleepForm.from(record, 0, systemZone.id)
        val restored = SleepForm.restore(form.saved())
        val start = restored.startAt(systemZone)
        val end = restored.endAt(systemZone)
        val unchanged = restored.pickStart(start.toLocalDateTime().withSecond(0).withNano(0), systemZone, start.offset)
            .pickEnd(end.toLocalDateTime().withSecond(0).withNano(0), systemZone, end.offset)
        assertEquals(form, unchanged)
        assertEquals(record.startedAtUtcMs, unchanged.draft(record.id).startedAtUtcMs)
        assertEquals(record.endedAtUtcMs, unchanged.draft(record.id).endedAtUtcMs)
        assertEquals(record.startZoneId, unchanged.draft(record.id).startZoneId)
    }

    @Test fun changedMinuteUsesSuppliedSystemZoneAndLeavesOtherEndpointExact() {
        val record = source()
        val zone = ZoneId.of("Pacific/Auckland")
        val form = SleepForm.from(record, 0, zone.id)
        val picked = LocalDateTime.parse("2026-11-01T18:45")
        val result = form.pickStart(picked, zone, zone.rules.getValidOffsets(picked).single()).draft(record.id)
        assertEquals(picked.atZone(zone).toInstant().toEpochMilli(), result.startedAtUtcMs)
        assertEquals(zone.id, result.startZoneId)
        assertEquals(record.endedAtUtcMs, result.endedAtUtcMs)
        assertEquals(record.endZoneId, result.endZoneId)
    }

    @Test fun repeatedMinuteCanExplicitlyChooseOtherOccurrenceAndGapIsUnavailable() {
        val record = source()
        val zone = ZoneId.of(record.startZoneId)
        val form = SleepForm.from(record, 0, zone.id)
        val repeated = LocalDateTime.parse("2026-11-01T01:30")
        assertEquals(2, zone.rules.getValidOffsets(repeated).size)
        val changed = form.pickStart(repeated, zone, zone.rules.getValidOffsets(repeated)[1]).draft(record.id)
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), changed.startedAtUtcMs)
        assertTrue(zone.rules.getValidOffsets(LocalDateTime.parse("2026-03-08T02:30")).isEmpty())
    }

    @Test fun newFormUsesInjectedClockInstantAndZoneAndDisplayHidesPrecision() {
        val now = Instant.parse("2026-10-23T04:35:27.123Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Kolkata")
        val form = SleepForm.at(now, zone.id)
        assertEquals(now, form.draft("fixture").startedAtUtcMs)
        assertEquals("2026年10月23日 10:05", chineseSleepTime(form.startAt(zone)))
        assertEquals(zone.id, form.startZone)
    }
}
