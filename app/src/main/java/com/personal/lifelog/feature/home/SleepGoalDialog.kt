package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.RecordTextField
import com.personal.lifelog.ui.theme.LogDimens

data class SleepGoalDraft(
    val open: Boolean = false,
    val hours: String = "8",
    val minutes: String = "0",
    val busy: Boolean = false,
    val invalid: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Safe text parsing: no truncation, overflow, clock interpretation or implicit carry. */
internal fun parseSleepGoal(hours: String, minutes: String): Int? {
    if (hours.isEmpty() || minutes.isEmpty() || hours.any { it !in '0'..'9' } || minutes.any { it !in '0'..'9' }) return null
    val h = hours.toIntOrNull() ?: return null
    val m = minutes.toIntOrNull() ?: return null
    if (h !in 0..24 || m !in 0..59 || (h == 24 && m != 0)) return null
    return (h * 60 + m).takeIf { it in 1..1440 }
}

@Composable
fun SleepGoalDialog(
    draft: SleepGoalDraft,
    onHours: (String) -> Unit,
    onMinutes: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    if (!draft.open) return
    AlertDialog(
        onDismissRequest = { if (!draft.busy) onCancel() },
        title = { Text(stringResource(R.string.home_sleep_goal_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
                Text(stringResource(R.string.home_sleep_goal_hint))
                val hours: @Composable (Modifier) -> Unit = { fieldModifier ->
                    RecordTextField(draft.hours, onHours, fieldModifier.testTag("sleep-goal-hours"), enabled = !draft.busy,
                        label = { Text(stringResource(R.string.home_sleep_hour_label)) }, singleLine = true,
                        isError = draft.invalid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                val minutes: @Composable (Modifier) -> Unit = { fieldModifier ->
                    RecordTextField(draft.minutes, onMinutes, fieldModifier.testTag("sleep-goal-minutes"), enabled = !draft.busy,
                        label = { Text(stringResource(R.string.home_sleep_minute_label)) }, singleLine = true,
                        isError = draft.invalid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (LocalDensity.current.fontScale > 1.2f) {
                    hours(Modifier.fillMaxWidth()); minutes(Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    hours(Modifier.weight(1f)); minutes(Modifier.weight(1f))
                }
                if (draft.invalid) Text(stringResource(R.string.home_sleep_goal_invalid), color = MaterialTheme.colorScheme.error)
                if (draft.saveFailed) Text(stringResource(R.string.home_sleep_goal_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            ActionButton(onClick = onSave, enabled = !draft.busy, modifier = Modifier.testTag("sleep-goal-save")) {
                Text(stringResource(if (draft.busy) R.string.saving else R.string.home_sleep_goal_save))
            }
        },
        dismissButton = {
            ActionButton(onClick = onCancel, enabled = !draft.busy) { Text(stringResource(R.string.sleep_cancel)) }
        },
    )
}
