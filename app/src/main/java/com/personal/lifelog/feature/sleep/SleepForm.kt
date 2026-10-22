package com.personal.lifelog.feature.sleep

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.domain.SleepDraft
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** ISO date-time strings include seconds/milliseconds when present, so edits preserve exact instants. */
data class SleepForm(
    val start: String, val startZone: String, val startOffset: String = "",
    val hasEnd: Boolean = true,
    val end: String, val endZone: String, val endOffset: String = "",
) {
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
            return SleepForm(local, zone, hasEnd = true, end = local, endZone = zone)
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
    }
}
