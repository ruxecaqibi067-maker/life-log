package com.personal.lifelog.feature.home

import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.TimelineEntry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun timelineRepository(
        meals: (String) -> Flow<List<MealRecord>> = { flowOf(emptyList()) },
    ) = TimelineRepository(
        datesWithRecordsFlow = { flowOf(emptyList()) },
        mealsOn = meals,
        sleepsOn = { flowOf(emptyList()) },
        workoutsOn = { flowOf(emptyList()) },
    )

    private fun viewModel(
        zone: ZoneId,
        repository: TimelineRepository = timelineRepository(),
    ) = HomeViewModel(clock, { zone }, repository)

    @Test
    fun todayUsesInjectedZoneNotSystemDefault() {
        assertEquals(LocalDate.of(2026, 10, 23), viewModel(ZoneId.of("Asia/Shanghai")).today.value)
        assertEquals(LocalDate.of(2026, 10, 22), viewModel(ZoneId.of("UTC")).today.value)
    }

    @Test
    fun todayUsesInjectedClockNotWallClock() {
        val otherClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val viewModel = HomeViewModel(
            otherClock,
            { ZoneId.of("Asia/Shanghai") },
            timelineRepository(),
        )
        assertEquals(LocalDate.of(2020, 1, 1), viewModel.today.value)
    }

    @Test
    fun timelineQueriesThePersistedLocalDateAndExposesSavedEntries() {
        val meal = MealRecord(
            id = "7e9d4c8b-2f1a-4e6b-9c3d-0a1b2c3d4e5f",
            occurredAtUtcMs = 1_792_670_400_000,
            zoneId = "Asia/Shanghai",
            localDate = "2026-10-23",
            text = "牛肉面",
            satiety = Satiety.SEVEN,
            photoId = null,
            createdAtUtcMs = 1,
            updatedAtUtcMs = 1,
        )
        var requestedDate: String? = null
        val viewModel = viewModel(
            ZoneId.of("Asia/Shanghai"),
            timelineRepository(meals = { date ->
                requestedDate = date
                flowOf(listOf(meal))
            }),
        )

        assertEquals("2026-10-23", requestedDate)
        val state = viewModel.timeline.value
        assertFalse(state.loading)
        assertFalse(state.failed)
        assertEquals(LocalDate.of(2026, 10, 23), state.date)
        val entry = state.entries.single() as TimelineEntry.Meal
        assertEquals("牛肉面", entry.text)
        assertEquals(Satiety.SEVEN, entry.satiety)
    }

    @Test
    fun timelineIsLoadingBeforeTheFirstRead() {
        // A flow that never emits keeps the screen from claiming an empty day.
        val viewModel = viewModel(
            ZoneId.of("Asia/Shanghai"),
            timelineRepository(meals = { emptyFlow() }),
        )
        assertTrue(viewModel.timeline.value.loading)
        assertFalse(viewModel.timeline.value.failed)
        assertTrue(viewModel.timeline.value.entries.isEmpty())
    }

    @Test
    fun failedReadIsReportedAsFailureNotAnEmptyDay() {
        val viewModel = viewModel(
            ZoneId.of("Asia/Shanghai"),
            timelineRepository(meals = { flow { throw IllegalStateException("synthetic read failure") } }),
        )
        val state = viewModel.timeline.value
        assertFalse(state.loading)
        assertTrue(state.failed)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun localDateUpdatesRollOverAtInjectedLocalMidnight() = runTest {
        val mutableClock = MutableClock(Instant.parse("2026-10-22T15:59:30Z")) // 23:59:30 Shanghai
        val values = mutableListOf<LocalDate>()
        val job = launch {
            localDateUpdates(mutableClock, { ZoneId.of("Asia/Shanghai") }).collect { values += it }
        }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 10, 22)), values)

        mutableClock.now = Instant.parse("2026-10-22T16:00:01Z") // 00:00:01 on Oct 23
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 23), values.last())
        job.cancel()
    }

    private class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
}
