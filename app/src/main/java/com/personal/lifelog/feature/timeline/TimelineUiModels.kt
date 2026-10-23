package com.personal.lifelog.feature.timeline

import com.personal.lifelog.domain.TimelineEntry
import java.time.LocalDate

/**
 * One day's timeline state. [loading] is true before the first database read for
 * a date; the UI must show progress rather than claim an empty day until the
 * query emits. [failed] means the read was rejected, not that the day is empty.
 */
data class TimelineUiState(
    val date: LocalDate? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val entries: List<TimelineEntry> = emptyList(),
)

/** Dates that contain at least one saved record, used by review's quick jump. */
data class RecordedDatesState(
    val dates: List<String>? = null,
    val failed: Boolean = false,
)
