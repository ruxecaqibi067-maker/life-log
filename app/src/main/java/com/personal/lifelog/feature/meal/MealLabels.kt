package com.personal.lifelog.feature.meal

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.personal.lifelog.R
import com.personal.lifelog.domain.Satiety

/**
 * Display order only; persistence uses the enum names. "没吃饱" is the least full
 * option, so it leads the row while the existing values keep their meaning; the
 * custom option stays last because it opens a text field.
 */
val satietyOptions: List<Satiety> = listOf(
    Satiety.NOT_FULL,
    Satiety.FIVE,
    Satiety.SEVEN,
    Satiety.NINE,
    Satiety.FULL,
    Satiety.CUSTOM,
)

@StringRes
fun satietyLabelRes(satiety: Satiety): Int = when (satiety) {
    Satiety.NOT_FULL -> R.string.meal_satiety_not_full
    Satiety.FIVE -> R.string.meal_satiety_five
    Satiety.SEVEN -> R.string.meal_satiety_seven
    Satiety.NINE -> R.string.meal_satiety_nine
    Satiety.FULL -> R.string.meal_satiety_full
    Satiety.CUSTOM -> R.string.meal_satiety_custom
}

/** Preset labels come from resources; a custom option shows its saved description. */
@Composable
fun satietyDisplayText(satiety: Satiety?, satietyText: String?): String? = when (satiety) {
    null -> null
    Satiety.CUSTOM -> satietyText?.takeIf { it.isNotBlank() }
    else -> stringResource(satietyLabelRes(satiety))
}

@StringRes
internal fun MealUiError.messageRes(): Int = when (this) {
    MealUiError.EMPTY_MEAL -> R.string.meal_error_empty
    MealUiError.TEXT_TOO_LONG -> R.string.meal_error_text_too_long
    MealUiError.INVALID_TIME -> R.string.meal_error_invalid_time
    MealUiError.PHOTO_INVALID -> R.string.meal_error_photo_invalid
    MealUiError.PHOTO_TOO_LARGE -> R.string.meal_error_photo_too_large
    MealUiError.SOURCE_UNAVAILABLE -> R.string.meal_error_source_unavailable
    MealUiError.STORAGE_FULL -> R.string.meal_error_storage_full
    MealUiError.STORAGE -> R.string.meal_error_storage
    MealUiError.RECOVERY_REQUIRED -> R.string.meal_error_recovery_required
    MealUiError.SAVE_FAILED -> R.string.meal_error_save_failed
    MealUiError.DELETE_FAILED -> R.string.meal_error_delete_failed
    MealUiError.LOAD_FAILED -> R.string.meal_error_load_failed
    MealUiError.SATIETY_TEXT_REQUIRED -> R.string.meal_error_satiety_required
    MealUiError.SATIETY_TEXT_TOO_LONG -> R.string.meal_error_satiety_too_long
}
