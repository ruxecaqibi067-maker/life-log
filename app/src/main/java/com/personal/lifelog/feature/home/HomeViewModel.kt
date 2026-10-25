package com.personal.lifelog.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.SleepSummary
import com.personal.lifelog.feature.timeline.TimelineUiState
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Today's persisted local date and the unified four-type timeline for it.
 *
 * [today] follows the injected clock/zone and re-emits when the local day rolls
 * over while the app stays open, so a late-night session moves to the new day
 * without a restart. The database is the only source of entries: the timeline is
 * re-queried for each date and after every save/edit/delete through Room flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
    private val timelineRepository: TimelineRepository,
    private val observeSleep: (LocalDate) -> Flow<DataResult<SleepSummary>>,
    private val saveTarget: suspend (Int) -> DataResult<AppSettings>,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val sleepRetry = MutableStateFlow(0)
    private val initialToday = LocalDate.now(clock.withZone(zoneIdProvider()))
    private var lastSleepDate: LocalDate? = null
    private var lastSleepContent: SleepCardContent? = null
    private val mutableGoal = MutableStateFlow(SleepGoalDraft(
        open = savedStateHandle["sleepGoal.open"] ?: false,
        hours = savedStateHandle["sleepGoal.hours"] ?: "8",
        minutes = savedStateHandle["sleepGoal.minutes"] ?: "0",
    ))
    val goal: StateFlow<SleepGoalDraft> = mutableGoal

    val today: StateFlow<LocalDate> = localDateUpdates(clock, zoneIdProvider)
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialToday)

    val timeline: StateFlow<TimelineUiState> = combine(today, retry) { date, _ -> date }
        .flatMapLatest { date ->
            timelineRepository.timelineOn(date.toString())
                .map { entries -> TimelineUiState(date = date, loading = false, entries = entries) }
                .onStart { emit(TimelineUiState(date = date, loading = true)) }
                .catch { emit(TimelineUiState(date = date, loading = false, failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimelineUiState(loading = true))

    val sleep: StateFlow<SleepCardState> = combine(today, sleepRetry) { date, _ -> date }
        .flatMapLatest { date ->
            if (lastSleepDate != date) {
                lastSleepDate = date
                lastSleepContent = null
            }
            flow { emitAll(observeSleep(date)) }.map { result ->
                when (result) {
                    is DataResult.Success -> {
                        val summary = result.value
                        lastSleepContent = SleepCardContent(summary.targetSleepMinutes, summary.recordedDurationMs,
                            summary.completedSegmentCount, summary.hasActiveSleep)
                        SleepCardState(loading = false, content = lastSleepContent)
                    }
                    is DataResult.Failure -> SleepCardState(loading = false, failed = true, content = lastSleepContent)
                }
            }.onStart { emit(SleepCardState(content = lastSleepContent)) }
                .catch { emit(SleepCardState(loading = false, failed = true, content = lastSleepContent)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SleepCardState())

    fun retrySleep() { sleepRetry.value++ }

    fun editGoal() {
        if (mutableGoal.value.busy || sleep.value.loading || sleep.value.failed) return
        val content = sleep.value.content ?: return
        val minutes = content.targetMinutes ?: 480
        updateGoal(SleepGoalDraft(open = true, hours = (minutes / 60).toString(), minutes = (minutes % 60).toString()))
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
        val minutes = parseSleepGoal(draft.hours, draft.minutes)
        if (minutes == null) {
            updateGoal(draft.copy(invalid = true, saveFailed = false))
            return
        }
        updateGoal(draft.copy(busy = true, invalid = false, saveFailed = false))
        viewModelScope.launch {
            val success = try { saveTarget(minutes) is DataResult.Success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            updateGoal(mutableGoal.value.copy(busy = false, open = !success, saveFailed = !success))
        }
    }

    private fun updateGoal(draft: SleepGoalDraft) {
        savedStateHandle["sleepGoal.open"] = draft.open
        savedStateHandle["sleepGoal.hours"] = draft.hours
        savedStateHandle["sleepGoal.minutes"] = draft.minutes
        mutableGoal.value = draft
    }

    fun retry() {
        retry.value++
    }
}

/**
 * Emits the local date immediately and then at each local midnight. The delay is
 * computed from the injected clock so DST-length days stay correct; it does not
 * poll every minute. The collector only reads the clock, never the wall clock.
 */
internal fun localDateUpdates(clock: Clock, zoneIdProvider: () -> ZoneId): Flow<LocalDate> = flow {
    while (true) {
        val zone = zoneIdProvider()
        val now = clock.instant().atZone(zone)
        emit(now.toLocalDate())
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
        delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000))
    }
}
