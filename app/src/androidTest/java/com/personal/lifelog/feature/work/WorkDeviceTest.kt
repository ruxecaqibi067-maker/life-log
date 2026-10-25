package com.personal.lifelog.feature.work

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
import com.personal.lifelog.feature.home.durationProgress
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** All fixture writes and captures are restricted to this task's isolated app. */
@RunWith(AndroidJUnit4::class)
class WorkDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val c get() = app.container
    private val zone get() = ZoneId.systemDefault()
    private val date get() = LocalDate.now(zone)
    private fun at(day: LocalDate, h: Int, m: Int) = day.atTime(h,m).atZone(zone).toInstant().toEpochMilli()
    private fun <T> DataResult<T>.saved() = (this as DataResult.Success).value
    private fun waitText(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) { waitText(text); val node=compose.onNodeWithText(text); try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }; node.performClick() }
    private fun tag(tag: String) {
        if (tag != "work-start-finish") compose.onNodeWithTag("home-timeline").performScrollToNode(hasTestTag(tag))
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun back() { compose.onNodeWithContentDescription("返回").performClick(); compose.waitForIdle() }
    private fun summary() = runBlocking { (c.workSummary.observe(date).first() as DataResult.Success).value }
    private fun capture(name: String) {
        compose.waitForIdle(); InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file=File(app.filesDir,"t21/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        val config=app.resources.configuration
        val metadata=org.json.JSONObject().put("name",name).put("widthPx",bitmap.width).put("heightPx",bitmap.height)
            .put("widthDp",config.screenWidthDp).put("fontScale",config.fontScale).put("densityDpi",config.densityDpi)
            .put("zone",zone.id).put("api",android.os.Build.VERSION.SDK_INT).put("capturedAt",Instant.now().toString())
        File(file.parentFile,"$name.json").writeText(metadata.toString(2));bitmap.recycle()
    }
    private fun shell(command: String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun historyRow(id: String) {
        compose.onNodeWithTag("work-history").performScrollToNode(hasTestTag("work-record-$id"))
        compose.onNode(hasText("查看 / 编辑") and hasAnyAncestor(hasTestTag("work-record-$id"))).performClick()
    }
    private fun goal(hours: String, minutes: String) {
        tag("work-target-edit")
        compose.onNodeWithTag("work-goal-hours").performTextReplacement(hours)
        compose.onNodeWithTag("work-goal-minutes").performTextReplacement(minutes)
        closeSoftKeyboard()
    }
    @Test fun goalCancelInvalidSaveAndClearKeepSleepTargetIndependent() {
        assertTrue((app.packageName == "com.personal.lifelog.t21check" || app.packageName == "com.personal.lifelog.workoutcheck"))
        runBlocking { c.settings.setTargetSleepMinutes(420);c.settings.setTargetWorkMinutes(null) }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("今日睡眠")
            goal("5","0");click("取消");assertNull(summary().targetWorkMinutes)
            goal("0","0");compose.onNodeWithTag("work-goal-save").performClick()
            waitText("请填写1分钟至24小时；分钟为0–59，24小时只能配0分钟。")
            assertNull(summary().targetWorkMinutes);capture("work-goal-invalid")
            compose.onNodeWithTag("work-goal-hours").performTextReplacement("8");closeSoftKeyboard()
            compose.onNodeWithTag("work-goal-save").performClick()
            compose.waitUntil(20_000) { summary().targetWorkMinutes==480 }
            assertEquals(420,runBlocking { c.settings.targetSleepMinutes().first() })
            assertEquals(0.625f,durationProgress(18_000_000,480)!!,0.000001f)
            tag("work-target-edit");click("清除工作目标")
            compose.waitUntil(20_000) { summary().targetWorkMinutes==null }
            assertEquals(420,runBlocking { c.settings.targetSleepMinutes().first() })
        }
    }

    @Test fun longFinishCancelFailureRetryAndSafeUndoUsePersistedState() {
        assertTrue((app.packageName == "com.personal.lifelog.t21check" || app.packageName == "com.personal.lifelog.workoutcheck"))
        runBlocking {
            for (row in c.snapshots.read().workRecords) c.work.deleteWork(row)
            c.settings.setTheme(ThemeMode.LIGHT)
            c.work.startWork(RecordIds.newId(), c.clock.millis()-48*3_600_000, zone.id).saved()
        }
        val old=runBlocking { c.work.activeWork().first()!! }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitText("今日睡眠");tag("work-start-finish")
            waitText("请检查工作时段");capture("work-long-confirmation")
            click("取消");assertEquals(old,runBlocking { c.work.work(old.id) })
            c.database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_work_finish BEFORE UPDATE ON WorkRecord BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            tag("work-start-finish");waitText("请检查工作时段");click("确认")
            waitText("无法读写工作记录，请重试；输入仍保留。")
            assertEquals(old,runBlocking { c.work.work(old.id) })
            compose.onNodeWithText("无法读写工作记录，请重试；输入仍保留。").performScrollTo().assertIsDisplayed();capture("work-failed-finish")
            c.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_work_finish")
            tag("work-start-finish");waitText("请检查工作时段")
            val before=c.clock.millis();click("确认")
            compose.waitUntil(20_000) { runBlocking { c.work.work(old.id)!!.endedAtUtcMs!=null } }
            assertTrue(runBlocking { c.work.work(old.id)!!.endedAtUtcMs!! } <= before)
            tag("work-undo");click("取消")
            assertNotNull(runBlocking { c.work.work(old.id)!!.endedAtUtcMs })
            tag("work-undo");click("确认")
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()!=null } }
            assertEquals(old.startedAtUtcMs,runBlocking { c.work.activeWork().first()!!.startedAtUtcMs })
            compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
        }
    }

    @Test fun realWorkHomeHistoryGoalsReviewRestoreAndLargeFont() {
        assertTrue("Only the explicit .t21check/.workoutcheck isolated packages may receive synthetic records",(app.packageName == "com.personal.lifelog.t21check" || app.packageName == "com.personal.lifelog.workoutcheck"))
        runBlocking {
            for (r in c.snapshots.read().workRecords) c.work.deleteWork(r)
            for (r in c.snapshots.read().sleeps) c.records.deleteSleep(r)
            c.settings.setTheme(ThemeMode.LIGHT);c.settings.setTargetWorkMinutes(null);c.settings.setTargetSleepMinutes(480)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitText("今日睡眠")
            tag("work-start-finish")
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()!=null } }
            compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
            compose.onNodeWithText("记饮食").assertIsDisplayed()
            val started=runBlocking { c.work.activeWork().first()!! }
            assertNull(summary().recordedDurationMs)
            capture("light-active-unset")
            scenario.recreate()
            tag("work-start-finish") // End persisted row after Activity recreation.
            compose.waitUntil(20_000) { runBlocking { c.work.work(started.id)!!.endedAtUtcMs!=null } }
            runBlocking { c.work.deleteWork(started.id) }
            val night=runBlocking { c.records.saveSleep(SleepDraft(RecordIds.newId(),at(date.minusDays(1),23,40),zone.id,at(date,7,0),zone.id)).saved() }
            val morning=runBlocking { c.work.saveWork(WorkDraft(RecordIds.newId(),at(date,9,0)+123,zone.id,at(date,12,0)+123,zone.id)).saved() }
            runBlocking { c.work.saveWork(WorkDraft(RecordIds.newId(),at(date,13,0)+789,zone.id,at(date,15,0)+789,zone.id)).saved() }
            goal("7","30");scenario.recreate();waitText("期望工作时长")
            compose.onNodeWithTag("work-goal-hours").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("7")))
            click("取消");assertNull(summary().targetWorkMinutes)
            goal("8","0");compose.onNodeWithTag("work-goal-save").performClick()
            compose.waitUntil(20_000) { summary().targetWorkMinutes==480 }
            assertEquals(18_000_000L,summary().recordedDurationMs);assertEquals(0.625f,durationProgress(summary().recordedDurationMs,480)!!,0.00001f)
            compose.onNodeWithTag("home-timeline").performScrollToIndex(0);capture("light-dual-top")
            compose.onNodeWithTag("home-timeline").performScrollToKey("duration-summaries");capture("light-work-five-of-eight")
            runBlocking { c.settings.setTheme(ThemeMode.DARK) };compose.waitForIdle();capture("dark-work-five-of-eight")
            compose.onNodeWithTag("home-timeline").performScrollToIndex(0);capture("dark-dual-top")
            compose.onNodeWithTag("home-timeline").performScrollToKey("duration-summaries")
            goal("8","0");capture("dark-work-goal");click("取消")
            runBlocking { c.settings.setTargetWorkMinutes(240) }
            compose.waitUntil(20_000) { summary().targetWorkMinutes==240 }
            assertEquals(1f,durationProgress(summary().recordedDurationMs,240)!!)
            compose.onNodeWithTag("home-timeline").performScrollToKey("duration-summaries");capture("dark-over-goal")
            runBlocking { c.settings.setTargetWorkMinutes(480);c.settings.setTheme(ThemeMode.LIGHT) }
            tag("work-summary-history");waitText("补录工作");capture("work-history-multiple")
            historyRow(morning.id);waitText("开始日期时间");click("开始日期时间");capture("work-native-picker");click("取消")
            click("保存工作");waitText("补录工作")
            assertEquals(morning.startedAtUtcMs,runBlocking { c.work.work(morning.id)!!.startedAtUtcMs })
            historyRow(morning.id);click("删除工作");click("取消")
            assertNotNull(runBlocking { c.work.work(morning.id) })
            click("删除工作");click("确认");waitText("补录工作")
            assertNull(runBlocking { c.work.work(morning.id) })
            click("撤销上次工作操作");click("确认")
            compose.waitUntil(20_000) { runBlocking { c.work.work(morning.id)!=null } }
            click("补录工作");waitText("开始日期时间")
            compose.onNode(isToggleable()).performClick();click("保存工作");waitText("补录工作")
            val active=runBlocking { c.work.activeWork().first()!! }
            capture("work-history-active")
            back();waitText("今日睡眠")
            assertEquals(night,runBlocking { c.records.sleep(night.id) })
            tag("work-start-finish");compose.waitUntil(20_000) { runBlocking { c.work.work(active.id)!!.endedAtUtcMs!=null } }
            runBlocking { c.work.deleteWork(active.id) }
            val cross=runBlocking { c.work.saveWork(WorkDraft(RecordIds.newId(),at(date.minusDays(2),23,30),zone.id,at(date.minusDays(1),2,30),zone.id)).saved() }
            compose.onNodeWithText("回顾").performClick();click("有记录的日期")
            compose.onNodeWithTag("recorded-date-${date.minusDays(1)}").performClick()
            waitText("工作 1 段");capture("review-work-only-cross-night")
            compose.onNodeWithText("工作").performClick();waitText("开始日期时间");capture("cross-night-editor");back()
            compose.onNodeWithText("今天").performClick();waitText("今日睡眠")
            // Preserve T06 direct-wake integration: only the sleep shortcut enters black mode.
            click("准备睡了")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty() }
            capture("integrated-sleep-black-wake")
            click("起床了")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("home-timeline").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-timeline").performScrollToIndex(0);waitText("今日睡眠")
            compose.waitUntil(20_000) { runBlocking { !((c.sleepSummary.observe(date).first() as DataResult.Success).value.hasActiveSleep) } }
            assertEquals(18_000_000L,summary().recordedDurationMs)
            assertEquals(2,runBlocking { (c.sleepSummary.observe(date).first() as DataResult.Success).value.completedSegmentCount })
            val backup=File(app.cacheDir,"t21-synthetic.zip")
            runBlocking { assertTrue(c.backupExporter.export(object : BackupDestination { override fun open()=backup.outputStream();override fun deleteIncomplete() { backup.delete() } }) is BackupResult.Success) }
            val before=runBlocking { c.snapshots.read() }
            compose.onNodeWithContentDescription("打开设置").performClick()
            c.backupRestore.selectBackup(BackupSource { backup.inputStream() });waitText("确认恢复此备份？");capture("backup-work-preview")
            compose.onNodeWithText("期望工作：8小时").performScrollTo().assertIsDisplayed();capture("backup-work-preview-goals")
            click("取消");waitText("已取消恢复");assertEquals(before,runBlocking { c.snapshots.read() })
            c.backupRestore.selectBackup(BackupSource { backup.inputStream() });waitText("确认恢复此备份？")
            runBlocking { c.settings.setTargetWorkMinutes(300);c.work.deleteWork(cross.id) }
            click("覆盖并恢复");waitText("恢复完成");capture("backup-work-restored")
            val rollback=File(app.cacheDir,"t21-rollback.zip")
            c.backupRestore.exportRollback(object : BackupDestination {
                override fun open()=rollback.outputStream()
                override fun deleteIncomplete() { rollback.delete() }
            })
            waitText("回滚备份已导出")
            c.backupRestore.selectBackup(BackupSource { rollback.inputStream() });waitText("确认恢复此备份？")
            val rollbackPreview=(c.backupRestore.state.value as com.personal.lifelog.feature.settings.BackupRestoreState.Preview).prepared.preview
            assertEquals(300,rollbackPreview.targetWorkMinutes);assertEquals(2,rollbackPreview.counts["workRecords"])
            compose.onNodeWithText("期望工作：5小时").performScrollTo().assertIsDisplayed();capture("backup-work-rollback-preview")
            click("取消");waitText("已取消恢复");back()
            compose.waitUntil(20_000) { summary().targetWorkMinutes==480 }
            assertNotNull(runBlocking { c.work.work(cross.id) });assertEquals(480,runBlocking { c.settings.targetSleepMinutes().first() })
        }
        shell("settings put system font_scale 1.5");android.os.SystemClock.sleep(600)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitText("今日睡眠");assertEquals(1.5f,app.resources.configuration.fontScale,0.01f)
                capture("large-dual-top")
                compose.onNodeWithTag("work-start-finish").assertIsDisplayed();capture("large-work-actions")
                goal("8","0");capture("large-work-goal")
                shell("settings put secure show_ime_with_hard_keyboard 1")
                compose.onNodeWithTag("work-goal-minutes").performClick().performTextReplacement("30")
                compose.waitUntil(10_000) {
                    var visible = false
                    scenario.onActivity { activity -> visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                    visible || shell("dumpsys input_method").contains("mInputShown=true")
                }
                File(app.filesDir, "t21/keyboard-state.txt").writeText(shell("dumpsys input_method"))
                compose.onNodeWithTag("work-goal-save").assertIsDisplayed();capture("large-work-keyboard")
                closeSoftKeyboard();click("取消")
                tag("work-summary-history");click("补录工作");capture("large-work-editor")
                compose.onNodeWithText("保存工作").performScrollTo().assertIsDisplayed();back();back()
                // Leave a real ongoing work row for the external force-stop/relaunch check.
                tag("work-start-finish");compose.waitUntil(20_000) { summary().hasActiveWork }
                capture("large-work-active")
                compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
            }
        } finally { shell("settings put system font_scale 1.0") }
    }
}
