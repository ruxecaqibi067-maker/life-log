package com.personal.lifelog.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.feature.timeline.RecordedDatesState
import com.personal.lifelog.feature.timeline.TimelineUiState
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Review keeps only the selected date; every record shown is queried from Room for
 * that stored local date. The selection survives configuration/process recreation
 * through [SavedStateHandle]. [recordedDates] drives the quick jump to days that
 * actually contain data and refreshes when any record is added, moved or deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val timelineRepository: TimelineRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val selected = MutableStateFlow(initialDate())
    val date: StateFlow<LocalDate> = selected.asStateFlow()

    val day: StateFlow<TimelineUiState> = combine(selected, retry) { date, _ -> date }
        .flatMapLatest { date ->
            timelineRepository.timelineOn(date.toString())
                .map { entries -> TimelineUiState(date = date, loading = false, entries = entries) }
                .onStart { emit(TimelineUiState(date = date, loading = true)) }
                .catch { emit(TimelineUiState(date = date, loading = false, failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimelineUiState(loading = true))

    val recordedDates: StateFlow<RecordedDatesState> = retry
        .flatMapLatest {
            timelineRepository.datesWithRecords()
                .map { dates -> RecordedDatesState(dates = dates) }
                .catch { emit(RecordedDatesState(failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordedDatesState())

    fun selectDate(date: LocalDate) {
        saved[KEY_DATE] = date.toString()
        selected.value = date
    }

    fun previousDay() = selectDate(date.value.minusDays(1))
    fun nextDay() = selectDate(date.value.plusDays(1))

    /** "Today" is resolved when tapped, not cached at ViewModel creation. */
    fun goToToday() = selectDate(LocalDate.now(clock.withZone(zoneIdProvider())))

    fun retry() {
        retry.value++
    }

    private fun initialDate(): LocalDate {
        val today = LocalDate.now(clock.withZone(zoneIdProvider()))
        val stored = saved.get<String>(KEY_DATE) ?: return today
        return runCatching { LocalDate.parse(stored) }.getOrNull() ?: today
    }

    companion object {
        const val KEY_DATE = "reviewDate"
    }
}
