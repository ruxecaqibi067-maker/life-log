package com.personal.lifelog.feature.workout

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkoutEditorState(
    val form: WorkoutForm? = null,
    val original: WorkoutForm? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val done: Boolean = false,
    val error: ErrorCode? = null,
    val errorItemId: String? = null,
) {
    val dirty get() = form != null && form != original
}

class WorkoutEditorViewModel(
    private val repository: WorkoutRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val workoutId: String? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutEditorState())
    val state = _state.asStateFlow()
    val picking = saved.getStateFlow("picking", false)
    private val id = workoutId ?: saved.get<String>("id") ?: RecordIds.newId().also { saved["id"] = it }
    init { load() }

    fun openPicker() { if (!_state.value.busy && !_state.value.done) saved["picking"] = true }
    fun closePicker() { saved["picking"] = false }

    fun load() {
        if (_state.value.busy || _state.value.done) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val record = workoutId?.let { repository.workout(it) }
                if (workoutId != null && record == null) {
                    _state.value = WorkoutEditorState(loading = false, error = ErrorCode.NOT_FOUND)
                    return@launch
                }
                val initial = record?.let(WorkoutForm::from) ?: WorkoutForm.at(id, clock.millis(), zoneIdProvider().id)
                val original = saved.get<Bundle>("original")?.let(WorkoutForm::restore) ?: initial
                val form = saved.get<Bundle>("form")?.let(WorkoutForm::restore) ?: initial
                saved["original"] = original.saved()
                saved["form"] = form.saved()
                _state.value = WorkoutEditorState(form, original, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                _state.value = WorkoutEditorState(loading = false, error = ErrorCode.STORAGE_UNAVAILABLE)
            }
        }
    }

    fun change(form: WorkoutForm) {
        if (_state.value.busy || _state.value.done || _state.value.loading) return
        saved["form"] = form.saved()
        _state.value = _state.value.copy(form = form, error = null, errorItemId = null)
    }

    fun add(definition: ExerciseDefinition) {
        val form = _state.value.form ?: return
        if (definition.isHidden) { fail(ErrorCode.HIDDEN_EXERCISE); return }
        if (form.items.size >= Rules.WORKOUT_ITEMS_LIMIT) { fail(ErrorCode.TOO_MANY_ITEMS); return }
        change(form.copy(items = form.items + WorkoutItemForm(RecordIds.newId(), definition.id, definition.name, definition.type)))
    }

    /** T09 can update a single item's sets through this event while retaining IDs. */
    fun changeItem(item: WorkoutItemForm) {
        val form = _state.value.form ?: return
        change(form.copy(items = form.items.map { if (it.id == item.id) item else it }))
    }
    fun remove(itemId: String) { _state.value.form?.let { change(it.copy(items = it.items.filterNot { row -> row.id == itemId })) } }
    fun move(itemId: String, delta: Int) {
        val form = _state.value.form ?: return
        val from = form.items.indexOfFirst { it.id == itemId }
        val to = from + delta
        if (from < 0 || to !in form.items.indices) return
        val items = form.items.toMutableList()
        items.add(to, items.removeAt(from))
        change(form.copy(items = items))
    }

    fun save() {
        val current = _state.value
        val form = current.form ?: return
        if (current.busy || current.done || current.loading) return
        if (form.items.isEmpty()) { fail(ErrorCode.EMPTY_WORKOUT); return }
        // Identify which repeated item contains an invalid number/note before aggregate validation.
        form.items.forEach { item ->
            try { item.draft() } catch (violation: DataViolation) { fail(violation.error.code, item.id); return }
        }
        val draft = try { form.draft() } catch (violation: DataViolation) {
            fail(violation.error.code); return
        } catch (_: DateTimeException) { fail(ErrorCode.INVALID_LOCAL_TIME); return }
        _state.value = current.copy(busy = true, error = null, errorItemId = null)
        viewModelScope.launch {
            try {
                when (val result = repository.saveWorkout(draft)) {
                    is DataResult.Success -> _state.value = _state.value.copy(done = true)
                    is DataResult.Failure -> fail(result.error.code)
                }
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }

    private fun fail(code: ErrorCode, itemId: String? = null) {
        _state.value = _state.value.copy(error = code, errorItemId = itemId)
    }
}
