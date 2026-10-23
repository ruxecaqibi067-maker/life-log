package com.personal.lifelog.feature.sleep

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.domain.SleepDraft
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

/** ISO date-time strings include seconds/milliseconds when present, so edits preserve exact instants. */
data class SleepForm(
    val start: String, val startZone: String, val startOffset: String = "",
    val hasEnd: Boolean = true,
    val end: String, val endZone: String, val endOffset: String = "",
) {
    fun startAt(zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(resolve(start, startZone, startOffset)).atZone(zone)
    fun endAt(zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(resolve(end, endZone, endOffset)).atZone(zone)

    /** The minute-only control is a projection, never the storage representation. */
    fun pickStart(local: LocalDateTime, zone: ZoneId, offset: ZoneOffset): SleepForm =
        if (sameMinute(startAt(zone), local, offset)) this
        else copy(start = local.truncatedTo(ChronoUnit.MINUTES).toString(), startZone = zone.id, startOffset = offset.id)

    fun pickEnd(local: LocalDateTime, zone: ZoneId, offset: ZoneOffset): SleepForm =
        if (sameMinute(endAt(zone), local, offset)) this
        else copy(end = local.truncatedTo(ChronoUnit.MINUTES).toString(), endZone = zone.id, endOffset = offset.id)

    fun draft(id: String) = SleepDraft(
        id, resolve(start, startZone, startOffset), startZone.trim(),
        if (hasEnd) resolve(end, endZone, endOffset) else null,
        if (hasEnd) endZone.trim() else null,
    )

    fun saved() = arrayListOf(start, startZone, startOffset, hasEnd.toString(), end, endZone, endOffset)

    companion object {
        fun restore(values: ArrayList<String>) = SleepForm(values[0], values[1], values[2], values[3].toBoolean(), values[4], values[5], values[6])
        fun at(now: Long, zone: String): SleepForm {
            val local = local(now, zone)
            return SleepForm(local, zone, offset(now, zone), hasEnd = true, end = local, endZone = zone, endOffset = offset(now, zone))
        }
        fun from(record: SleepRecord, now: Long, zone: String) = SleepForm(
            local(record.startedAtUtcMs, record.startZoneId), record.startZoneId,
            offset(record.startedAtUtcMs, record.startZoneId), record.endedAtUtcMs != null,
            local(record.endedAtUtcMs ?: now, record.endZoneId ?: zone), record.endZoneId ?: zone,
            record.endedAtUtcMs?.let { offset(it, record.endZoneId!!) } ?: "",
        )
        private fun local(instant: Long, zone: String) = Instant.ofEpochMilli(instant).atZone(RecordTime.zone(zone)).toLocalDateTime().toString()
        private fun offset(instant: Long, zone: String) = Instant.ofEpochMilli(instant).atZone(RecordTime.zone(zone)).offset.id
        private fun resolve(value: String, zone: String, offset: String): Long = RecordTime.resolve(
            LocalDateTime.parse(value.trim()), zone.trim(), offset.trim().takeIf { it.isNotEmpty() }?.let(ZoneOffset::of),
        )
        private fun sameMinute(old: ZonedDateTime, local: LocalDateTime, offset: ZoneOffset) =
            old.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) == local.truncatedTo(ChronoUnit.MINUTES) && old.offset == offset
    }
}

private val chineseMinute = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
fun chineseSleepTime(value: ZonedDateTime): String = chineseMinute.format(value)
fun chineseSleepTime(value: LocalDateTime): String = chineseMinute.format(value)
