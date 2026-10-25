package com.personal.lifelog.feature.workout

import android.os.Bundle
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.core.validation.Units
import com.personal.lifelog.domain.*

/** Compact input expands to the same independently editable, stable-ID sets used by old records. */
data class StrengthBatch(
    val count: String = "",
    val kilograms: String = "",
    val repetitions: String = "",
    val ids: List<String> = listOf(RecordIds.newId()),
) {
    fun withCount(value: String): StrengthBatch {
        val size = value.trim().toIntOrNull()?.takeIf { it in 1..Rules.SETS_PER_ITEM_LIMIT } ?: 1
        return copy(count = value, ids = ids + List((size - ids.size).coerceAtLeast(0)) { RecordIds.newId() })
    }

    fun sets(): List<WorkoutSetForm> {
        if (count.isBlank() && kilograms.isBlank() && repetitions.isBlank()) return emptyList()
        val size = if (count.isBlank()) 1 else Units.repetitions(count)!!
        if (size > Rules.SETS_PER_ITEM_LIMIT) reject(ErrorCode.TOO_MANY_SETS)
        if (kilograms.isBlank() && repetitions.isBlank()) reject(ErrorCode.INVALID_NUMBER, "batchQuantity")
        // Validation is shared with individual set editing and never substitutes zeros for missing values.
        Units.kilogramsToGrams(kilograms)
        Units.repetitions(repetitions)
        check(ids.size >= size)
        return List(size) { WorkoutSetForm(ids[it], kilograms, repetitions) }
    }

    fun saved() = Bundle().apply {
        putString("count", count); putString("kg", kilograms); putString("reps", repetitions)
        putStringArrayList("ids", ArrayList(ids))
    }

    companion object {
        fun restore(b: Bundle) = StrengthBatch(b.getString("count").orEmpty(), b.getString("kg").orEmpty(),
            b.getString("reps").orEmpty(), b.getStringArrayList("ids").orEmpty())

        /** Different historical sets stay in individual mode; never flatten them on load. */
        fun from(sets: List<WorkoutSetForm>): StrengthBatch? {
            if (sets.isEmpty()) return StrengthBatch()
            val first = sets.first()
            if (sets.any { it.blank || it.kilograms != first.kilograms || it.repetitions != first.repetitions }) return null
            return StrengthBatch(sets.size.toString(), first.kilograms, first.repetitions, sets.map { it.id })
        }
    }
}
