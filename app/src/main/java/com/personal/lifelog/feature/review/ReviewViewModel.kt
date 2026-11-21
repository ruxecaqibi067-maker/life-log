package com.personal.lifelog.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.domain.DailyReviewSender
import com.personal.lifelog.domain.DailyReviewSnapshot
import com.personal.lifelog.domain.DailyReviewSource
import com.personal.lifelog.domain.DataError
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.DeepSeekResult
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.feature.timeline.RecordedDatesState
import com.personal.lifelog.feature.timeline.TimelineUiState
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch

/** In-memory AI review preview state for the Review screen; never persisted to SavedStateHandle or storage. */
sealed interface DailyReviewPreviewState {
    data object Idle : DailyReviewPreviewState
    data class Loading(val date: LocalDate) : DailyReviewPreviewState
    data class Ready(
        val date: LocalDate,
        val snapshot: DailyReviewSnapshot,
        /** Unique monotonic identifier per preview generation, distinguishing two previews on the same date. */
        val generationId: Long = 0L,
    ) : DailyReviewPreviewState
    data class Stale(val date: LocalDate) : DailyReviewPreviewState
    data class Failed(val date: LocalDate, val error: DataError) : DailyReviewPreviewState
}

/** In-memory send/result state strictly bound to one [generationId] and [date] of [DailyReviewPreviewState.Ready]. */
sealed interface DailyReviewSendState {
    data object Idle : DailyReviewSendState
    data class Confirming(val generationId: Long, val date: LocalDate) : DailyReviewSendState
    data class Sending(val generationId: Long, val date: LocalDate) : DailyReviewSendState
    data class Succeeded(val generationId: Long, val date: LocalDate, val content: String) : DailyReviewSendState
    data class Failed(val generationId: Long, val date: LocalDate, val error: DeepSeekError) : DailyReviewSendState
}

/**
 * Review keeps only the selected date in [SavedStateHandle]; every record shown is
 * queried from Room for that stored local date. The AI review preview payload and
 * any generated Chinese review text are held exclusively in ViewModel memory,
 * invalidated on date switch, relevant database changes, or exit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val timelineRepository: TimelineRepository,
    private val saved: SavedStateHandle,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
    private val dailyReviewSource: DailyReviewSource?,
    private val dailyReviewSender: DailyReviewSender? = null,
) : ViewModel() {
    constructor(
        timelineRepository: TimelineRepository,
        saved: SavedStateHandle,
        clock: Clock,
        zoneIdProvider: () -> ZoneId,
    ) : this(timelineRepository, saved, clock, zoneIdProvider, null, null)

    constructor(
        timelineRepository: TimelineRepository,
        saved: SavedStateHandle,
        clock: Clock,
        zoneIdProvider: () -> ZoneId,
        dailyReviewSource: DailyReviewSource?,
    ) : this(timelineRepository, saved, clock, zoneIdProvider, dailyReviewSource, null)

    private val retry = MutableStateFlow(0)
    private val selected = MutableStateFlow(initialDate())
    val date: StateFlow<LocalDate> = selected.asStateFlow()

    val supportsAiReviewPreview: Boolean get() = dailyReviewSource != null
    val supportsAiReviewSend: Boolean get() = dailyReviewSource != null && dailyReviewSender != null

    val hasAiReviewApiKey: StateFlow<Boolean> = (dailyReviewSender?.session?.apiKey?.map { it.trim().isNotEmpty() }
        ?: flowOf(false))
        .stateIn(viewModelScope, SharingStarted.Eagerly, dailyReviewSender?.session?.hasApiKey == true)

    private val _aiReviewPreview = MutableStateFlow<DailyReviewPreviewState>(DailyReviewPreviewState.Idle)
    val aiReviewPreview: StateFlow<DailyReviewPreviewState> = _aiReviewPreview.asStateFlow()

    private val _aiReviewSend = MutableStateFlow<DailyReviewSendState>(DailyReviewSendState.Idle)
    val aiReviewSend: StateFlow<DailyReviewSendState> = _aiReviewSend.asStateFlow()

    private var previewJob: Job? = null
    private var previewRequestSeq: Long = 0L
    private var sendJob: Job? = null
    private var sendRequestSeq: Long = 0L

    val day: StateFlow<TimelineUiState> = combine(selected, retry) { date, _ -> date }
        .flatMapLatest { date ->
            timelineRepository.timelineOn(date.toString())
                .map { entries -> TimelineUiState(date = date, loading = false, entries = entries) }
                .onStart { emit(TimelineUiState(date = date, loading = true)) }
                .catch { emit(TimelineUiState(date = date, loading = false, failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimelineUiState(loading = true))

    val recordedDates: StateFlow<RecordedDatesState> = retry
        .flatMapLatest {
            timelineRepository.datesWithRecords()
                .map { dates -> RecordedDatesState(dates = dates) }
                .catch { emit(RecordedDatesState(failed = true)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecordedDatesState())

    fun selectDate(date: LocalDate) {
        val changed = date != selected.value
        saved[KEY_DATE] = date.toString()
        selected.value = date
        if (changed) {
            clearAiReviewPreview()
        }
    }

    fun previousDay() = selectDate(date.value.minusDays(1))
    fun nextDay() = selectDate(date.value.plusDays(1))

    /** "Today" is resolved when tapped, not cached at ViewModel creation. */
    fun goToToday() = selectDate(LocalDate.now(clock.withZone(zoneIdProvider())))

    fun retry() {
        retry.value++
    }

    /** Generates a fresh single-day AI review JSON snapshot for the currently selected date. */
    fun previewAiReview() {
        val source = dailyReviewSource ?: return
        val targetDate = selected.value
        val seq = ++previewRequestSeq
        previewJob?.cancel()
        resetSendState()
        _aiReviewPreview.value = DailyReviewPreviewState.Loading(targetDate)
        previewJob = viewModelScope.launch {
            try {
                source.observeChanges()
                    .onCompletion { cause ->
                        if (cause == null) error("Daily review observation ended unexpectedly")
                    }
                    .take(2)
                    .withIndex()
                    .collectLatest { (index, _) ->
                        val state = if (index == 0) {
                            when (val result = source.generate(targetDate)) {
                                is DataResult.Success -> DailyReviewPreviewState.Ready(
                                    date = targetDate,
                                    snapshot = result.value,
                                    generationId = seq,
                                )
                                is DataResult.Failure -> DailyReviewPreviewState.Failed(targetDate, result.error)
                            }
                        } else {
                            DailyReviewPreviewState.Stale(targetDate)
                        }
                        currentCoroutineContext().ensureActive()
                        if (previewRequestSeq == seq) {
                            if (state !is DailyReviewPreviewState.Ready) {
                                resetSendState()
                            }
                            _aiReviewPreview.value = state
                        }
                    }
            } catch (_: Exception) {
                ensureActive()
                if (previewRequestSeq == seq) {
                    resetSendState()
                    _aiReviewPreview.value = DailyReviewPreviewState.Failed(
                        targetDate,
                        DataError(ErrorCode.STORAGE_UNAVAILABLE),
                    )
                }
            }
        }
    }

    /** Opens the explicit confirmation gate bound to the current valid preview generation. */
    fun requestSendAiReview() {
        if (dailyReviewSender == null) return
        val ready = _aiReviewPreview.value as? DailyReviewPreviewState.Ready ?: return
        if (ready.date != selected.value) return
        if (_aiReviewSend.value is DailyReviewSendState.Sending) return
        _aiReviewSend.value = DailyReviewSendState.Confirming(ready.generationId, ready.date)
    }

    /** Cancels an open confirmation dialog without making any network request. */
    fun cancelSendAiReview() {
        if (_aiReviewSend.value is DailyReviewSendState.Confirming) {
            _aiReviewSend.value = DailyReviewSendState.Idle
        }
    }

    /** Confirms and locks a single send for the currently open confirmation dialog; double-taps send only once. */
    fun confirmSendAiReview() {
        val sender = dailyReviewSender ?: return
        val confirming = _aiReviewSend.value as? DailyReviewSendState.Confirming ?: return
        val ready = _aiReviewPreview.value as? DailyReviewPreviewState.Ready ?: run {
            resetSendState()
            return
        }
        if (ready.generationId != confirming.generationId ||
            ready.date != confirming.date ||
            selected.value != confirming.date
        ) {
            resetSendState()
            return
        }
        val frozenJson = frozenAiReviewJson(confirming.date) ?: run {
            resetSendState()
            return
        }
        launchSend(sender = sender, generationId = ready.generationId, targetDate = confirming.date, frozenJson = frozenJson)
    }

    /** Manually retries sending the exact frozen JSON while the original preview generation is still valid. */
    fun retrySendAiReview() {
        val sender = dailyReviewSender ?: return
        val failed = _aiReviewSend.value as? DailyReviewSendState.Failed ?: return
        val ready = _aiReviewPreview.value as? DailyReviewPreviewState.Ready ?: run {
            resetSendState()
            return
        }
        if (ready.generationId != failed.generationId ||
            ready.date != failed.date ||
            selected.value != failed.date
        ) {
            resetSendState()
            return
        }
        val frozenJson = frozenAiReviewJson(failed.date) ?: run {
            resetSendState()
            return
        }
        launchSend(sender = sender, generationId = ready.generationId, targetDate = failed.date, frozenJson = frozenJson)
    }

    private fun launchSend(
        sender: DailyReviewSender,
        generationId: Long,
        targetDate: LocalDate,
        frozenJson: String,
    ) {
        val sendSeq = ++sendRequestSeq
        sendJob?.cancel()
        _aiReviewSend.value = DailyReviewSendState.Sending(generationId, targetDate)
        sendJob = viewModelScope.launch {
            val result = sender.sendDailyReview(frozenJson)
            val currentPreview = _aiReviewPreview.value as? DailyReviewPreviewState.Ready
            if (sendRequestSeq != sendSeq ||
                currentPreview == null ||
                currentPreview.generationId != generationId ||
                currentPreview.date != targetDate ||
                selected.value != targetDate
            ) {
                return@launch
            }
            _aiReviewSend.value = when (result) {
                is DeepSeekResult.Success -> DailyReviewSendState.Succeeded(generationId, targetDate, result.content)
                is DeepSeekResult.Failure -> DailyReviewSendState.Failed(generationId, targetDate, result.error)
            }
        }
    }

    /** Releases the in-memory preview snapshot, clears send result, and cancels any in-flight work. */
    fun dismissAiReviewPreview() {
        clearAiReviewPreview()
    }

    /**
     * Exact frozen JSON string for [expectedDate], shared by the preview UI and
     * send/retry actions without re-querying Room or advancing snapshotAt.
     */
    fun frozenAiReviewJson(expectedDate: LocalDate = date.value): String? {
        val ready = _aiReviewPreview.value as? DailyReviewPreviewState.Ready ?: return null
        return ready.snapshot.rawJson.takeIf { ready.date == expectedDate && selected.value == expectedDate }
    }

    override fun onCleared() {
        clearAiReviewPreview()
        super.onCleared()
    }

    private fun clearAiReviewPreview() {
        previewRequestSeq++
        previewJob?.cancel()
        previewJob = null
        resetSendState()
        _aiReviewPreview.value = DailyReviewPreviewState.Idle
    }

    private fun resetSendState() {
        sendRequestSeq++
        sendJob?.cancel()
        sendJob = null
        _aiReviewSend.value = DailyReviewSendState.Idle
    }

    private fun initialDate(): LocalDate {
        val today = LocalDate.now(clock.withZone(zoneIdProvider()))
        val stored = saved.get<String>(KEY_DATE) ?: return today
        return runCatching { LocalDate.parse(stored) }.getOrNull() ?: today
    }

    companion object {
        const val KEY_DATE = "reviewDate"
    }
}
