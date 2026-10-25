package com.personal.lifelog.domain

import java.time.LocalDate

/** Recorded bed-to-rise intervals, not measured sleep or a health score. */
data class SleepSummary(
    val date: LocalDate,
    val targetSleepMinutes: Int?,
    val completedSegmentCount: Int,
    /** Whole completed intervals assigned to [date]; null means no completed records. */
    val recordedDurationMs: Long?,
    /** Global ongoing record, including one whose saved start date is yesterday. */
    val hasActiveSleep: Boolean,
)
