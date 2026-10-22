package com.personal.lifelog.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Today's local date and whether any of the three record types already exist for it,
 * both derived from the injected clock/zone and the database's date index. The shell
 * must not claim "no records" before that first read completes, so [hasRecordsToday]
 * starts as null. Day rollover while the app stays open is left to the timeline
 * package (T10), which will own this state.
 */
class HomeViewModel(
    clock: Clock,
    zoneIdProvider: () -> ZoneId,
    datesWithRecords: () -> Flow<List<String>>,
) : ViewModel() {
    private val todayDate = LocalDate.now(clock.withZone(zoneIdProvider()))
    val today: StateFlow<LocalDate> = MutableStateFlow(todayDate)

    val hasRecordsToday: StateFlow<Boolean?> = datesWithRecords()
        .map { dates -> dates.contains(todayDate.toString()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
