package com.personal.lifelog.feature.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkHistoryViewModelTest {
    private val today = MutableStateFlow(LocalDate.of(2026, 10, 26))
    private var zone = ZoneId.of("Asia/Shanghai")
    private val instances = mutableListOf<WorkHistoryViewModel>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun close() = runBlocking {
        instances.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }
    private fun model(rows: () -> Flow<List<com.personal.lifelog.data.local.entity.WorkRecord>>, saved: SavedStateHandle = SavedStateHandle()) =
        WorkHistoryViewModel(rows, today, { zone }, saved).also { instances += it }
    private suspend fun ready(vm: WorkHistoryViewModel, predicate: (WorkHistoryState) -> Boolean = { true }) =
        withTimeout(5_000) { vm.state.first { !it.loading && predicate(it) } }

    @Test fun defaultsToTodayWithOnlyThatEndDateAndExactlySevenChartDays() = runBlocking {
        val prior = workAnalysisRecord("prior", "2026-10-25T23:00", "2026-10-26T07:00")
        val current = workAnalysisRecord("today", "2026-10-26T13:00", "2026-10-26T14:00")
        val old = workAnalysisRecord("old", "2026-10-25T02:00", "2026-10-25T08:00")
        val vm = model({ flowOf(listOf(prior, current, old)) })
        val state = ready(vm)
        assertEquals(today.value, state.date)
        assertEquals(listOf(current, prior), state.records)
        assertEquals(28_800_000L, state.day!!.durationMs)
        assertEquals((6L downTo 0L).map { today.value.minusDays(it) }, state.week.map { it.date })
    }

    @Test fun selectionSurvivesRecreationAndTodayKeepsFollowingMidnight() = runBlocking {
        val saved = SavedStateHandle()
        val vm = model({ flowOf(emptyList()) }, saved)
        vm.selectDate(today.value.minusDays(2))
        assertEquals(today.value.minusDays(2), ready(vm).date)
        val restored = model({ flowOf(emptyList()) }, SavedStateHandle(mapOf(WorkHistoryViewModel.KEY_DATE to saved.get<String>(WorkHistoryViewModel.KEY_DATE))))
        assertEquals(today.value.minusDays(2), ready(restored).date)
        today.value = today.value.plusDays(1)
        assertEquals(today.value.minusDays(3), ready(restored).date)
        restored.goToToday()
        assertEquals(today.value, ready(restored) { it.date == today.value }.date)
        today.value = today.value.plusDays(1)
        assertEquals(today.value, ready(restored) { it.date == today.value }.date)
        assertEquals(today.value, ready(model({ flowOf(emptyList()) })).date)
    }

    @Test fun readFailureCanRetryAndDoesNotLookLikeAnEmptyResult() = runBlocking {
        var failed = true
        val vm = model({ flow { if (failed) error("synthetic read failure") else emit(emptyList()) } })
        assertTrue(ready(vm).failed)
        failed = false
        vm.retry()
        val empty = ready(vm) { !it.failed }
        assertTrue(empty.records.isEmpty())
        assertNull(empty.day!!.durationMs)
    }

    @Test fun editedEndDateAndDeletionRefreshBothRecordsAndAnalysis() = runBlocking {
        val row = workAnalysisRecord("same-id", "2026-10-26T01:00", "2026-10-26T08:00")
        val rows = MutableStateFlow(listOf(row))
        val vm = model({ rows })
        val subscription = launch { vm.state.collect() }
        try {
            assertEquals(1, ready(vm).records.size)
            rows.value = listOf(workAnalysisRecord("same-id", "2026-10-25T01:00", "2026-10-25T08:00"))
            val moved = ready(vm) { it.records.isEmpty() && it.day?.durationMs == null }
            assertTrue(moved.records.isEmpty())
            vm.selectDate(today.value.minusDays(1))
            assertEquals("same-id", ready(vm) { it.date == today.value.minusDays(1) }.records.single().id)
            rows.value = emptyList()
            val deleted = ready(vm) { it.records.isEmpty() && it.week.all { day -> day.durationMs == null } }
            assertTrue(deleted.records.isEmpty())
        } finally { subscription.cancel() }
    }

    @Test fun foregroundTimezoneRefreshReprojectsChartAndPreservesArchiveSelection() = runBlocking {
        val row = workAnalysisRecord("travel", "2026-10-26T01:00", "2026-10-26T03:00")
        val vm = model({ flowOf(listOf(row)) })
        assertEquals(7_200_000L, ready(vm).day!!.durationMs)
        zone = ZoneId.of("America/New_York")
        vm.refreshZone()
        val moved = ready(vm) { it.zone == zone }
        assertEquals(today.value, moved.date)
        assertEquals(listOf(row), moved.records)
        assertNull(moved.day!!.durationMs)
        assertEquals(7_200_000L, moved.week[moved.week.lastIndex - 1].durationMs)
    }

}
