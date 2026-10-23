package com.personal.lifelog.feature.review

import androidx.lifecycle.SavedStateHandle
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.repository.TimelineRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUpMain() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun repository(
        dates: Flow<List<String>> = flowOf(emptyList()),
        meals: (String) -> Flow<List<MealRecord>> = { flowOf(emptyList()) },
    ) = TimelineRepository(
        datesWithRecordsFlow = { dates },
        mealsOn = meals,
        sleepsOn = { flowOf(emptyList()) },
        workoutsOn = { flowOf(emptyList()) },
    )

    @Test
    fun defaultDateIsPersistedTodayAndSelectionSurvivesRecreation() {
        val saved = SavedStateHandle()
        val viewModel = ReviewViewModel(repository(), saved, clock) { zone }
        assertEquals(LocalDate.of(2026, 10, 23), viewModel.date.value)

        viewModel.selectDate(LocalDate.of(2026, 10, 20))
        assertEquals("2026-10-20", saved.get<String>(ReviewViewModel.KEY_DATE))

        val restored = ReviewViewModel(repository(), SavedStateHandle(mapOf(
            ReviewViewModel.KEY_DATE to "2026-10-20",
        )), clock) { zone }
        assertEquals(LocalDate.of(2026, 10, 20), restored.date.value)
    }

    @Test
    fun previousNextAndTodayQueryTheSelectedPersistedDate() {
        val requested = mutableListOf<String>()
        val viewModel = ReviewViewModel(
            repository(meals = { date -> requested += date; flowOf(emptyList()) }),
            SavedStateHandle(),
            clock,
        ) { zone }
        assertEquals(listOf("2026-10-23"), requested.distinct())

        viewModel.previousDay()
        assertEquals(LocalDate.of(2026, 10, 22), viewModel.date.value)
        assertTrue("2026-10-22" in requested)

        viewModel.nextDay()
        assertEquals(LocalDate.of(2026, 10, 23), viewModel.date.value)

        viewModel.selectDate(LocalDate.of(2026, 10, 1))
        viewModel.goToToday()
        assertEquals(LocalDate.of(2026, 10, 23), viewModel.date.value)
    }

    @Test
    fun recordedDatesComeFromTheSavedRecordIndex() {
        val viewModel = ReviewViewModel(
            repository(dates = flowOf(listOf("2026-10-23", "2026-10-20"))),
            SavedStateHandle(),
            clock,
        ) { zone }
        assertEquals(listOf("2026-10-23", "2026-10-20"), viewModel.recordedDates.value.dates)
    }

    @Test
    fun readFailureIsReportedWithoutClaimingAnEmptyDay() {
        val viewModel = ReviewViewModel(
            repository(meals = { flow { throw IllegalStateException("synthetic read failure") } }),
            SavedStateHandle(),
            clock,
        ) { zone }
        val state = viewModel.day.value
        assertFalse(state.loading)
        assertTrue(state.failed)
        assertTrue(state.entries.isEmpty())
    }
}
