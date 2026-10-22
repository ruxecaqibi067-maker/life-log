package com.personal.lifelog.feature.home

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    // 20:30Z is 04:30 on Oct 23 in Asia/Shanghai but still Oct 22 in UTC,
    // so the same instant must derive different local dates per injected zone.
    private val clock = Clock.fixed(Instant.parse("2026-10-22T20:30:00Z"), ZoneOffset.UTC)

    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        zone: ZoneId,
        dates: () -> kotlinx.coroutines.flow.Flow<List<String>>,
    ) = HomeViewModel(clock, { zone }, dates)

    @Test
    fun todayUsesInjectedZoneNotSystemDefault() {
        val shanghai = viewModel(ZoneId.of("Asia/Shanghai")) { flowOf(emptyList()) }
        assertEquals(LocalDate.of(2026, 10, 23), shanghai.today.value)

        val utc = viewModel(ZoneId.of("UTC")) { flowOf(emptyList()) }
        assertEquals(LocalDate.of(2026, 10, 22), utc.today.value)
    }

    @Test
    fun todayUsesInjectedClockNotWallClock() {
        val otherClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val viewModel = HomeViewModel(otherClock, { ZoneId.of("Asia/Shanghai") }, { flowOf(emptyList()) })
        assertEquals(LocalDate.of(2020, 1, 1), viewModel.today.value)
    }

    @Test
    fun hasRecordsTodayReflectsDatabaseDateIndex() {
        assertEquals(false, viewModel(ZoneId.of("Asia/Shanghai")) { flowOf(emptyList()) }.hasRecordsToday.value)
        assertEquals(true, viewModel(ZoneId.of("Asia/Shanghai")) { flowOf(listOf("2026-10-23")) }.hasRecordsToday.value)
        assertEquals(
            false,
            viewModel(ZoneId.of("Asia/Shanghai")) { flowOf(listOf("2026-10-20", "2026-10-22")) }.hasRecordsToday.value,
        )
    }

    @Test
    fun hasRecordsTodayIsNullUntilFirstRead() {
        // A flow that never emits keeps the shell from claiming an empty day.
        assertEquals(null, viewModel(ZoneId.of("Asia/Shanghai")) { emptyFlow() }.hasRecordsToday.value)
    }
}
