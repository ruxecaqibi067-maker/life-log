package com.personal.lifelog.feature.sleep

import android.content.Context
import android.graphics.Bitmap
import android.widget.NumberPicker
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic Room fixture, actual Android NumberPickers and device screenshots. */
@RunWith(AndroidJUnit4::class)
class SleepPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val restoration = StateRestorationTester(compose)
    private lateinit var db: AppDatabase
    private lateinit var repository: RecordRepository
    private val zone get() = ZoneId.systemDefault()
    private val clock = Clock.systemDefaultZone()
    @Before fun setup() {
        // A sleeping emulator does not attach Compose roots, even when the test Activity launches.
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        repository = RecordRepository(db, WriteCoordinator(), clock) { zone }
    }
    @After fun close() { db.close() }

    private fun open(id: String? = null, fontScale: Float = 1f): SleepEditorViewModel {
        val vm = SleepEditorViewModel(repository, clock, { zone }, id, SavedStateHandle())
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                LifeLogTheme(ThemeMode.LIGHT) { SleepEditorScreen(vm, id != null, {}) }
            }
        }
        compose.waitForIdle()
        waitText("就寝日期时间")
        return vm
    }
    private fun waitText(value: String) = compose.waitUntil(10_000) {
        try { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    }
    private fun adjust(unit: String, value: Int) {
        compose.onNodeWithTag("sleep-wheel-$unit").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }
    private fun pick(label: String, local: String) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        val value = LocalDateTime.parse(local)
        adjust("年", value.year); adjust("月", value.monthValue); adjust("日", value.dayOfMonth)
        adjust("时", value.hour); adjust("分", value.minute)
        compose.onNodeWithText("确认").performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idle does not include the platform Dialog window's fade-in animation.
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(700)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "sleep-wheel/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        // Gradle removes the isolated test package after execution; keep synthetic evidence outside it.
        shell("cp ${file.absolutePath} /data/local/tmp/sleep-wheel-$name.png")
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> assertTrue(input.readBytes().isEmpty()) }
        }
    }

    @Test fun oldInstantsSurviveNativeWheelCancelAndUnchangedConfirmAndSave() {
        val start = LocalDateTime.parse("2026-10-31T23:50:19.123").atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.parse("2026-11-01T07:10:42.987").atZone(zone).toInstant().toEpochMilli()
        val old = runBlocking { (repository.saveSleep(SleepDraft(RecordIds.newId(), start, zone.id, end, zone.id)) as DataResult.Success).value }
        val vm = open(old.id)
        val form = vm.state.value.form
        screenshot("editor")
        compose.onNodeWithText("UTC偏移", substring = true).assertDoesNotExist()
        compose.onNodeWithText("时区", substring = true).assertDoesNotExist()
        compose.onNodeWithText("就寝日期时间").performClick()
        screenshot("wheel")
        // Actual touch gesture on Android's wheel, in addition to numeric accessibility actions.
        onView(allOf(isAssignableFrom(NumberPicker::class.java), withContentDescription("分"))).perform(swipeUp())
        compose.onNodeWithText("取消").performClick()
        assertEquals(form, vm.state.value.form)
        compose.onNodeWithText("就寝日期时间").performClick()
        compose.onNodeWithText("确认").performClick()
        assertEquals(form, vm.state.value.form)
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.done }
        val saved = runBlocking { repository.sleep(old.id)!! }
        assertEquals(start, saved.startedAtUtcMs); assertEquals(end, saved.endedAtUtcMs)
        assertEquals(old.startZoneId, saved.startZoneId); assertEquals(old.archiveLocalDate, saved.archiveLocalDate)
    }

    @Test fun crossNightSupplementAndUnfinishedSwitchKeepValidationAndPersistence() {
        val vm = open()
        pick("就寝日期时间", "2026-10-31T23:50")
        pick("起床日期时间", "2026-11-01T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.done }
        val saved = runBlocking { db.records().allSleeps().single() }
        assertEquals("2026-11-01", saved.archiveLocalDate)
        assertEquals(26_400_000L, saved.endedAtUtcMs!! - saved.startedAtUtcMs)
    }

    @Test fun largeFontWheelClampsMonthAndRestoresItsDraftAndCanCancel() {
        val vm = open(fontScale = 2f)
        val original = vm.state.value.form
        compose.onNodeWithText("就寝日期时间").performScrollTo().performClick()
        adjust("年", 2026); adjust("月", 1); adjust("日", 31); adjust("月", 2)
        compose.onNodeWithText("2026年2月28日", substring = true).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2026年2月28日", substring = true).assertExists()
        screenshot("wheel-large-font")
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertEquals(original, vm.state.value.form)
        screenshot("editor-large-font")
        compose.onNodeWithText("保存作息").performScrollTo().assertIsDisplayed()
    }

    @Test fun unfinishedEditorHidesWakeTimeAndKeepsAnOpenRow() {
        val old = runBlocking { (repository.startSleep(RecordIds.newId()) as DataResult.Success).value }
        val vm = open(old.id)
        compose.onNodeWithText("起床日期时间").assertDoesNotExist()
        screenshot("unfinished")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.done }
        val saved = runBlocking { repository.sleep(old.id)!! }
        assertNull(saved.endedAtUtcMs); assertEquals(old.startedAtUtcMs, saved.startedAtUtcMs)
        assertEquals(1, saved.activeSlot)
    }
}
