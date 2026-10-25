package com.personal.lifelog.feature.sleep

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens
import java.time.Instant
import java.time.ZoneId

private fun recordedTime(instant: Long, zone: String): String =
    chineseSleepTime(Instant.ofEpochMilli(instant).atZone(RecordTime.zone(zone)))

@Composable
fun SleepRecordSummary(record: SleepRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sleep_start_value, recordedTime(record.startedAtUtcMs, record.startZoneId)))
        val end = record.endedAtUtcMs
        if (end == null) Text(stringResource(R.string.sleep_active), color = MaterialTheme.colorScheme.primary)
        else {
            Text(stringResource(R.string.sleep_end_value, recordedTime(end, record.endZoneId!!)))
            val seconds = RecordTime.durationSeconds(record.startedAtUtcMs, end)
            Text(if (seconds < 60) stringResource(R.string.sleep_duration_seconds, seconds)
                else stringResource(R.string.sleep_duration, seconds / 3600, seconds % 3600 / 60))
        }
        Text(stringResource(R.string.sleep_archive, record.archiveLocalDate), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SleepError(code: ErrorCode) {
    val message = when (code) {
        ErrorCode.INVALID_INTERVAL -> R.string.sleep_invalid_interval
        ErrorCode.OVERLAPPING_SLEEP -> R.string.sleep_overlap
        ErrorCode.INVALID_TIME_ZONE -> R.string.sleep_invalid_zone
        ErrorCode.INVALID_LOCAL_TIME -> R.string.sleep_invalid_time
        ErrorCode.AMBIGUOUS_LOCAL_TIME -> R.string.sleep_ambiguous
        ErrorCode.NOT_FOUND, ErrorCode.INVALID_ID -> R.string.sleep_stale
        ErrorCode.STORAGE_FULL -> R.string.sleep_storage_full
        else -> R.string.sleep_storage_error
    }
    com.personal.lifelog.ui.components.ErrorState(stringResource(message))
}

@Composable
fun SleepHomeFeedback(viewModel: SleepViewModel, onEdit: (String) -> Unit, onRequestUndo: () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status.failed) {
            SleepError(ErrorCode.STORAGE_UNAVAILABLE)
            ActionButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
        } else if (!status.loaded) Text(stringResource(R.string.sleep_loading))
        status.active?.let { record ->
            Text(stringResource(R.string.sleep_start_value, recordedTime(record.startedAtUtcMs, record.startZoneId)),
                style = MaterialTheme.typography.bodyMedium)
            ActionButton(onClick = { onEdit(record.id) }) { Text(stringResource(R.string.sleep_edit)) }
        }
        error?.let { SleepError(it) }
        if (undo != null) ActionButton(onClick = onRequestUndo, enabled = !busy) {
            Text(stringResource(R.string.sleep_undo))
        }
    }
}

/** Mounted outside LazyColumn so scrolling cannot unmount pending confirmations. */
@Composable
fun SleepHomeDialogs(viewModel: SleepViewModel, confirmUndo: Boolean, onDismissUndo: () -> Unit) {
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    if (confirmUndo && undo != null) ConfirmDialog(
        R.string.sleep_undo,
        if (undo!!.previous == null) R.string.sleep_undo_start else R.string.sleep_undo_finish,
        onCancel = onDismissUndo,
        onConfirm = { onDismissUndo(); viewModel.undoLast() },
    )
    val pending by viewModel.longFinish.collectAsStateWithLifecycle()
    if (pending != null) LongIntervalDialog(
        onCancel = viewModel::dismissLong,
        onConfirm = viewModel::confirmLong,
        details = "${recordedTime(pending!!.record.startedAtUtcMs, pending!!.record.startZoneId)}\n${recordedTime(pending!!.instant, pending!!.zone)}",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepHistoryScreen(viewModel: SleepViewModel, onAdd: () -> Unit, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    SleepScaffold(R.string.sleep_title, onBack) { modifier ->
        Column(modifier) {
            PrimaryButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sleep_add)) }
            Text(stringResource(R.string.sleep_note), modifier = Modifier.padding(vertical = 8.dp))
            val rows = history.records
            when {
                history.failed -> {
                    SleepError(ErrorCode.STORAGE_UNAVAILABLE)
                    ActionButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                }
                rows == null -> {
                    Text(stringResource(R.string.sleep_loading))
                    ActionButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                }
                rows.isEmpty() -> EmptyState(stringResource(R.string.sleep_none), hint = stringResource(R.string.sleep_empty_hint))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    items(rows, key = { it.id }) { record ->
                        RecordCard(onClick = { onEdit(record.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(LogDimens.card)) { SleepRecordSummary(record) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SleepEditorScreen(viewModel: SleepEditorViewModel, editing: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerZone by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = viewModel.systemZone()
    val leave = { if (!state.busy) { if (state.dirty) discard = true else onBack() } }
    BackHandler { leave() }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    SleepScaffold(if (editing) R.string.sleep_edit else R.string.sleep_add, leave) { modifier ->
        Column(modifier.testTag("sleep-form").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
            val form = state.form
            if (state.loading) Text(stringResource(R.string.sleep_loading))
            state.error?.let { SleepError(it) }
            if (form == null && !state.loading) ActionButton(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            if (form != null) {
                Text(stringResource(R.string.sleep_note))
                TimeChoice(R.string.sleep_start_input, chineseSleepTime(form.startAt(zone)), !state.busy) {
                    pickerZone = zone.id; picker = "start"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = form.hasEnd, enabled = !state.busy, onCheckedChange = { viewModel.change(form.copy(hasEnd = it)) })
                    Text(stringResource(R.string.sleep_has_end), modifier = Modifier.weight(1f))
                }
                if (form.hasEnd) TimeChoice(R.string.sleep_end_input, chineseSleepTime(form.endAt(zone)), !state.busy) {
                    pickerZone = zone.id; picker = "end"
                }
                PrimaryButton(onClick = viewModel::save, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.busy) R.string.saving else R.string.sleep_save))
                }
                if (editing) SecondaryButton(onClick = { delete = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.sleep_delete))
                }
            }
        }
    }
    val form = state.form
    val target = picker
    if (form != null && target != null) {
        val selectedZone = ZoneId.of(pickerZone ?: zone.id)
        SleepDateTimePicker(
            title = if (target == "start") R.string.sleep_pick_start else R.string.sleep_pick_end,
            initial = if (target == "start") form.startAt(selectedZone) else form.endAt(selectedZone),
            zone = selectedZone,
            onCancel = { picker = null },
            onConfirm = { local, offset ->
                viewModel.change(if (target == "start") form.pickStart(local, selectedZone, offset) else form.pickEnd(local, selectedZone, offset))
                picker = null
            },
        )
    }
    if (discard) ConfirmDialog(R.string.sleep_discard, R.string.sleep_discard_hint, { discard = false }, { discard = false; onBack() })
    if (delete) ConfirmDialog(R.string.sleep_delete, R.string.sleep_delete_prompt, { delete = false }, { delete = false; viewModel.delete() })
    state.pendingLong?.let { draft ->
        LongIntervalDialog(viewModel::cancelLong, viewModel::confirmLong,
            "${recordedTime(draft.startedAtUtcMs, draft.startZoneId)}\n${recordedTime(draft.endedAtUtcMs!!, draft.endZoneId!!)}")
    }
}

@Composable
private fun TimeChoice(title: Int, value: String, enabled: Boolean, onClick: () -> Unit) {
    RecordCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LogDimens.page), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sleep_picker_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepScaffold(title: Int, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = { RecordTopBar(title = { Text(stringResource(title)) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding -> content(Modifier.fillMaxSize().padding(padding).imePadding().padding(LogDimens.page)) }
}

@Composable
private fun ConfirmDialog(title: Int, message: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(title)) }, text = { Text(stringResource(message)) },
        confirmButton = { ActionButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(
            contentColor = if (title == R.string.sleep_delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Text(stringResource(R.string.sleep_confirm)) } },
        dismissButton = { ActionButton(onClick = onCancel) { Text(stringResource(R.string.sleep_cancel)) } })
}

@Composable
private fun LongIntervalDialog(onCancel: () -> Unit, onConfirm: () -> Unit, details: String) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.sleep_long_title)) },
        text = { Text("${stringResource(R.string.sleep_long_hint)}\n\n$details") },
        confirmButton = { ActionButton(onClick = onConfirm) { Text(stringResource(R.string.sleep_confirm)) } },
        dismissButton = { ActionButton(onClick = onCancel) { Text(stringResource(R.string.sleep_cancel)) } })
}
