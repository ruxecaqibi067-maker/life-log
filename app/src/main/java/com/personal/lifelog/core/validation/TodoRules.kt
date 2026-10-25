package com.personal.lifelog.core.validation

import com.personal.lifelog.domain.*
import java.time.LocalDate

object TodoRules {
    const val TITLE_LIMIT = 120
    const val HABIT_DAYS = 21L
    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2199, 12, 31)

    fun title(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) reject(ErrorCode.INVALID_NAME, "title")
        if (trimmed.codePointCount(0, trimmed.length) > TITLE_LIMIT) reject(ErrorCode.TEXT_TOO_LONG, "title")
        return trimmed
    }

    fun date(value: LocalDate): LocalDate {
        if (value < MIN_DATE || value > MAX_DATE) reject(ErrorCode.INVALID_LOCAL_TIME, "date")
        return value
    }

    fun date(value: String): LocalDate {
        val parsed = try { LocalDate.parse(value) } catch (_: java.time.DateTimeException) {
            reject(ErrorCode.INVALID_LOCAL_TIME, "date")
        }
        if (parsed.toString() != value) reject(ErrorCode.INVALID_LOCAL_TIME, "date")
        return date(parsed)
    }

    fun minute(value: Int?): Int? {
        if (value != null && value !in 0..1439) reject(ErrorCode.INVALID_NUMBER, "scheduledMinute")
        return value
    }

    fun interval(value: Int): Int {
        if (value !in 1..365) reject(ErrorCode.INVALID_NUMBER, "intervalDays")
        return value
    }

    fun weekdays(value: Int): Int {
        if (value !in 1..127) reject(ErrorCode.INVALID_NUMBER, "weekdaysMask")
        return value
    }
}
