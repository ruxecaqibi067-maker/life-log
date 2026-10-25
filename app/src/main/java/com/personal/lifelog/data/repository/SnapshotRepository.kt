package com.personal.lifelog.data.repository

import androidx.room.withTransaction
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.*

/** Data fields for the future versioned DTO. No ZIP, paths to other apps, or restore journal. */
data class DataSnapshot(
    val meals: List<MealRecord>,
    val sleeps: List<SleepRecord>,
    val exercises: List<ExerciseDefinition>,
    val sessions: List<WorkoutSession>,
    val items: List<WorkoutItem>,
    val sets: List<WorkoutSet>,
    val media: List<MediaAsset>,
    val settings: AppSettings,
    val workRecords: List<WorkRecord> = emptyList(),
    /** Includes deletion tombstones so restored habits cannot recreate removed instances. */
    val todos: List<TodoRecord> = emptyList(),
    val habits: List<HabitRecord> = emptyList(),
)

class SnapshotRepository(private val db: AppDatabase) {
    /** DB-consistent read. T11 must also hold WriteCoordinator through associated photo copying. */
    suspend fun read(): DataSnapshot = db.withTransaction {
        val meals = db.records().allMeals()
        val photoIds = meals.mapNotNull { it.photoId }.toSet()
        DataSnapshot(meals, db.records().allSleeps(), db.workouts().allExercises(),
            db.workouts().allSessions(), db.workouts().allItems(), db.workouts().allSets(),
            db.metadata().allMedia().filter { it.id in photoIds }, db.metadata().settingsNow(),
            workRecords = db.work().allWorks(), todos = db.todos().allTodos(), habits = db.todos().allHabits())
    }
}
