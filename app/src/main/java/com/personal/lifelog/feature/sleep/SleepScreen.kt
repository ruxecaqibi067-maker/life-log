package com.personal.lifelog.feature.sleep

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.ConfirmActionDialog
import com.personal.lifelog.ui.components.LoadingState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordTimeChoice
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.components.TonalCard
import com.personal.lifelog.ui.theme.LogDimens
import com.personal.lifelog.ui.theme.TabularTextStyle
import java.time.Instant
import java.time.ZoneId

private fun recordedTime(instant: Long, zone: String): String =
    chineseSleepTime(Instant.ofEpochMilli(instant).atZone(RecordTime.zone(zone)))

@Composable
fun SleepRecordSummary(record: SleepRecord, showArchive: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sleep_start_value, recordedTime(record.startedAtUtcMs, record.startZoneId)),
            style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle))
        val end = record.endedAtUtcMs
        if (end == null) Text(stringResource(R.string.sleep_active), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge)
        else {
            Text(stringResource(R.string.sleep_end_value, recordedTime(end, record.endZoneId!!)),
                style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle))
            val seconds = RecordTime.durationSeconds(record.startedAtUtcMs, end)
            Text(if (seconds < 60) stringResource(R.string.sleep_duration_seconds, seconds)
                else stringResource(R.string.sleep_duration, seconds / 3600, seconds % 3600 / 60),
                style = MaterialTheme.typography.titleSmall.merge(TabularTextStyle),
                color = MaterialTheme.colorScheme.primary)
        }
        if (showArchive) Text(stringResource(R.string.sleep_archive, record.archiveLocalDate),
            style = MaterialTheme.typography.bodySmall.merge(TabularTextStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SleepError(code: ErrorCode) {
    val message = when (code) {
        ErrorCode.INTERVAL_TOO_SHORT -> R.string.sleep_too_short
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
fun SleepHomeFeedback(viewModel: SleepViewModel, onEdit: (String) -> Unit, onRequestUndo: () -> Unit,
    showRecordActions: Boolean = true) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
        if (status.failed) {
            SleepError(ErrorCode.STORAGE_UNAVAILABLE)
            SecondaryButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
        } else if (!status.loaded) LoadingState(stringResource(R.string.sleep_loading))
        if (showRecordActions) status.active?.let { record ->
            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    Text(stringResource(R.string.sleep_current_actions), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.sleep_start_value, recordedTime(record.startedAtUtcMs, record.startZoneId)),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularTextStyle))
                    SecondaryButton(onClick = { onEdit(record.id) }, enabled = !busy,
                        modifier = Modifier.testTag("sleep-edit-active")) { Text(stringResource(R.string.sleep_edit)) }
                }
            }
        }
        error?.let { SleepError(it) }
        if (showRecordActions && undo != null) SecondaryButton(onClick = onRequestUndo, enabled = !busy,
            modifier = Modifier.testTag("sleep-undo")) {
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
    SleepFinishConfirmation(viewModel)
}

/** Shared by Home and the dark bedtime page: same captured end instant and validation. */
@Composable
internal fun SleepFinishConfirmation(viewModel: SleepViewModel) {
    val pending by viewModel.longFinish.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    pending?.let { request ->
        com.personal.lifelog.ui.components.ConfirmIntervalEndDialog(
            title = R.string.sleep_long_title,
            start = request.record.startedAtUtcMs, startZone = request.record.startZoneId,
            end = request.instant, endZone = request.zone, latestEnd = request.latestEnd,
            minimumMs = com.personal.lifelog.core.time.IntervalDurationPolicy.MIN_SLEEP_MS,
            busy = busy, onChangeEnd = viewModel::changeFinishEnd,
            onCancel = viewModel::dismissLong, onConfirm = viewModel::confirmLong,
            feedback = { error?.let { SleepError(it) } },
        )
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
    SleepScaffold(if (editing) R.string.sleep_edit else R.string.sleep_add, !state.busy, leave) { modifier ->
        Column(modifier.testTag("sleep-form").verticalScroll(rememberScrollState()).padding(LogDimens.page),
            verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
            val form = state.form
            if (state.loading) LoadingState(stringResource(R.string.sleep_loading))
            state.error?.let { SleepError(it) }
            if (form == null && !state.loading) SecondaryButton(onClick = viewModel::load) { Text(stringResource(R.string.retry)) }
            if (form != null) {
                Text(stringResource(R.string.sleep_note), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RecordTimeChoice(R.string.sleep_start_input, chineseSleepTime(form.startAt(zone)), !state.busy, R.string.sleep_picker_hint) {
                    pickerZone = zone.id; picker = "start"
                }
                TonalCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = LogDimens.action)
                            .toggleable(value = form.hasEnd, enabled = !state.busy, role = Role.Switch,
                                onValueChange = { viewModel.change(form.copy(hasEnd = it)) })
                            .padding(horizontal = LogDimens.card, vertical = LogDimens.gap),
                        horizontalArrangement = Arrangement.spacedBy(LogDimens.gap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.sleep_has_end), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = form.hasEnd, enabled = !state.busy, onCheckedChange = null)
                    }
                }
                if (form.hasEnd) RecordTimeChoice(R.string.sleep_end_input, chineseSleepTime(form.endAt(zone)), !state.busy, R.string.sleep_picker_hint) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepScaffold(title: Int, backEnabled: Boolean, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(topBar = { RecordTopBar(title = { Text(stringResource(title)) }, navigationIcon = {
        IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding -> content(Modifier.fillMaxSize().padding(padding).imePadding()) }
}

@Composable
private fun ConfirmDialog(title: Int, message: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmActionDialog(
        titleRes = title,
        messageRes = message,
        confirmRes = R.string.sleep_confirm,
        cancelRes = R.string.sleep_cancel,
        destructive = title == R.string.sleep_delete || title == R.string.sleep_discard,
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}

@Composable
private fun LongIntervalDialog(onCancel: () -> Unit, onConfirm: () -> Unit, details: String) {
    ConfirmActionDialog(
        titleRes = R.string.sleep_long_title,
        messageText = "${stringResource(R.string.sleep_long_hint)}\n\n$details",
        confirmRes = R.string.sleep_confirm,
        cancelRes = R.string.sleep_cancel,
        onConfirm = onConfirm,
        onDismiss = onCancel,
    )
}
