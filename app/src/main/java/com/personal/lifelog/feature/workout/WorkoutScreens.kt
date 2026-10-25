package com.personal.lifelog.feature.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.WorkoutEffort
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.feature.meal.MealTimeDisplay
import com.personal.lifelog.ui.components.RecordDatePicker
import com.personal.lifelog.ui.components.RecordDateTimePicker
import com.personal.lifelog.ui.components.RecordTimeChoice
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.RecordTextField
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WorkoutEditorScreen(
    viewModel: WorkoutEditorViewModel,
    editing: Boolean,
    onFinished: () -> Unit,
    picker: @Composable (onBack: () -> Unit, onSelected: (ExerciseDefinition) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picking by viewModel.picking.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    val back = { if (!state.busy) { if (state.dirty) discard = true else onFinished() } }
    LaunchedEffect(state.done) { if (state.done) onFinished() }
    if (picking) {
        BackHandler { viewModel.closePicker() }
        picker(viewModel::closePicker, { viewModel.add(it); viewModel.closePicker() })
        return
    }
    BackHandler { back() }
    WorkoutPage(stringResource(if (editing) R.string.workout_edit else R.string.workout_new), back) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Persistent status/save controls stay visible even when a long form is scrolled.
            state.error?.let { error ->
                if (state.errorSetId != null) Text(stringResource(R.string.workout_error_set_number),
                    Modifier.padding(LogDimens.card), color = MaterialTheme.colorScheme.error)
                else if (error == ErrorCode.INVALID_NUMBER && state.form?.items?.any { it.id == state.errorItemId && it.batch != null } == true)
                    com.personal.lifelog.ui.components.ErrorState(stringResource(R.string.workout_batch_error))
                else WorkoutError(error)
                state.errorItemId?.let { id -> state.form?.items?.indexOfFirst { it.id == id }?.let { index ->
                    Text(stringResource(R.string.workout_item_error, index + 1, state.form!!.items[index].name),
                        Modifier.padding(horizontal = LogDimens.page), color = MaterialTheme.colorScheme.error)
                    state.errorSetId?.let { setId ->
                        val setIndex = state.form!!.items[index].sets.indexOfFirst { it.id == setId }
                        Text(stringResource(R.string.workout_set_error, setIndex + 1), Modifier.padding(horizontal = LogDimens.page),
                            color = MaterialTheme.colorScheme.error)
                    }
                } }
            }
            if (state.loading) Text(stringResource(R.string.workout_loading), Modifier.padding(LogDimens.page))
            val form = state.form
            if (form == null && !state.loading) {
                ActionButton(onClick = viewModel::load) { Text(stringResource(R.string.workout_retry)) }
            }
            if (form != null) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("workout-form"), contentPadding = PaddingValues(LogDimens.page),
                    verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
                    item {
                        Text(stringResource(R.string.workout_optional))
                        WorkoutTimeFields(form, !state.busy) { viewModel.change(it) }
                        RecordTextField(form.note, { viewModel.change(form.copy(note = it)) },
                            Modifier.fillMaxWidth(), enabled = !state.busy,
                            label = { Text(stringResource(R.string.workout_note)) })
                    }
                    form.items.forEachIndexed { index, row ->
                        item(key = row.id) {
                            RecordCard(Modifier.fillMaxWidth().testTag("workout-item-${row.id}")) {
                                Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                                    Text(itemHeading(index, row), style = MaterialTheme.typography.titleMedium)
                                    Row {
                                        IconButton({ viewModel.move(row.id, -1) }, enabled = !state.busy && index > 0) {
                                            Icon(Icons.Default.ArrowUpward, stringResource(R.string.workout_move_up, index + 1))
                                        }
                                        IconButton({ viewModel.move(row.id, 1) }, enabled = !state.busy && index < form.items.lastIndex) {
                                            Icon(Icons.Default.ArrowDownward, stringResource(R.string.workout_move_down, index + 1))
                                        }
                                        IconButton({ viewModel.remove(row.id) }, enabled = !state.busy) {
                                            Icon(Icons.Default.Close, stringResource(R.string.workout_remove, index + 1))
                                        }
                                    }
                                    if (row.type != ExerciseType.STRENGTH) {
                                        WorkoutNumber(row.minutes, R.string.workout_minutes, "${row.id}-minutes", !state.busy) {
                                            viewModel.changeItem(row.copy(minutes = it))
                                        }
                                        // Preserve arbitrary integer seconds in existing records without decimal rounding.
                                        WorkoutNumber(row.seconds, R.string.workout_seconds, "${row.id}-seconds", !state.busy) {
                                            viewModel.changeItem(row.copy(seconds = it))
                                        }
                                    }
                                    if (row.type == ExerciseType.CARDIO) {
                                        WorkoutNumber(row.kilometers, R.string.workout_km, "${row.id}-km", !state.busy) {
                                            viewModel.changeItem(row.copy(kilometers = it))
                                        }
                                    }
                                    if (row.type == ExerciseType.STRENGTH && row.batch != null) {
                                        StrengthBatchEditor(row, !state.busy, viewModel)
                                    }
                                    RecordTextField(row.note, { viewModel.changeItem(row.copy(note = it)) },
                                        Modifier.fillMaxWidth(), enabled = !state.busy,
                                        label = { Text(stringResource(R.string.workout_item_note)) })
                                }
                            }
                        }
                        if (row.type == ExerciseType.STRENGTH && row.batch == null) {
                            itemsIndexed(row.sets, key = { _, set -> "${row.id}-${set.id}" }) { setIndex, set ->
                                WorkoutSetEditor(row, index, set, setIndex, !state.busy,
                                    state.errorSetId == set.id, viewModel)
                            }
                            item(key = "${row.id}-sets-actions") {
                                WorkoutSetActions(row, !state.busy, viewModel)
                            }
                        }
                    }
                    item {
                        SecondaryButton(viewModel::openPicker, enabled = !state.busy && form.items.size < 100,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workout_add)) }
                    }
                    item { WorkoutEffortEditor(form, !state.busy, viewModel::change) }
                }
                PrimaryButton(viewModel::save, enabled = !state.busy && !state.done,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LogDimens.page, vertical = 8.dp)) {
                    Text(stringResource(if (state.busy) R.string.workout_busy else R.string.workout_save))
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.workout_discard_question)) },
        confirmButton = { ActionButton(onClick = onFinished) { Text(stringResource(R.string.workout_discard)) } },
        dismissButton = { ActionButton({ discard = false }) { Text(stringResource(R.string.workout_keep)) } })
}

@Composable
private fun WorkoutTimeFields(form: WorkoutForm, enabled: Boolean, change: (WorkoutForm) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    RecordTimeChoice(R.string.picker_workout_label,
        form.local.format(DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm")), enabled) { picking = true }
    if (picking) {
        val zone = ZoneId.of(form.zoneId)
        val initial = java.time.ZonedDateTime.ofLocal(form.local, zone, form.offset.takeIf { it.isNotEmpty() }?.let(java.time.ZoneOffset::of))
        RecordDateTimePicker(R.string.picker_workout_title, initial, zone, { picking = false }, { local, offset ->
            change(form.pickTime(local, offset)); picking = false
        })
    }
}

@Composable
private fun WorkoutNumber(value: String, label: Int, tag: String, enabled: Boolean, change: (String) -> Unit) {
    RecordTextField(value, change, Modifier.fillMaxWidth().testTag(tag), enabled = enabled,
        singleLine = true, label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun WorkoutHistoryScreen(viewModel: WorkoutDayViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    WorkoutPage(stringResource(R.string.workout_history), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(LogDimens.page)) {
            ActionButton(onClick = {
                pickingDate = true
            }) { Text(stringResource(R.string.workout_date_select, state.date.toString())) }
            Row {
                ActionButton({ viewModel.selectDate(state.date.minusDays(1)) }) { Text(stringResource(R.string.workout_previous_day)) }
                ActionButton({ viewModel.selectDate(state.date.plusDays(1)) }) { Text(stringResource(R.string.workout_next_day)) }
            }
            SecondaryButton(onAdd) { Text(stringResource(R.string.workout_new)) }
            WorkoutDayRows(state, onOpen) { viewModel.selectDate(state.date) }
        }
    }
    if (pickingDate) RecordDatePicker(R.string.picker_date_title, state.date, { pickingDate = false }, {
        viewModel.selectDate(it); pickingDate = false
    })
}

@Composable
private fun WorkoutDayRows(state: WorkoutDayState, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    when {
        state.loading -> Text(stringResource(R.string.workout_loading))
        state.failed -> { WorkoutError(ErrorCode.STORAGE_UNAVAILABLE); ActionButton(onRetry) { Text(stringResource(R.string.workout_retry)) } }
        state.rows.isEmpty() -> com.personal.lifelog.ui.components.EmptyState(stringResource(R.string.workout_empty))
        else -> state.rows.forEach { record ->
            RecordCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(record.session.id) }) {
                Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(MealTimeDisplay.full(record.session.occurredAtUtcMs, record.session.zoneId))
                    Text(record.items.joinToString("、") { it.item.nameSnapshot })
                    record.session.effort?.let { Text(stringResource(R.string.workout_effort_value, effortLabel(it, record.session.effortText))) }
                    record.session.note?.let { Text(it) }
                }
            }
        }
    }
}


@Composable
fun WorkoutDetailScreen(viewModel: WorkoutDetailViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    BackHandler(enabled = state.busy) { }
    WorkoutPage(stringResource(R.string.workout_detail), { if (!state.busy) onBack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(LogDimens.page),
            verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
            if (state.loading) Text(stringResource(R.string.workout_loading))
            state.error?.let { WorkoutError(it) }
            if (state.error != null && state.record == null) ActionButton(viewModel::load) { Text(stringResource(R.string.workout_retry)) }
            state.record?.let { record ->
                WorkoutDetailContent(record)
                PrimaryButton({ onEdit(record.session.id) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(stringResource(R.string.workout_edit)) }
                SecondaryButton({ deleting = true }, Modifier.fillMaxWidth(), enabled = !state.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.workout_delete)) }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.workout_delete_question)) },
        confirmButton = { ActionButton({ deleting = false; viewModel.delete() },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.workout_delete)) } },
        dismissButton = { ActionButton({ deleting = false }) { Text(stringResource(R.string.workout_cancel)) } })
}

/** Read-only content shared by real detail and host screenshot checks. */
@Composable
internal fun WorkoutDetailContent(record: WorkoutWithItems) {
    val form = WorkoutForm.from(record)
    Text(MealTimeDisplay.full(record.session.occurredAtUtcMs, record.session.zoneId), style = MaterialTheme.typography.titleMedium)
    record.session.effort?.let { Text(stringResource(R.string.workout_effort_value, effortLabel(it, record.session.effortText))) }
    record.session.note?.let { Text(it) }
    record.items.forEachIndexed { index, entry ->
        val row = form.items[index]
        RecordCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(itemHeading(index, row), style = MaterialTheme.typography.titleMedium)
                entry.item.durationSeconds?.let { duration ->
                    // Integer minutes + remaining seconds display every stored second exactly.
                    if (duration >= 60) Text(stringResource(R.string.workout_duration_value, (duration / 60).toString()))
                    if (duration % 60 != 0L) Text(stringResource(R.string.workout_seconds_value, duration % 60))
                }
                entry.item.distanceMeters?.let { Text(stringResource(R.string.workout_distance_value, BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString())) }
                entry.sets.forEachIndexed { setIndex, set ->
                    val values = mutableListOf<String>()
                    set.weightGrams?.let { values += stringResource(R.string.workout_weight_value, BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString()) }
                    set.repetitions?.let { values += stringResource(R.string.workout_reps_value, it) }
                    Text(stringResource(R.string.workout_set_value, setIndex + 1, values.joinToString("，")))
                }
                if (entry.item.durationSeconds == null && entry.item.distanceMeters == null && entry.sets.isEmpty()) Text(stringResource(R.string.workout_no_quantity))
                entry.item.note?.let { Text(it) }
            }
        }
    }
}

@Composable
private fun itemHeading(index: Int, row: WorkoutItemForm) = stringResource(R.string.workout_item_heading, index + 1, row.name,
    stringResource(when (row.type) {
        ExerciseType.CARDIO -> R.string.workout_type_cardio
        ExerciseType.STRENGTH -> R.string.workout_type_strength
        ExerciseType.OTHER -> R.string.workout_type_other
    }))

@Composable
private fun WorkoutError(code: ErrorCode) {
    com.personal.lifelog.ui.components.ErrorState(stringResource(when (code) {
        ErrorCode.INVALID_EFFORT -> R.string.workout_effort_error
        ErrorCode.EMPTY_WORKOUT -> R.string.workout_error_empty
        ErrorCode.INVALID_NUMBER -> R.string.workout_error_number
        ErrorCode.TEXT_TOO_LONG -> R.string.workout_error_note
        ErrorCode.INVALID_LOCAL_TIME, ErrorCode.INVALID_TIME_ZONE -> R.string.workout_error_time
        ErrorCode.AMBIGUOUS_LOCAL_TIME -> R.string.workout_error_overlap
        ErrorCode.HIDDEN_EXERCISE -> R.string.workout_error_hidden
        ErrorCode.TOO_MANY_ITEMS -> R.string.workout_error_limit
        ErrorCode.TOO_MANY_SETS -> R.string.workout_error_sets_limit
        ErrorCode.NOT_FOUND -> R.string.workout_error_missing
        ErrorCode.STORAGE_FULL -> R.string.workout_error_full
        else -> R.string.workout_error_storage
    }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { RecordTopBar(title = { Text(title) }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }, content = content)
}
