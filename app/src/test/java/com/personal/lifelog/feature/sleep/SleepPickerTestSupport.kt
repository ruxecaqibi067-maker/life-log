package com.personal.lifelog.feature.sleep

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.time.LocalDateTime

/** Exercises the same numeric accessibility adjustment exposed by the native wheel wrapper. */
fun ComposeContentTestRule.pickSleepTime(label: String, value: String) {
    onNodeWithText(label).performScrollTo().performClick()
    val local = LocalDateTime.parse(value)
    listOf("年" to local.year, "月" to local.monthValue, "日" to local.dayOfMonth, "时" to local.hour, "分" to local.minute).forEach { (unit, number) ->
        onNodeWithTag("sleep-wheel-$unit").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(number.toFloat()) }
    }
    onNodeWithText("确认").performClick()
}
