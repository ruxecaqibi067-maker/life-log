package com.personal.lifelog.feature.meal

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.media.PhotoError
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.media.PhotoTicket
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.Satiety
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Create/edit form for one meal. The ViewModel owns the draft, the photo ticket and
 * retry IDs, so a failed save keeps every input and a retry uses the same record ID.
 * It never reports success before PhotoStorage's transactional save succeeds.
 *
 * Draft fields and ticket IDs live in SavedStateHandle: a rotation or a short process
 * death restores the form, while the photo service still owns the actual files.
 */
class MealEditorViewModel(
    private val mealId: String?,
    private val savedStateHandle: SavedStateHandle,
    private val records: RecordRepository,
    private val photos: PhotoStorage,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) : ViewModel() {

    private companion object {
        const val KEY_ID = "meal.draft.id"
        const val KEY_LOADED = "meal.draft.loaded"
        const val KEY_TEXT = "meal.draft.text"
        const val KEY_SATIETY = "meal.draft.satiety"
        const val KEY_SATIETY_TEXT = "meal.draft.satietyText"
        const val KEY_DATE = "meal.draft.date"
        const val KEY_TIME = "meal.draft.time"
        const val KEY_ZONE = "meal.draft.zone"
        const val KEY_OFFSET = "meal.draft.offset"
        const val KEY_PHOTO_TICKET = "meal.draft.photoTicket"
        const val KEY_CAPTURE_TICKET = "meal.draft.captureTicket"
        const val KEY_PHOTO_REMOVED = "meal.draft.photoRemoved"

        /** Hard stop against an accidental megabyte paste bloating saved instance state. */
        const val MAX_RAW_TEXT_LENGTH = 4000
    }

    private val draftId: String = mealId
        ?: savedStateHandle.get<String>(KEY_ID)
        ?: RecordIds.newId().also { savedStateHandle[KEY_ID] = it }

    /** True when the route carried a record ID; false is the create flow. */
    val isEditing: Boolean = mealId != null

    private var zoneId: String = savedStateHandle.get<String>(KEY_ZONE) ?: zoneIdProvider().id
    private var originalText: String? = null
    private var originalSatiety: Satiety? = null
    private var originalSatietyText: String? = null
    private var originalPhotoId: String? = null
    private var originalDate: LocalDate? = null
    private var originalTime: LocalTime? = null
    private var originalOccurredAt: Long? = null
    private var originalZoneId: String? = null
    private var originalOffset: String? = null
    private var closing = false

    private val initialLocal = Instant.ofEpochMilli(clock.millis())
        .atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(zoneIdProvider()))

    private val _state = MutableStateFlow(
        MealEditorUiState(
            loading = true,
            text = savedStateHandle.get<String>(KEY_TEXT) ?: "",
            satiety = savedStateHandle.get<String>(KEY_SATIETY)
                ?.let { runCatching { Satiety.valueOf(it) }.getOrNull() },
            satietyText = savedStateHandle.get<String>(KEY_SATIETY_TEXT) ?: "",
            date = savedStateHandle.get<String>(KEY_DATE)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: initialLocal.toLocalDate(),
            time = savedStateHandle.get<String>(KEY_TIME)
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: initialLocal.toLocalTime(),
            zoneId = zoneId,
            timeOffset = savedStateHandle.get<String>(KEY_OFFSET),
            newPhotoTicket = savedStateHandle.get<String>(KEY_PHOTO_TICKET)?.let(::PhotoTicket),
            captureTicket = savedStateHandle.get<String>(KEY_CAPTURE_TICKET)?.let(::PhotoTicket),
            photoRemoved = savedStateHandle.get<Boolean>(KEY_PHOTO_REMOVED) ?: false,
            dirty = mealId == null && savedStateHandle.get<Boolean>(KEY_LOADED) == true,
        ),
    )
    val state: StateFlow<MealEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onTextChanged(value: String) {
        if (value.length > MAX_RAW_TEXT_LENGTH) return
        update { it.copy(text = value, error = null) }
    }

    fun toggleSatiety(value: Satiety) {
        update {
            val next = if (it.satiety == value) null else value
            val customText = if (it.satiety == Satiety.CUSTOM) it.satietyText else ""
            it.copy(
                satiety = next,
                satietyText = if (next == Satiety.CUSTOM) customText else "",
                error = null,
            )
        }
    }

    fun onSatietyTextChanged(value: String) {
        if (value.length > 200) return
        update { it.copy(satietyText = value, error = null) }
    }

    fun onDateChanged(value: LocalDate) = update { it.copy(date = value, timeOffset = null) }

    fun onTimeChanged(value: LocalTime) = update { it.copy(time = value, timeOffset = null) }

    /** One atomic confirmation, including an explicitly selected repeated DST occurrence. */
    fun onDateTimePicked(value: LocalDateTime, offset: ZoneOffset) {
        update {
            val local = LocalDateTime.of(it.date, it.time)
            val previousOffset = it.timeOffset ?: originalOffset
                ?: ZoneId.of(it.zoneId).rules.getValidOffsets(local).singleOrNull()?.id
            if (local.truncatedTo(ChronoUnit.MINUTES) == value.truncatedTo(ChronoUnit.MINUTES) && previousOffset == offset.id) it
            else it.copy(date = value.toLocalDate(), time = value.toLocalTime().truncatedTo(ChronoUnit.MINUTES), timeOffset = offset.id)
        }
    }

    /** null is the system picker's cancel path: keep the current text and photo untouched. */
    fun onPhotoPicked(uri: Uri?) {
        val current = _state.value
        if (uri == null || current.saving || current.photoStatus == MealPhotoStatus.PROCESSING) return
        val previousTicket = current.newPhotoTicket
        val previousCapture = current.captureTicket
        val fallbackStatus = if (current.photoPreview != null) MealPhotoStatus.READY else MealPhotoStatus.NONE
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null, error = null) }
        viewModelScope.launch {
            when (val result = photos.importPhoto(uri)) {
                is PhotoResult.Success -> {
                    if (closing) {
                        photos.release(result.value.ticket)
                        return@launch
                    }
                    val ticket = result.value.ticket
                    val preview = photos.previewUri(ticket)
                    update {
                        it.copy(
                            newPhotoTicket = ticket,
                            captureTicket = null,
                            photoPreview = (preview as? PhotoResult.Success)?.value,
                            photoStatus = if (preview is PhotoResult.Success) {
                                MealPhotoStatus.READY
                            } else {
                                MealPhotoStatus.ERROR
                            },
                            photoError = (preview as? PhotoResult.Failure)?.error?.toMealUiError(),
                            error = null,
                        )
                    }
                    persistDraft()
                    listOfNotNull(previousTicket, previousCapture).distinct().forEach { photos.release(it) }
                }
                is PhotoResult.Failure -> _state.update {
                    it.copy(
                        photoStatus = fallbackStatus,
                        photoError = result.error.toMealUiError(),
                    )
                }
                PhotoResult.Cancelled -> _state.update { it.copy(photoStatus = fallbackStatus) }
            }
        }
    }

    /** Discard a replacement first; if the record already had a photo, that photo comes back. */
    fun removePhoto() {
        val current = _state.value
        if (current.saving || current.photoStatus == MealPhotoStatus.PROCESSING) return
        val ticket = current.newPhotoTicket
        val capture = current.captureTicket
        val removingReplacement = ticket != null || capture != null
        viewModelScope.launch {
            listOfNotNull(ticket, capture).distinct().forEach { photos.release(it) }
            update {
                it.copy(
                    newPhotoTicket = null,
                    captureTicket = null,
                    captureRequest = null,
                    photoRemoved = if (removingReplacement) it.photoRemoved else it.originalPhotoId != null,
                    photoPreview = null,
                    photoStatus = MealPhotoStatus.NONE,
                    photoError = null,
                )
            }
            persistDraft()
            if (removingReplacement && _state.value.originalPhotoId != null && !_state.value.photoRemoved) {
                showOriginal()
            }
        }
    }

    /** Starts a capture, or continues the same ticket after a cancel/process recreation. */
    fun beginCapture() {
        val current = _state.value
        if (current.saving || current.photoStatus == MealPhotoStatus.PROCESSING) return
        val existing = current.captureTicket
        val fallbackStatus = if (current.photoPreview != null) MealPhotoStatus.READY else MealPhotoStatus.NONE
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null, error = null) }
        viewModelScope.launch {
            val result = if (existing == null) photos.beginCapture() else photos.resumeCapture(existing)
            when (result) {
                is PhotoResult.Success -> {
                    if (closing) {
                        photos.release(result.value.ticket)
                        return@launch
                    }
                    update {
                        it.copy(
                            captureTicket = result.value.ticket,
                            captureRequest = result.value,
                            photoStatus = MealPhotoStatus.CAPTURE_PENDING,
                        )
                    }
                    persistDraft()
                }
                is PhotoResult.Failure -> {
                    val invalid = result.error == PhotoError.INVALID_TICKET
                    _state.update {
                        it.copy(
                            captureTicket = if (invalid) null else existing,
                            captureRequest = null,
                            photoStatus = fallbackStatus,
                            photoError = result.error.toMealUiError(),
                        )
                    }
                    persistDraft()
                }
                PhotoResult.Cancelled -> _state.update {
                    it.copy(captureRequest = null, photoStatus = fallbackStatus)
                }
            }
        }
    }

    /** The screen has launched the camera Intent; never relaunch from state restoration. */
    fun onCaptureRequestLaunched() {
        _state.update { it.copy(captureRequest = null) }
    }

    /** Camera app missing or refused to start: release the unused ticket, keep the form. */
    fun onCaptureLaunchFailed() {
        val ticket = _state.value.captureTicket
        viewModelScope.launch {
            ticket?.let { photos.release(it) }
            _state.update {
                it.copy(
                    captureTicket = null,
                    captureRequest = null,
                    photoStatus = if (it.photoPreview != null) MealPhotoStatus.READY else MealPhotoStatus.NONE,
                    photoError = MealUiError.SOURCE_UNAVAILABLE,
                )
            }
            persistDraft()
        }
    }

    fun onCaptureResult(succeeded: Boolean) {
        val ticket = _state.value.captureTicket ?: return
        // A previously prepared gallery photo stays the preview while the camera is
        // open. A successful capture replaces and releases it; a cancel keeps it.
        val previousPrepared = _state.value.newPhotoTicket
        viewModelScope.launch {
            _state.update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null) }
            when (val result = photos.finishCapture(ticket, succeeded)) {
                is PhotoResult.Success -> {
                    if (closing) {
                        photos.release(result.value.ticket)
                        return@launch
                    }
                    val preview = photos.previewUri(result.value.ticket)
                    update {
                        it.copy(
                            newPhotoTicket = result.value.ticket,
                            captureTicket = null,
                            captureRequest = null,
                            photoPreview = (preview as? PhotoResult.Success)?.value,
                            photoStatus = if (preview is PhotoResult.Success) {
                                MealPhotoStatus.READY
                            } else {
                                MealPhotoStatus.ERROR
                            },
                            photoError = (preview as? PhotoResult.Failure)?.error?.toMealUiError(),
                            error = null,
                        )
                    }
                    persistDraft()
                    listOfNotNull(previousPrepared).distinct().forEach { photos.release(it) }
                }
                is PhotoResult.Failure -> _state.update {
                    // Keep the ticket: a successful-but-invalid image can be retaken.
                    it.copy(
                        captureTicket = ticket,
                        captureRequest = null,
                        photoStatus = MealPhotoStatus.CAPTURE_PENDING,
                        photoError = result.error.toMealUiError(),
                    )
                }
                PhotoResult.Cancelled -> update {
                    it.copy(
                        captureTicket = null,
                        captureRequest = null,
                        photoStatus = if (it.photoPreview != null) MealPhotoStatus.READY else MealPhotoStatus.NONE,
                    )
                }
            }
        }
    }

    fun save() {
        val current = _state.value
        if (current.saving || current.loading || current.missing || closing ||
            current.photoStatus == MealPhotoStatus.PROCESSING
        ) return
        val hasPhoto = current.newPhotoTicket != null ||
            (current.originalPhotoId != null && !current.photoRemoved)
        mealValidationError(current.text, hasPhoto)?.let { error ->
            update { it.copy(error = error) }
            return
        }
        val satietyText = if (current.satiety == Satiety.CUSTOM) current.satietyText.trim() else null
        if (current.satiety == Satiety.CUSTOM && satietyText.isNullOrEmpty()) {
            update { it.copy(error = MealUiError.SATIETY_TEXT_REQUIRED) }
            return
        }
        if (satietyText != null &&
            satietyText.codePointCount(0, satietyText.length) > Rules.SATIETY_TEXT_LIMIT
        ) {
            update { it.copy(error = MealUiError.SATIETY_TEXT_TOO_LONG) }
            return
        }
        val occurredAt = try {
            // An existing instant already disambiguates a repeated DST local time.
            // Editing text/photo must preserve that exact instant, including milliseconds.
            originalOccurredAt?.takeIf {
                current.date == originalDate && current.time == originalTime && current.zoneId == originalZoneId &&
                    (current.timeOffset == null || current.timeOffset == originalOffset)
            } ?: RecordTime.resolve(LocalDateTime.of(current.date, current.time), current.zoneId, current.timeOffset?.let(ZoneOffset::of))
        } catch (_: DataViolation) {
            update { it.copy(error = MealUiError.INVALID_TIME) }
            return
        }
        val draft = MealDraft(
            id = draftId,
            occurredAtUtcMs = occurredAt,
            zoneId = current.zoneId,
            text = current.text,
            satiety = current.satiety,
            satietyText = satietyText,
            photoId = if (current.newPhotoTicket != null) {
                null
            } else if (current.photoRemoved) {
                null
            } else {
                current.originalPhotoId
            },
        )
        update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = photos.saveMeal(draft, current.newPhotoTicket)) {
                is PhotoResult.Success -> {
                    // Cleanup cannot undo a committed save; maintain retries release failures.
                    listOfNotNull(current.newPhotoTicket, current.captureTicket).distinct().forEach {
                        photos.release(it)
                    }
                    clearDraft()
                    _state.update { it.copy(saving = false, savedSuccessfully = true, dirty = false) }
                }
                is PhotoResult.Failure -> _state.update {
                    it.copy(
                        saving = false,
                        error = result.dataError?.toMealUiError() ?: result.error.toMealUiError(),
                    )
                }
                PhotoResult.Cancelled -> _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Explicit cancel/discard: release only unreferenced draft files; never touches a saved record. */
    fun discard() {
        if (_state.value.saving) return
        closing = true
        viewModelScope.launch {
            listOfNotNull(_state.value.newPhotoTicket, _state.value.captureTicket).distinct().forEach {
                photos.release(it)
            }
            clearDraft()
            _state.update { it.copy(discarded = true) }
        }
    }

    /** Retry a transient database read failure without losing the draft. */
    fun retryLoad() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, loadFailed = false, missing = false) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val existing: MealRecord? = if (mealId == null) {
            null
        } else {
            try {
                records.meal(mealId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DataViolation) {
                null
            } catch (_: Exception) {
                _state.update { it.copy(loading = false, loadFailed = true) }
                return
            }
        }
        if (mealId != null && existing == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        if (existing != null) {
            originalOccurredAt = existing.occurredAtUtcMs
            originalZoneId = existing.zoneId
            originalText = existing.text
            originalSatiety = existing.satiety
            originalSatietyText = existing.satietyText
            originalPhotoId = existing.photoId
            val local = Instant.ofEpochMilli(existing.occurredAtUtcMs).atZone(MealTimeDisplay.zone(existing.zoneId))
            originalDate = local.toLocalDate()
            originalTime = local.toLocalTime()
            originalOffset = local.offset.id
            _state.update { it.copy(originalPhotoId = existing.photoId) }
        }
        val restored = savedStateHandle.get<Boolean>(KEY_LOADED) == true
        if (!restored) {
            if (existing != null) {
                zoneId = existing.zoneId
                _state.update {
                    it.copy(
                        text = existing.text ?: "",
                        satiety = existing.satiety,
                        satietyText = existing.satietyText ?: "",
                        date = originalDate ?: it.date,
                        time = originalTime ?: it.time,
                        zoneId = existing.zoneId,
                        timeOffset = originalOffset,
                    )
                }
            }
            savedStateHandle[KEY_LOADED] = true
            persistDraft()
        }
        restorePhotoState()
        maintainDrafts()
        // Keep the form disabled until the existing photo/ticket has been restored;
        // otherwise a fast tap can race initialization and be overwritten.
        _state.update { it.copy(loading = false, dirty = computeDirty(it)) }
    }

    private suspend fun restorePhotoState() {
        val current = _state.value
        when {
            current.newPhotoTicket != null -> resumePrepared(current.newPhotoTicket)
            current.captureTicket != null -> resumeCaptureTicket(current.captureTicket)
            current.originalPhotoId != null && !current.photoRemoved -> showOriginal()
        }
    }

    private suspend fun resumePrepared(ticket: PhotoTicket) {
        when (val result = photos.resumePrepared(ticket)) {
            is PhotoResult.Success -> {
                val preview = photos.previewUri(ticket)
                _state.update {
                    it.copy(
                        photoPreview = (preview as? PhotoResult.Success)?.value,
                        photoStatus = if (preview is PhotoResult.Success) {
                            MealPhotoStatus.READY
                        } else {
                            MealPhotoStatus.ERROR
                        },
                        photoError = (preview as? PhotoResult.Failure)?.error?.toMealUiError(),
                    )
                }
            }
            is PhotoResult.Failure -> {
                if (result.error == PhotoError.INVALID_TICKET || result.error == PhotoError.MISSING_PHOTO) {
                    savedStateHandle[KEY_PHOTO_TICKET] = null
                    _state.update { it.copy(newPhotoTicket = null) }
                    if (!_state.value.photoRemoved) showOriginal()
                } else {
                    _state.update {
                        it.copy(photoStatus = MealPhotoStatus.ERROR, photoError = result.error.toMealUiError())
                    }
                }
            }
            PhotoResult.Cancelled -> Unit
        }
    }

    private suspend fun resumeCaptureTicket(ticket: PhotoTicket) {
        when (val result = photos.resumeCapture(ticket)) {
            // Validate the same URI without launching: the restored screen must not
            // reopen the camera by itself; the user can tap "continue" instead.
            is PhotoResult.Success -> _state.update { it.copy(photoStatus = MealPhotoStatus.CAPTURE_PENDING) }
            is PhotoResult.Failure -> {
                if (result.error == PhotoError.INVALID_TICKET) {
                    savedStateHandle[KEY_CAPTURE_TICKET] = null
                    _state.update { it.copy(captureTicket = null) }
                    if (!_state.value.photoRemoved) showOriginal()
                } else {
                    _state.update {
                        it.copy(
                            photoStatus = MealPhotoStatus.CAPTURE_PENDING,
                            photoError = result.error.toMealUiError(),
                        )
                    }
                }
            }
            PhotoResult.Cancelled -> Unit
        }
    }

    private suspend fun showOriginal() {
        val id = _state.value.originalPhotoId ?: return
        when (val result = photos.photoUri(id)) {
            is PhotoResult.Success -> _state.update {
                it.copy(photoPreview = result.value, photoStatus = MealPhotoStatus.READY)
            }
            is PhotoResult.Failure -> _state.update {
                it.copy(
                    photoPreview = null,
                    photoStatus = MealPhotoStatus.ERROR,
                    photoError = result.error.toMealUiError(),
                )
            }
            PhotoResult.Cancelled -> Unit
        }
    }

    private suspend fun maintainDrafts() {
        val tickets = setOfNotNull(_state.value.newPhotoTicket, _state.value.captureTicket)
        if (tickets.isEmpty()) return
        val result = photos.maintain(tickets)
        if (result is PhotoResult.Failure) {
            _state.update { it.copy(photoError = result.error.toMealUiError()) }
        }
    }

    private fun update(transform: (MealEditorUiState) -> MealEditorUiState) {
        _state.update { current ->
            transform(current).let { next -> next.copy(dirty = computeDirty(next)) }
        }
        persistDraft()
    }

    /**
     * New meals are dirty as soon as any content exists. Edits compare with the loaded
     * record, so merely opening and closing the page does not prompt.
     */
    private fun computeDirty(current: MealEditorUiState): Boolean {
        if (mealId == null) {
            return current.text.isNotBlank() || current.satiety != null ||
                current.newPhotoTicket != null || current.captureTicket != null
        }
        return current.text.trim() != (originalText ?: "") ||
            current.satiety != originalSatiety ||
            (if (current.satiety == Satiety.CUSTOM) current.satietyText.trim() else "") !=
            (if (originalSatiety == Satiety.CUSTOM) originalSatietyText ?: "" else "") ||
            current.date != originalDate ||
            current.time != originalTime ||
            (current.timeOffset != null && originalOffset != null && current.timeOffset != originalOffset) ||
            current.newPhotoTicket != null ||
            current.captureTicket != null ||
            (current.photoRemoved && originalPhotoId != null)
    }

    private fun persistDraft() {
        if (savedStateHandle.get<Boolean>(KEY_LOADED) != true) return
        val current = _state.value
        savedStateHandle[KEY_TEXT] = current.text
        savedStateHandle[KEY_SATIETY] = current.satiety?.name
        savedStateHandle[KEY_SATIETY_TEXT] = current.satietyText
        savedStateHandle[KEY_DATE] = current.date.toString()
        savedStateHandle[KEY_TIME] = current.time.toString()
        savedStateHandle[KEY_ZONE] = current.zoneId
        savedStateHandle[KEY_OFFSET] = current.timeOffset
        savedStateHandle[KEY_PHOTO_TICKET] = current.newPhotoTicket?.id
        savedStateHandle[KEY_CAPTURE_TICKET] = current.captureTicket?.id
        savedStateHandle[KEY_PHOTO_REMOVED] = current.photoRemoved
    }

    private fun clearDraft() {
        listOf(
            KEY_ID, KEY_LOADED, KEY_TEXT, KEY_SATIETY, KEY_SATIETY_TEXT, KEY_DATE, KEY_TIME, KEY_ZONE,
            KEY_PHOTO_TICKET, KEY_CAPTURE_TICKET, KEY_PHOTO_REMOVED, KEY_OFFSET,
        ).forEach { savedStateHandle.remove<Any?>(it) }
    }
}
