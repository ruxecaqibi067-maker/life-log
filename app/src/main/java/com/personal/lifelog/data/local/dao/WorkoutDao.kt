package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Update
import androidx.room.OnConflictStrategy
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.local.entity.WorkoutItem
import com.personal.lifelog.data.local.entity.WorkoutSession
import com.personal.lifelog.data.local.entity.WorkoutSet
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.domain.ExerciseType
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM ExerciseDefinition WHERE :includeHidden OR isHidden = 0 ORDER BY lastUsedAtUtcMs DESC, name ASC, id ASC")
    fun exercises(includeHidden: Boolean = false): Flow<List<ExerciseDefinition>>
    @Query("SELECT * FROM ExerciseDefinition WHERE id = :id") suspend fun exercise(id: String): ExerciseDefinition?
    @Query("SELECT * FROM ExerciseDefinition ORDER BY id") suspend fun allExercises(): List<ExerciseDefinition>
    @Query("SELECT EXISTS(SELECT 1 FROM ExerciseDefinition WHERE type = :type AND activeNameKey = :key AND id != :excludeId)")
    suspend fun hasName(type: ExerciseType, key: String, excludeId: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM WorkoutItem WHERE exerciseId = :id)") suspend fun isUsed(id: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExercise(definition: ExerciseDefinition)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateExercise(definition: ExerciseDefinition): Int
    @Transaction suspend fun putExercise(definition: ExerciseDefinition) {
        if (exercise(definition.id) == null) insertExercise(definition) else check(updateExercise(definition) == 1)
    }

    @Transaction
    @Query("SELECT * FROM WorkoutSession WHERE localDate = :date ORDER BY occurredAtUtcMs DESC, id ASC")
    fun workoutsOn(date: String): Flow<List<WorkoutWithItems>>
    @Transaction @Query("SELECT * FROM WorkoutSession WHERE id = :id") suspend fun workout(id: String): WorkoutWithItems?
    @Transaction @Query("SELECT * FROM WorkoutSession WHERE id = :id") fun observeWorkout(id: String): Flow<WorkoutWithItems?>
    @Query("SELECT * FROM WorkoutSession ORDER BY id") suspend fun allSessions(): List<WorkoutSession>
    @Query("SELECT * FROM WorkoutItem ORDER BY sessionId, sortOrder, id") suspend fun allItems(): List<WorkoutItem>
    @Query("SELECT * FROM WorkoutSet ORDER BY itemId, sortOrder, id") suspend fun allSets(): List<WorkoutSet>
    @Query("SELECT * FROM WorkoutItem WHERE id = :id") suspend fun item(id: String): WorkoutItem?
    @Query("SELECT * FROM WorkoutSet WHERE id = :id") suspend fun set(id: String): WorkoutSet?
    @Upsert suspend fun putSession(session: WorkoutSession)
    @Insert suspend fun insertItems(items: List<WorkoutItem>)
    @Insert suspend fun insertSets(sets: List<WorkoutSet>)
    @Query("DELETE FROM WorkoutItem WHERE sessionId = :sessionId") suspend fun deleteItems(sessionId: String)
    @Query("DELETE FROM WorkoutSession WHERE id = :id") suspend fun deleteWorkout(id: String): Int
}
