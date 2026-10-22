package com.personal.lifelog.feature.meal

import androidx.annotation.StringRes
import com.personal.lifelog.R
import com.personal.lifelog.domain.Satiety

@StringRes
fun satietyLabelRes(satiety: Satiety): Int = when (satiety) {
    Satiety.FIVE -> R.string.meal_satiety_five
    Satiety.SEVEN -> R.string.meal_satiety_seven
    Satiety.NINE -> R.string.meal_satiety_nine
    Satiety.FULL -> R.string.meal_satiety_full
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
}
