package com.personal.lifelog.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.personal.lifelog.domain.ExerciseType

@Entity(
    tableName = "ExerciseDefinition",
    indices = [Index(value = ["type", "activeNameKey"], unique = true)],
)
data class ExerciseDefinition(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val type: ExerciseType,
    val isBuiltin: Boolean,
    val isHidden: Boolean,
    /** Derived key: normalizedName when available, null when hidden; never trust it on import. */
    val activeNameKey: String?,
    val lastUsedAtUtcMs: Long?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(tableName = "WorkoutSession", indices = [Index("localDate")])
data class WorkoutSession(
    @PrimaryKey val id: String,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val localDate: String,
    val note: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "WorkoutItem",
    foreignKeys = [
        ForeignKey(entity = WorkoutSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseDefinition::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sessionId", "sortOrder"], unique = true), Index("exerciseId")],
)
data class WorkoutItem(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val nameSnapshot: String,
    val typeSnapshot: ExerciseType,
    val sortOrder: Int,
    val durationSeconds: Long?,
    val distanceMeters: Long?,
    val note: String?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "WorkoutSet",
    foreignKeys = [ForeignKey(entity = WorkoutItem::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["itemId", "sortOrder"], unique = true)],
)
data class WorkoutSet(
    @PrimaryKey val id: String,
    val itemId: String,
    val sortOrder: Int,
    val weightGrams: Long?,
    val repetitions: Int?,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

data class ItemWithSets(
    @Embedded val item: WorkoutItem,
    @Relation(parentColumn = "id", entityColumn = "itemId") val sets: List<WorkoutSet>,
)

data class WorkoutWithItems(
    @Embedded val session: WorkoutSession,
    @Relation(entity = WorkoutItem::class, parentColumn = "id", entityColumn = "sessionId") val items: List<ItemWithSets>,
) {
    fun sorted(): WorkoutWithItems = copy(items = items.sortedBy { it.item.sortOrder }
        .map { it.copy(sets = it.sets.sortedBy(WorkoutSet::sortOrder)) })
}
