package com.personal.lifelog.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DeepSeekError {
    MISSING_API_KEY,
    INVALID_API_KEY,
    UNAUTHORIZED,
    INSUFFICIENT_BALANCE,
    RATE_LIMITED,
    BAD_REQUEST,
    TIMEOUT,
    NETWORK,
    SERVER,
    EMPTY_CONTENT,
    TRUNCATED,
    MALFORMED_RESPONSE,
    ASSET_UNAVAILABLE,
}

sealed interface DeepSeekResult {
    data class Success(val content: String) : DeepSeekResult
    data class Failure(val error: DeepSeekError) : DeepSeekResult
}

/**
 * Process-scoped in-memory holder for the test-build DeepSeek API key.
 * Never persisted to SharedPreferences, Room, files, SavedStateHandle, backups or logs.
 */
class AiReviewSessionStore {
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    val hasApiKey: Boolean
        get() = _apiKey.value.trim().isNotEmpty()

    fun setApiKey(value: String) {
        _apiKey.value = value
    }

    fun clearApiKey() {
        _apiKey.value = ""
    }

    fun currentApiKey(): String = _apiKey.value.trim()

    override fun toString(): String = "AiReviewSessionStore(hasApiKey=$hasApiKey)"
}

interface DailyReviewSender {
    val session: AiReviewSessionStore
    fun sampleExampleJson(): Result<String>
    suspend fun sendDailyReview(userContent: String): DeepSeekResult
    suspend fun sendSampleReview(): DeepSeekResult
}
