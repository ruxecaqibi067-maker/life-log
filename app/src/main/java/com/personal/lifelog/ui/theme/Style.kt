package com.personal.lifelog.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared dimensions; controls grow with content instead of fixing their height. */
object LogDimens {
    val tiny = 4.dp
    val gap = 8.dp
    val group = 12.dp
    val page = 16.dp
    val card = 16.dp
    val section = 24.dp
    val touch = 48.dp
    val action = 52.dp
    val border = 1.dp
}

internal val LogShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp,
)

// System font and sp preserve user font settings, including Chinese fallback fonts.
internal val LogTypography = Typography(
    headlineSmall = text(24, 34, FontWeight.Medium),
    titleLarge = text(22, 32, FontWeight.Medium),
    titleMedium = text(18, 28, FontWeight.Medium),
    titleSmall = text(16, 24, FontWeight.Medium),
    bodyLarge = text(16, 26),
    bodyMedium = text(14, 22),
    bodySmall = text(12, 20),
    labelLarge = text(14, 22, FontWeight.Medium),
    labelMedium = text(12, 18, FontWeight.Medium),
    labelSmall = text(12, 18, FontWeight.Medium),
)
