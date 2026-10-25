package com.personal.lifelog.feature.todo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.HabitRecord
import com.personal.lifelog.data.local.entity.TodoRecord
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TodoDayState(
    val date: LocalDate,
    val items: List<TodoRecord> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

data class HabitListState(val items: List<HabitRecord> = emptyList(), val loading: Boolean = true, val failed: Boolean = false)

/** Only primitive fields are persisted in SavedStateHandle; an unconfirmed draft never writes Room. */
data class TodoEditorDraft(
    val id: String,
    val habit: Boolean,
    val editing: Boolean = false,
    val title: String = "",
    val date: LocalDate,
    val scheduledMinute: Int? = null,
    val frequency: HabitFrequency = HabitFrequency.DAILY,
    val interval: String = "2",
    val weekdays: Int = 0,
) {
    internal fun saved() = arrayListOf(id, habit.toString(), editing.toString(), title, date.toString(),
        scheduledMinute?.toString().orEmpty(), frequency.name, interval, weekdays.toString())

    companion object {
        internal fun restore(v: ArrayList<String>): TodoEditorDraft? = runCatching {
            TodoEditorDraft(v[0], v[1].toBooleanStrict(), v[2].toBooleanStrict(), v[3], LocalDate.parse(v[4]),
                v[5].toIntOrNull(), HabitFrequency.valueOf(v[6]), v[7], v[8].toInt())
        }.getOrNull()
    }
}

data class TodoDeleteRequest(val id: String, val title: String, val habit: Boolean)
data class TodoCommandState(val busy: Boolean = false, val error: ErrorCode? = null, val deletion: TodoDeleteRequest? = null)

/** The same phone-local day stream drives Home's summaries and its independently stored checklist. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModel(
    private val repository: TodoRepository,
    val today: StateFlow<LocalDate>,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val clock: Clock = Clock.systemUTC(),
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val selected = MutableStateFlow(saved.get<String>("todo.date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    private val historyDate = combine(today, selected) { date, selection -> selection ?: date }
    val home = observeDay(today).stateIn(viewModelScope, SharingStarted.Eagerly, TodoDayState(today.value))
    val history = observeDay(historyDate).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoDayState(today.value))
    val habits: StateFlow<HabitListState> = combine(today, retry) { date, _ -> date }
        .flatMapLatest { date -> repository.observeOngoingHabits(date)
            .map { HabitListState(it, loading = false) }
            .onStart { emit(HabitListState()) }
            .catch { emit(HabitListState(loading = false, failed = true)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitListState())
    private val mutableEditor = MutableStateFlow(saved.get<ArrayList<String>>("todo.editor")?.let(TodoEditorDraft::restore))
    val editor: StateFlow<TodoEditorDraft?> = mutableEditor.asStateFlow()
    private val mutableCommands = MutableStateFlow(TodoCommandState())
    val commands: StateFlow<TodoCommandState> = mutableCommands.asStateFlow()

    private fun observeDay(dates: Flow<LocalDate>): Flow<TodoDayState> = combine(dates, retry) { date, _ -> date }
        .flatMapLatest { date -> flow {
            // Repository refuses past/future generation. History reads never backfill missed days.
            val generated = repository.generateFor(date)
            emitAll(repository.observeOn(date).map { TodoDayState(date, it, loading = false, failed = generated is DataResult.Failure) })
        }.onStart { emit(TodoDayState(date)) }
            .catch { emit(TodoDayState(date, loading = false, failed = true)) }
        }

    fun refresh() { retry.value++ }

    fun selectDate(date: LocalDate?) {
        saved["todo.date"] = date?.toString()
        selected.value = date
    }

    fun addTodo(date: LocalDate = today.value) = open(TodoEditorDraft(RecordIds.newId(), habit = false, date = date))
    fun addHabit() = open(TodoEditorDraft(RecordIds.newId(), habit = true, date = today.value,
        weekdays = 1 shl (today.value.dayOfWeek.value - 1)))
    fun editTodo(row: TodoRecord) = open(TodoEditorDraft(row.id, habit = false, editing = true,
        title = row.title, date = LocalDate.parse(row.localDate), scheduledMinute = row.scheduledMinute))
    fun editHabit(row: HabitRecord) = open(TodoEditorDraft(row.id, habit = true, editing = true,
        title = row.title, date = LocalDate.parse(row.startDate), scheduledMinute = row.scheduledMinute,
        frequency = row.frequency, interval = row.intervalDays.toString(), weekdays = row.weekdaysMask))

    private fun open(draft: TodoEditorDraft) {
        if (commands.value.busy) return
        mutableCommands.value = TodoCommandState()
        update(draft)
    }

    fun update(draft: TodoEditorDraft) {
        if (commands.value.busy) return
        saved["todo.editor"] = draft.saved()
        mutableEditor.value = draft
        mutableCommands.value = commands.value.copy(error = null)
    }

    fun cancelEditor() {
        if (commands.value.busy) return
        closeEditor()
        clearError()
    }

    private fun closeEditor() { saved.remove<ArrayList<String>>("todo.editor"); mutableEditor.value = null }
    fun clearError() { mutableCommands.value = commands.value.copy(error = null) }
    fun pickerMinute(): Int = editor.value?.scheduledMinute ?: LocalTime.now(clock.withZone(zoneIdProvider())).let { it.hour * 60 + it.minute }

    fun saveEditor() {
        val draft = editor.value ?: return
        val interval = if (draft.frequency == HabitFrequency.INTERVAL) draft.interval.toIntOrNull() else 1
        if (draft.habit && interval == null) {
            mutableCommands.value = commands.value.copy(error = ErrorCode.INVALID_NUMBER)
            return
        }
        command {
            val result = if (draft.habit) repository.saveHabit(HabitDraft(draft.id, draft.title, draft.date,
                draft.scheduledMinute, draft.frequency, interval ?: 1, draft.weekdays))
                else repository.saveTodo(TodoDraft(draft.id, draft.title, draft.date, draft.scheduledMinute))
            when (result) {
                is DataResult.Failure -> fail(result.error.code)
                is DataResult.Success -> {
                    closeEditor()
                    if (draft.habit) regenerate()
                }
            }
        }
    }

    fun complete(row: TodoRecord, completed: Boolean) = command {
        when (val result = repository.setCompleted(row.id, completed)) {
            is DataResult.Failure -> fail(result.error.code)
            is DataResult.Success -> Unit
        }
    }

    fun toggleHabit(row: HabitRecord) = command {
        val next = if (row.status == HabitStatus.PAUSED) HabitStatus.ACTIVE else HabitStatus.PAUSED
        when (val result = repository.setHabitStatus(row.id, next)) {
            is DataResult.Failure -> fail(result.error.code)
            is DataResult.Success -> if (next == HabitStatus.ACTIVE) regenerate()
        }
    }

    fun requestDelete(id: String, title: String, habit: Boolean) {
        if (!commands.value.busy) mutableCommands.value = TodoCommandState(deletion = TodoDeleteRequest(id, title, habit))
    }

    fun cancelDelete() { if (!commands.value.busy) mutableCommands.value = commands.value.copy(deletion = null) }
    fun confirmDelete() {
        val request = commands.value.deletion ?: return
        command {
            val result = if (request.habit) repository.setHabitStatus(request.id, HabitStatus.ARCHIVED) else repository.deleteTodo(request.id)
            when (result) {
                is DataResult.Failure -> fail(result.error.code)
                is DataResult.Success -> {
                    mutableCommands.value = commands.value.copy(deletion = null)
                    if (editor.value?.id == request.id) closeEditor()
                }
            }
        }
    }

    private suspend fun regenerate() {
        when (val result = repository.generateFor(today.value)) {
            is DataResult.Failure -> fail(result.error.code)
            is DataResult.Success -> refresh()
        }
    }

    private fun fail(code: ErrorCode) { mutableCommands.value = commands.value.copy(error = code) }

    private fun command(action: suspend () -> Unit) {
        if (commands.value.busy) return
        mutableCommands.value = commands.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(ErrorCode.STORAGE_UNAVAILABLE) }
            finally { mutableCommands.value = commands.value.copy(busy = false) }
        }
    }
}
