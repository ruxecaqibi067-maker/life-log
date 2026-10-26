package com.personal.lifelog.feature.work

import com.personal.lifelog.core.time.DayTimeAnalysis
import com.personal.lifelog.core.time.DayTimeSegment
import com.personal.lifelog.core.time.RecordedTimeInterval
import com.personal.lifelog.core.time.dayTimeAnalysis
import com.personal.lifelog.data.local.entity.WorkRecord
import java.time.LocalDate
import java.time.ZoneId

/** Preserve the recorded end-day archive used by Home/Review. */
internal fun workHistoryDate(record: WorkRecord): LocalDate = LocalDate.parse(record.archiveLocalDate)

typealias WorkDaySegment = DayTimeSegment
typealias WorkDayAnalysis = DayTimeAnalysis

internal fun workDayAnalysis(records: List<WorkRecord>, date: LocalDate, zone: ZoneId): WorkDayAnalysis =
    dayTimeAnalysis(records.map { RecordedTimeInterval(it.id, it.startedAtUtcMs, it.endedAtUtcMs) }, date, zone)
