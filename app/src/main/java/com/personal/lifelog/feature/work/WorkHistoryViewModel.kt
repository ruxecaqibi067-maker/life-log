package com.personal.lifelog.feature.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.WorkRecord
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class WorkHistoryState(
    val date: LocalDate,
    val zone: ZoneId,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val records: List<WorkRecord> = emptyList(),
    val day: WorkDayAnalysis? = null,
    val week: List<WorkDayAnalysis> = emptyList(),
)

/** Each new history route follows Today; an explicit selection survives editors/recreation. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkHistoryViewModel(
    observeRecords: () -> Flow<List<WorkRecord>>,
    val today: StateFlow<LocalDate>,
    private val zoneIdProvider: () -> ZoneId,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val selection = MutableStateFlow(saved.get<String>(KEY_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
    private val zone = MutableStateFlow(zoneIdProvider())
    private val retry = MutableStateFlow(0)
    private val date = combine(today, selection) { current, chosen -> chosen ?: current }

    val state: StateFlow<WorkHistoryState> = combine(date, zone, retry) { selected, zone, _ -> selected to zone }
        .flatMapLatest { (selected, zone) ->
            flow { emitAll(observeRecords()) }.map { records ->
                val week = (6L downTo 0L).map { days -> workDayAnalysis(records, selected.minusDays(days), zone) }
                WorkHistoryState(selected, zone, loading = false,
                    records = records.filter { workHistoryDate(it) == selected }
                        .sortedWith(compareByDescending<WorkRecord> { it.endedAtUtcMs ?: it.startedAtUtcMs }.thenBy { it.id }),
                    day = week.last(), week = week)
            }.onStart { emit(WorkHistoryState(selected, zone)) }
                .catch { emit(WorkHistoryState(selected, zone, loading = false, failed = true)) }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkHistoryState(today.value, zone.value))

    fun selectDate(value: LocalDate) {
        val selected = value.takeUnless { it == today.value }
        saved[KEY_DATE] = selected?.toString()
        selection.value = selected
    }
    fun goToToday() { saved[KEY_DATE] = null; selection.value = null }
    fun refreshZone() { zone.value = zoneIdProvider() }
    fun retry() { refreshZone(); retry.value++ }

    companion object { const val KEY_DATE = "work.history.date" }
}
