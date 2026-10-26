package com.personal.lifelog.feature.home

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ActiveDurationPreviewTest {
    @Test fun aRunningIntervalUsesItsRealStartAcrossMidnightAndKeepsMilliseconds() {
        val start = Instant.parse("2026-10-25T23:59:59.123Z").toEpochMilli()
        val now = Instant.parse("2026-10-26T01:00:00.456Z").toEpochMilli()
        assertEquals(3_601_333L, activeElapsedMs(start, now))
        assertNull(activeElapsedMs(null, now))
    }

    @Test fun clockRollbackIsZeroAndExtremeClockSpanCannotOverflow() {
        assertEquals(0L, activeElapsedMs(20, 10))
        assertEquals(0L, activeElapsedMs(20, 20))
        assertEquals(Long.MAX_VALUE, activeElapsedMs(Long.MIN_VALUE, Long.MAX_VALUE))
    }

    @Test fun ongoingOnlyIsARealPreviewButMissingInactiveDataStaysMissing() {
        assertNull(previewDurationMs(null, null))
        assertEquals(3_600_000L, previewDurationMs(null, 3_600_000))
        assertEquals(0.25f, durationProgress(previewDurationMs(null, 3_600_000), 240)!!, 0.000001f)
        assertNull(durationProgress(previewDurationMs(null, 3_600_000), null))
    }

    @Test fun previewAddsCompletedAndRunningWithoutReplacingTheCompletedValueOrCappingTime() {
        val completed = 3_600_001L
        assertEquals(7_200_003L, previewDurationMs(completed, 3_600_002))
        assertEquals(completed, previewDurationMs(completed, null))
        assertEquals(72_000_000L, previewDurationMs(36_000_000L, 36_000_000L))
        assertEquals(1f, durationProgress(previewDurationMs(36_000_000L, 36_000_000L), 240)!!)
        assertEquals(Long.MAX_VALUE, previewDurationMs(Long.MAX_VALUE - 1, 2))
    }
}
