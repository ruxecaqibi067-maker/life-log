package com.personal.lifelog.feature.meal

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.media.*
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.domain.*
import java.time.*
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Ordered draft photos are stable IDs/tickets; no transient source URI enters saved state. */
class MealEditorViewModel(
    private val mealId: String?, private val savedStateHandle: SavedStateHandle,
    private val records: RecordRepository, private val photos: PhotoStorage,
    private val clock: Clock, private val zoneIdProvider: () -> ZoneId,
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
        const val KEY_ITEMS = "meal.draft.photoItems"
        const val KEY_CAPTURE_TICKET = "meal.draft.captureTicket"
        const val MAX_RAW_TEXT_LENGTH = 4000
    }
    private val draftId = mealId ?: savedStateHandle.get<String>(KEY_ID) ?: RecordIds.newId().also { savedStateHandle[KEY_ID] = it }
    val isEditing = mealId != null
    private var original: MealRecord? = null
    private var originalIds: List<String> = emptyList()
    private var originalDate: LocalDate? = null
    private var originalTime: LocalTime? = null
    private var originalOffset: String? = null
    private var closing = false
    private val initialLocal = clock.instant().atZone(runCatching { ZoneId.of(savedStateHandle.get<String>(KEY_ZONE)) }.getOrDefault(zoneIdProvider()))
    private val _state = MutableStateFlow(MealEditorUiState(
        text = savedStateHandle[KEY_TEXT] ?: "",
        satiety = savedStateHandle.get<String>(KEY_SATIETY)?.let { runCatching { Satiety.valueOf(it) }.getOrNull() },
        satietyText = savedStateHandle[KEY_SATIETY_TEXT] ?: "",
        date = savedStateHandle.get<String>(KEY_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: initialLocal.toLocalDate(),
        time = savedStateHandle.get<String>(KEY_TIME)?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: initialLocal.toLocalTime(),
        zoneId = savedStateHandle[KEY_ZONE] ?: zoneIdProvider().id,
        timeOffset = savedStateHandle[KEY_OFFSET], captureTicket = savedStateHandle.get<String>(KEY_CAPTURE_TICKET)?.let(::PhotoTicket),
    ))
    val state: StateFlow<MealEditorUiState> = _state.asStateFlow()
    init { viewModelScope.launch { load() } }
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


    fun onPhotoPicked(uri: Uri?) {
        val current = state.value
        if (uri == null || current.saving || current.photoStatus == MealPhotoStatus.PROCESSING || closing) return
        if (current.photoItems.size >= 9) { update { it.copy(photoError = MealUiError.PHOTO_LIMIT) }; return }
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null, error = null) }
        viewModelScope.launch {
            when (val result = photos.importPhoto(uri)) {
                is PhotoResult.Success -> {
                    if (closing) { photos.release(result.value.ticket); return@launch }
                    current.captureTicket?.let { photos.release(it) }
                    append(result.value.ticket)
                }
                is PhotoResult.Failure -> update { it.copy(photoStatus = status(it), photoError = result.error.toMealUiError()) }
                PhotoResult.Cancelled -> update { it.copy(photoStatus = status(it)) }
            }
        }
    }
    fun removePhoto() {
        val first = state.value.photoItems.firstOrNull()
        if (first == null) cancelPendingCapture() else removePhoto(first.photoId)
    }
    fun cancelPendingCapture() {
        val current = state.value
        if (current.saving || current.photoStatus == MealPhotoStatus.PROCESSING) return
        val ticket = current.captureTicket ?: return
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING) }
        viewModelScope.launch {
            photos.release(ticket)
            update { it.copy(captureTicket = null, captureRequest = null, photoStatus = status(it), photoError = null) }
        }
    }
    fun removePhoto(id: String) {
        val current = state.value
        if (current.saving || current.photoStatus == MealPhotoStatus.PROCESSING) return
        val item = current.photoItems.find { it.photoId == id } ?: return
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING) }
        viewModelScope.launch {
            item.ticket?.let { photos.release(it) }
            update { it.copy(photoItems = it.photoItems.filterNot { item -> item.photoId == id }, photoError = null,
                photoStatus = if (it.photoItems.size > 1) MealPhotoStatus.READY else MealPhotoStatus.NONE) }
        }
    }
    fun beginCapture() {
        val current = state.value
        if (current.saving || current.photoStatus == MealPhotoStatus.PROCESSING || closing) return
        if (current.photoItems.size >= 9) { update { it.copy(photoError = MealUiError.PHOTO_LIMIT) }; return }
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null) }
        viewModelScope.launch {
            val result = current.captureTicket?.let { photos.resumeCapture(it) } ?: photos.beginCapture()
            when (result) {
                is PhotoResult.Success -> {
                    if (closing) { photos.release(result.value.ticket); return@launch }
                    update { it.copy(captureTicket = result.value.ticket, captureRequest = result.value, photoStatus = MealPhotoStatus.CAPTURE_PENDING) }
                }
                is PhotoResult.Failure -> update { it.copy(photoStatus = status(it),
                    captureTicket = if (result.error == PhotoError.INVALID_TICKET) null else it.captureTicket, photoError = result.error.toMealUiError()) }
                PhotoResult.Cancelled -> update { it.copy(photoStatus = status(it)) }
            }
        }
    }
    fun onCaptureRequestLaunched() { update { it.copy(captureRequest = null) } }
    fun onCaptureLaunchFailed() {
        val ticket = state.value.captureTicket
        viewModelScope.launch {
            ticket?.let { photos.release(it) }
            update { it.copy(captureTicket = null, captureRequest = null, photoStatus = status(it), photoError = MealUiError.SOURCE_UNAVAILABLE) }
        }
    }
    fun onCaptureResult(succeeded: Boolean) {
        val ticket = state.value.captureTicket ?: return
        update { it.copy(photoStatus = MealPhotoStatus.PROCESSING, photoError = null) }
        viewModelScope.launch {
            when (val result = photos.finishCapture(ticket, succeeded)) {
                is PhotoResult.Success -> {
                    if (closing) { photos.release(ticket); return@launch }
                    append(result.value.ticket)
                }
                is PhotoResult.Failure -> update { it.copy(captureRequest = null, photoStatus = MealPhotoStatus.CAPTURE_PENDING,
                    photoError = result.error.toMealUiError()) }
                PhotoResult.Cancelled -> update { it.copy(captureTicket = null, captureRequest = null, photoStatus = status(it)) }
            }
        }
    }
    private suspend fun append(ticket: PhotoTicket) {
        val preview = photos.previewUri(ticket)
        update { it.copy(photoItems = it.photoItems + MealPhotoItem(ticket.id, ticket, (preview as? PhotoResult.Success)?.value,
            preview is PhotoResult.Failure), captureTicket = null, captureRequest = null,
            photoStatus = MealPhotoStatus.READY, photoError = (preview as? PhotoResult.Failure)?.error?.toMealUiError()) }
    }
    fun save() {
        val current = state.value
        if (current.saving || current.loading || current.missing || closing || current.photoStatus == MealPhotoStatus.PROCESSING) return
        mealValidationError(current.text, current.photoItems.isNotEmpty())?.let { error -> update { it.copy(error = error) }; return }
        val satietyText = if (current.satiety == Satiety.CUSTOM) current.satietyText.trim() else null
        if (current.satiety == Satiety.CUSTOM && satietyText.isNullOrEmpty()) { update { it.copy(error = MealUiError.SATIETY_TEXT_REQUIRED) }; return }
        if (satietyText != null && satietyText.codePointCount(0, satietyText.length) > Rules.SATIETY_TEXT_LIMIT) {
            update { it.copy(error = MealUiError.SATIETY_TEXT_TOO_LONG) }; return
        }
        val occurredAt = try {
            original?.takeIf { current.date == originalDate && current.time == originalTime && current.zoneId == it.zoneId &&
                (current.timeOffset == null || current.timeOffset == originalOffset) }?.occurredAtUtcMs
                ?: RecordTime.resolve(LocalDateTime.of(current.date, current.time), current.zoneId, current.timeOffset?.let(ZoneOffset::of))
        } catch (_: DataViolation) { update { it.copy(error = MealUiError.INVALID_TIME) }; return }
        val draft = MealDraft(draftId, occurredAt, current.zoneId, current.text, current.satiety,
            current.photoItems.firstOrNull()?.photoId, satietyText, current.photoItems.map { it.photoId })
        update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = photos.saveMealPhotos(draft, current.photoItems.mapNotNull { it.ticket })) {
                is PhotoResult.Success -> {
                    (current.photoItems.mapNotNull { it.ticket } + listOfNotNull(current.captureTicket)).distinct().forEach { photos.release(it) }
                    clearDraft(); _state.update { it.copy(saving = false, savedSuccessfully = true, dirty = false) }
                }
                is PhotoResult.Failure -> update { it.copy(saving = false, error = result.dataError?.toMealUiError() ?: result.error.toMealUiError()) }
                PhotoResult.Cancelled -> update { it.copy(saving = false) }
            }
        }
    }
    fun discard() {
        if (state.value.saving) return
        closing = true
        viewModelScope.launch {
            (state.value.photoItems.mapNotNull { it.ticket } + listOfNotNull(state.value.captureTicket)).distinct().forEach { photos.release(it) }
            clearDraft(); _state.update { it.copy(discarded = true) }
        }
    }
    fun retryLoad() { if (!state.value.loading) { _state.update { it.copy(loading = true, loadFailed = false, missing = false) }; viewModelScope.launch { load() } } }
    private suspend fun load() {
        val old = try { mealId?.let { records.meal(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.update { it.copy(loading = false, loadFailed = true) }; return }
        if (mealId != null && old == null) { _state.update { it.copy(loading = false, missing = true) }; return }
        original = old
        originalIds = if (old == null) emptyList() else try { records.mealPhotoIds(old.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(loading = false, loadFailed = true) }; return }
        old?.let {
            val local = Instant.ofEpochMilli(it.occurredAtUtcMs).atZone(MealTimeDisplay.zone(it.zoneId))
            originalDate = local.toLocalDate(); originalTime = local.toLocalTime(); originalOffset = local.offset.id
        }
        val restored = savedStateHandle.get<Boolean>(KEY_LOADED) == true
        if (!restored && old != null) _state.update { it.copy(text = old.text.orEmpty(), satiety = old.satiety,
            satietyText = old.satietyText.orEmpty(), date = originalDate!!, time = originalTime!!, zoneId = old.zoneId, timeOffset = originalOffset) }
        val savedItems = savedStateHandle.get<ArrayList<String>>(KEY_ITEMS)
        val items = if (savedItems != null) savedItems.mapNotNull { encoded ->
            val parts = encoded.split('|'); parts.firstOrNull()?.takeIf { runCatching { Rules.id(it) }.isSuccess }
                ?.let { MealPhotoItem(it, parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let(::PhotoTicket)) }
        } else originalIds.map { MealPhotoItem(it) }
        val loaded = items.map { item ->
            val result = item.ticket?.let { ticket ->
                when (photos.resumePrepared(ticket)) {
                    is PhotoResult.Success -> photos.previewUri(ticket)
                    is PhotoResult.Failure -> PhotoResult.Failure(PhotoError.MISSING_PHOTO)
                    PhotoResult.Cancelled -> PhotoResult.Cancelled
                }
            } ?: photos.photoUri(item.photoId)
            item.copy(preview = (result as? PhotoResult.Success)?.value, missing = result !is PhotoResult.Success)
        }
        _state.update { it.copy(photoItems = loaded, originalPhotoId = old?.photoId,
            photoStatus = if (loaded.isEmpty()) MealPhotoStatus.NONE else MealPhotoStatus.READY,
            photoError = if (loaded.any { it.missing }) MealUiError.PHOTO_INVALID else null) }
        state.value.captureTicket?.let { ticket ->
            when (val result = photos.resumeCapture(ticket)) {
                is PhotoResult.Success -> _state.update { it.copy(photoStatus = MealPhotoStatus.CAPTURE_PENDING) }
                is PhotoResult.Failure -> _state.update { it.copy(captureTicket = null, photoError = result.error.toMealUiError()) }
                PhotoResult.Cancelled -> Unit
            }
        }
        savedStateHandle[KEY_LOADED] = true
        maintainDrafts()
        update { it.copy(loading = false) }
    }
    private suspend fun maintainDrafts() {
        val tickets = (state.value.photoItems.mapNotNull { it.ticket } + listOfNotNull(state.value.captureTicket)).toSet()
        if (tickets.isNotEmpty()) {
            val result = photos.maintain(tickets)
            if (result is PhotoResult.Failure) _state.update { it.copy(photoError = result.error.toMealUiError()) }
        }
    }
    private fun status(current: MealEditorUiState) = if (current.photoItems.isEmpty()) MealPhotoStatus.NONE else MealPhotoStatus.READY
    private fun update(transform: (MealEditorUiState) -> MealEditorUiState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(photoPreview = next.photoItems.firstOrNull()?.preview,
                newPhotoTicket = next.photoItems.firstOrNull { it.ticket != null }?.ticket,
                photoRemoved = original?.photoId != null && original?.photoId !in next.photoItems.map { it.photoId },
                dirty = computeDirty(next))
        }
        persistDraft()
    }
    private fun computeDirty(s: MealEditorUiState): Boolean {
        val old = original
        if (mealId == null) return s.text.isNotBlank() || s.satiety != null || s.photoItems.isNotEmpty() || s.captureTicket != null
        return s.text.trim() != old?.text.orEmpty() || s.satiety != old?.satiety ||
            (if (s.satiety == Satiety.CUSTOM) s.satietyText.trim() else "") != (if (old?.satiety == Satiety.CUSTOM) old.satietyText.orEmpty() else "") ||
            s.date != originalDate || s.time != originalTime || (s.timeOffset != null && s.timeOffset != originalOffset) ||
            s.photoItems.map { it.photoId } != originalIds || s.captureTicket != null
    }
    private fun persistDraft() {
        if (savedStateHandle.get<Boolean>(KEY_LOADED) != true) return
        val s=state.value
        savedStateHandle[KEY_TEXT]=s.text; savedStateHandle[KEY_SATIETY]=s.satiety?.name; savedStateHandle[KEY_SATIETY_TEXT]=s.satietyText
        savedStateHandle[KEY_DATE]=s.date.toString(); savedStateHandle[KEY_TIME]=s.time.toString(); savedStateHandle[KEY_ZONE]=s.zoneId; savedStateHandle[KEY_OFFSET]=s.timeOffset
        savedStateHandle[KEY_ITEMS]=ArrayList(s.photoItems.map { it.photoId + "|" + it.ticket?.id.orEmpty() })
        savedStateHandle[KEY_CAPTURE_TICKET]=s.captureTicket?.id
    }
    private fun clearDraft() { listOf(KEY_ID,KEY_LOADED,KEY_TEXT,KEY_SATIETY,KEY_SATIETY_TEXT,KEY_DATE,KEY_TIME,KEY_ZONE,KEY_OFFSET,KEY_ITEMS,KEY_CAPTURE_TICKET).forEach { savedStateHandle.remove<Any?>(it) } }
}
