package com.personal.lifelog.feature.home

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.domain.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity + Room. Synthetic fixtures are restricted to the isolated T19 package. */
@RunWith(AndroidJUnit4::class)
class HomeSleepDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val container get() = app.container
    private val zone get() = ZoneId.systemDefault()
    private val date get() = LocalDate.now(zone)
    private fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun waitText(text: String) = compose.waitUntil(20_000) {
        try { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }
    }
    private fun click(text: String) {
        waitText(text)
        val node = compose.onNodeWithText(text)
        try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }
        node.performClick()
    }
    private fun top() { compose.onNodeWithTag("home-timeline").performScrollToIndex(0); compose.waitForIdle() }
    private fun back() { compose.onNodeWithContentDescription("返回").performClick(); compose.waitForIdle() }
    private fun target() = runBlocking { container.settings.settings().first().targetSleepMinutes }
    private fun summary() = runBlocking { (container.sleepSummary.observe(date).first() as DataResult.Success).value }
    private fun setGoal(hours: String, minutes: String) {
        compose.onNodeWithTag("sleep-target-edit").performScrollTo().performClick()
        compose.onNodeWithTag("sleep-goal-hours").performTextReplacement(hours)
        compose.onNodeWithTag("sleep-goal-minutes").performTextReplacement(minutes)
        closeSoftKeyboard()
    }
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(700)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "t19/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val config = app.resources.configuration
        val metadata = org.json.JSONObject().put("name", name).put("widthPx", bitmap.width).put("heightPx", bitmap.height)
            .put("widthDp", config.screenWidthDp).put("densityDpi", config.densityDpi).put("fontScale", config.fontScale)
            .put("timezone", zone.id).put("locale", config.locales[0].toLanguageTag()).put("api", android.os.Build.VERSION.SDK_INT)
            .put("appTheme", runBlocking { container.settings.settings().first().themeMode.name })
            .put("targetMinutes", target()).put("recordedDurationMs", summary().recordedDurationMs)
            .put("completedSegments", summary().completedSegmentCount).put("activeSleep", summary().hasActiveSleep)
            .put("capturedAt", java.time.Instant.now().toString())
        File(file.parentFile, "$name.json").writeText(metadata.toString(2))
        bitmap.recycle()
    }

    @Test fun realHomeGoalsHistoryRestoreAndLargeFont() {
        assumeTrue("Synthetic fixtures require .t19check", app.packageName.endsWith(".t19check"))
        assertTrue(runBlocking { container.snapshots.read().sleeps.isEmpty() })
        runBlocking { assertTrue(container.settings.setTheme(ThemeMode.LIGHT) is DataResult.Success) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitText("设置期望睡眠")
            waitText("暂无已完成记录")
            capture("light-unset")
            setGoal("7", "30")
            scenario.recreate()
            waitText("期望睡眠时长")
            compose.onNodeWithTag("sleep-goal-hours").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("7")))
            compose.onNodeWithTag("sleep-goal-minutes").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("30")))
            pressBack()
            assertNull(target())
            setGoal("8", "0")
            compose.onNodeWithTag("sleep-goal-save").performClick()
            waitText("期望睡眠：8小时")
            assertEquals(480, target())
            capture("light-empty")

            val activeId = RecordIds.newId()
            runBlocking { assertTrue(container.records.startSleep(activeId, at(date.minusDays(1), 22, 0), zone.id) is DataResult.Success) }
            waitText("作息进行中")
            assertNull(summary().recordedDurationMs)
            capture("light-active-only")
            runBlocking { assertTrue(container.records.deleteSleep(activeId) is DataResult.Success) }
            val nightId = RecordIds.newId()
            runBlocking { assertTrue(container.records.saveSleep(SleepDraft(nightId, at(date.minusDays(1), 23, 40), zone.id,
                at(date, 7, 0), zone.id)) is DataResult.Success) }
            waitText("7小时20分")
            assertEquals(26_400_000L, summary().recordedDurationMs)
            assertEquals(440f / 480f, sleepProgress(summary().recordedDurationMs, target())!!, 0.000001f)
            top(); capture("light-normal")
            runBlocking { container.settings.setTheme(ThemeMode.DARK) }
            compose.waitForIdle()
            capture("dark-normal")
            compose.onNodeWithTag("sleep-target-edit").performClick()
            capture("dark-goal-dialog")
            click("取消")
            scenario.recreate()
            waitText("期望睡眠：8小时")
            waitText("7小时20分")

            val backup = File(app.cacheDir, "t19-synthetic.zip")
            runBlocking { assertTrue(container.backupExporter.export(object : BackupDestination {
                override fun open() = backup.outputStream()
                override fun deleteIncomplete() { backup.delete() }
            }) is BackupResult.Success) }
            // Actual SQLite write failure; only the isolated synthetic database is affected.
            container.database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER t19_goal_insert BEFORE INSERT ON AppSettings BEGIN SELECT RAISE(ABORT, 'synthetic goal failure'); END")
            container.database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER t19_goal_update BEFORE UPDATE ON AppSettings BEGIN SELECT RAISE(ABORT, 'synthetic goal failure'); END")
            setGoal("7", "30")
            compose.onNodeWithTag("sleep-goal-save").performClick()
            waitText("保存失败，输入已保留，请重试。")
            assertEquals(480, target())
            compose.onNodeWithTag("sleep-goal-hours").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("7")))
            capture("goal-save-failure")
            container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER t19_goal_insert")
            container.database.openHelper.writableDatabase.execSQL("DROP TRIGGER t19_goal_update")
            compose.onNodeWithTag("sleep-goal-save").performClick()
            waitText("期望睡眠：7小时30分")
            assertEquals(450, target())

            runBlocking { container.settings.setTargetSleepMinutes(480); container.settings.setTheme(ThemeMode.LIGHT) }
            val napId = RecordIds.newId()
            runBlocking { assertTrue(container.records.saveSleep(SleepDraft(napId, at(date, 13, 0), zone.id,
                at(date, 14, 40), zone.id)) is DataResult.Success) }
            waitText("9小时")
            assertEquals(2, summary().completedSegmentCount)
            assertEquals(32_400_000L, summary().recordedDurationMs)
            assertEquals(1f, sleepProgress(summary().recordedDurationMs, target())!!)
            top(); capture("light-over-target")
            runBlocking { assertTrue(container.records.startSleep(activeId, at(date, 19, 0), zone.id) is DataResult.Success) }
            waitText("作息进行中")
            assertEquals(32_400_000L, summary().recordedDurationMs)
            top(); capture("light-completed-active")
            runBlocking { container.records.deleteSleep(activeId); container.records.deleteSleep(napId) }
            waitText("7小时20分")

            compose.onNodeWithTag("sleep-summary-history").performScrollTo().performClick()
            waitText("补录作息")
            compose.onAllNodes(hasText("就寝", substring = true) and hasClickAction()).onFirst().performClick()
            waitText("就寝日期时间")
            click("就寝日期时间")
            waitText("取消")
            capture("native-sleep-wheel")
            click("取消")
            click("保存作息")
            waitText("补录作息")
            click("补录作息")
            waitText("就寝日期时间")
            compose.onNode(isToggleable()).performClick() // Supplement an open interval after the morning record.
            click("保存作息")
            waitText("补录作息")
            back()
            waitText("作息进行中")
            assertTrue(summary().hasActiveSleep)
            compose.onNodeWithTag("home-timeline").performScrollToIndex(0)
            compose.onNodeWithTag("sleep-summary-history").performClick()
            waitText("补录作息")
            compose.onNodeWithText("编辑作息").performScrollTo().performClick()
            click("删除作息"); click("确认")
            waitText("补录作息");back()
            waitText("准备睡了")
            assertFalse(summary().hasActiveSleep)
            assertEquals(26_400_000L, summary().recordedDurationMs)

            compose.onNodeWithTag("home-workout-history").performScrollTo().assertIsDisplayed().performClick()
            waitText("记录训练")
            capture("workout-history-empty")
            click("记录训练")
            waitText("保存训练")
            back(); back()
            waitText("今日睡眠")
            click("记运动"); waitText("保存训练"); back()
            click("记饮食"); waitText("吃了什么（选填）"); back()
            click("准备睡了")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty() }
            pressBack()
            waitText("起床了")
            val started = runBlocking { container.records.activeSleep().first()!! }
            compose.onNodeWithTag("home-timeline").performScrollToIndex(0)
            compose.onNodeWithTag("sleep-summary-history").performClick()
            waitText("补录作息")
            click("撤销上次作息操作"); click("取消")
            assertEquals(started.id, runBlocking { container.records.activeSleep().first()!!.id })
            click("撤销上次作息操作"); click("确认")
            compose.waitUntil(20_000) { runBlocking { container.records.activeSleep().first() == null } }
            back()
            waitText("准备睡了")
            assertFalse(summary().hasActiveSleep)

            // Integration: the bedtime wake action must refresh the real T19 summary.
            click("准备睡了")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty() }
            val bedtimeRecord = runBlocking { container.records.activeSleep().first()!! }
            capture("integrated-bedtime-wake-screen")
            click("起床了")
            waitText("今日睡眠")
            waitText("准备睡了")
            assertNull(runBlocking { container.records.activeSleep().first() })
            val finished = runBlocking { container.records.sleep(bedtimeRecord.id)!! }
            assertNotNull(finished.endedAtUtcMs)
            assertEquals(2, summary().completedSegmentCount)
            assertEquals(26_400_000L + finished.endedAtUtcMs!! - finished.startedAtUtcMs, summary().recordedDurationMs)
            top(); capture("integrated-bedtime-wake-home")

            // Restore through the existing confirmation flow; the same HomeViewModel refreshes.
            compose.onNodeWithContentDescription("打开设置").performClick()
            container.backupRestore.selectBackup(BackupSource { backup.inputStream() })
            waitText("确认恢复此备份？")
            click("覆盖并恢复")
            waitText("恢复完成")
            back()
            waitText("期望睡眠：8小时")
            waitText("7小时20分")
            assertEquals(480, target())
            top(); capture("restored-home")
        }
        shell("settings put system font_scale 1.5")
        android.os.SystemClock.sleep(700)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitText("期望睡眠：8小时")
                assertEquals(1.5f, app.resources.configuration.fontScale, 0.01f)
                top(); capture("large-home")
                compose.onNodeWithTag("sleep-target-edit").performScrollTo().assertIsDisplayed().performClick()
                capture("large-goal-dialog")
                compose.onNodeWithTag("sleep-goal-minutes").performTextReplacement("30")
                compose.onNodeWithTag("sleep-goal-save").assertIsDisplayed()
                capture("large-goal-keyboard")
                closeSoftKeyboard(); click("取消")
                assertEquals(480, target())
                compose.onNodeWithTag("home-workout-history").performScrollTo().assertIsDisplayed()
                capture("large-workout-entry")
                compose.onNodeWithTag("home-workout-history").performClick()
                waitText("记录训练"); back()
                compose.onNodeWithText("记饮食").assertIsDisplayed()
                compose.onNodeWithText("记运动").assertIsDisplayed()
                compose.onNodeWithText("准备睡了").assertIsDisplayed()
            }
        } finally { shell("settings put system font_scale 1.0") }
    }
}
