package com.personal.lifelog.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.domain.DailyReviewSender
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.DeepSeekResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** In-memory sample connection test state; completely separate from selected-date reviews. */
sealed interface SampleConnectionState {
    data object Idle : SampleConnectionState
    data class Confirming(val sampleJson: String) : SampleConnectionState
    data class Sending(val sampleJson: String) : SampleConnectionState
    data class Succeeded(val sampleJson: String, val content: String) : SampleConnectionState
    data class Failed(val sampleJson: String?, val error: DeepSeekError) : SampleConnectionState
}

/**
 * ViewModel for the isolated DeepSeek test screen:
 * - Binds the masked API key input directly to the process-scoped [DailyReviewSender.session] (memory only).
 * - Runs the built-in fictional sample connection test without ever reading Room.
 */
class AiReviewTestViewModel(
    private val sender: DailyReviewSender,
) : ViewModel() {
    val apiKey: StateFlow<String> = sender.session.apiKey

    private val _sampleState = MutableStateFlow<SampleConnectionState>(SampleConnectionState.Idle)
    val sampleState: StateFlow<SampleConnectionState> = _sampleState.asStateFlow()

    private var sampleJob: Job? = null
    private var sampleSeq: Long = 0L

    fun updateApiKey(value: String) {
        sender.session.setApiKey(value)
    }

    fun clearApiKey() {
        sender.session.clearApiKey()
    }

    /** Opens the sample preview & confirmation panel using the built-in accepted v1 JSON (no Room read). */
    fun openSampleConfirm() {
        if (_sampleState.value is SampleConnectionState.Sending) return
        val sampleJson = sender.sampleExampleJson().getOrElse {
            _sampleState.value = SampleConnectionState.Failed(null, DeepSeekError.ASSET_UNAVAILABLE)
            return
        }
        _sampleState.value = SampleConnectionState.Confirming(sampleJson)
    }

    fun cancelSampleTest() {
        clearSampleState()
    }

    /** Locks the request on confirmation so rapid double-taps send at most once. */
    fun confirmSampleSend() {
        val current = _sampleState.value as? SampleConnectionState.Confirming ?: return
        if (!sender.session.hasApiKey) {
            _sampleState.value = SampleConnectionState.Failed(current.sampleJson, DeepSeekError.MISSING_API_KEY)
            return
        }
        launchSampleSend(current.sampleJson)
    }

    fun retrySampleSend() {
        val current = _sampleState.value as? SampleConnectionState.Failed ?: return
        val sampleJson = current.sampleJson ?: sender.sampleExampleJson().getOrElse {
            _sampleState.value = SampleConnectionState.Failed(null, DeepSeekError.ASSET_UNAVAILABLE)
            return
        }
        if (!sender.session.hasApiKey) {
            _sampleState.value = SampleConnectionState.Failed(sampleJson, DeepSeekError.MISSING_API_KEY)
            return
        }
        launchSampleSend(sampleJson)
    }

    private fun launchSampleSend(sampleJson: String) {
        val seq = ++sampleSeq
        sampleJob?.cancel()
        _sampleState.value = SampleConnectionState.Sending(sampleJson)
        sampleJob = viewModelScope.launch {
            val result = sender.sendDailyReview(sampleJson)
            if (sampleSeq != seq) return@launch
            _sampleState.value = when (result) {
                is DeepSeekResult.Success -> SampleConnectionState.Succeeded(sampleJson, result.content)
                is DeepSeekResult.Failure -> SampleConnectionState.Failed(sampleJson, result.error)
            }
        }
    }

    fun clearSampleState() {
        sampleSeq++
        sampleJob?.cancel()
        sampleJob = null
        _sampleState.value = SampleConnectionState.Idle
    }

    override fun onCleared() {
        clearSampleState()
        super.onCleared()
    }
}
