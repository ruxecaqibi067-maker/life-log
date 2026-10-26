package com.personal.lifelog.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
class SleepHistoryViewModelTest {
    private val today = MutableStateFlow(LocalDate.of(2026, 10, 26))
    private val zone = ZoneId.of("Asia/Shanghai")
    private val instances = mutableListOf<SleepHistoryViewModel>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun close() = runBlocking {
        instances.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }
    private fun model(rows: () -> Flow<List<com.personal.lifelog.data.local.entity.SleepRecord>>, saved: SavedStateHandle = SavedStateHandle()) =
        SleepHistoryViewModel(rows, today, { zone }, saved).also { instances += it }
    private suspend fun ready(vm: SleepHistoryViewModel, predicate: (SleepHistoryState) -> Boolean = { true }) =
        withTimeout(5_000) { vm.state.first { !it.loading && predicate(it) } }

    @Test fun defaultsToTodayWithOnlyThatWakeDateAndExactlySevenChartDays() = runBlocking {
        val prior = analysisRecord("prior", "2026-10-25T23:00", "2026-10-26T07:00")
        val current = analysisRecord("today", "2026-10-26T13:00", "2026-10-26T14:00")
        val old = analysisRecord("old", "2026-10-25T02:00", "2026-10-25T08:00")
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
        val restored = model({ flowOf(emptyList()) }, SavedStateHandle(mapOf(SleepHistoryViewModel.KEY_DATE to saved.get<String>(SleepHistoryViewModel.KEY_DATE))))
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

    @Test fun editedWakeDateAndDeletionRefreshBothRecordsAndAnalysis() = runBlocking {
        val row = analysisRecord("same-id", "2026-10-26T01:00", "2026-10-26T08:00")
        val rows = MutableStateFlow(listOf(row))
        val vm = model({ rows })
        val subscription = launch { vm.state.collect() }
        try {
            assertEquals(1, ready(vm).records.size)
            rows.value = listOf(analysisRecord("same-id", "2026-10-25T01:00", "2026-10-25T08:00"))
            val moved = ready(vm) { it.records.isEmpty() && it.day?.durationMs == null }
            assertTrue(moved.records.isEmpty())
            vm.selectDate(today.value.minusDays(1))
            assertEquals("same-id", ready(vm) { it.date == today.value.minusDays(1) }.records.single().id)
            rows.value = emptyList()
            val deleted = ready(vm) { it.records.isEmpty() && it.week.all { day -> day.durationMs == null } }
            assertTrue(deleted.records.isEmpty())
        } finally { subscription.cancel() }
    }
}
