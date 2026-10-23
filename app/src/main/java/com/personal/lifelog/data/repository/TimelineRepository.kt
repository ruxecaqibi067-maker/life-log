package com.personal.lifelog.data.repository

import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.domain.TimelineEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Read-only aggregation across the three record tables for one saved local date.
 *
 * The repository adds no state and writes nothing: every emission comes from the
 * existing Room flows, so an edit that changes a record's local date moves it
 * between days on the next query without a second timeline kept by the UI.
 * Sleep membership is the stored [SleepRecord.archiveLocalDate] (wake-up date for
 * completed records, start date while active).
 */
class TimelineRepository(
    private val datesWithRecordsFlow: () -> Flow<List<String>>,
    private val mealsOn: (String) -> Flow<List<MealRecord>>,
    private val sleepsOn: (String) -> Flow<List<SleepRecord>>,
    private val workoutsOn: (String) -> Flow<List<WorkoutWithItems>>,
) {
    constructor(records: RecordRepository, workouts: WorkoutRepository) : this(
        datesWithRecordsFlow = records::datesWithRecords,
        mealsOn = records::mealsOn,
        sleepsOn = records::sleepsOn,
        workoutsOn = workouts::workoutsOn,
    )

    /** Dates that have at least one saved record in any table, newest first. */
    fun datesWithRecords(): Flow<List<String>> = datesWithRecordsFlow()

    fun timelineOn(date: String): Flow<List<TimelineEntry>> = combine(
        mealsOn(date),
        sleepsOn(date),
        workoutsOn(date),
    ) { meals, sleeps, workouts -> mergeTimeline(meals, sleeps, workouts) }
}

/**
 * Newest first; equal instants fall back to the stable type-qualified ID. A
 * workout session contributes one entry with all item snapshot names, so the
 * timeline never splits one training into several rows.
 */
internal fun mergeTimeline(
    meals: List<MealRecord>,
    sleeps: List<SleepRecord>,
    workouts: List<WorkoutWithItems>,
): List<TimelineEntry> = buildList {
    meals.forEach { meal ->
        add(
            TimelineEntry.Meal(
                id = meal.id,
                sortUtcMs = meal.occurredAtUtcMs,
                zoneId = meal.zoneId,
                text = meal.text,
                satiety = meal.satiety,
                photoId = meal.photoId,
            ),
        )
    }
    sleeps.forEach { sleep ->
        add(
            TimelineEntry.Sleep(
                id = sleep.id,
                sortUtcMs = sleep.endedAtUtcMs ?: sleep.startedAtUtcMs,
                startedAtUtcMs = sleep.startedAtUtcMs,
                startZoneId = sleep.startZoneId,
                endedAtUtcMs = sleep.endedAtUtcMs,
                endZoneId = sleep.endZoneId,
            ),
        )
    }
    workouts.forEach { workout ->
        add(
            TimelineEntry.Workout(
                id = workout.session.id,
                sortUtcMs = workout.session.occurredAtUtcMs,
                zoneId = workout.session.zoneId,
                itemNames = workout.sorted().items.map { it.item.nameSnapshot },
            ),
        )
    }
}.sortedWith(
    compareByDescending<TimelineEntry> { it.sortUtcMs }
        .thenBy { it.id }
        .thenBy { it.stableKey },
)
