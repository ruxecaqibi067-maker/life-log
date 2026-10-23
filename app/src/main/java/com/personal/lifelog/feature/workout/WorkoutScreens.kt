package com.personal.lifelog.feature.workout

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.feature.meal.MealTimeDisplay
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
                WorkoutError(error)
                state.errorItemId?.let { id -> state.form?.items?.indexOfFirst { it.id == id }?.let { index ->
                    Text(stringResource(R.string.workout_item_error, index + 1, state.form!!.items[index].name),
                        Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                } }
            }
            if (state.loading) Text(stringResource(R.string.workout_loading), Modifier.padding(16.dp))
            val form = state.form
            if (form == null && !state.loading) {
                TextButton(onClick = viewModel::load) { Text(stringResource(R.string.workout_retry)) }
            }
            if (form != null) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("workout-form"), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.workout_optional))
                        WorkoutTimeFields(form, !state.busy) { viewModel.change(it) }
                        OutlinedTextField(form.note, { viewModel.change(form.copy(note = it)) },
                            Modifier.fillMaxWidth(), enabled = !state.busy,
                            label = { Text(stringResource(R.string.workout_note)) })
                    }
                    itemsIndexed(form.items, key = { _, row -> row.id }) { index, row ->
                        OutlinedCard(Modifier.fillMaxWidth().testTag("workout-item-${row.id}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                if (row.type == ExerciseType.STRENGTH) {
                                    Text(if (row.sets.isEmpty()) stringResource(R.string.workout_no_quantity)
                                        else stringResource(R.string.workout_sets_retained, row.sets.size))
                                }
                                OutlinedTextField(row.note, { viewModel.changeItem(row.copy(note = it)) },
                                    Modifier.fillMaxWidth(), enabled = !state.busy,
                                    label = { Text(stringResource(R.string.workout_item_note)) })
                            }
                        }
                    }
                    item {
                        OutlinedButton(viewModel::openPicker, enabled = !state.busy && form.items.size < 100,
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workout_add)) }
                    }
                }
                Button(viewModel::save, enabled = !state.busy && !state.done,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(if (state.busy) R.string.workout_busy else R.string.workout_save))
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.workout_discard_question)) },
        confirmButton = { TextButton(onClick = onFinished) { Text(stringResource(R.string.workout_discard)) } },
        dismissButton = { TextButton({ discard = false }) { Text(stringResource(R.string.workout_keep)) } })
}

@Composable
private fun WorkoutTimeFields(form: WorkoutForm, enabled: Boolean, change: (WorkoutForm) -> Unit) {
    val context = LocalContext.current
    val local = form.local
    TextButton(onClick = {
        DatePickerDialog(context, { _, year, month, day ->
            change(form.atLocal(local.withYear(year).withMonth(1).withDayOfMonth(1).withMonth(month + 1).withDayOfMonth(day)))
        }, local.year, local.monthValue - 1, local.dayOfMonth).show()
    }, enabled = enabled) { Text(stringResource(R.string.workout_date, local.toLocalDate().toString())) }
    TextButton(onClick = {
        TimePickerDialog(context, { _, hour, minute ->
            change(form.atLocal(local.withHour(hour).withMinute(minute).withSecond(0).withNano(0)))
        }, local.hour, local.minute, true).show()
    }, enabled = enabled) { Text(stringResource(R.string.workout_time, local.format(DateTimeFormatter.ofPattern("HH:mm")))) }
    Text(stringResource(R.string.workout_zone, form.zoneId), style = MaterialTheme.typography.bodySmall)
    val offsets = ZoneId.of(form.zoneId).rules.getValidOffsets(local)
    if (offsets.size > 1) {
        Text(stringResource(R.string.workout_overlap))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            offsets.forEach { offset ->
                FilterChip(selected = form.offset == offset.id, onClick = { change(form.copy(offset = offset.id)) },
                    enabled = enabled, label = { Text(offset.id) })
            }
        }
    }
}

@Composable
private fun WorkoutNumber(value: String, label: Int, tag: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth().testTag(tag), enabled = enabled,
        singleLine = true, label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
fun WorkoutHomeSection(state: WorkoutDayState, onHistory: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    Text(stringResource(R.string.workout_today), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    TextButton(onHistory) { Text(stringResource(R.string.workout_history)) }
    WorkoutDayRows(state, onOpen, onRetry)
}

@Composable
fun WorkoutHistoryScreen(viewModel: WorkoutDayViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    WorkoutPage(stringResource(R.string.workout_history), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, month, day -> viewModel.selectDate(LocalDate.of(year, month + 1, day)) },
                    state.date.year, state.date.monthValue - 1, state.date.dayOfMonth).show()
            }) { Text(stringResource(R.string.workout_date_select, state.date.toString())) }
            Row {
                TextButton({ viewModel.selectDate(state.date.minusDays(1)) }) { Text(stringResource(R.string.workout_previous_day)) }
                TextButton({ viewModel.selectDate(state.date.plusDays(1)) }) { Text(stringResource(R.string.workout_next_day)) }
            }
            OutlinedButton(onAdd) { Text(stringResource(R.string.workout_new)) }
            WorkoutDayRows(state, onOpen) { viewModel.selectDate(state.date) }
        }
    }
}

@Composable
private fun WorkoutDayRows(state: WorkoutDayState, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    when {
        state.loading -> Text(stringResource(R.string.workout_loading))
        state.failed -> { WorkoutError(ErrorCode.STORAGE_UNAVAILABLE); TextButton(onRetry) { Text(stringResource(R.string.workout_retry)) } }
        state.rows.isEmpty() -> Text(stringResource(R.string.workout_empty), Modifier.padding(12.dp))
        else -> state.rows.forEach { record ->
            OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(record.session.id) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(MealTimeDisplay.full(record.session.occurredAtUtcMs, record.session.zoneId))
                    Text(record.items.joinToString("、") { it.item.nameSnapshot })
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) Text(stringResource(R.string.workout_loading))
            state.error?.let { WorkoutError(it) }
            if (state.error != null && state.record == null) TextButton(viewModel::load) { Text(stringResource(R.string.workout_retry)) }
            state.record?.let { record ->
                WorkoutDetailContent(record)
                Button({ onEdit(record.session.id) }, enabled = !state.busy) { Text(stringResource(R.string.workout_edit)) }
                OutlinedButton({ deleting = true }, enabled = !state.busy) { Text(stringResource(R.string.workout_delete)) }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.workout_delete_question)) },
        confirmButton = { TextButton({ deleting = false; viewModel.delete() }) { Text(stringResource(R.string.workout_delete)) } },
        dismissButton = { TextButton({ deleting = false }) { Text(stringResource(R.string.workout_cancel)) } })
}

/** Read-only content shared by real detail and host screenshot checks. */
@Composable
internal fun WorkoutDetailContent(record: WorkoutWithItems) {
    val form = WorkoutForm.from(record)
    Text(MealTimeDisplay.full(record.session.occurredAtUtcMs, record.session.zoneId), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.workout_zone, record.session.zoneId), style = MaterialTheme.typography.bodySmall)
    record.session.note?.let { Text(it) }
    record.items.forEachIndexed { index, entry ->
        val row = form.items[index]
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    Text(stringResource(when (code) {
        ErrorCode.EMPTY_WORKOUT -> R.string.workout_error_empty
        ErrorCode.INVALID_NUMBER -> R.string.workout_error_number
        ErrorCode.TEXT_TOO_LONG -> R.string.workout_error_note
        ErrorCode.INVALID_LOCAL_TIME, ErrorCode.INVALID_TIME_ZONE -> R.string.workout_error_time
        ErrorCode.AMBIGUOUS_LOCAL_TIME -> R.string.workout_error_overlap
        ErrorCode.HIDDEN_EXERCISE -> R.string.workout_error_hidden
        ErrorCode.TOO_MANY_ITEMS -> R.string.workout_error_limit
        ErrorCode.NOT_FOUND -> R.string.workout_error_missing
        ErrorCode.STORAGE_FULL -> R.string.workout_error_full
        else -> R.string.workout_error_storage
    }), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
    }) }, content = content)
}
