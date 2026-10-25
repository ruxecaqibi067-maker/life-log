package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "WorkRecord", indices = [Index("archiveLocalDate"), Index(value = ["activeSlot"], unique = true)])
data class WorkRecord(
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
