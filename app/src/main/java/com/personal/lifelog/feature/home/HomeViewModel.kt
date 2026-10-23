package com.personal.lifelog.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.feature.timeline.TimelineUiState
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Today's persisted local date and the unified three-type timeline for it.
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
) : ViewModel() {
    private val retry = MutableStateFlow(0)
    private val initialToday = LocalDate.now(clock.withZone(zoneIdProvider()))

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
