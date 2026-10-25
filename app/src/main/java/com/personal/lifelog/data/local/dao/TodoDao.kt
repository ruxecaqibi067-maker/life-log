package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.personal.lifelog.data.local.entity.HabitRecord
import com.personal.lifelog.data.local.entity.TodoRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM TodoRecord WHERE localDate = :date AND deletedAtUtcMs IS NULL ORDER BY completedAtUtcMs IS NOT NULL, scheduledMinute IS NULL, scheduledMinute, createdAtUtcMs, id")
    fun observeOn(date: String): Flow<List<TodoRecord>>
    @Query("SELECT * FROM HabitRecord ORDER BY createdAtUtcMs, id")
    fun observeHabits(): Flow<List<HabitRecord>>
    @Query("SELECT * FROM TodoRecord WHERE id = :id") suspend fun todo(id: String): TodoRecord?
    @Query("SELECT * FROM HabitRecord WHERE id = :id") suspend fun habit(id: String): HabitRecord?
    @Query("SELECT * FROM TodoRecord WHERE habitId = :habitId AND habitDate = :date")
    suspend fun generated(habitId: String, date: String): TodoRecord?
    @Query("SELECT * FROM HabitRecord ORDER BY id") suspend fun allHabits(): List<HabitRecord>
    @Query("SELECT * FROM TodoRecord ORDER BY id") suspend fun allTodos(): List<TodoRecord>
    @Insert suspend fun insertTodo(record: TodoRecord)
    @Update suspend fun updateTodo(record: TodoRecord)
    @Insert suspend fun insertHabit(record: HabitRecord)
    @Update suspend fun updateHabit(record: HabitRecord)
}
