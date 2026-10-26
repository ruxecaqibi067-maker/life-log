package com.personal.lifelog.feature.home

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.theme.LifeLogTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h800dp")
class SleepCardPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun targetActionDoesNotNavigateButEmptyCardStillOpensHistory() {
        var edits = 0
        var opens = 0
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                SleepSummaryCard(SleepCardState(false, content = SleepCardContent(null, null, 0, true)),
                    { opens++ }, { edits++ }, {})
            }
        }
        compose.onNodeWithTag("sleep-target-edit").performClick()
        assertEquals(1, edits)
        assertEquals(0, opens)
        compose.onNodeWithTag("sleep-summary-history").performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("查看 / 补录").assertDoesNotExist()
        compose.onNodeWithText("暂无已完成记录").assertExists()
        compose.onNodeWithText("作息进行中").assertExists()
        compose.onAllNodesWithText("0小时", substring = true).assertCountEquals(0)
    }

    @Test fun failedReadHasRetryAndHistoryWithoutPretendingToBeEmpty() {
        var retries = 0
        var opens = 0
        compose.setContent {
            LifeLogTheme(ThemeMode.DARK) {
                SleepSummaryCard(SleepCardState(false, failed = true), { opens++ }, {}, { retries++ })
            }
        }
        compose.onNodeWithTag("sleep-target-edit").assertIsNotEnabled()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("暂无已完成记录").assertDoesNotExist()
        compose.onNodeWithTag("sleep-summary-history").performClick()
        assertEquals(1, opens)
    }

    @Test fun completedCardsShowStableFeedbackAndActualTimeWithoutLabelsOrCounts() {
        val revision = androidx.compose.runtime.mutableIntStateOf(0)
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("recompose ${revision.intValue}")
                    SleepSummaryCard(SleepCardState(false, content = SleepCardContent(480, 26_400_000, 7, false)), {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("sleep-duration", useUnmergedTree = true).assertTextEquals("7小时20分")
        compose.onNodeWithText("已记录").assertDoesNotExist()
        compose.onAllNodesWithText("段已完成记录", substring = true).assertCountEquals(0)
        val before = compose.onNodeWithTag("sleep-feedback", useUnmergedTree = true).fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertTrue(before.isNotBlank())
        compose.runOnIdle { revision.intValue++ }
        compose.onNodeWithTag("sleep-feedback", useUnmergedTree = true).assertTextEquals(before)
    }

    @Test fun activeWorkRingIncludesCurrentTimeAndKeepsCompletedTimeClearlySeparate() {
        val content = WorkCardContent(240, 3_600_000, 1, true)
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                WorkSummaryCard(WorkCardState(false, content = content), {}, {}, {}, activeDurationMs = 3_600_000)
            }
        }
        compose.onNodeWithTag("work-active-duration", useUnmergedTree = true).assertTextEquals("本次已持续 1小时")
        compose.onNodeWithTag("work-duration", useUnmergedTree = true).assertTextEquals("已完成 1小时")
        val range = compose.onNodeWithTag("work-progress", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.5f, range.current, 0.000001f)
        assertEquals(3_600_000L, content.recordedDurationMs)
        compose.onNodeWithText("查看 / 补录").assertDoesNotExist()
    }

    @Test fun progressUsesTargetAsWholeRetainsMillisecondsAndOnlyCapsDrawing() {
        assertEquals(440f / 480f, sleepProgress(26_400_000, 480)!!, 0.000001f)
        assertEquals(1f, sleepProgress(32_400_000, 480)!!)
        assertEquals(1f / 60_000f, sleepProgress(1, 1)!!, 0.00000001f)
        assertNull(sleepProgress(null, 480))
        assertNull(sleepProgress(26_400_000, null))
        assertNull(sleepProgress(26_400_000, 0))
    }

    @Test fun durationInputRejectsClockCarryOverflowAndZero() {
        assertEquals(1, parseSleepGoal("0", "1"))
        assertEquals(450, parseSleepGoal("7", "30"))
        assertEquals(1440, parseSleepGoal("24", "0"))
        listOf("0" to "0", "24" to "1", "7" to "60", "-1" to "30", "" to "1",
            "2147483648" to "0", "7.5" to "0", "8" to "+1").forEach { (hours, minutes) ->
            assertNull("$hours/$minutes", parseSleepGoal(hours, minutes))
        }
    }
}
