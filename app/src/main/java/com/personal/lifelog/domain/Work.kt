package com.personal.lifelog.domain

import java.time.LocalDate

data class WorkDraft(
    val id: String,
    val startedAtUtcMs: Long,
    val startZoneId: String,
    val endedAtUtcMs: Long? = null,
    val endZoneId: String? = null,
    val confirmLongInterval: Boolean = false,
)

/** Completed whole intervals assigned to the end date, with an independent global active flag. */
data class WorkSummary(
    val date: LocalDate,
    val targetWorkMinutes: Int?,
    val completedSegmentCount: Int,
    /** null means no completed records; milliseconds are never rounded or capped. */
    val recordedDurationMs: Long?,
    val hasActiveWork: Boolean,
)
