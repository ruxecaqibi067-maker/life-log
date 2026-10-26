package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.local.entity.RestoreState
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {
    @Query("SELECT * FROM MediaAsset WHERE id = :id") suspend fun media(id: String): MediaAsset?
    @Query("SELECT * FROM MediaAsset ORDER BY id") suspend fun allMedia(): List<MediaAsset>
    @Insert suspend fun insertMedia(media: MediaAsset)
    @Query("SELECT * FROM MediaAsset WHERE NOT EXISTS (SELECT 1 FROM MealRecord WHERE photoId = MediaAsset.id) AND NOT EXISTS (SELECT 1 FROM MealPhoto WHERE photoId = MediaAsset.id) ORDER BY id")
    suspend fun unreferencedMedia(): List<MediaAsset>
    @Query("DELETE FROM MediaAsset WHERE id = :id AND NOT EXISTS (SELECT 1 FROM MealRecord WHERE photoId = :id) AND NOT EXISTS (SELECT 1 FROM MealPhoto WHERE photoId = :id)")
    suspend fun deleteUnreferencedMedia(id: String): Int
    @Query("SELECT * FROM AppSettings WHERE id = 1") fun settings(): Flow<AppSettings>
    @Query("SELECT * FROM AppSettings WHERE id = 1") suspend fun settingsNow(): AppSettings
    @Upsert suspend fun putSettings(settings: AppSettings)
    @Query("SELECT * FROM RestoreState WHERE id = 1") suspend fun restoreState(): RestoreState
    @Upsert suspend fun putRestoreState(state: RestoreState)
}
