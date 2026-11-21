package com.personal.lifelog.data.repository

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import androidx.room.withTransaction
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.dao.DailyReviewActiveIntervalRow
import com.personal.lifelog.data.local.dao.DailyReviewCompletedIntervalRow
import com.personal.lifelog.domain.DailyReviewCompletedSegment
import com.personal.lifelog.domain.DailyReviewIntervalSection
import com.personal.lifelog.domain.DailyReviewJson
import com.personal.lifelog.domain.DailyReviewMealItem
import com.personal.lifelog.domain.DailyReviewMealsSection
import com.personal.lifelog.domain.DailyReviewPayload
import com.personal.lifelog.domain.DailyReviewSnapshot
import com.personal.lifelog.domain.DailyReviewSource
import com.personal.lifelog.domain.DailyReviewWorkoutItem
import com.personal.lifelog.domain.DailyReviewWorkoutSession
import com.personal.lifelog.domain.DailyReviewWorkoutSet
import com.personal.lifelog.domain.DailyReviewWorkoutsSection
import com.personal.lifelog.domain.DataError
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.reject
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Generates a single-day v1 AI review JSON payload from one Room read transaction.
 * Strictly projects only whitelisted fields; photos, paths, IDs, hashes and notes never leave SQLite.
 */
class DailyReviewRepository(
    private val db: AppDatabase,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DailyReviewSource {

    override suspend fun generate(date: LocalDate): DataResult<DailyReviewSnapshot> = withContext(ioDispatcher) {
        try {
            val payload = db.withTransaction {
                val selectedDate = date.toString()
                val snapshotZone = RecordTime.zone(zoneIdProvider().id)
                val snapshotNowMs = clock.millis()
                val dao = db.dailyReview()

                val goals = dao.goals() ?: reject(ErrorCode.STORAGE)
                val targetSleepMinutes = Rules.targetSleepMinutes(goals.targetSleepMinutes)
                val targetWorkMinutes = Rules.targetWorkMinutes(goals.targetWorkMinutes)

                val sleep = buildIntervalSection(
                    targetMinutes = targetSleepMinutes,
                    completedRows = dao.completedSleepsOn(selectedDate),
                    activeRow = dao.activeSleepOn(selectedDate),
                )
                val work = buildIntervalSection(
                    targetMinutes = targetWorkMinutes,
                    completedRows = dao.completedWorksOn(selectedDate),
                    activeRow = dao.activeWorkOn(selectedDate),
                )

                val mealItems = dao.mealsOn(selectedDate).map { row ->
                    val mealZone = RecordTime.zone(row.zoneId)
                    DailyReviewMealItem(
                        occurredAt = formatOffsetDateTime(row.occurredAtUtcMs, mealZone),
                        hasText = row.hasText,
                        photoCount = row.photoCount,
                        satiety = row.satiety?.name,
                    )
                }
                val meals = DailyReviewMealsSection(
                    recordCount = mealItems.size,
                    photoOnlyRecordCount = mealItems.count { !it.hasText && it.photoCount > 0 },
                    totalPhotoCount = mealItems.sumOf { it.photoCount },
                    records = mealItems,
                )

                val sessionRows = dao.workoutSessionsOn(selectedDate)
                val itemsBySession = dao.workoutItemsOn(selectedDate).groupBy { it.sessionId }
                val setsByItem = dao.workoutSetsOn(selectedDate).groupBy { it.itemId }

                val sessions = sessionRows.map { session ->
                    val sessionZone = RecordTime.zone(session.zoneId)
                    val items = itemsBySession[session.id].orEmpty().map { item ->
                        val displayName = resolveExerciseDisplayName(
                            exerciseId = item.exerciseId,
                            typeSnapshot = item.typeSnapshot,
                            exerciseIsBuiltin = item.exerciseIsBuiltin,
                            exerciseType = item.exerciseType,
                        )
                        val durationSeconds: Long?
                        val distanceMeters: Long?
                        val sets: List<DailyReviewWorkoutSet>
                        when (item.typeSnapshot) {
                            ExerciseType.CARDIO -> {
                                durationSeconds = Rules.positive(item.durationSeconds, "durationSeconds")
                                distanceMeters = Rules.positive(item.distanceMeters, "distanceMeters")
                                sets = emptyList()
                            }
                            ExerciseType.STRENGTH -> {
                                durationSeconds = null
                                distanceMeters = null
                                sets = setsByItem[item.id].orEmpty().map { set ->
                                    DailyReviewWorkoutSet(
                                        weightGrams = Rules.weight(set.weightGrams),
                                        repetitions = Rules.repetitions(set.repetitions),
                                    )
                                }
                            }
                            ExerciseType.OTHER -> {
                                durationSeconds = Rules.positive(item.durationSeconds, "durationSeconds")
                                distanceMeters = null
                                sets = emptyList()
                            }
                        }
                        DailyReviewWorkoutItem(
                            name = displayName,
                            type = item.typeSnapshot.name,
                            durationSeconds = durationSeconds,
                            distanceMeters = distanceMeters,
                            setCount = sets.size,
                            sets = sets,
                        )
                    }
                    DailyReviewWorkoutSession(
                        occurredAt = formatOffsetDateTime(session.occurredAtUtcMs, sessionZone),
                        effort = session.effort?.name,
                        itemCount = items.size,
                        items = items,
                    )
                }
                val workouts = DailyReviewWorkoutsSection(
                    sessionCount = sessions.size,
                    itemCount = sessions.sumOf { it.itemCount },
                    sessions = sessions,
                )

                val hasAnyRecord = sleep.completedSegmentCount > 0 ||
                    sleep.hasActiveSegment ||
                    work.completedSegmentCount > 0 ||
                    work.hasActiveSegment ||
                    meals.recordCount > 0 ||
                    workouts.sessionCount > 0

                DailyReviewPayload(
                    schemaVersion = DailyReviewPayload.SCHEMA_VERSION,
                    selectedDate = selectedDate,
                    timeZone = snapshotZone.id,
                    snapshotAt = formatOffsetDateTime(snapshotNowMs, snapshotZone),
                    hasAnyRecord = hasAnyRecord,
                    sleep = sleep,
                    work = work,
                    meals = meals,
                    workouts = workouts,
                )
            }
            DataResult.Success(DailyReviewSnapshot(payload = payload, rawJson = DailyReviewJson.encode(payload)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (violation: DataViolation) {
            DataResult.Failure(violation.error)
        } catch (_: SQLiteFullException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_FULL))
        } catch (_: SQLiteCantOpenDatabaseException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
        } catch (_: SQLiteException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE))
        } catch (_: IOException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
        } catch (_: SecurityException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
        } catch (_: ErrnoException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
        } catch (_: IllegalStateException) {
            DataResult.Failure(DataError(ErrorCode.STORAGE_UNAVAILABLE))
        }
    }

    override fun observeChanges(): Flow<Unit> = db.invalidationTracker.createFlow(
        "AppSettings",
        "SleepRecord",
        "WorkRecord",
        "MealRecord",
        "MealPhoto",
        "ExerciseDefinition",
        "WorkoutSession",
        "WorkoutItem",
        "WorkoutSet",
        emitInitialState = true,
    ).map { }.flowOn(ioDispatcher)

    private fun buildIntervalSection(
        targetMinutes: Int?,
        completedRows: List<DailyReviewCompletedIntervalRow>,
        activeRow: DailyReviewActiveIntervalRow?,
    ): DailyReviewIntervalSection {
        var totalMs = 0L
        val segments = completedRows.map { row ->
            val endZoneId = row.endZoneId ?: reject(ErrorCode.INVALID_INTERVAL)
            val startZone = RecordTime.zone(row.startZoneId)
            val endZone = RecordTime.zone(endZoneId)
            if (row.endedAtUtcMs <= row.startedAtUtcMs) reject(ErrorCode.INVALID_INTERVAL)
            val durationMs = try {
                Math.subtractExact(row.endedAtUtcMs, row.startedAtUtcMs)
            } catch (_: ArithmeticException) {
                reject(ErrorCode.INVALID_INTERVAL)
            }
            totalMs = try {
                Math.addExact(totalMs, durationMs)
            } catch (_: ArithmeticException) {
                reject(ErrorCode.INVALID_INTERVAL)
            }
            val segmentMinutes = durationMs / MILLIS_PER_MINUTE
            if (segmentMinutes > Int.MAX_VALUE) reject(ErrorCode.INVALID_INTERVAL)
            DailyReviewCompletedSegment(
                startedAt = formatOffsetDateTime(row.startedAtUtcMs, startZone),
                endedAt = formatOffsetDateTime(row.endedAtUtcMs, endZone),
                durationMinutes = segmentMinutes.toInt(),
            )
        }
        val completedDurationMinutes = if (segments.isEmpty()) {
            null
        } else {
            val totalMinutes = totalMs / MILLIS_PER_MINUTE
            if (totalMinutes > Int.MAX_VALUE) reject(ErrorCode.INVALID_INTERVAL)
            totalMinutes.toInt()
        }
        val activeStartedAt = activeRow?.let { row ->
            formatOffsetDateTime(row.startedAtUtcMs, RecordTime.zone(row.startZoneId))
        }
        return DailyReviewIntervalSection(
            targetMinutes = targetMinutes,
            completedSegmentCount = segments.size,
            completedDurationMinutes = completedDurationMinutes,
            hasActiveSegment = activeRow != null,
            activeStartedAt = activeStartedAt,
            completedSegments = segments,
        )
    }

    private fun formatOffsetDateTime(utcMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(utcMs).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private val builtinById = BuiltinExercises.entries.associateBy { it.id }

        /**
         * Whitelist name resolution per daily-rules.v1.md §2.1:
         * Use standard catalog name only when matching BuiltinExercises, ExerciseDefinition.isBuiltin == true,
         * and matching typeSnapshot; otherwise emit the generic type label so custom names never leave device.
         */
        internal fun resolveExerciseDisplayName(
            exerciseId: String,
            typeSnapshot: ExerciseType,
            exerciseIsBuiltin: Boolean?,
            exerciseType: String?,
        ): String {
            val builtin = builtinById[exerciseId]
            if (builtin != null &&
                exerciseIsBuiltin == true &&
                builtin.type == typeSnapshot &&
                exerciseType == typeSnapshot.name
            ) {
                return builtin.name
            }
            return when (typeSnapshot) {
                ExerciseType.CARDIO -> "自定义有氧项目"
                ExerciseType.STRENGTH -> "自定义力量项目"
                ExerciseType.OTHER -> "自定义其他项目"
            }
        }
    }
}
