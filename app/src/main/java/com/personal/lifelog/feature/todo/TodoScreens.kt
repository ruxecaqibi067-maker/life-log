package com.personal.lifelog.feature.todo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.local.entity.HabitRecord
import com.personal.lifelog.data.local.entity.TodoRecord
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.HabitFrequency
import com.personal.lifelog.domain.HabitStatus
import com.personal.lifelog.ui.components.*
import com.personal.lifelog.ui.theme.LogDimens
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
private val fullDayFormat = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.CHINA)
private fun minuteText(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

/** Compact summary sits immediately below the two duration cards; all records remain available in the full checklist. */
@Composable
fun TodoHomeSection(viewModel: TodoViewModel, onOpenAll: () -> Unit) {
    val day by viewModel.home.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    RecordCard(Modifier.fillMaxWidth().testTag("todo-home-section")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.todo_today), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!day.loading && !day.failed) Text(stringResource(R.string.todo_progress,
                    day.items.count { it.isCompleted }, day.items.size), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AddMenu(commands.busy, { viewModel.addTodo() }, viewModel::addHabit)
            }
            DayFeedback(day, viewModel::refresh)
            day.items.take(4).forEach { item -> TodoRow(item, commands.busy, { viewModel.complete(item, it) }, { viewModel.editTodo(item) }) }
            if (day.items.size > 4) Text(stringResource(R.string.todo_more_items, day.items.size - 4),
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            ActionButton(onClick = onOpenAll, modifier = Modifier.align(Alignment.End).testTag("todo-open-all")) {
                Text(stringResource(R.string.todo_all_and_habits))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun AddMenu(busy: Boolean, onTodo: () -> Unit, onHabit: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        ActionButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.testTag("todo-add-menu")) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Text(stringResource(R.string.todo_add))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.todo_manual)) }, onClick = { expanded = false; onTodo() }, enabled = !busy)
            DropdownMenuItem(text = { Text(stringResource(R.string.todo_habit)) }, onClick = { expanded = false; onHabit() }, enabled = !busy)
        }
    }
}

@Composable
private fun DayFeedback(day: TodoDayState, onRetry: () -> Unit) {
    when {
        day.loading -> CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
        day.failed -> ErrorState(stringResource(R.string.todo_load_failed), onRetry = onRetry)
        day.items.isEmpty() -> Text(stringResource(R.string.todo_empty), Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TodoRow(item: TodoRecord, busy: Boolean, onComplete: (Boolean) -> Unit, onEdit: () -> Unit) {
    RecordCard(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        .testTag("todo-row-${item.id}")) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val description = stringResource(if (item.isCompleted) R.string.todo_uncheck else R.string.todo_check, item.title)
            Checkbox(checked = item.isCompleted, onCheckedChange = onComplete, enabled = !busy,
                modifier = Modifier.testTag("todo-check-${item.id}").semantics { contentDescription = description })
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val details = listOfNotNull(item.scheduledMinute?.let(::minuteText),
                    if (item.habitId != null) stringResource(R.string.todo_from_habit) else null).joinToString(" · ")
                if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TodoScreen(viewModel: TodoViewModel, onBack: () -> Unit) {
    val day by viewModel.history.collectAsStateWithLifecycle()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    var datePicker by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        RecordTopBar(title = { Text(stringResource(R.string.todo_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        }, actions = { AddMenu(commands.busy, { viewModel.addTodo(day.date) }, viewModel::addHabit) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("todo-management"),
            contentPadding = PaddingValues(LogDimens.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "date") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(onClick = { datePicker = true }, modifier = Modifier.weight(1f).testTag("todo-select-date"), enabled = !commands.busy) {
                        Text(day.date.format(dayFormat))
                    }
                    ActionButton(onClick = { viewModel.selectDate(null) }, enabled = !commands.busy) { Text(stringResource(R.string.nav_today)) }
                }
            }
            item(key = "day-status") {
                Text(stringResource(R.string.todo_progress, day.items.count { it.isCompleted }, day.items.size), style = MaterialTheme.typography.titleSmall)
                DayFeedback(day, viewModel::refresh)
            }
            items(day.items, key = { "todo-${it.id}" }) { item ->
                TodoRow(item, commands.busy, { viewModel.complete(item, it) }, { viewModel.editTodo(item) })
            }
            item(key = "habit-heading") {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.todo_habits_ongoing), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    ActionButton(onClick = viewModel::addHabit, enabled = !commands.busy) { Text(stringResource(R.string.todo_habit_add)) }
                }
                Text(stringResource(R.string.todo_habit_cycle_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    habits.loading -> CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                    habits.failed -> ErrorState(stringResource(R.string.todo_load_failed), onRetry = viewModel::refresh)
                    habits.items.isEmpty() -> Text(stringResource(R.string.todo_habits_empty), Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(habits.items, key = { "habit-${it.id}" }) { habit ->
                HabitRow(habit, commands.busy, { viewModel.editHabit(habit) }, { viewModel.toggleHabit(habit) },
                    { viewModel.requestDelete(habit.id, habit.title, habit = true) })
            }
        }
    }
    if (datePicker) RecordDatePicker(R.string.todo_select_day, day.date, onCancel = { datePicker = false },
        onConfirm = { viewModel.selectDate(it); datePicker = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitRow(habit: HabitRecord, busy: Boolean, onEdit: () -> Unit, onPause: () -> Unit, onArchive: () -> Unit) {
    RecordCard(Modifier.fillMaxWidth().testTag("habit-row-${habit.id}")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(habit.title, style = MaterialTheme.typography.titleSmall)
            val start = LocalDate.parse(habit.startDate)
            Text(stringResource(R.string.todo_habit_range, start.format(fullDayFormat), start.plusDays(20).format(fullDayFormat)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val recurrence = when (habit.frequency) {
                HabitFrequency.DAILY -> stringResource(R.string.todo_frequency_daily)
                HabitFrequency.INTERVAL -> stringResource(R.string.todo_every_days, habit.intervalDays)
                HabitFrequency.WEEKLY -> stringResource(R.string.todo_frequency_weekly) + " " + weekdaysText(habit.weekdaysMask)
            }
            Text(listOfNotNull(recurrence, habit.scheduledMinute?.let(::minuteText),
                if (habit.status == HabitStatus.PAUSED) stringResource(R.string.todo_habit_paused) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ActionButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.todo_edit_short)) }
                ActionButton(onClick = onPause, enabled = !busy) {
                    Text(stringResource(if (habit.status == HabitStatus.PAUSED) R.string.todo_habit_resume else R.string.todo_habit_pause))
                }
                ActionButton(onClick = onArchive, enabled = !busy) { Text(stringResource(R.string.todo_habit_archive)) }
            }
        }
    }
}

/** Kept above navigation rather than inside a LazyColumn item, so scrolling cannot dismiss an unsaved editor. */
@Composable
fun TodoDialogs(viewModel: TodoViewModel) {
    val draft by viewModel.editor.collectAsStateWithLifecycle()
    val commands by viewModel.commands.collectAsStateWithLifecycle()
    draft?.takeIf { commands.deletion == null }?.let { EditorDialog(it, commands, viewModel) }
    commands.deletion?.let { request ->
        AlertDialog(onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(if (request.habit) R.string.todo_habit_archive_confirm else R.string.todo_delete_confirm)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.title)
                Text(stringResource(if (request.habit) R.string.todo_habit_archive_note else R.string.todo_delete_note))
                commands.error?.let { ErrorState(todoError(it)) }
            } },
            confirmButton = { ActionButton(onClick = viewModel::confirmDelete, enabled = !commands.busy) {
                Text(stringResource(if (commands.busy) R.string.saving else R.string.picker_confirm))
            } },
            dismissButton = { ActionButton(onClick = viewModel::cancelDelete, enabled = !commands.busy) { Text(stringResource(R.string.picker_cancel)) } })
    }
    if (draft == null && commands.deletion == null && commands.error != null) AlertDialog(onDismissRequest = viewModel::clearError,
        title = { Text(stringResource(R.string.todo_operation_failed)) }, text = { Text(todoError(commands.error!!)) },
        confirmButton = { ActionButton(onClick = viewModel::clearError) { Text(stringResource(R.string.backup_dismiss)) } })
}

@Composable
private fun EditorDialog(draft: TodoEditorDraft, commands: TodoCommandState, viewModel: TodoViewModel) {
    var picker by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = viewModel::cancelEditor,
        modifier = Modifier.testTag("todo-editor"),
        title = { Text(stringResource(when {
            draft.habit && draft.editing -> R.string.todo_habit_edit
            draft.habit -> R.string.todo_habit_add
            draft.editing -> R.string.todo_edit
            else -> R.string.todo_manual
        })) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordTextField(draft.title, { viewModel.update(draft.copy(title = it.take(120))) },
                    Modifier.fillMaxWidth().testTag("todo-title-input"), enabled = !commands.busy,
                    label = { Text(stringResource(R.string.todo_name)) }, maxLines = 3)
                RecordTimeChoice(if (draft.habit) R.string.todo_habit_start else R.string.todo_date,
                    draft.date.format(fullDayFormat), enabled = !commands.busy) { picker = "date" }
                RecordTimeChoice(R.string.todo_time_optional, draft.scheduledMinute?.let(::minuteText) ?: stringResource(R.string.todo_time_none),
                    enabled = !commands.busy) { picker = "time" }
                if (draft.scheduledMinute != null) ActionButton(onClick = { viewModel.update(draft.copy(scheduledMinute = null)) }, enabled = !commands.busy) {
                    Text(stringResource(R.string.todo_remove_time))
                }
                if (draft.habit) {
                    HabitFrequencyFields(draft, commands.busy, viewModel::update)
                    val end = runCatching { draft.date.plusDays(20).format(fullDayFormat) }.getOrDefault("")
                    Text(stringResource(R.string.todo_habit_end_note, end), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (draft.editing) Text(stringResource(R.string.todo_habit_edit_note), style = MaterialTheme.typography.bodySmall)
                }
                commands.error?.let { ErrorState(todoError(it)) }
                if (draft.editing) ActionButton(onClick = { viewModel.requestDelete(draft.id, draft.title, draft.habit) }, enabled = !commands.busy) {
                    Text(stringResource(if (draft.habit) R.string.todo_habit_archive else R.string.todo_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { PrimaryButton(onClick = viewModel::saveEditor, enabled = !commands.busy, modifier = Modifier.testTag("todo-save")) {
            Text(stringResource(if (commands.busy) R.string.saving else R.string.todo_save))
        } },
        dismissButton = { ActionButton(onClick = viewModel::cancelEditor, enabled = !commands.busy) { Text(stringResource(R.string.picker_cancel)) } })

    if (picker == "date") RecordDatePicker(if (draft.habit) R.string.todo_habit_start else R.string.todo_date,
        draft.date, onCancel = { picker = null }, onConfirm = { viewModel.update(draft.copy(date = it)); picker = null })
    if (picker == "time") {
        val minute = viewModel.pickerMinute()
        // This is a calendar task's wall time, not an elapsed-time endpoint. UTC is only a wheel adapter;
        // no offset/instant is stored and a phone zone change cannot move an existing todo to another day.
        RecordDateTimePicker(R.string.todo_choose_time, draft.date.atTime(minute / 60, minute % 60).atZone(ZoneOffset.UTC), ZoneOffset.UTC,
            onCancel = { picker = null }, onConfirm = { local, _ ->
                viewModel.update(draft.copy(date = local.toLocalDate(), scheduledMinute = local.hour * 60 + local.minute)); picker = null
            }, tagPrefix = "todo-wheel")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HabitFrequencyFields(draft: TodoEditorDraft, busy: Boolean, onChange: (TodoEditorDraft) -> Unit) {
    Text(stringResource(R.string.todo_repeat), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HabitFrequency.entries.forEach { frequency ->
            FilterChip(selected = draft.frequency == frequency, onClick = { onChange(draft.copy(frequency = frequency)) }, enabled = !busy,
                label = { Text(stringResource(when (frequency) {
                    HabitFrequency.DAILY -> R.string.todo_frequency_daily
                    HabitFrequency.INTERVAL -> R.string.todo_frequency_interval
                    HabitFrequency.WEEKLY -> R.string.todo_frequency_weekly
                })) })
        }
    }
    when (draft.frequency) {
        HabitFrequency.DAILY -> Unit
        HabitFrequency.INTERVAL -> RecordTextField(draft.interval, { onChange(draft.copy(interval = it.take(3))) },
            Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.todo_interval_label)) },
            supportingText = { Text(stringResource(R.string.todo_interval_hint)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        HabitFrequency.WEEKLY -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weekdayLabels().forEachIndexed { index, name ->
                    val bit = 1 shl index
                    FilterChip(selected = draft.weekdays and bit != 0,
                        onClick = { onChange(draft.copy(weekdays = draft.weekdays xor bit)) }, enabled = !busy, label = { Text(name) })
                }
            }
            if (draft.weekdays == 0) Text(stringResource(R.string.todo_weekday_required), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun weekdayLabels() = listOf(R.string.todo_mon, R.string.todo_tue, R.string.todo_wed, R.string.todo_thu,
    R.string.todo_fri, R.string.todo_sat, R.string.todo_sun).map { stringResource(it) }

@Composable
private fun weekdaysText(mask: Int): String = weekdayLabels().filterIndexed { index, _ -> mask and (1 shl index) != 0 }.joinToString("、")

@Composable
private fun todoError(error: ErrorCode): String = stringResource(when (error) {
    ErrorCode.INVALID_NAME, ErrorCode.TEXT_TOO_LONG -> R.string.todo_invalid_name
    ErrorCode.INVALID_NUMBER -> R.string.todo_invalid_repeat
    ErrorCode.INVALID_LOCAL_TIME -> R.string.todo_invalid_date
    ErrorCode.NOT_FOUND -> R.string.todo_not_found
    else -> R.string.todo_write_failed
})
