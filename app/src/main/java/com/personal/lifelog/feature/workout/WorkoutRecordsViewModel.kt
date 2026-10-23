package com.personal.lifelog.feature.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ErrorCode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class WorkoutDayState(
    val date: LocalDate, val loading: Boolean = true,
    val rows: List<WorkoutWithItems> = emptyList(), val failed: Boolean = false,
)

/** Date membership comes exclusively from Room's saved localDate, not the current zone. */
class WorkoutDayViewModel(
    private val repository: WorkoutRepository,
    private val saved: SavedStateHandle,
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutDayState(
        saved.get<String>("date")?.let(LocalDate::parse) ?: LocalDate.now(clock.withZone(zoneIdProvider()))))
    val state = _state.asStateFlow()
    private var job: Job? = null
    init { selectDate(_state.value.date) }
    fun selectDate(date: LocalDate) {
        job?.cancel()
        saved["date"] = date.toString()
        _state.value = WorkoutDayState(date)
        job = viewModelScope.launch {
            repository.workoutsOn(date.toString())
                .catch { _state.value = WorkoutDayState(date, loading = false, failed = true) }
                .collect { _state.value = WorkoutDayState(date, loading = false, rows = it) }
        }
    }
}

data class WorkoutDetailState(
    val record: WorkoutWithItems? = null, val loading: Boolean = true,
    val busy: Boolean = false, val deleted: Boolean = false, val error: ErrorCode? = null,
)

class WorkoutDetailViewModel(private val repository: WorkoutRepository, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutDetailState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    init { load() }
    fun load() {
        if (_state.value.busy || _state.value.deleted) return
        job?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        job = viewModelScope.launch {
            repository.observeWorkout(id)
                .catch { _state.value = _state.value.copy(loading = false, error = ErrorCode.STORAGE_UNAVAILABLE) }
                .collect { _state.value = _state.value.copy(record = it, loading = false,
                    error = if (it == null && !_state.value.busy && !_state.value.deleted) ErrorCode.NOT_FOUND else _state.value.error) }
        }
    }
    fun delete() {
        if (_state.value.loading || _state.value.busy || _state.value.deleted || _state.value.record == null) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                when (val result = repository.deleteWorkout(id)) {
                    is DataResult.Success -> _state.value = _state.value.copy(deleted = true)
                    is DataResult.Failure -> _state.value = _state.value.copy(error = result.error.code)
                }
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
