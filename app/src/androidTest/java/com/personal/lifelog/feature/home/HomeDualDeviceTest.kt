package com.personal.lifelog.feature.home

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.R
import com.personal.lifelog.domain.*
import java.io.File
import java.time.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real MainActivity, synthetic fixtures, and a separate app package only. */
@RunWith(AndroidJUnit4::class)
class HomeDualDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val c get() = app.container
    private val zone get() = ZoneId.systemDefault()
    private val date get() = LocalDate.now(zone)
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h,m).atZone(zone).toInstant().toEpochMilli()
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
    private fun waitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun ready() { waitTag("sleep-feedback");waitTag("work-feedback") }
    private fun clickTag(tag: String) {
        compose.waitUntil(20_000) { runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun top() { compose.onNodeWithTag("home-timeline").performScrollToIndex(0) }
    private fun capture(name: String) {
        compose.waitForIdle();android.os.SystemClock.sleep(250)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file=File(app.filesDir,"home-dual/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        val config=app.resources.configuration
        File(file.parentFile,"$name.json").writeText(org.json.JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
            .put("widthDp",config.screenWidthDp).put("fontScale",config.fontScale).put("widthPx",image.width)
            .put("heightPx",image.height).put("densityDpi",config.densityDpi).put("capturedAt",Instant.now().toString()).toString(2))
        image.recycle()
    }
    private fun seed() {
        assertTrue("Only isolated home-dual package may receive fixtures",app.packageName.endsWith(".homedual.t21check"))
        runBlocking {
            c.work.works().first().forEach { c.work.deleteWork(it) }
            c.records.sleeps().first().forEach { c.records.deleteSleep(it.id) }
            c.settings.setTheme(ThemeMode.LIGHT);c.settings.setTargetSleepMinutes(480);c.settings.setTargetWorkMinutes(480)
            assertTrue(c.records.saveSleep(SleepDraft(RecordIds.newId(),at(date.minusDays(1),23),zone.id,at(date,5),zone.id)) is DataResult.Success)
            assertTrue(c.work.saveWork(WorkDraft(RecordIds.newId(),at(date,7),zone.id,at(date,17),zone.id)) is DataResult.Success)
        }
    }
    private fun assertParallelCards() {
        val sleep=compose.onNodeWithTag("sleep-summary-card").fetchSemanticsNode().boundsInRoot
        val work=compose.onNodeWithTag("work-summary-card").fetchSemanticsNode().boundsInRoot
        assertEquals(sleep.top,work.top,1f);assertTrue(sleep.right <= work.left)
        assertEquals(sleep.width,work.width,1f)
        val restAction=compose.onNodeWithTag("sleep-start-finish").fetchSemanticsNode().boundsInRoot
        val workAction=compose.onNodeWithTag("work-start-finish").fetchSemanticsNode().boundsInRoot
        assertEquals(restAction.top,workAction.top,1f);assertTrue(restAction.right <= workAction.left)
        compose.onNodeWithTag("sleep-start-finish").assertIsDisplayed()
        compose.onNodeWithTag("work-start-finish").assertIsDisplayed()
    }
    @Test fun pairedCardsUseRatioMessagesAndFixedActionsRemainIndependent() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready();assertParallelCards()
            compose.onNodeWithTag("sleep-duration").assertTextEquals("6小时")
            compose.onNodeWithTag("work-duration").assertTextEquals("10小时")
            assertTrue(app.resources.getStringArray(R.array.sleep_feedback_short).contains(text("sleep-feedback")))
            assertTrue(app.resources.getStringArray(R.array.work_feedback_long).contains(text("work-feedback")))
            compose.onAllNodesWithText("已记录",substring=true).assertCountEquals(0)
            compose.onAllNodesWithText("段已完成记录",substring=true).assertCountEquals(0)
            val originalSleep=text("sleep-feedback");val originalWork=text("work-feedback")
            capture("light-short-sleep-long-work")
            scenario.recreate();ready()
            assertEquals(originalSleep,text("sleep-feedback"));assertEquals(originalWork,text("work-feedback"))
            clickTag("work-start-finish")
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()!=null } }
            compose.onNodeWithTag("bedtime-screen").assertDoesNotExist()
            assertEquals(1,runBlocking { c.records.sleeps().first().size })
            assertEquals(originalWork,text("work-feedback"))
            capture("light-active-work")
            clickTag("work-start-finish")
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()==null } }
            clickTag("sleep-start-finish");waitTag("bedtime-screen")
            compose.onNodeWithText("起床了").performClick();waitTag("home-timeline");top();ready()
            assertEquals(2,runBlocking { c.records.sleeps().first().size })
            assertEquals(2,runBlocking { c.work.works().first().size })
            runBlocking { c.settings.setTheme(ThemeMode.DARK) };compose.waitForIdle();capture("dark-paired-cards")
            clickTag("work-target-edit")
            compose.onNodeWithTag("work-goal-hours").performTextReplacement("12")
            closeSoftKeyboard();clickTag("work-goal-save");ready()
            compose.waitUntil(20_000) { runBlocking { c.settings.targetWorkMinutes().first()==720 } }
            compose.waitUntil(20_000) { app.resources.getStringArray(R.array.work_feedback_balanced).contains(text("work-feedback")) }
            capture("dark-balanced-work")
            assertEquals(480,runBlocking { c.settings.targetSleepMinutes().first() })
            clickTag("work-summary-history");waitTag("work-history")
            compose.onNodeWithContentDescription("返回").performClick();ready()
            clickTag("sleep-summary-history")
            compose.waitUntil(20_000) { compose.onAllNodesWithText("补录作息").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    @Test fun systemLargeFontKeepsCardsParallelAndGoalActionsReachable() {
        seed();shell("settings put system font_scale 1.5");android.os.SystemClock.sleep(500)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready();assertEquals(1.5f,app.resources.configuration.fontScale,0.01f)
                assertParallelCards();capture("large-paired-cards")
                compose.onNodeWithTag("home-timeline").performScrollToNode(hasTestTag("work-summary-history"))
                compose.onNodeWithTag("work-summary-history").assertIsDisplayed()
                compose.onNodeWithTag("sleep-summary-history").assertIsDisplayed();capture("large-history-actions")
                top();clickTag("work-target-edit")
                shell("settings put secure show_ime_with_hard_keyboard 1")
                compose.onNodeWithTag("work-goal-minutes").performClick().performTextReplacement("30")
                compose.waitUntil(10_000) { shell("dumpsys input_method").contains("mInputShown=true") }
                compose.onNodeWithTag("work-goal-save").assertIsDisplayed();capture("large-goal-keyboard")
                closeSoftKeyboard();compose.onNodeWithText("取消").performClick()
                assertEquals(480,runBlocking { c.settings.targetWorkMinutes().first() })
                clickTag("work-start-finish")
                compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()!=null } }
                scenario.recreate();ready();assertParallelCards()
                compose.onNodeWithText("结束工作").assertIsDisplayed();capture("large-active-after-recreate")
            }
        } finally { shell("settings put system font_scale 1.0") }
    }
}
