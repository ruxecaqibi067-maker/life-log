package com.personal.lifelog.feature.home

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.R
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.ui.theme.LifeLogTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host layout/callback checks only; recognition and physical-device rendering are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w360dp-h800dp")
class HomeQuickActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centerMicrophoneKeepsAllFourActionsAndItsAccessibleTouchTarget() {
        val calls = mutableListOf<String>()
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(328.dp)) {
                    HomeQuickActions("准备睡了", "开始工作", false, true, true,
                        { calls += "sleep" }, { calls += "work" }, { calls += "meal" },
                        { calls += "workout" }, { calls += "voice" })
                }
            }
        }
        compose.onNodeWithTag("sleep-start-finish").performClick()
        compose.onNodeWithTag("work-start-finish").performClick()
        compose.onNodeWithTag("home-quick-meal").performClick()
        compose.onNodeWithTag("home-quick-workout").performClick()
        compose.onNodeWithTag("home-voice-start").assertHasClickAction()
            .assertContentDescriptionEquals(context.getString(R.string.quick_voice_entry)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("sleep", "work", "meal", "workout", "voice"), calls)
        }
        val button = compose.onNodeWithTag("home-voice-start").fetchSemanticsNode()
        val minPixels = 48 * context.resources.displayMetrics.density
        assertTrue(button.boundsInRoot.width >= minPixels)
        assertTrue(button.boundsInRoot.height >= minPixels)
        assertFalse("The center entry must contain only an icon", button.config.contains(SemanticsProperties.Text))
        compose.onNodeWithTag("home-voice-type").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.quick_type_short)).assertDoesNotExist()
    }

    @Test fun enlargedTextStaysClearOfTheMicrophoneAtTheActualFourCardIntersection() {
        val scale = mutableStateOf(1f)
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value)) {
                LifeLogTheme(if (dark.value) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.width(328.dp)) {
                        // A longer first-row state also checks an intersection above the grid's center.
                        HomeQuickActions("正在加载睡眠记录", "开始工作", false, true, true,
                            {}, {}, {}, {}, {})
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { scale.value = fontScale; dark.value = fontScale > 1f }
            compose.waitForIdle()
            val microphone = bounds("home-voice-start")
            val topLeft = bounds("sleep-start-finish")
            val topRight = bounds("work-start-finish")
            val bottomLeft = bounds("home-quick-meal")
            val bottomRight = bounds("home-quick-workout")
            assertEquals((topLeft.right + topRight.left) / 2, microphone.center.x, 1f)
            assertEquals((topLeft.bottom + bottomLeft.top) / 2, microphone.center.y, 1f)
            assertTrue(microphone.left < topLeft.right && microphone.right > topRight.left)
            assertTrue(microphone.top < topLeft.bottom && microphone.bottom > bottomLeft.top)
            assertEquals(topLeft.bottom, topRight.bottom, 1f)
            assertEquals(bottomLeft.top, bottomRight.top, 1f)
            for (label in listOf("正在加载睡眠记录", "开始工作", "记饮食", "记运动")) {
                val node = compose.onNodeWithText(label, useUnmergedTree = true)
                val textBounds = node.fetchSemanticsNode().boundsInRoot
                assertFalse("$label overlaps microphone at scale $fontScale", overlaps(textBounds, microphone))
                val results = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertEquals(1, results.size)
                val result = results.single()
                assertFalse("$label is clipped at scale $fontScale: bounds=$textBounds, layout=${result.size}, " +
                    "lines=${result.lineCount}, widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}",
                    result.hasVisualOverflow)
            }
        }
    }

    @Test fun busyRecordActionsStayDisabledWhileVoiceAndOtherEntriesRemainUsable() {
        compose.setContent {
            LifeLogTheme(ThemeMode.DARK) {
                Box(Modifier.width(328.dp)) {
                    HomeQuickActions("正在保存…", "加载中…", true, false, false,
                        {}, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("sleep-start-finish").assertIsNotEnabled()
        compose.onNodeWithTag("work-start-finish").assertIsNotEnabled()
        compose.onNodeWithTag("home-quick-meal").assertIsEnabled()
        compose.onNodeWithTag("home-quick-workout").assertIsEnabled()
        compose.onNodeWithTag("home-voice-start").assertIsEnabled()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}
