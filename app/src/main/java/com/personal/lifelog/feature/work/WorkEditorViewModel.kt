package com.personal.lifelog.feature.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.WorkRecord
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class WorkEditorState(
    val form: WorkForm? = null, val original: WorkForm? = null,
    val loading: Boolean = true, val busy: Boolean = false,
    val error: ErrorCode? = null, val pendingLong: WorkDraft? = null, val done: Boolean = false,
) {
    val dirty: Boolean get() = form != null && form != original
}

class WorkEditorViewModel(
    private val repository: WorkRepository,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val recordId: String?,
    private val saved: SavedStateHandle,
    private val onDeleted: (WorkRecord) -> Unit = {},
) : ViewModel() {
    val state = MutableStateFlow(WorkEditorState())
    private val id = recordId ?: saved.get<String>("id") ?: RecordIds.newId().also { saved["id"] = it }
    private var expected: WorkRecord? = null

    init { load() }

    fun systemZone(): ZoneId = zoneIdProvider()

    fun load() {
        if (state.value.busy) return
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val old = recordId?.let { repository.work(it) }
                if (recordId != null && old == null) {
                    state.value = WorkEditorState(loading = false, error = ErrorCode.NOT_FOUND)
                    return@launch
                }
                // A recreated stale draft must keep the exact row it originally edited.
                expected = saved.get<ArrayList<String>>("expected")?.let(::restoreWorkRow) ?: old
                expected?.let { saved["expected"] = saveWorkRow(it) }
                val initial = old?.let { WorkForm.from(it, clock.millis(), zoneIdProvider().id) }
                    ?: WorkForm.at(clock.millis(), zoneIdProvider().id)
                val original = saved.get<ArrayList<String>>("original")?.let(WorkForm::restore) ?: initial
                val form = saved.get<ArrayList<String>>("form")?.let(WorkForm::restore) ?: initial
                saved["original"] = original.saved()
                saved["form"] = form.saved()
                state.value = WorkEditorState(form, original, loading = false)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (violation: DataViolation) {
                state.value = WorkEditorState(loading = false, error = violation.error.code)
            } catch (_: Exception) {
                state.value = WorkEditorState(loading = false, error = ErrorCode.STORAGE_UNAVAILABLE)
            }
        }
    }

    fun change(form: WorkForm) {
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
        } catch (_: ArithmeticException) {
            state.value = state.value.copy(error = ErrorCode.INVALID_LOCAL_TIME); return
        }
        persist(draft)
    }

    fun cancelLong() { state.value = state.value.copy(pendingLong = null) }
    fun confirmLong() { state.value.pendingLong?.let { persist(it.copy(confirmLongInterval = true)) } }

    private fun persist(draft: WorkDraft) = command {
        val old = expected
        val result = if (old == null) repository.saveWork(draft) else repository.editWork(draft, old)
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
            when (val result = repository.deleteWork(old)) {
                is DataResult.Success -> { onDeleted(old); state.value = state.value.copy(done = true) }
                is DataResult.Failure -> state.value = state.value.copy(error = result.error.code)
            }
        }
    }

    private fun command(action: suspend () -> Unit) {
        if (state.value.busy || state.value.done) return
        state.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch { try { action() } finally { state.value = state.value.copy(busy = false) } }
    }
}

internal fun saveWorkRow(row: WorkRecord) = arrayListOf(row.id, row.startedAtUtcMs.toString(), row.startZoneId,
    row.endedAtUtcMs?.toString() ?: "", row.endZoneId ?: "", row.archiveLocalDate,
    row.activeSlot?.toString() ?: "", row.createdAtUtcMs.toString(), row.updatedAtUtcMs.toString())
internal fun restoreWorkRow(v: ArrayList<String>) = WorkRecord(v[0], v[1].toLong(), v[2],
    v[3].toLongOrNull(), v[4].takeIf { it.isNotEmpty() }, v[5], v[6].toIntOrNull(), v[7].toLong(), v[8].toLong())
