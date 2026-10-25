package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.lifelog.domain.ThemeMode

@Entity(tableName = "MediaAsset", indices = [Index(value = ["relativePath"], unique = true)])
data class MediaAsset(
    @PrimaryKey val id: String,
    val generationId: String,
    /** Relative to application filesDir, always media/<generationId>/<id>.jpg. */
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val byteSize: Long,
    val sha256: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(tableName = "AppSettings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val lastSuccessfulExportAtUtcMs: Long? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    /** Current global daily duration goal, in minutes; null means not set. */
    val targetSleepMinutes: Int? = null,
)

/** Internal restore commit marker; excluded from user backup DTOs. */
@Entity(tableName = "RestoreState")
data class RestoreState(
    @PrimaryKey val id: Int = 1,
    val lastCommittedRestoreId: String? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)
