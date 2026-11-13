package com.personal.lifelog.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.feature.home.recordedDurationText
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val actualTimeFormat = DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)

/** Editing this confirmation only changes the captured draft; persistence happens on Confirm. */
@Composable
fun ConfirmIntervalEndDialog(
    @StringRes title: Int,
    start: Long, startZone: String, end: Long, endZone: String, latestEnd: Long,
    minimumMs: Long, busy: Boolean,
    onChangeEnd: (Long, String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit,
    feedback: @Composable () -> Unit = {},
) {
    var choosing by rememberSaveable(start, latestEnd) { mutableStateOf(false) }
    var invalidChoice by rememberSaveable(start, latestEnd) { mutableStateOf(false) }
    val valid = end > start && end <= latestEnd && !invalidChoice
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("actual-interval-dialog"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.interval_check_actual))
                Text(stringResource(R.string.interval_start,
                    actualTimeFormat.format(Instant.ofEpochMilli(start).atZone(RecordTime.zone(startZone)))))
                Text(stringResource(R.string.interval_end,
                    actualTimeFormat.format(Instant.ofEpochMilli(end).atZone(RecordTime.zone(endZone)))))
                if (end > start) {
                    Text(stringResource(R.string.interval_duration, recordedDurationText(end - start)),
                        style = MaterialTheme.typography.titleMedium)
                    if (end - start < minimumMs) Text(stringResource(R.string.interval_short_notice, minimumMs / 60_000))
                }
                SecondaryButton(onClick = { choosing = true }, enabled = !busy,
                    modifier = Modifier.testTag("actual-interval-edit-end")) {
                    Text(stringResource(R.string.interval_edit_end))
                }
                if (!valid) Text(stringResource(R.string.interval_end_range), color = MaterialTheme.colorScheme.error)
                feedback()
            }
        },
        confirmButton = {
            PrimaryButton(onClick = onConfirm, enabled = !busy && valid, modifier = Modifier.testTag("actual-interval-confirm")) {
                Text(stringResource(if (busy) R.string.saving else R.string.sleep_confirm))
            }
        },
        dismissButton = {
            SecondaryButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.sleep_cancel)) }
        },
    )
    if (choosing) {
        val zone = RecordTime.zone(endZone)
        val original = Instant.ofEpochMilli(end).atZone(zone)
        RecordDateTimePicker(
            title = R.string.interval_pick_end, initial = original, zone = zone,
            onCancel = { choosing = false },
            onConfirm = { local, offset ->
                val picked = if (local.truncatedTo(ChronoUnit.MINUTES) == original.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
                    && offset == original.offset) end else RecordTime.resolve(local, zone.id, offset)
                invalidChoice = picked <= start || picked > latestEnd
                if (!invalidChoice) onChangeEnd(picked, zone.id)
                choosing = false
            },
        )
    }
}
