package com.personal.lifelog.feature.sleep

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.domain.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One focused real navigation pass; synthetic data only, isolated from personal installs. */
@RunWith(AndroidJUnit4::class)
class SleepHistoryAnalysisDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val container get() = app.container
    private fun waitText(value: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "sleep-analysis/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val config = app.resources.configuration
        File(file.parentFile, "$name.json").writeText(org.json.JSONObject()
            .put("name", name).put("widthPx", image.width).put("heightPx", image.height)
            .put("fontScale", config.fontScale).put("widthDp", config.screenWidthDp)
            .put("timezone", ZoneId.systemDefault().id).put("api", android.os.Build.VERSION.SDK_INT)
            .put("capturedAt", java.time.Instant.now().toString()).toString(2))
        image.recycle()
    }
    private fun select(date: LocalDate) {
        compose.onNodeWithTag("sleep-history-list").performScrollToIndex(0)
        compose.onNodeWithTag("sleep-history-choose").performClick()
        listOf("年" to date.year, "月" to date.monthValue, "日" to date.dayOfMonth).forEach { (unit, n) ->
            compose.onNodeWithTag("record-wheel-$unit").performSemanticsAction(SemanticsActions.SetProgress) { it(n.toFloat()) }
        }
        compose.onNodeWithText("确认").performClick()
    }
    @Test fun todayHistoryChartsPickerAndEditorStayConsistent() {
        assumeTrue(app.packageName.endsWith(".sleepanalysis"))
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        val prior = RecordIds.newId()
        val previous = RecordIds.newId()
        val morning = RecordIds.newId()
        val nap = RecordIds.newId()
        runBlocking {
            assertTrue(container.snapshots.read().sleeps.isEmpty())
            container.settings.setTheme(ThemeMode.LIGHT)
            listOf(
                SleepDraft(RecordIds.newId(), at(today.minusDays(6), 23, 0), zone.id, at(today.minusDays(5), 6, 40), zone.id),
                SleepDraft(RecordIds.newId(), at(today.minusDays(3), 1, 0), zone.id, at(today.minusDays(3), 7, 30), zone.id),
                SleepDraft(previous, at(today.minusDays(1), 2, 0), zone.id, at(today.minusDays(1), 6, 0), zone.id),
                SleepDraft(prior, at(today.minusDays(1), 22, 15), zone.id, at(today, 0, 15), zone.id),
                SleepDraft(morning, at(today, 0, 40), zone.id, at(today, 6, 25), zone.id),
                SleepDraft(nap, at(today, 14, 10), zone.id, at(today, 15, 0), zone.id),
            ).forEach { assertTrue(container.records.saveSleep(it) is DataResult.Success) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitText("准备睡了")
            compose.onNodeWithTag("sleep-summary-history").performClick()
            waitText("当天作息记录")
            compose.onNodeWithTag("sleep-record-$morning").assertExists()
            compose.onNodeWithTag("sleep-record-$nap").assertExists()
            compose.onNodeWithTag("sleep-record-$previous").assertDoesNotExist()
            screenshot("light-today-records")
            compose.onNodeWithTag("sleep-history-list").performScrollToKey("day-analysis")
            waitText("已记录6小时50分")
            screenshot("light-day-analysis")
            compose.onNodeWithTag("sleep-history-list").performScrollToKey("week-analysis")
            waitText("近七天作息分析")
            screenshot("light-seven-days")
            select(today.minusDays(1))
            waitText("卧床时长：4小时0分")
            compose.onNodeWithTag("sleep-record-$previous").assertExists()
            compose.onNodeWithTag("sleep-record-$morning").assertDoesNotExist()
            screenshot("light-previous-day")
            scenario.recreate()
            waitText("卧床时长：4小时0分")
            compose.onNodeWithText("补录作息").performClick()
            waitText("就寝日期时间")
            compose.onNodeWithText("就寝日期时间").performClick()
            compose.onNodeWithTag("record-time-picker").assertExists()
            listOf("年" to today.minusDays(1).year, "月" to today.minusDays(1).monthValue, "日" to today.minusDays(1).dayOfMonth).forEach { (unit, value) ->
                assertEquals(value.toFloat(), compose.onNodeWithTag("sleep-wheel-$unit").fetchSemanticsNode()
                    .config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
            }
            // Cancel the unchanged draft, preserving both the selected date and real records.
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            waitText("卧床时长：4小时0分")
            compose.onNodeWithText("回到今天").performClick()
            waitText("卧床时长：5小时45分")
            compose.onNodeWithTag("sleep-record-$nap").performClick()
            waitText("就寝日期时间")
            compose.onNodeWithText("删除作息").performScrollTo().performClick()
            compose.onNodeWithText("确认").performClick()
            waitText("当天作息记录")
            compose.onNodeWithTag("sleep-history-list").performScrollToKey("day-analysis")
            waitText("已记录6小时0分")
            runBlocking { container.settings.setTheme(ThemeMode.DARK) }
            compose.waitForIdle()
            screenshot("dark-day-analysis")
            compose.onNodeWithTag("sleep-history-list").performScrollToKey("week-analysis")
            screenshot("dark-seven-days")
            select(today.minusDays(12))
            waitText("这一天没有作息记录")
            screenshot("empty-day")
            compose.onNodeWithText("回到今天").performClick()
            // A fresh visit defaults to Today after the date selection's route has been popped.
            compose.onNodeWithContentDescription("返回").performClick()
            waitText("准备睡了")
            compose.onNodeWithTag("sleep-summary-history").performClick()
            waitText("当天作息记录")
            compose.onNodeWithTag("sleep-record-$morning").assertExists()
            assertEquals(5, runBlocking { container.snapshots.read().sleeps.size })
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun font(value: String) = automation.executeShellCommand("settings put system font_scale $value").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        font("1.5")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitText("准备睡了")
                compose.onNodeWithTag("sleep-summary-history").performClick()
                waitText("当天作息记录")
                assertEquals(1.5f, app.resources.configuration.fontScale, 0.01f)
                screenshot("large-today")
                compose.onNodeWithTag("sleep-history-list").performScrollToKey("day-analysis")
                screenshot("large-day-analysis")
                compose.onNodeWithTag("sleep-history-list").performScrollToKey("week-analysis")
                screenshot("large-seven-days")
            }
        } finally { font("1.0") }
    }
}
