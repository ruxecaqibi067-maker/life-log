package com.personal.lifelog.feature.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.personal.lifelog.R
import com.personal.lifelog.domain.WorkoutEffort
import com.personal.lifelog.ui.components.*
import com.personal.lifelog.ui.theme.LogDimens

@Composable
internal fun StrengthBatchEditor(item: WorkoutItemForm, enabled: Boolean, viewModel: WorkoutEditorViewModel) {
    val batch = requireNotNull(item.batch)
    Column(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
        RecordTextField(batch.count, { value -> viewModel.changeBatch(item.id) { it.withCount(value) } },
            Modifier.fillMaxWidth().testTag("${item.id}-batch-count"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.workout_batch_count)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        RecordTextField(batch.repetitions, { value -> viewModel.changeBatch(item.id) { it.copy(repetitions = value) } },
            Modifier.fillMaxWidth().testTag("${item.id}-batch-reps"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.workout_batch_reps)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        RecordTextField(batch.kilograms, { value -> viewModel.changeBatch(item.id) { it.copy(kilograms = value) } },
            Modifier.fillMaxWidth().testTag("${item.id}-batch-kg"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.workout_batch_kg)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text(stringResource(R.string.workout_batch_hint), style = MaterialTheme.typography.bodySmall)
        ActionButton({ viewModel.editIndividualSets(item.id) }, enabled = enabled,
            modifier = Modifier.testTag("${item.id}-individual")) { Text(stringResource(R.string.workout_individual)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutEffortEditor(form: WorkoutForm, enabled: Boolean, change: (WorkoutForm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
        Text(stringResource(R.string.workout_effort_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
            WorkoutEffort.entries.forEach { value ->
                FilterChip(selected = form.effort == value, enabled = enabled,
                    onClick = {
                        change(form.copy(effort = value.takeUnless { it == form.effort },
                            effortText = if (value == WorkoutEffort.CUSTOM && form.effort != value) form.effortText else ""))
                    }, label = { Text(effortLabel(value)) },
                    modifier = Modifier.heightIn(min = LogDimens.touch).testTag("workout-effort-${value.name}"))
            }
        }
        if (form.effort == WorkoutEffort.CUSTOM) RecordTextField(form.effortText,
            { change(form.copy(effortText = it)) }, Modifier.fillMaxWidth().testTag("workout-effort-text"),
            enabled = enabled, label = { Text(stringResource(R.string.workout_effort_custom_input)) }, singleLine = true)
    }
}

@Composable
internal fun effortLabel(value: WorkoutEffort, custom: String? = null): String =
    if (value == WorkoutEffort.CUSTOM && custom != null) custom else stringResource(when (value) {
        WorkoutEffort.EASY -> R.string.workout_effort_easy
        WorkoutEffort.MODERATE -> R.string.workout_effort_moderate
        WorkoutEffort.HARD -> R.string.workout_effort_hard
        WorkoutEffort.VERY_HARD -> R.string.workout_effort_very_hard
        WorkoutEffort.CUSTOM -> R.string.workout_effort_custom
    })
