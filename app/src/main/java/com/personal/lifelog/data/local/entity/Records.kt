package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.personal.lifelog.domain.Satiety

@Entity(
    tableName = "MealRecord",
    foreignKeys = [ForeignKey(
        entity = MediaAsset::class, parentColumns = ["id"], childColumns = ["photoId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("localDate"), Index("photoId")],
)
data class MealRecord(
    @PrimaryKey val id: String,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val localDate: String,
    val text: String?,
    val satiety: Satiety?,
    val photoId: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    /** Present only when [satiety] is [Satiety.CUSTOM]. */
    val satietyText: String? = null,
)

@Entity(tableName = "SleepRecord", indices = [Index("archiveLocalDate"), Index(value = ["activeSlot"], unique = true)])
data class SleepRecord(
    @PrimaryKey val id: String,
    val startedAtUtcMs: Long,
    val startZoneId: String,
    val endedAtUtcMs: Long?,
    val endZoneId: String?,
    val archiveLocalDate: String,
    val activeSlot: Int?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)
