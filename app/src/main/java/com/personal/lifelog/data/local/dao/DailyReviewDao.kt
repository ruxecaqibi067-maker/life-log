package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.WorkoutEffort

/** Projection-only rows for daily AI review; omits local notes, paths, hashes and unused columns. */
data class DailyReviewGoalsRow(
    val targetSleepMinutes: Int?,
    val targetWorkMinutes: Int?,
)

data class DailyReviewCompletedIntervalRow(
    val startedAtUtcMs: Long,
    val startZoneId: String,
    val endedAtUtcMs: Long,
    val endZoneId: String?,
)

data class DailyReviewActiveIntervalRow(
    val startedAtUtcMs: Long,
    val startZoneId: String,
)

data class DailyReviewMealRow(
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val hasText: Boolean,
    val satiety: Satiety?,
    val photoCount: Int,
)

data class DailyReviewWorkoutSessionRow(
    val id: String,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val effort: WorkoutEffort?,
)

data class DailyReviewWorkoutItemRow(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val typeSnapshot: ExerciseType,
    val durationSeconds: Long?,
    val distanceMeters: Long?,
    val exerciseIsBuiltin: Boolean?,
    val exerciseType: String?,
)

data class DailyReviewWorkoutSetRow(
    val itemId: String,
    val weightGrams: Long?,
    val repetitions: Int?,
)

@Dao
interface DailyReviewDao {
    @Query("SELECT targetSleepMinutes, targetWorkMinutes FROM AppSettings WHERE id = 1")
    suspend fun goals(): DailyReviewGoalsRow?

    @Query("""
        SELECT startedAtUtcMs, startZoneId, endedAtUtcMs, endZoneId
        FROM SleepRecord
        WHERE archiveLocalDate = :date AND endedAtUtcMs IS NOT NULL
        ORDER BY endedAtUtcMs ASC, id ASC
    """)
    suspend fun completedSleepsOn(date: String): List<DailyReviewCompletedIntervalRow>

    @Query("""
        SELECT startedAtUtcMs, startZoneId
        FROM SleepRecord
        WHERE archiveLocalDate = :date AND activeSlot = 1 AND endedAtUtcMs IS NULL
        ORDER BY startedAtUtcMs ASC, id ASC
        LIMIT 1
    """)
    suspend fun activeSleepOn(date: String): DailyReviewActiveIntervalRow?

    @Query("""
        SELECT startedAtUtcMs, startZoneId, endedAtUtcMs, endZoneId
        FROM WorkRecord
        WHERE archiveLocalDate = :date AND endedAtUtcMs IS NOT NULL
        ORDER BY endedAtUtcMs ASC, id ASC
    """)
    suspend fun completedWorksOn(date: String): List<DailyReviewCompletedIntervalRow>

    @Query("""
        SELECT startedAtUtcMs, startZoneId
        FROM WorkRecord
        WHERE archiveLocalDate = :date AND activeSlot = 1 AND endedAtUtcMs IS NULL
        ORDER BY startedAtUtcMs ASC, id ASC
        LIMIT 1
    """)
    suspend fun activeWorkOn(date: String): DailyReviewActiveIntervalRow?

    @Query("""
        SELECT
            occurredAtUtcMs,
            zoneId,
            (text IS NOT NULL AND length(trim(text)) > 0) AS hasText,
            satiety,
            CASE
                WHEN EXISTS(SELECT 1 FROM MealPhoto WHERE mealId = MealRecord.id)
                    THEN (SELECT COUNT(*) FROM MealPhoto WHERE mealId = MealRecord.id)
                WHEN photoId IS NOT NULL THEN 1
                ELSE 0
            END AS photoCount
        FROM MealRecord
        WHERE localDate = :date
        ORDER BY occurredAtUtcMs ASC, id ASC
    """)
    suspend fun mealsOn(date: String): List<DailyReviewMealRow>

    @Query("""
        SELECT id, occurredAtUtcMs, zoneId, effort
        FROM WorkoutSession
        WHERE localDate = :date
        ORDER BY occurredAtUtcMs ASC, id ASC
    """)
    suspend fun workoutSessionsOn(date: String): List<DailyReviewWorkoutSessionRow>

    @Query("""
        SELECT
            i.id,
            i.sessionId,
            i.exerciseId,
            i.typeSnapshot,
            i.durationSeconds,
            i.distanceMeters,
            e.isBuiltin AS exerciseIsBuiltin,
            e.type AS exerciseType
        FROM WorkoutItem i
        INNER JOIN WorkoutSession s ON s.id = i.sessionId
        LEFT JOIN ExerciseDefinition e ON e.id = i.exerciseId
        WHERE s.localDate = :date
        ORDER BY i.sessionId ASC, i.sortOrder ASC, i.id ASC
    """)
    suspend fun workoutItemsOn(date: String): List<DailyReviewWorkoutItemRow>

    @Query("""
        SELECT
            ws.itemId,
            ws.weightGrams,
            ws.repetitions
        FROM WorkoutSet ws
        INNER JOIN WorkoutItem i ON i.id = ws.itemId
        INNER JOIN WorkoutSession s ON s.id = i.sessionId
        WHERE s.localDate = :date
        ORDER BY ws.itemId ASC, ws.sortOrder ASC, ws.id ASC
    """)
    suspend fun workoutSetsOn(date: String): List<DailyReviewWorkoutSetRow>
}
