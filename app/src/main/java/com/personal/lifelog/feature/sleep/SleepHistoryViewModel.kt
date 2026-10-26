package com.personal.lifelog.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.SleepRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class SleepHistoryState(
    val date: LocalDate,
    val zone: ZoneId,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val records: List<SleepRecord> = emptyList(),
    val day: SleepDayAnalysis? = null,
    val week: List<SleepDayAnalysis> = emptyList(),
)

/** Each new history route follows Today; an explicit selection survives editors/recreation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepHistoryViewModel(
    observeRecords: () -> Flow<List<SleepRecord>>,
    val today: StateFlow<LocalDate>,
    private val zoneIdProvider: () -> ZoneId,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val selection = MutableStateFlow(saved.get<String>(KEY_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    private val zone = MutableStateFlow(zoneIdProvider())
    private val retry = MutableStateFlow(0)
    private val date = combine(today, selection) { current, chosen -> chosen ?: current }

    val state: StateFlow<SleepHistoryState> = combine(date, zone, retry) { selected, zone, _ -> selected to zone }
        .flatMapLatest { (selected, zone) ->
            flow { emitAll(observeRecords()) }.map { records ->
                val week = (6L downTo 0L).map { days -> sleepDayAnalysis(records, selected.minusDays(days), zone) }
                SleepHistoryState(selected, zone, loading = false,
                    records = records.filter { sleepHistoryDate(it) == selected }
                        .sortedWith(compareByDescending<SleepRecord> { it.endedAtUtcMs ?: it.startedAtUtcMs }.thenBy { it.id }),
                    day = week.last(), week = week)
            }.onStart { emit(SleepHistoryState(selected, zone)) }
                .catch { emit(SleepHistoryState(selected, zone, loading = false, failed = true)) }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepHistoryState(today.value, zone.value))

    fun selectDate(value: LocalDate) {
        val selected = value.takeUnless { it == today.value }
        saved[KEY_DATE] = selected?.toString()
        selection.value = selected
    }
    fun goToToday() { saved[KEY_DATE] = null; selection.value = null }
    fun refreshZone() { zone.value = zoneIdProvider() }
    fun retry() { refreshZone(); retry.value++ }

    companion object { const val KEY_DATE = "sleep.history.date" }
}
