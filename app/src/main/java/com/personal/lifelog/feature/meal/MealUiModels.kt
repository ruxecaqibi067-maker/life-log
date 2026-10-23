package com.personal.lifelog.feature.meal

import android.net.Uri
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.media.CaptureRequest
import com.personal.lifelog.data.media.PhotoError
import com.personal.lifelog.data.media.PhotoTicket
import com.personal.lifelog.domain.DataError
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.Satiety
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable presentation errors; screens map these to resources, never to raw exception text. */
enum class MealUiError {
    EMPTY_MEAL,
    TEXT_TOO_LONG,
    INVALID_TIME,
    PHOTO_INVALID,
    PHOTO_TOO_LARGE,
    SOURCE_UNAVAILABLE,
    STORAGE_FULL,
    STORAGE,
    RECOVERY_REQUIRED,
    SAVE_FAILED,
    DELETE_FAILED,
    LOAD_FAILED,
}

enum class MealPhotoStatus { NONE, PROCESSING, READY, CAPTURE_PENDING, ERROR }

/**
 * A draft is the editable form, not a saved record. [dirty] compares the form with the
 * loaded record (or "nothing entered" for a new meal) and only guards the back/cancel
 * prompt; the repository remains the final validator.
 */
data class MealEditorUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val loadFailed: Boolean = false,
    val text: String = "",
    val satiety: Satiety? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now(),
    val zoneId: String = ZoneId.systemDefault().id,
    val originalPhotoId: String? = null,
    val newPhotoTicket: PhotoTicket? = null,
    val captureTicket: PhotoTicket? = null,
    val photoPreview: Uri? = null,
    val photoStatus: MealPhotoStatus = MealPhotoStatus.NONE,
    val photoRemoved: Boolean = false,
    val photoError: MealUiError? = null,
    val captureRequest: CaptureRequest? = null,
    val saving: Boolean = false,
    val error: MealUiError? = null,
    val dirty: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val discarded: Boolean = false,
)

/** Presentation copy of a meal, detached from the Room entity. */
data class MealDetail(
    val id: String,
    val text: String?,
    val satiety: Satiety?,
    val occurredAtUtcMs: Long,
    val zoneId: String,
    val photoId: String?,
)

data class MealDetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val loadError: MealUiError? = null,
    val meal: MealDetail? = null,
    val photoUri: Uri? = null,
    val photoMissing: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: MealUiError? = null,
    val deleted: Boolean = false,
)

internal fun MealRecord.toDetail(): MealDetail = MealDetail(
    id = id,
    text = text,
    satiety = satiety,
    occurredAtUtcMs = occurredAtUtcMs,
    zoneId = zoneId,
    photoId = photoId,
)

/** UI-side early check; [Rules] and RecordRepository repeat it as the authority. */
internal fun mealValidationError(text: String, hasPhoto: Boolean): MealUiError? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() && !hasPhoto) return MealUiError.EMPTY_MEAL
    if (trimmed.codePointCount(0, trimmed.length) > Rules.MEAL_TEXT_LIMIT) return MealUiError.TEXT_TOO_LONG
    return null
}

internal fun DataError.toMealUiError(): MealUiError = when (code) {
    ErrorCode.EMPTY_MEAL -> MealUiError.EMPTY_MEAL
    ErrorCode.TEXT_TOO_LONG -> MealUiError.TEXT_TOO_LONG
    ErrorCode.INVALID_TIME_ZONE,
    ErrorCode.INVALID_LOCAL_TIME,
    ErrorCode.AMBIGUOUS_LOCAL_TIME,
    ErrorCode.INVALID_INTERVAL,
    -> MealUiError.INVALID_TIME
    ErrorCode.STORAGE_FULL -> MealUiError.STORAGE_FULL
    ErrorCode.STORAGE,
    ErrorCode.STORAGE_UNAVAILABLE,
    -> MealUiError.STORAGE
    ErrorCode.INVALID_MEDIA,
    ErrorCode.IMMUTABLE_MEDIA,
    -> MealUiError.PHOTO_INVALID
    else -> MealUiError.SAVE_FAILED
}

internal fun PhotoError.toMealUiError(): MealUiError = when (this) {
    PhotoError.INVALID_IMAGE,
    PhotoError.INVALID_TICKET,
    PhotoError.MISSING_PHOTO,
    -> MealUiError.PHOTO_INVALID
    PhotoError.INPUT_TOO_LARGE,
    PhotoError.OUTPUT_TOO_LARGE,
    PhotoError.PIXEL_LIMIT,
    PhotoError.RESOURCE_LIMIT,
    -> MealUiError.PHOTO_TOO_LARGE
    PhotoError.SOURCE_UNAVAILABLE -> MealUiError.SOURCE_UNAVAILABLE
    PhotoError.STORAGE_FULL -> MealUiError.STORAGE_FULL
    PhotoError.RECOVERY_REQUIRED -> MealUiError.RECOVERY_REQUIRED
    PhotoError.IO,
    PhotoError.DATABASE,
    -> MealUiError.STORAGE
}

/** Times are shown in the record's own zone; a bad stored zone falls back to the device zone. */
object MealTimeDisplay {
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

    fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

    fun full(occurredAtUtcMs: Long, zoneId: String): String =
        Instant.ofEpochMilli(occurredAtUtcMs).atZone(zone(zoneId)).format(fullFormatter)

    fun time(occurredAtUtcMs: Long, zoneId: String): String =
        Instant.ofEpochMilli(occurredAtUtcMs).atZone(zone(zoneId)).format(timeFormatter)
}
