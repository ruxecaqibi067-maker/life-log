package com.personal.lifelog.feature.home

import androidx.annotation.ArrayRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringArrayResource
import com.personal.lifelog.R
import kotlin.random.Random

internal enum class DurationKind { SLEEP, WORK }
internal enum class DurationFeedback { UNSET, EMPTY, ACTIVE, SHORT, BALANCED, LONG }

/** A comparison with the user's own goal, not a health assessment or a new duration calculation. */
internal fun durationFeedback(kind: DurationKind, recordedMs: Long?, targetMinutes: Int?, active: Boolean): DurationFeedback {
    if (recordedMs == null || recordedMs <= 0) return if (active) DurationFeedback.ACTIVE else DurationFeedback.EMPTY
    if (targetMinutes == null || targetMinutes !in 1..1440) return DurationFeedback.UNSET
    val ratio = recordedMs.toDouble() / (targetMinutes * 60_000L)
    return when {
        ratio < 0.8 -> DurationFeedback.SHORT
        kind == DurationKind.WORK && ratio > 1.2 -> DurationFeedback.LONG
        else -> DurationFeedback.BALANCED
    }
}

@ArrayRes
internal fun feedbackMessages(kind: DurationKind, feedback: DurationFeedback): Int = when (kind) {
    DurationKind.SLEEP -> when (feedback) {
        DurationFeedback.SHORT -> R.array.sleep_feedback_short
        DurationFeedback.BALANCED, DurationFeedback.LONG -> R.array.sleep_feedback_balanced
        DurationFeedback.UNSET -> R.array.sleep_feedback_unset
        DurationFeedback.EMPTY -> R.array.sleep_feedback_empty
        DurationFeedback.ACTIVE -> R.array.sleep_feedback_active
    }
    DurationKind.WORK -> when (feedback) {
        DurationFeedback.SHORT -> R.array.work_feedback_short
        DurationFeedback.BALANCED -> R.array.work_feedback_balanced
        DurationFeedback.LONG -> R.array.work_feedback_long
        DurationFeedback.UNSET -> R.array.work_feedback_unset
        DurationFeedback.EMPTY -> R.array.work_feedback_empty
        DurationFeedback.ACTIVE -> R.array.work_feedback_active
    }
}

@Composable
internal fun durationFeedbackText(kind: DurationKind, feedback: DurationFeedback): String {
    val messages = stringArrayResource(feedbackMessages(kind, feedback))
    // Keep the random choice through scrolling, recomposition and Activity recreation.
    // Only a different feedback category or a new home visit chooses again.
    val choice = rememberSaveable(kind, feedback) { Random.nextInt(messages.size) }
    return messages[choice.mod(messages.size)]
}
