package com.personal.lifelog.feature.workout

import android.os.Bundle
import com.personal.lifelog.core.validation.Units
import com.personal.lifelog.domain.WorkoutSetDraft
import java.math.BigDecimal

/** Per-set editing input, including incomplete/invalid text; only draft() crosses into persistence. */
data class WorkoutSetForm(
    val id: String,
    val kilograms: String = "",
    val repetitions: String = "",
) {
    val blank get() = kilograms.isBlank() && repetitions.isBlank()
    fun draft() = WorkoutSetDraft(id, Units.kilogramsToGrams(kilograms), Units.repetitions(repetitions))
    fun saved() = Bundle().apply {
        putString("id", id); putString("kgInput", kilograms); putString("repsInput", repetitions)
    }

    companion object {
        fun from(set: WorkoutSetDraft) = WorkoutSetForm(set.id,
            set.weightGrams?.let { BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString() }.orEmpty(),
            set.repetitions?.toString().orEmpty())

        /** Accept T08's saved lightweight draft as well as T09's unfinished numeric input. */
        fun restore(bundle: Bundle): WorkoutSetForm = if (bundle.containsKey("kgInput")) {
            WorkoutSetForm(bundle.getString("id")!!, bundle.getString("kgInput").orEmpty(), bundle.getString("repsInput").orEmpty())
        } else from(WorkoutSetDraft(bundle.getString("id")!!,
            if (bundle.containsKey("weight")) bundle.getLong("weight") else null,
            if (bundle.containsKey("reps")) bundle.getInt("reps") else null))
    }
}
