package com.personal.lifelog.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.ui.components.createPickerContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise real API 28 framework dialogs with app and system themes in opposition. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativePickerThemeTest {
    @Test fun darkAppOnLightSystemKeepsNativeDialogsReadable() = verify(true)

    @Test fun lightAppOnDarkSystemKeepsNativeDialogsReadable() = verify(false)

    private fun verify(darkApp: Boolean) {
        val base = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(),
            if (darkApp) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar)
        val context = createPickerContext(base, darkApp)
        val dialogs = listOf(DatePickerDialog(context, null, 2026, 9, 24), TimePickerDialog(context, null, 23, 40, true))
        dialogs.forEach { dialog ->
            val colors = dialog.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary, android.R.attr.colorBackground))
            try {
                val text = requireNotNull(colors.getColorStateList(0)).defaultColor
                val background = colors.getColor(1, android.graphics.Color.TRANSPARENT)
                assertTrue("Native dialog follows app choice", (ColorUtils.calculateLuminance(background) < 0.5) == darkApp)
                assertTrue("Native dialog body remains readable", ColorUtils.calculateContrast(text, background) >= 4.5)
            } finally {
                colors.recycle()
            }
            dialog.dismiss()
        }
    }
}
