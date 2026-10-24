package com.personal.lifelog.feature.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SleepStatus(val loaded: Boolean = false, val active: SleepRecord? = null, val failed: Boolean = false)
data class SleepUndo(val saved: SleepRecord, val previous: SleepRecord?)
data class SleepFinish(val record: SleepRecord, val instant: Long, val zone: String)
data class SleepHistory(val records: List<SleepRecord>? = null, val failed: Boolean = false)

/** Activity-scoped commands. No timer: every active-state emission comes from Room. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SleepViewModel(
    val repository: RecordRepository,
    val clock: Clock,
    val zoneIdProvider: () -> ZoneId,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val status = refresh.flatMapLatest {
        repository.activeSleep().map { SleepStatus(loaded = true, active = it) }
            .catch { emit(SleepStatus(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SleepStatus())
    val history = refresh.flatMapLatest {
        repository.sleeps().map { SleepHistory(records = it) }.catch { emit(SleepHistory(failed = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SleepHistory())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<ErrorCode?>(null)
    val undo = MutableStateFlow<SleepUndo?>(null)
    val longFinish = MutableStateFlow<SleepFinish?>(null)
    private val pendingBedtime = MutableStateFlow<String?>(null)
    val bedtimeRequest = pendingBedtime.asStateFlow()

    fun consumeBedtimeRequest(id: String) { if (pendingBedtime.value == id) pendingBedtime.value = null }

    fun retry() { refresh.value++ }
    fun dismissError() { error.value = null }
    fun dismissLong() { longFinish.value = null }

    // Capture the record/instant once. Repeated callbacks for that button state share its identity.
    fun start(id: String) = command {
        when (val result = repository.startSleep(id)) {
            is DataResult.Success -> if (result.value.id == id && result.value.endedAtUtcMs == null) {
                undo.value = SleepUndo(result.value, null)
                pendingBedtime.value = result.value.id
            }
            is DataResult.Failure -> error.value = result.error.code
        }
    }

    fun finish(record: SleepRecord) {
        val request = SleepFinish(record, clock.millis(), zoneIdProvider().id)
        finish(request, false)
    }

    fun confirmLong() { longFinish.value?.let { finish(it, true) } }

    private fun finish(request: SleepFinish, confirmed: Boolean) = command {
        // A stale button must not finish a record that was edited since it was displayed.
        when (val result = repository.finishSleep(request.record.id, request.instant, request.zone, confirmed, request.record)) {
            is DataResult.Success -> {
                undo.value = SleepUndo(result.value, request.record)
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
            when (val result = repository.undoSleep(change.saved, change.previous)) {
                is DataResult.Success -> undo.value = null
                is DataResult.Failure -> { error.value = result.error.code; undo.value = null }
            }
        }
    }

    private fun command(action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try { action() } finally { busy.value = false }
        }
    }
}
