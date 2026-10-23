package com.personal.lifelog.feature.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.core.validation.Rules

/** Each group is a separate lazy row; a long exercise never creates a nested scrolling form. */
@Composable
internal fun WorkoutSetEditor(
    item: WorkoutItemForm, itemIndex: Int, set: WorkoutSetForm, index: Int,
    enabled: Boolean, error: Boolean, viewModel: WorkoutEditorViewModel,
) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("workout-set-${set.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.workout_set_heading, item.name, index + 1), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(set.kilograms, { viewModel.changeSetWeight(item.id, set.id, it) },
                Modifier.fillMaxWidth().testTag("${set.id}-kg"), enabled = enabled, isError = error, singleLine = true,
                label = { Text(stringResource(R.string.workout_set_kg)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(set.repetitions, { viewModel.changeSetRepetitions(item.id, set.id, it) },
                Modifier.fillMaxWidth().testTag("${set.id}-reps"), enabled = enabled, isError = error, singleLine = true,
                label = { Text(stringResource(R.string.workout_set_reps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row {
                TextButton({ viewModel.copySet(item.id, set.id) }, enabled = enabled && !set.blank && item.sets.size < Rules.SETS_PER_ITEM_LIMIT,
                    modifier = Modifier.weight(1f).testTag("${set.id}-copy")) { Text(stringResource(R.string.workout_set_copy)) }
                IconButton({ viewModel.moveSet(item.id, set.id, -1) }, enabled = enabled && index > 0) {
                    Icon(Icons.Default.ArrowUpward, stringResource(R.string.workout_set_up, itemIndex + 1, index + 1))
                }
                IconButton({ viewModel.moveSet(item.id, set.id, 1) }, enabled = enabled && index < item.sets.lastIndex) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.workout_set_down, itemIndex + 1, index + 1))
                }
                IconButton({ viewModel.removeSet(item.id, set.id) }, enabled = enabled, modifier = Modifier.testTag("${set.id}-remove")) {
                    Icon(Icons.Default.Close, stringResource(R.string.workout_set_remove, itemIndex + 1, index + 1))
                }
            }
        }
    }
}

@Composable
internal fun WorkoutSetActions(item: WorkoutItemForm, enabled: Boolean, viewModel: WorkoutEditorViewModel) {
    Column(Modifier.fillMaxWidth().testTag("${item.id}-set-actions")) {
        Text(stringResource(R.string.workout_sets_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ viewModel.addSets(item.id) }, enabled = enabled && item.sets.size < Rules.SETS_PER_ITEM_LIMIT,
                modifier = Modifier.weight(1f).testTag("${item.id}-add-set")) { Text(stringResource(R.string.workout_set_add)) }
            TextButton({ viewModel.copyLastSet(item.id) }, enabled = enabled && item.sets.lastOrNull()?.blank == false && item.sets.size < Rules.SETS_PER_ITEM_LIMIT,
                modifier = Modifier.weight(1f).testTag("${item.id}-copy-last")) { Text(stringResource(R.string.workout_set_copy_last)) }
        }
        TextButton({ viewModel.addSets(item.id, 3) }, enabled = enabled && item.sets.size <= Rules.SETS_PER_ITEM_LIMIT - 3,
            modifier = Modifier.testTag("${item.id}-add-three")) { Text(stringResource(R.string.workout_set_add_three)) }
    }
}
