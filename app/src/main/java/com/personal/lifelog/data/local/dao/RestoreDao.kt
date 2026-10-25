package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** Only called inside the restore engine's single Room transaction, under WriteCoordinator. */
@Dao
interface RestoreDao {
    @Query("DELETE FROM MealRecord") suspend fun clearMeals()
    @Query("DELETE FROM WorkRecord") suspend fun clearWorkRecords()
    @Query("DELETE FROM SleepRecord") suspend fun clearSleeps()
    @Query("DELETE FROM WorkoutSession") suspend fun clearSessions()
    @Query("DELETE FROM ExerciseDefinition") suspend fun clearExercises()
    @Query("DELETE FROM MediaAsset") suspend fun clearMedia()
}
