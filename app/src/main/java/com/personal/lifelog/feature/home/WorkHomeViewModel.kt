package com.personal.lifelog.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.domain.*
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkHomeViewModel(
    private val today: StateFlow<LocalDate>,
    private val observeWork: (LocalDate) -> Flow<DataResult<WorkSummary>>,
    private val saveTarget: suspend (Int?) -> DataResult<AppSettings>,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val workRetry = MutableStateFlow(0)
    private var lastWorkDate: LocalDate? = null
    private var lastWorkContent: WorkCardContent? = null
    private val mutableGoal = MutableStateFlow(DurationGoalDraft(
        open = savedStateHandle["workGoal.open"] ?: false,
        hours = savedStateHandle["workGoal.hours"] ?: "8",
        minutes = savedStateHandle["workGoal.minutes"] ?: "0",
    ))
    val goal: StateFlow<DurationGoalDraft> = mutableGoal
    val work: StateFlow<WorkCardState> = combine(today, workRetry) { date, _ -> date }
        .flatMapLatest { date ->
            if (lastWorkDate != date) {
                lastWorkDate = date
                lastWorkContent = null
            }
            flow { emitAll(observeWork(date)) }.map { result ->
                when (result) {
                    is DataResult.Success -> {
                        val summary = result.value
                        lastWorkContent = WorkCardContent(summary.targetWorkMinutes, summary.recordedDurationMs,
                            summary.completedSegmentCount, summary.hasActiveWork)
                        WorkCardState(loading = false, content = lastWorkContent)
                    }
                    is DataResult.Failure -> WorkCardState(loading = false, failed = true, content = lastWorkContent)
                }
            }.onStart { emit(WorkCardState(content = lastWorkContent)) }
                .catch { emit(WorkCardState(loading = false, failed = true, content = lastWorkContent)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, WorkCardState())

    fun retryWork() { workRetry.value++ }

    fun editGoal() {
        if (mutableGoal.value.busy || work.value.loading || work.value.failed) return
        val content = work.value.content ?: return
        val minutes = content.targetMinutes ?: 480
        updateGoal(DurationGoalDraft(open = true, hours = (minutes / 60).toString(), minutes = (minutes % 60).toString()))
    }

    fun goalHours(value: String) {
        if (!mutableGoal.value.busy) updateGoal(mutableGoal.value.copy(hours = value, invalid = false, saveFailed = false))
    }

    fun goalMinutes(value: String) {
        if (!mutableGoal.value.busy) updateGoal(mutableGoal.value.copy(minutes = value, invalid = false, saveFailed = false))
    }

    fun cancelGoal() {
        if (!mutableGoal.value.busy) updateGoal(mutableGoal.value.copy(open = false))
    }

    fun saveGoal() {
        val draft = mutableGoal.value
        if (!draft.open || draft.busy) return
        val minutes = parseDurationGoal(draft.hours, draft.minutes)
        if (minutes == null) {
            updateGoal(draft.copy(invalid = true, saveFailed = false))
            return
        }
        persistGoal(minutes)
    }

    fun clearGoal() {
        if (mutableGoal.value.open && !mutableGoal.value.busy) persistGoal(null)
    }

    private fun persistGoal(minutes: Int?) {
        updateGoal(mutableGoal.value.copy(busy = true, invalid = false, saveFailed = false))
        viewModelScope.launch {
            val success = try { saveTarget(minutes) is DataResult.Success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            updateGoal(mutableGoal.value.copy(busy = false, open = !success, saveFailed = !success))
        }
    }

    private fun updateGoal(draft: DurationGoalDraft) {
        savedStateHandle["workGoal.open"] = draft.open
        savedStateHandle["workGoal.hours"] = draft.hours
        savedStateHandle["workGoal.minutes"] = draft.minutes
        mutableGoal.value = draft
    }

}
