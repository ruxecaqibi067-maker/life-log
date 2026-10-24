package com.personal.lifelog.feature.sleep

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The oldest supported Android needs readable native wheel text even when app/system themes differ. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SleepWheelThemeTest {
    @Test fun darkAppOnLightSystemHasReadableNumbers() {
        verify(android.R.style.Theme_Material_Light_NoActionBar, true, 0xFF141218.toInt())
    }

    @Test fun lightAppOnDarkSystemHasReadableNumbers() {
        verify(android.R.style.Theme_Material_NoActionBar, false, 0xFFFFFBFE.toInt())
    }

    private fun verify(systemTheme: Int, darkApp: Boolean, background: Int) {
        val base = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), systemTheme)
        val wheel = createSleepWheel(base, darkApp).apply { minValue = 0; maxValue = 59; value = 30 }
        val labels = textViews(wheel)
        assertTrue("Native wheel must contain a number label", labels.isNotEmpty())
        labels.forEach { label ->
            assertTrue("Numbers must remain readable against the app surface",
                ColorUtils.calculateContrast(label.currentTextColor, background) >= 4.5)
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
