package com.personal.lifelog.feature.home

import org.junit.Assert.*
import org.junit.Test

class DurationFeedbackTest {
    private val targetMs = 480 * 60_000L

    @Test fun sleepUsesPersonalGoalBoundaryWithoutCappingActualRatio() {
        assertEquals(DurationFeedback.SHORT, durationFeedback(DurationKind.SLEEP, targetMs * 8 / 10 - 1, 480, false))
        assertEquals(DurationFeedback.BALANCED, durationFeedback(DurationKind.SLEEP, targetMs * 8 / 10, 480, false))
        assertEquals(DurationFeedback.BALANCED, durationFeedback(DurationKind.SLEEP, targetMs * 2, 480, false))
    }

    @Test fun workLowBalancedAndLongUseExactInclusiveBoundaries() {
        assertEquals(DurationFeedback.SHORT, durationFeedback(DurationKind.WORK, targetMs * 8 / 10 - 1, 480, false))
        assertEquals(DurationFeedback.BALANCED, durationFeedback(DurationKind.WORK, targetMs * 8 / 10, 480, false))
        assertEquals(DurationFeedback.BALANCED, durationFeedback(DurationKind.WORK, targetMs * 12 / 10, 480, false))
        assertEquals(DurationFeedback.LONG, durationFeedback(DurationKind.WORK, targetMs * 12 / 10 + 1, 480, false))
        // A full ring must not turn a very long day into the balanced category.
        assertEquals(DurationFeedback.LONG, durationFeedback(DurationKind.WORK, Long.MAX_VALUE, 480, false))
    }

    @Test fun missingDataAndActiveIntervalsNeverPretendToBeZeroCompletedWork() {
        for (kind in DurationKind.entries) {
            assertEquals(DurationFeedback.EMPTY, durationFeedback(kind, null, 480, false))
            assertEquals(DurationFeedback.ACTIVE, durationFeedback(kind, null, 480, true))
            assertEquals(DurationFeedback.UNSET, durationFeedback(kind, targetMs, null, false))
            assertEquals(DurationFeedback.UNSET, durationFeedback(kind, targetMs, 0, false))
            assertEquals(DurationFeedback.UNSET, durationFeedback(kind, targetMs, 1441, false))
            assertEquals(DurationFeedback.ACTIVE, durationFeedback(kind, null, null, true))
        }
        // Ongoing state never changes the already completed amount used for comparison.
        assertEquals(DurationFeedback.SHORT, durationFeedback(DurationKind.WORK, targetMs / 2, 480, true))
    }
}
