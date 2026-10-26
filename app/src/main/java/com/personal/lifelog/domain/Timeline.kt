package com.personal.lifelog.domain

import java.util.Locale

/**
 * Presentation-neutral view of the four record types on one day.
 *
 * Date membership is decided by Room's saved local dates (meal.localDate,
 * sleep/work.archiveLocalDate, workout.localDate); the UI never re-derives it from the
 * current device zone. [sortUtcMs] is the ordering instant required by the spec:
 * meals/workouts use their recorded instant, completed sleep/work uses its end
 * time, and an active interval falls back to its start time. Ties are broken by
 * [stableKey] so a reload cannot shuffle equal timestamps.
 */
enum class TimelineKind { MEAL, SLEEP, WORKOUT, WORK }

sealed interface TimelineEntry {
    val id: String
    val kind: TimelineKind
    val sortUtcMs: Long
    val stableKey: String get() = "${kind.name.lowercase(Locale.ROOT)}:$id"

    data class Meal(
        override val id: String,
        override val sortUtcMs: Long,
        val zoneId: String,
        val text: String?,
        val satiety: Satiety?,
        val satietyText: String?,
        val photoId: String?,
        val photoCount: Int = if (photoId == null) 0 else 1,
    ) : TimelineEntry {
        override val kind: TimelineKind get() = TimelineKind.MEAL
    }

    data class Sleep(
        override val id: String,
        override val sortUtcMs: Long,
        val startedAtUtcMs: Long,
        val startZoneId: String,
        val endedAtUtcMs: Long?,
        val endZoneId: String?,
    ) : TimelineEntry {
        override val kind: TimelineKind get() = TimelineKind.SLEEP
    }

    data class Work(
        override val id: String,
        override val sortUtcMs: Long,
        val startedAtUtcMs: Long,
        val startZoneId: String,
        val endedAtUtcMs: Long?,
        val endZoneId: String?,
    ) : TimelineEntry {
        override val kind: TimelineKind get() = TimelineKind.WORK
    }

    data class Workout(
        override val id: String,
        override val sortUtcMs: Long,
        val zoneId: String,
        val itemNames: List<String>,
    ) : TimelineEntry {
        override val kind: TimelineKind get() = TimelineKind.WORKOUT
    }
}
