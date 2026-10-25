package com.personal.lifelog.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.lifelog.R
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.meal.*
import com.personal.lifelog.feature.review.ReviewScreen
import com.personal.lifelog.feature.review.ReviewViewModel
import com.personal.lifelog.ui.components.RecordDatePicker
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.time.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android shared wheels and the actual meal/review entry points, under an isolated APK. */
@RunWith(AndroidJUnit4::class)
class RecordPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val clock = Clock.fixed(Instant.parse("2026-10-26T03:04:05.123Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var photos: PhotoStorage
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { zone }
        workouts = WorkoutRepository(db, writes, clock)
        photos = PhotoStorage(context, db, records, writes, clock)
        runBlocking { db.workouts().allExercises() }
    }
    @After fun close() { models.forEach { it.viewModelScope.cancel() }; db.close() }
    private fun adjust(unit: String, value: Int) {
        compose.onNodeWithTag("record-wheel-$unit").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }

    @Test fun dateOnlyWheelUsesSameDarkLargeFontLayoutAndClampsMonthEnd() {
        val open = mutableStateOf(true)
        var result: LocalDate? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                LifeLogTheme(ThemeMode.DARK) {
                    if (open.value) RecordDatePicker(R.string.picker_date_title, LocalDate.of(2026, 1, 31),
                        { open.value = false }, { result = it; open.value = false })
                }
            }
        }
        adjust("月", 2)
        compose.onNodeWithTag("record-wheel-时").assertDoesNotExist()
        compose.onNodeWithText("确认").assertIsDisplayed().performClick()
        assertEquals(LocalDate.of(2026, 2, 28), result)
    }

    @Test fun mealUsesOnePickerAndCancelOrUnchangedConfirmPreservesExactTime() {
        lateinit var vm: MealEditorViewModel
        compose.runOnUiThread { vm = MealEditorViewModel(null, SavedStateHandle(), records, photos, clock) { zone }; models += vm }
        compose.setContent { LifeLogTheme(ThemeMode.DARK) { MealEditorScreen(vm, {}) } }
        compose.waitUntil(10_000) { !vm.state.value.loading }
        compose.onNodeWithTag("meal_text_input").performTextInput("虚构饮食时间测试")
        val before = vm.state.value
        compose.onNodeWithText("时间").performScrollTo().performClick()
        compose.onNodeWithTag("record-time-picker").assertExists()
        adjust("分", 17)
        compose.onNodeWithText("取消").performClick()
        assertEquals(before.date, vm.state.value.date); assertEquals(before.time, vm.state.value.time)
        compose.onNodeWithText("时间").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        assertEquals(before.time, vm.state.value.time)
        compose.onNodeWithText("时间").performScrollTo().performClick()
        adjust("分", 17)
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithTag("meal_save_button").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.savedSuccessfully }
        assertEquals(Instant.parse("2026-10-26T03:17:00Z").toEpochMilli(), runBlocking { db.records().allMeals().single().occurredAtUtcMs })
    }

    @Test fun reviewDateEntryUsesYearMonthDayWheelsAndCancelKeepsFilter() {
        lateinit var vm: ReviewViewModel
        compose.runOnUiThread {
            vm = ReviewViewModel(TimelineRepository(records, workouts), SavedStateHandle(), clock) { zone }; models += vm
        }
        compose.setContent { LifeLogTheme(ThemeMode.LIGHT) { ReviewScreen(vm, photos, {}, {}, {}, {}) } }
        compose.onNodeWithText("2026年10月26日 星期一").performClick()
        compose.onNodeWithTag("record-time-picker").assertExists()
        adjust("日", 25)
        compose.onNodeWithText("取消").performClick()
        assertEquals(LocalDate.of(2026, 10, 26), vm.date.value)
        compose.onNodeWithText("2026年10月26日 星期一").performClick()
        adjust("日", 25)
        compose.onNodeWithText("确认").performClick()
        assertEquals(LocalDate.of(2026, 10, 25), vm.date.value)
    }
}
