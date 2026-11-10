package com.personal.lifelog.feature.sleep

import com.personal.lifelog.core.time.DayTimeAnalysis
import com.personal.lifelog.core.time.DayTimeSegment
import com.personal.lifelog.core.time.RecordedTimeInterval
import com.personal.lifelog.core.time.dayTimeAnalysis
import com.personal.lifelog.data.local.entity.SleepRecord
import java.time.LocalDate
import java.time.ZoneId

/** Preserve the recorded end-day archive used by Home/Review. */
internal fun sleepHistoryDate(record: SleepRecord): LocalDate = LocalDate.parse(record.archiveLocalDate)

typealias SleepDaySegment = DayTimeSegment
typealias SleepDayAnalysis = DayTimeAnalysis

internal fun sleepDayAnalysis(records: List<SleepRecord>, date: LocalDate, zone: ZoneId): SleepDayAnalysis =
    dayTimeAnalysis(records.map { RecordedTimeInterval(it.id, it.startedAtUtcMs, it.endedAtUtcMs) }, date, zone)
