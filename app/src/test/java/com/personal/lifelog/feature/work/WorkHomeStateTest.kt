package com.personal.lifelog.feature.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.home.WorkHomeViewModel
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkHomeStateTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun failurePreservesSameDayContentAndRetryButMidnightDoesNotShowYesterday() = runTest {
        val day = MutableStateFlow(LocalDate.of(2026,10,25))
        var reads = 0
        val vm = WorkHomeViewModel(day, { date ->
            reads++
            when (reads) {
                1 -> flow { emit(DataResult.Success(WorkSummary(date,480,2,18_000_000,true)));awaitCancellation() }
                2 -> flowOf(DataResult.Failure(DataError(ErrorCode.STORAGE)))
                3 -> flow { emit(DataResult.Success(WorkSummary(date,480,2,18_000_000,true)));awaitCancellation() }
                else -> emptyFlow()
            }
        }, { DataResult.Success(AppSettings(createdAtUtcMs=1,updatedAtUtcMs=1,targetWorkMinutes=it)) })
        try {
            assertEquals(18_000_000L,vm.work.value.content!!.recordedDurationMs)
            vm.retryWork();runCurrent()
            assertTrue(vm.work.value.failed)
            assertEquals(18_000_000L,vm.work.value.content!!.recordedDurationMs)
            vm.editGoal();assertFalse(vm.goal.value.open)
            vm.retryWork();runCurrent();assertFalse(vm.work.value.failed)
            day.value=day.value.plusDays(1);runCurrent()
            assertTrue(vm.work.value.loading);assertNull(vm.work.value.content)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun goalDraftSurvivesRecreationCancelDoesNotWriteAndBusyPreventsDoubleSave() = runTest {
        val day=MutableStateFlow(LocalDate.of(2026,10,25))
        val saved=SavedStateHandle()
        var writes=0
        val gate=CompletableDeferred<Unit>()
        fun make()=WorkHomeViewModel(day,{flowOf(DataResult.Success(WorkSummary(it,null,0,null,false)))},
            { writes++;gate.await();DataResult.Success(AppSettings(createdAtUtcMs=1,updatedAtUtcMs=1,targetWorkMinutes=it)) },saved)
        val first=make()
        first.editGoal();first.goalHours("7");first.goalMinutes("30")
        first.viewModelScope.cancel()
        val second=make()
        try {
            assertTrue(second.goal.value.open);assertEquals("7",second.goal.value.hours);assertEquals("30",second.goal.value.minutes)
            second.cancelGoal();assertEquals(0,writes)
            second.editGoal();second.saveGoal();second.saveGoal();second.clearGoal();second.cancelGoal()
            assertEquals(1,writes);assertTrue(second.goal.value.busy);assertTrue(second.goal.value.open)
            gate.complete(Unit);runCurrent();assertFalse(second.goal.value.open)
        } finally { second.viewModelScope.cancel() }
    }
}
