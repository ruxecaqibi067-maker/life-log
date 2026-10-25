package com.personal.lifelog.core.time

import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.reject

object WorkTime {
    /** Also reject subtraction overflow so summaries always use exact signed milliseconds. */
    fun validateInterval(start: Long, end: Long) {
        RecordTime.durationSeconds(start, end)
        try { Math.subtractExact(end, start) }
        catch (_: ArithmeticException) { reject(ErrorCode.INVALID_INTERVAL) }
    }
}
