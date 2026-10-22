package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.OnConflictStrategy
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.SleepRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT localDate AS recordDate FROM MealRecord UNION SELECT archiveLocalDate AS recordDate FROM SleepRecord UNION SELECT localDate AS recordDate FROM WorkoutSession ORDER BY recordDate DESC")
    fun datesWithRecords(): Flow<List<String>>
    @Query("SELECT * FROM MealRecord WHERE localDate = :date ORDER BY occurredAtUtcMs DESC, id ASC")
    fun mealsOn(date: String): Flow<List<MealRecord>>
    @Query("SELECT * FROM MealRecord WHERE id = :id") suspend fun meal(id: String): MealRecord?
    @Query("SELECT * FROM MealRecord WHERE id = :id") fun observeMeal(id: String): Flow<MealRecord?>
    @Query("SELECT * FROM MealRecord ORDER BY id") suspend fun allMeals(): List<MealRecord>
    @Upsert suspend fun putMeal(record: MealRecord)
    @Query("DELETE FROM MealRecord WHERE id = :id") suspend fun deleteMeal(id: String): Int

    @Query("SELECT * FROM SleepRecord WHERE archiveLocalDate = :date ORDER BY COALESCE(endedAtUtcMs, startedAtUtcMs) DESC, id ASC")
    fun sleepsOn(date: String): Flow<List<SleepRecord>>
    @Query("SELECT * FROM SleepRecord ORDER BY COALESCE(endedAtUtcMs, startedAtUtcMs) DESC, id ASC")
    fun observeSleeps(): Flow<List<SleepRecord>>
    @Query("SELECT * FROM SleepRecord WHERE activeSlot = 1") fun activeSleep(): Flow<SleepRecord?>
    @Query("SELECT * FROM SleepRecord WHERE activeSlot = 1") suspend fun activeSleepNow(): SleepRecord?
    @Query("SELECT * FROM SleepRecord WHERE id = :id") suspend fun sleep(id: String): SleepRecord?
    @Query("SELECT * FROM SleepRecord WHERE id = :id") fun observeSleep(id: String): Flow<SleepRecord?>
    @Query("SELECT * FROM SleepRecord ORDER BY id") suspend fun allSleeps(): List<SleepRecord>
    @Query("SELECT EXISTS(SELECT 1 FROM SleepRecord WHERE id != :excludeId AND (:end IS NULL OR startedAtUtcMs < :end) AND (endedAtUtcMs IS NULL OR :start < endedAtUtcMs))")
    suspend fun hasOverlap(start: Long, end: Long?, excludeId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSleep(record: SleepRecord)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateSleep(record: SleepRecord): Int
    // Upsert can swallow a secondary unique-index conflict and update zero rows. Never report that as a save.
    @Transaction suspend fun putSleep(record: SleepRecord) {
        if (sleep(record.id) == null) insertSleep(record) else check(updateSleep(record) == 1)
    }
    @Query("DELETE FROM SleepRecord WHERE id = :id") suspend fun deleteSleep(id: String): Int
}
