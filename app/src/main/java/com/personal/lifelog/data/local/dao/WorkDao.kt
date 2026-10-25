package com.personal.lifelog.data.local.dao

import androidx.room.*
import com.personal.lifelog.data.local.entity.WorkRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Query("SELECT DISTINCT archiveLocalDate FROM WorkRecord ORDER BY archiveLocalDate DESC")
    fun datesWithRecords(): Flow<List<String>>
    @Query("SELECT * FROM WorkRecord WHERE archiveLocalDate = :date ORDER BY COALESCE(endedAtUtcMs, startedAtUtcMs) DESC, id ASC")
    fun worksOn(date: String): Flow<List<WorkRecord>>
    @Query("SELECT * FROM WorkRecord ORDER BY COALESCE(endedAtUtcMs, startedAtUtcMs) DESC, id ASC")
    fun observeWorks(): Flow<List<WorkRecord>>
    @Query("SELECT * FROM WorkRecord WHERE activeSlot = 1") fun activeWork(): Flow<WorkRecord?>
    @Query("SELECT * FROM WorkRecord WHERE activeSlot = 1") suspend fun activeWorkNow(): WorkRecord?
    @Query("SELECT * FROM WorkRecord WHERE id = :id") suspend fun work(id: String): WorkRecord?
    @Query("SELECT * FROM WorkRecord WHERE id = :id") fun observeWork(id: String): Flow<WorkRecord?>
    @Query("SELECT * FROM WorkRecord ORDER BY id") suspend fun allWorks(): List<WorkRecord>
    @Query("SELECT EXISTS(SELECT 1 FROM WorkRecord WHERE id != :excludeId AND (:end IS NULL OR startedAtUtcMs < :end) AND (endedAtUtcMs IS NULL OR :start < endedAtUtcMs))")
    suspend fun hasOverlap(start: Long, end: Long?, excludeId: String): Boolean
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertWork(record: WorkRecord)
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun updateWork(record: WorkRecord): Int
    // Upsert can swallow a secondary unique-index conflict and update zero rows. Never report that as a save.
    @Transaction suspend fun putWork(record: WorkRecord) {
        if (work(record.id) == null) insertWork(record) else check(updateWork(record) == 1)
    }
    @Query("DELETE FROM WorkRecord WHERE id = :id") suspend fun deleteWork(id: String): Int
}
