package com.personal.lifelog.core.time

import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.reject

/** Interactive recording rules; legacy stored/imported intervals keep their original meaning. */
object IntervalDurationPolicy {
    const val MIN_SLEEP_MS = 10 * 60_000L
    const val MIN_WORK_MS = 5 * 60_000L
    const val CONFIRM_AFTER_MS = 12 * 3_600_000L

    fun elapsed(start: Long, end: Long): Long {
        if (end < start) reject(ErrorCode.INVALID_INTERVAL)
        return try { Math.subtractExact(end, start) } catch (_: ArithmeticException) { reject(ErrorCode.INVALID_INTERVAL) }
    }

    fun validateCompleted(start: Long, end: Long?, minimum: Long, confirmed: Boolean) {
        if (end == null) return
        if (end <= start) reject(ErrorCode.INVALID_INTERVAL)
        val duration = elapsed(start, end)
        if (duration < minimum) reject(ErrorCode.INTERVAL_TOO_SHORT)
        if (duration > CONFIRM_AFTER_MS && !confirmed) reject(ErrorCode.LONG_INTERVAL_CONFIRMATION)
    }
}
