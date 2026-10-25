package com.personal.lifelog.feature.home

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
