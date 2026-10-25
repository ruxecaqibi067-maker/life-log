package com.personal.lifelog.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.R
import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.settings.BackupRestoreState
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Evidence helper only. Runs the real MainActivity in the isolated baseline package. */
@RunWith(AndroidJUnit4::class)
class VisualFlowDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val container get() = app.container
    private val mealText = "番茄鸡蛋面，今天吃得很舒服"

    private fun waitText(text: String) = compose.waitUntil(20_000) {
        try { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        catch (_: IllegalStateException) { false }
    }
    private fun click(text: String) {
        waitText(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }
    private fun back() {
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Entering immersive mode can briefly reveal transient system bars.
        android.os.SystemClock.sleep(if (name.endsWith("bedtime")) 6_000 else 900)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "after"
        val file = File(app.filesDir, "t17-$phase/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val config = app.resources.configuration
        val info = org.json.JSONObject().put("phase", phase).put("name", name)
            .put("widthPx", bitmap.width).put("heightPx", bitmap.height)
            .put("widthDp", config.screenWidthDp).put("densityDpi", config.densityDpi)
            .put("fontScale", config.fontScale).put("locale", config.locales[0].toLanguageTag())
            .put("timezone", ZoneId.systemDefault().id).put("api", android.os.Build.VERSION.SDK_INT)
            .put("systemNight", config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            .put("appTheme", runBlocking { container.settings.settings().first().themeMode.name })
            .put("capturedAt", java.time.Instant.now().toString())
        File(file.parentFile, "$name.json").writeText(info.toString(2))
        bitmap.recycle()

    }

    @Test fun navigateEditCancelAndCapturePresentation() {
        org.junit.Assume.assumeTrue("Run only in the isolated T17 fixture package", app.packageName.endsWith(".t17baseline"))
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        fun at(day: LocalDate, hour: Int, minute: Int) = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        val marker = File(app.filesDir, "t17-fixture.txt")
        if (!marker.exists()) runBlocking {
            assertTrue(container.records.saveMeal(MealDraft(RecordIds.newId(), at(date, 12, 20), zone.id, mealText, Satiety.SEVEN)) is DataResult.Success)
            assertTrue(container.records.saveSleep(SleepDraft(RecordIds.newId(), at(date.minusDays(1), 23, 40), zone.id, at(date, 7, 10), zone.id)) is DataResult.Success)
            val exercise = container.workouts.exercises().first().first { it.name == "卧推" }
            assertTrue(container.workouts.saveExercise(ExerciseDraft(RecordIds.newId(), "公园轻松拉伸与肩背活动", ExerciseType.OTHER)) is DataResult.Success)
            assertTrue(container.workouts.saveWorkout(WorkoutDraft(RecordIds.newId(), at(date, 18, 30), zone.id,
                listOf(WorkoutItemDraft(RecordIds.newId(), exercise.id, sets = listOf(
                    WorkoutSetDraft(RecordIds.newId(), 20_000, 12), WorkoutSetDraft(RecordIds.newId(), 25_000, 10)))), "周末轻松活动，按自己的节奏")) is DataResult.Success)
        }
        if (!marker.exists()) marker.writeText("baseline=066279c\ndate=$date\nzone=$zone\nmeal=12:20,SEVEN\nsleep=previous-day23:40..07:10\nworkout=18:30,卧推,20kgx12/25kgx10\n")
        val backup = File(app.cacheDir, "t17-synthetic.zip")
        if (!backup.exists()) runBlocking {
            assertTrue(container.backupExporter.export(object : BackupDestination {
                override fun open() = backup.outputStream()
                override fun deleteIncomplete() { backup.delete() }
            }) is BackupResult.Success)
        }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            runBlocking { assertTrue(container.settings.setTheme(mode) is DataResult.Success) }
            ActivityScenario.launch(MainActivity::class.java).use {
                val prefix = mode.name.lowercase()
                waitText(mealText)
                screenshot("$prefix-home")
                click("回顾")
                waitText(mealText)
                screenshot("$prefix-review")
                click("今天")
                click(mealText)
                screenshot("$prefix-meal-detail")
                compose.onNodeWithContentDescription("编辑记录").performClick()
                waitText("吃了什么（选填）")
                screenshot("$prefix-meal-editor")
                compose.onNodeWithText("保存").performScrollTo()
                screenshot("$prefix-meal-editor-options")
                compose.onNodeWithTag("meal_satiety_CUSTOM").performScrollTo().performClick()
                compose.onNodeWithTag("meal_satiety_custom_input").performScrollTo().performClick().performTextReplacement("刚刚好，留一点余地")
                screenshot("$prefix-meal-custom-keyboard")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                screenshot("$prefix-meal-custom")
                compose.onNodeWithTag("meal_satiety_SEVEN").performScrollTo().performClick()
                back()
                back()
                compose.onNodeWithTag("sleep-summary-history").performScrollTo().performClick()
                screenshot("$prefix-sleep-history")
                compose.onAllNodes(hasText("就寝", substring = true) and hasClickAction()).onFirst().performClick()
                waitText("就寝日期时间")
                screenshot("$prefix-sleep-editor")
                click("就寝日期时间")
                screenshot("$prefix-sleep-wheel")
                click("取消")
                back()
                back()
                click("卧推")
                click("编辑训练")
                waitText("保存训练")
                screenshot("$prefix-workout-editor")
                compose.onNodeWithTag("workout-form").performScrollToNode(hasText("新增一组"))
                screenshot("$prefix-workout-sets")
                back()
                back()
                compose.onNodeWithContentDescription("打开设置").performClick()
                waitText("主题")
                screenshot("$prefix-settings-backup")
                click("运动项目管理")
                waitText("新增项目")
                screenshot("$prefix-exercise-library")
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("公园")
                waitText("公园轻松拉伸与肩背活动")
                screenshot("$prefix-exercise-search")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                back()
                val unchanged = runBlocking { container.snapshots.read() }
                container.backupRestore.selectBackup(BackupSource { backup.inputStream() })
                compose.waitUntil(20_000) { container.backupRestore.state.value is BackupRestoreState.Preview }
                waitText("确认恢复此备份？")
                screenshot("$prefix-backup-preview")
                click("取消")
                compose.waitUntil(20_000) { container.backupRestore.state.value is BackupRestoreState.Result }
                container.backupRestore.dismissResult()
                compose.waitUntil(20_000) { container.backupRestore.state.value is BackupRestoreState.Idle }
                assertEquals("Cancel preflight must preserve every database field", unchanged, runBlocking { container.snapshots.read() })
            }
        }
        captureBedtimeAndUndo()
        if ((InstrumentationRegistry.getArguments().getString("phase") ?: "after") == "after") {
            captureLargeFontAndRestore(backup)
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun captureBedtimeAndUndo() {
        val original = runBlocking { container.snapshots.read().sleeps }
        runBlocking { container.settings.setTheme(ThemeMode.LIGHT) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitText(mealText)
            click("准备睡了")
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty() }
            screenshot("bedtime")
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)!!
                assertFalse(insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                assertFalse(insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
            }
            val active = runBlocking { container.records.activeSleep().first()!! }
            androidx.test.espresso.Espresso.pressBack()
            waitText("起床了")
            assertEquals(active.id, runBlocking { container.records.activeSleep().first()!!.id })
            click(app.getString(R.string.sleep_undo))
            click("确认")
            waitText("准备睡了")
            assertNull(runBlocking { container.records.activeSleep().first() })
            assertEquals(original, runBlocking { container.snapshots.read().sleeps })
        }
    }

    private fun captureLargeFontAndRestore(backup: File) {
        val initial = runBlocking { container.snapshots.read() }
        shell("settings put system font_scale 1.5")
        android.os.SystemClock.sleep(700)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitText(mealText)
                assertEquals(1.5f, app.resources.configuration.fontScale, 0.01f)
                screenshot("large-home")
                compose.onNodeWithContentDescription("打开设置").performClick()
                click("深色")
                compose.waitUntil(20_000) { runBlocking { container.settings.settings().first().themeMode } == ThemeMode.DARK }
                screenshot("large-settings")
                back()
                click("记饮食")
                compose.onNodeWithTag("meal_satiety_CUSTOM").performScrollTo().performClick()
                compose.onNodeWithTag("meal_satiety_custom_input").performScrollTo().performClick().performTextReplacement("还想再吃一点")
                compose.onNodeWithTag("meal_text_input").performScrollTo().performClick().performTextReplacement("放大字体测试餐")
                screenshot("large-meal-keyboard")
                compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
                screenshot("large-meal-save-keyboard")
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
                waitText("今日睡眠")
                val saved = runBlocking { container.snapshots.read().meals.single { row -> row.text == "放大字体测试餐" } }
                assertEquals(Satiety.CUSTOM, saved.satiety)
                assertEquals("还想再吃一点", saved.satietyText)
                click("准备睡了")
                compose.waitUntil(20_000) { compose.onAllNodesWithTag("bedtime-screen").fetchSemanticsNodes().isNotEmpty() }
                screenshot("large-bedtime")
                androidx.test.espresso.Espresso.pressBack()
                waitText("起床了")
                click("起床了")
                waitText("准备睡了")
                compose.onNodeWithTag("sleep-summary-history").performScrollTo().performClick()
                click("补录作息")
                waitText("就寝日期时间")
                screenshot("large-sleep-editor")
                compose.onNodeWithText("保存作息").performScrollTo().assertIsDisplayed()
                screenshot("large-sleep-actions")
                compose.onNodeWithText("就寝日期时间").performScrollTo().performClick()
                screenshot("large-sleep-wheel")
                click("取消")
                back()
                back()
                compose.onNodeWithContentDescription("打开设置").performClick()
                container.backupRestore.selectBackup(BackupSource { backup.inputStream() })
                waitText("确认恢复此备份？")
                click("覆盖并恢复")
                waitText("恢复完成")
                compose.onNodeWithText("恢复完成").performScrollTo().assertIsDisplayed()
                screenshot("large-restore-success")
                val restored = runBlocking { container.snapshots.read() }
                assertEquals(initial.meals, restored.meals)
                assertEquals(initial.sleeps, restored.sleeps)
                assertEquals(initial.exercises, restored.exercises)
                assertEquals(initial.sessions, restored.sessions)
                assertEquals(initial.items, restored.items)
                assertEquals(initial.sets, restored.sets)
                assertEquals(initial.media, restored.media)
                assertEquals(ThemeMode.SYSTEM, runBlocking { container.settings.settings().first().themeMode })
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }
}
