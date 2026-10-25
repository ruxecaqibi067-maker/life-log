package com.personal.lifelog.feature.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.WorkRecord
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class WorkStatus(val loaded: Boolean = false, val active: WorkRecord? = null, val failed: Boolean = false)
data class WorkUndo(val saved: WorkRecord, val previous: WorkRecord?, val deleted: Boolean = false)
data class WorkFinish(val record: WorkRecord, val instant: Long, val zone: String)
data class WorkHistory(val records: List<WorkRecord>? = null, val failed: Boolean = false)

/** Activity-scoped commands. No timer: every active-state emission comes from Room. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkViewModel(
    val repository: WorkRepository,
    val clock: Clock,
    val zoneIdProvider: () -> ZoneId,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val status = refresh.flatMapLatest {
        repository.activeWork().map { WorkStatus(loaded = true, active = it) }
            .catch { emit(WorkStatus(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkStatus())
    val history = refresh.flatMapLatest {
        repository.works().map { WorkHistory(records = it) }.catch { emit(WorkHistory(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkHistory())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<ErrorCode?>(null)
    val undo = MutableStateFlow<WorkUndo?>(null)
    val longFinish = MutableStateFlow<WorkFinish?>(null)
    fun retry() { refresh.value++ }
    fun dismissError() { error.value = null }
    fun dismissLong() { longFinish.value = null }

    // Capture the record/instant once. Repeated callbacks for that button state share its identity.
    fun start(id: String) {
        if (!status.value.loaded || status.value.failed) return
        startCommand(id)
    }
    private fun startCommand(id: String) = command {
        when (val result = repository.startWork(id)) {
            is DataResult.Success -> if (result.value.id == id && result.value.endedAtUtcMs == null) {
                undo.value = WorkUndo(result.value, null)
            }
            is DataResult.Failure -> error.value = result.error.code
        }
    }

    fun finish(record: WorkRecord) {
        if (!status.value.loaded || status.value.failed) return
        val request = WorkFinish(record, clock.millis(), zoneIdProvider().id)
        finish(request, false)
    }

    fun confirmLong() { longFinish.value?.let { finish(it, true) } }

    private fun finish(request: WorkFinish, confirmed: Boolean) = command {
        // A stale button must not finish a record that was edited since it was displayed.
        when (val result = repository.finishWork(request.record.id, request.instant, request.zone, confirmed, request.record)) {
            is DataResult.Success -> {
                undo.value = WorkUndo(result.value, request.record)
                longFinish.value = null
            }
            is DataResult.Failure -> if (result.error.code == ErrorCode.LONG_INTERVAL_CONFIRMATION) {
                longFinish.value = request
            } else {
                longFinish.value = null
                error.value = result.error.code
            }
        }
    }

    fun undoLast() {
        val change = undo.value ?: return
        command {
            when (val result = if (change.deleted) repository.restoreDeletedWork(change.saved)
                else repository.undoWork(change.saved, change.previous)) {
                is DataResult.Success -> undo.value = null
                is DataResult.Failure -> { error.value = result.error.code; undo.value = null }
            }
        }
    }

    fun deleted(record: WorkRecord) { undo.value = WorkUndo(record, null, deleted = true) }

    private fun command(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { action() } finally { busy.value = false }
        }
    }
}
