package com.personal.lifelog.core.validation

import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.reject
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

object Rules {
    const val MEAL_TEXT_LIMIT = 500
    const val SATIETY_TEXT_LIMIT = 40
    const val NOTE_LIMIT = 1000
    const val EXERCISE_NAME_LIMIT = 40
    const val WORKOUT_ITEMS_LIMIT = 100
    const val SETS_PER_ITEM_LIMIT = 100
    const val PHOTO_BYTES_LIMIT = 8 * 1024 * 1024L
    const val TARGET_SLEEP_MINUTES_MAX = 1440

    fun targetSleepMinutes(value: Int?): Int? {
        if (value != null && value !in 1..TARGET_SLEEP_MINUTES_MAX) {
            reject(ErrorCode.INVALID_NUMBER, "targetSleepMinutes")
        }
        return value
    }

    const val TARGET_WORK_MINUTES_MAX = 1440

    fun targetWorkMinutes(value: Int?): Int? {
        if (value != null && value !in 1..TARGET_WORK_MINUTES_MAX) {
            reject(ErrorCode.INVALID_NUMBER, "targetWorkMinutes")
        }
        return value
    }

    fun id(value: String): String {
        val parsed = runCatching { UUID.fromString(value).toString() }.getOrNull()
        if (parsed != value) reject(ErrorCode.INVALID_ID, "id")
        return value
    }

    fun text(value: String?, limit: Int): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.codePointCount(0, trimmed.length) > limit) reject(ErrorCode.TEXT_TOO_LONG)
        return trimmed
    }

    /**
     * Custom satiety carries a short user description; every other value must not
     * carry one. Returns the normalized text (null unless [satiety] is CUSTOM).
     */
    fun satietyText(satiety: Satiety?, value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        if (satiety == Satiety.CUSTOM) {
            if (trimmed == null || trimmed.codePointCount(0, trimmed.length) > SATIETY_TEXT_LIMIT) {
                reject(ErrorCode.INVALID_SATIETY, "satietyText")
            }
            return trimmed
        }
        if (trimmed != null) reject(ErrorCode.INVALID_SATIETY, "satietyText")
        return null
    }

    fun exerciseName(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > EXERCISE_NAME_LIMIT) {
            reject(ErrorCode.INVALID_NAME, "name")
        }
        return trimmed
    }

    fun normalizedName(value: String): String =
        Normalizer.normalize(exerciseName(value), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun positive(value: Long?, field: String): Long? {
        if (value != null && value <= 0) reject(ErrorCode.INVALID_NUMBER, field)
        return value
    }

    fun weight(value: Long?): Long? {
        if (value != null && value < 0) reject(ErrorCode.INVALID_NUMBER, "weightGrams")
        return value
    }

    fun repetitions(value: Int?): Int? {
        if (value != null && value <= 0) reject(ErrorCode.INVALID_NUMBER, "repetitions")
        return value
    }
}

/** Decimal UI input is converted exactly; no Double rounding or zero defaults. */
object Units {
    fun kilogramsToGrams(input: String?): Long? = decimal(input, 1000, allowZero = true)
    fun kilometersToMeters(input: String?): Long? = decimal(input, 1000, allowZero = false)
    fun minutesToSeconds(input: String?): Long? = decimal(input, 60, allowZero = false)

    fun repetitions(input: String?): Int? {
        val value = input?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.length > 64) reject(ErrorCode.INVALID_NUMBER, "repetitions")
        if (!value.matches(Regex("[0-9]+"))) reject(ErrorCode.INVALID_NUMBER, "repetitions")
        return Rules.repetitions(value.toIntOrNull() ?: reject(ErrorCode.INVALID_NUMBER, "repetitions"))
    }

    private fun decimal(input: String?, multiplier: Int, allowZero: Boolean): Long? {
        val value = input?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.length > 64) reject(ErrorCode.INVALID_NUMBER)
        if (!value.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) reject(ErrorCode.INVALID_NUMBER)
        val converted = try {
            BigDecimal(value).multiply(BigDecimal(multiplier)).longValueExact()
        } catch (_: ArithmeticException) {
            reject(ErrorCode.INVALID_NUMBER)
        }
        if (converted < 0 || (!allowZero && converted == 0L)) reject(ErrorCode.INVALID_NUMBER)
        return converted
    }
}
