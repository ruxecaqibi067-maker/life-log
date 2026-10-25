package com.personal.lifelog.domain

import java.util.UUID

// Enum names are persisted and form part of the v1 data contract. Add new values
// at the end; never rename or remove an existing name (Room and backups store it).
enum class Satiety { FIVE, SEVEN, NINE, FULL, NOT_FULL, CUSTOM }
enum class ExerciseType { CARDIO, STRENGTH, OTHER }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

object RecordIds {
    fun newId(): String = UUID.randomUUID().toString()
}

/** Callers retain IDs across retries; edits retain item IDs to preserve historical snapshots. */
data class MealDraft(
    val id: String,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val text: String? = null,
    val satiety: Satiety? = null,
    val photoId: String? = null,
    /** Only meaningful when [satiety] is [Satiety.CUSTOM]; trimmed and length-checked by Rules. */
    val satietyText: String? = null,
)

data class SleepDraft(
    val id: String,
    val startedAtUtcMs: Long,
    val startZoneId: String,
    val endedAtUtcMs: Long? = null,
    val endZoneId: String? = null,
    val confirmLongInterval: Boolean = false,
)

data class ExerciseDraft(val id: String, val name: String, val type: ExerciseType)

data class WorkoutDraft(
    val id: String,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val items: List<WorkoutItemDraft>,
    val note: String? = null,
)

data class WorkoutItemDraft(
    val id: String,
    val exerciseId: String,
    val durationSeconds: Long? = null,
    val distanceMeters: Long? = null,
    val note: String? = null,
    val sets: List<WorkoutSetDraft> = emptyList(),
)

data class WorkoutSetDraft(
    val id: String,
    val weightGrams: Long? = null,
    val repetitions: Int? = null,
)

enum class ErrorCode {
    INVALID_ID, INVALID_TIME_ZONE, INVALID_LOCAL_TIME, AMBIGUOUS_LOCAL_TIME,
    INVALID_INTERVAL, LONG_INTERVAL_CONFIRMATION, OVERLAPPING_SLEEP,
    EMPTY_MEAL, TEXT_TOO_LONG, INVALID_NAME, DUPLICATE_NAME,
    INVALID_NUMBER, EMPTY_WORKOUT, TOO_MANY_ITEMS, TOO_MANY_SETS,
    DUPLICATE_ID, INVALID_FIELDS_FOR_TYPE, NOT_FOUND, HIDDEN_EXERCISE,
    USED_EXERCISE_TYPE, INVALID_MEDIA, IMMUTABLE_MEDIA, STORAGE, STORAGE_FULL, STORAGE_UNAVAILABLE,
    INVALID_SATIETY, OVERLAPPING_WORK,
}

data class DataError(val code: ErrorCode, val field: String? = null)
class DataViolation(val error: DataError) : IllegalArgumentException(error.code.name)

sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>
    data class Failure(val error: DataError) : DataResult<Nothing>
}

fun reject(code: ErrorCode, field: String? = null): Nothing = throw DataViolation(DataError(code, field))
