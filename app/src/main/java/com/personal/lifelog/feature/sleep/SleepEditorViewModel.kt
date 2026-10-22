package com.personal.lifelog.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class SleepEditorState(
    val form: SleepForm? = null, val original: SleepForm? = null,
    val loading: Boolean = true, val busy: Boolean = false,
    val error: ErrorCode? = null, val pendingLong: SleepDraft? = null, val done: Boolean = false,
) {
    val dirty: Boolean get() = form != null && form != original
}

class SleepEditorViewModel(
    private val repository: RecordRepository,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val recordId: String?,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val state = MutableStateFlow(SleepEditorState())
    private val id = recordId ?: saved.get<String>("id") ?: RecordIds.newId().also { saved["id"] = it }
    private var expected: SleepRecord? = null

    init { load() }

    fun load() {
        if (state.value.busy) return
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val old = recordId?.let { repository.sleep(it) }
                if (recordId != null && old == null) {
                    state.value = SleepEditorState(loading = false, error = ErrorCode.NOT_FOUND)
                    return@launch
                }
                expected = old
                val initial = old?.let { SleepForm.from(it, clock.millis(), zoneIdProvider().id) }
                    ?: SleepForm.at(clock.millis(), zoneIdProvider().id)
                val original = saved.get<ArrayList<String>>("original")?.let(SleepForm::restore) ?: initial
                val form = saved.get<ArrayList<String>>("form")?.let(SleepForm::restore) ?: initial
                saved["original"] = original.saved()
                state.value = SleepEditorState(form, original, loading = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) {
                state.value = SleepEditorState(loading = false, error = ErrorCode.STORAGE_UNAVAILABLE)
            }
        }
    }

    fun change(form: SleepForm) {
        if (state.value.busy) return
        saved["form"] = form.saved()
        state.value = state.value.copy(form = form, error = null, pendingLong = null)
    }

    fun save() {
        val form = state.value.form ?: return
        if (state.value.busy) return
        val draft = try { form.draft(id) } catch (violation: DataViolation) {
            state.value = state.value.copy(error = violation.error.code); return
        } catch (_: DateTimeException) {
            state.value = state.value.copy(error = ErrorCode.INVALID_LOCAL_TIME); return
        }
        persist(draft)
    }

    fun cancelLong() { state.value = state.value.copy(pendingLong = null) }
    fun confirmLong() { state.value.pendingLong?.let { persist(it.copy(confirmLongInterval = true)) } }

    private fun persist(draft: SleepDraft) = command {
        val old = expected
        val result = if (old == null) repository.saveSleep(draft) else repository.editSleep(draft, old)
        when (result) {
            is DataResult.Success -> state.value = state.value.copy(done = true, pendingLong = null)
            is DataResult.Failure -> state.value = state.value.copy(
                error = result.error.code.takeUnless { it == ErrorCode.LONG_INTERVAL_CONFIRMATION },
                pendingLong = draft.takeIf { result.error.code == ErrorCode.LONG_INTERVAL_CONFIRMATION },
            )
        }
    }

    fun delete() {
        val old = expected ?: return
        command {
            when (val result = repository.deleteSleep(old)) {
                is DataResult.Success -> state.value = state.value.copy(done = true)
                is DataResult.Failure -> state.value = state.value.copy(error = result.error.code)
            }
        }
    }

    private fun command(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch { try { action() } finally { state.value = state.value.copy(busy = false) } }
    }
}
