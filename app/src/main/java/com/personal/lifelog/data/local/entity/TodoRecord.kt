package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "TodoRecord", foreignKeys = [ForeignKey(
    entity = HabitRecord::class, parentColumns = ["id"], childColumns = ["habitId"],
    onDelete = ForeignKey.RESTRICT,
)], indices = [Index("localDate"), Index(value = ["habitId", "habitDate"], unique = true)])
data class TodoRecord(
    @PrimaryKey val id: String,
    val title: String,
    val localDate: String,
    val scheduledMinute: Int?,
    val habitId: String?,
    /** Immutable generation key, independent of an individually edited due date. */
    val habitDate: String?,
    val completedAtUtcMs: Long?,
    val deletedAtUtcMs: Long?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
) {
    @get:Ignore val isCompleted: Boolean get() = completedAtUtcMs != null
}
