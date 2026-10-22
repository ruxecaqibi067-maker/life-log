package com.personal.lifelog.core.time

import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.reject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object RecordTime {
    const val LONG_INTERVAL_SECONDS = 24 * 60 * 60L

    fun zone(id: String): ZoneId {
        if (id !in ZoneId.getAvailableZoneIds()) reject(ErrorCode.INVALID_TIME_ZONE, "zoneId")
        return ZoneId.of(id)
    }

    fun localDate(utcMs: Long, zoneId: String): String =
        Instant.ofEpochMilli(utcMs).atZone(zone(zoneId)).toLocalDate().toString()

    /** DST gaps are rejected; overlaps require a caller-selected valid offset. */
    fun resolve(local: LocalDateTime, zoneId: String, offset: ZoneOffset? = null): Long {
        val offsets = zone(zoneId).rules.getValidOffsets(local)
        if (offsets.isEmpty()) reject(ErrorCode.INVALID_LOCAL_TIME)
        if (offset == null && offsets.size > 1) reject(ErrorCode.AMBIGUOUS_LOCAL_TIME)
        val selected = offset ?: offsets.single()
        if (selected !in offsets) reject(ErrorCode.INVALID_LOCAL_TIME)
        return local.toInstant(selected).toEpochMilli()
    }

    fun durationSeconds(startUtcMs: Long, endUtcMs: Long): Long {
        if (endUtcMs <= startUtcMs) reject(ErrorCode.INVALID_INTERVAL)
        return Duration.between(Instant.ofEpochMilli(startUtcMs), Instant.ofEpochMilli(endUtcMs)).seconds
    }

    fun isLongInterval(startUtcMs: Long, endUtcMs: Long): Boolean =
        Duration.between(Instant.ofEpochMilli(startUtcMs), Instant.ofEpochMilli(endUtcMs)) > Duration.ofSeconds(LONG_INTERVAL_SECONDS)

    fun overlaps(start: Long, end: Long?, otherStart: Long, otherEnd: Long?): Boolean =
        (otherEnd == null || start < otherEnd) && (end == null || otherStart < end)
}
