package com.personal.lifelog.data.repository

import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.*
import com.personal.lifelog.domain.*
import java.time.Clock
import kotlinx.coroutines.flow.map

class WorkoutRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
) {
    fun exercises(includeHidden: Boolean = false) = db.workouts().exercises(includeHidden)
    fun workoutsOn(date: String) = db.workouts().workoutsOn(date).map { rows -> rows.map(WorkoutWithItems::sorted) }
    suspend fun workout(id: String) = db.workouts().workout(Rules.id(id))?.sorted()
    fun observeWorkout(id: String) = db.workouts().observeWorkout(Rules.id(id)).map { it?.sorted() }

    suspend fun saveExercise(draft: ExerciseDraft): DataResult<ExerciseDefinition> = write(db, writes) {
        Rules.id(draft.id)
        val name = Rules.exerciseName(draft.name)
        val key = Rules.normalizedName(name)
        val old = db.workouts().exercise(draft.id)
        if (old != null && old.type != draft.type && (old.lastUsedAtUtcMs != null || db.workouts().isUsed(old.id))) {
            reject(ErrorCode.USED_EXERCISE_TYPE)
        }
        val hidden = old?.isHidden ?: false
        if (!hidden && db.workouts().hasName(draft.type, key, draft.id)) reject(ErrorCode.DUPLICATE_NAME)
        val now = clock.millis()
        ExerciseDefinition(draft.id, name, key, draft.type, old?.isBuiltin ?: false, hidden,
            if (hidden) null else key, old?.lastUsedAtUtcMs, old?.createdAtUtcMs ?: now,
            updatedAt(now, old?.createdAtUtcMs ?: now, old?.updatedAtUtcMs ?: now))
            .also { db.workouts().putExercise(it) }
    }

    suspend fun setExerciseHidden(id: String, hidden: Boolean): DataResult<ExerciseDefinition> = write(db, writes) {
        val old = db.workouts().exercise(Rules.id(id)) ?: reject(ErrorCode.NOT_FOUND)
        if (!hidden && db.workouts().hasName(old.type, old.normalizedName, id)) reject(ErrorCode.DUPLICATE_NAME)
        old.copy(isHidden = hidden, activeNameKey = if (hidden) null else old.normalizedName,
            updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
            .also { db.workouts().putExercise(it) }
    }

    suspend fun saveWorkout(draft: WorkoutDraft): DataResult<WorkoutWithItems> = write(db, writes) {
        Rules.id(draft.id)
        if (draft.items.isEmpty()) reject(ErrorCode.EMPTY_WORKOUT)
        if (draft.items.size > Rules.WORKOUT_ITEMS_LIMIT) reject(ErrorCode.TOO_MANY_ITEMS)
        val date = RecordTime.localDate(draft.occurredAtUtcMs, draft.zoneId)
        val note = Rules.text(draft.note, Rules.NOTE_LIMIT)
        val old = db.workouts().workout(draft.id)
        val oldItems = old?.items?.associateBy { it.item.id }.orEmpty()
        val now = clock.millis()
        val itemIds = mutableSetOf<String>()
        val setIds = mutableSetOf<String>()
        val items = mutableListOf<WorkoutItem>()
        val sets = mutableListOf<WorkoutSet>()
        val usedDefinitions = linkedMapOf<String, ExerciseDefinition>()
        draft.items.forEachIndexed { order, input ->
            Rules.id(input.id)
            Rules.id(input.exerciseId)
            if (!itemIds.add(input.id)) reject(ErrorCode.DUPLICATE_ID, "itemId")
            val existingItem = db.workouts().item(input.id)
            if (existingItem != null && existingItem.sessionId != draft.id) reject(ErrorCode.DUPLICATE_ID, "itemId")
            val definition = db.workouts().exercise(input.exerciseId) ?: reject(ErrorCode.NOT_FOUND, "exerciseId")
            val previous = oldItems[input.id]
            val retained = previous?.item?.takeIf { it.exerciseId == input.exerciseId }
            if (definition.isHidden && retained == null) reject(ErrorCode.HIDDEN_EXERCISE)
            val type = retained?.typeSnapshot ?: definition.type
            val nonBlankSets = input.sets.filter { it.weightGrams != null || it.repetitions != null }
            if (nonBlankSets.size > Rules.SETS_PER_ITEM_LIMIT) reject(ErrorCode.TOO_MANY_SETS)
            Rules.positive(input.durationSeconds, "durationSeconds")
            Rules.positive(input.distanceMeters, "distanceMeters")
            if ((type == ExerciseType.STRENGTH && (input.durationSeconds != null || input.distanceMeters != null)) ||
                (type != ExerciseType.STRENGTH && nonBlankSets.isNotEmpty()) ||
                (type == ExerciseType.OTHER && input.distanceMeters != null)
            ) reject(ErrorCode.INVALID_FIELDS_FOR_TYPE)
            items += WorkoutItem(input.id, draft.id, input.exerciseId, retained?.nameSnapshot ?: definition.name,
                type, order, input.durationSeconds, input.distanceMeters, Rules.text(input.note, Rules.NOTE_LIMIT),
                previous?.item?.createdAtUtcMs ?: now,
                updatedAt(now, previous?.item?.createdAtUtcMs ?: now, previous?.item?.updatedAtUtcMs ?: now))
            val previousSets = previous?.sets?.associateBy { it.id }.orEmpty()
            nonBlankSets.forEachIndexed { setOrder, set ->
                Rules.id(set.id)
                if (!setIds.add(set.id)) reject(ErrorCode.DUPLICATE_ID, "setId")
                val existingSet = db.workouts().set(set.id)
                if (existingSet != null && existingSet.itemId != input.id) reject(ErrorCode.DUPLICATE_ID, "setId")
                val previousSet = previousSets[set.id]
                sets += WorkoutSet(set.id, input.id, setOrder, Rules.weight(set.weightGrams), Rules.repetitions(set.repetitions),
                    previousSet?.createdAtUtcMs ?: now,
                    updatedAt(now, previousSet?.createdAtUtcMs ?: now, previousSet?.updatedAtUtcMs ?: now))
            }
            usedDefinitions[definition.id] = definition
        }
        val session = WorkoutSession(draft.id, draft.occurredAtUtcMs, draft.zoneId, date, note,
            old?.session?.createdAtUtcMs ?: now,
            updatedAt(now, old?.session?.createdAtUtcMs ?: now, old?.session?.updatedAtUtcMs ?: now))
        db.workouts().putSession(session)
        db.workouts().deleteItems(draft.id) // Child sets cascade; every replacement happens in this transaction.
        db.workouts().insertItems(items)
        if (sets.isNotEmpty()) db.workouts().insertSets(sets)
        usedDefinitions.values.forEach {
            db.workouts().putExercise(it.copy(lastUsedAtUtcMs = maxOf(now, it.lastUsedAtUtcMs ?: now),
                updatedAtUtcMs = updatedAt(now, it.createdAtUtcMs, it.updatedAtUtcMs)))
        }
        db.workouts().workout(draft.id)!!.sorted()
    }

    suspend fun deleteWorkout(id: String): DataResult<Boolean> = write(db, writes) { db.workouts().deleteWorkout(Rules.id(id)) > 0 }
}
