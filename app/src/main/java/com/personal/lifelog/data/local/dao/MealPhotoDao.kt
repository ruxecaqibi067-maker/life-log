package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.personal.lifelog.data.local.entity.MealPhoto
import kotlinx.coroutines.flow.Flow

@Dao
interface MealPhotoDao {
    @Query("SELECT * FROM MealPhoto WHERE mealId = :mealId ORDER BY sortOrder") suspend fun forMeal(mealId: String): List<MealPhoto>
    @Query("SELECT * FROM MealPhoto WHERE mealId = :mealId ORDER BY sortOrder") fun observe(mealId: String): Flow<List<MealPhoto>>
    @Query("SELECT * FROM MealPhoto ORDER BY mealId, sortOrder") suspend fun all(): List<MealPhoto>
    @Query("SELECT * FROM MealPhoto ORDER BY mealId, sortOrder") fun observeAll(): Flow<List<MealPhoto>>
    @Query("DELETE FROM MealPhoto WHERE mealId = :mealId") suspend fun clear(mealId: String)
    @Query("DELETE FROM MealPhoto") suspend fun clearAll()
    @Insert suspend fun insert(rows: List<MealPhoto>)
}
