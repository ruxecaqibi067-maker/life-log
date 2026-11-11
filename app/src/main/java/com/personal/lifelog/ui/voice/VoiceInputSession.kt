package com.personal.lifelog.ui.voice

import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceRequest(val token: String, val target: String, val launched: Boolean = false)

/** One outstanding request per editor, persisted separately from the Activity Result registry. */
class VoiceInputSession(private val saved: SavedStateHandle, private val key: String) {
    private val mutableRequest = MutableStateFlow(saved.get<ArrayList<String>>(key)?.let {
        if (it.size == 3) VoiceRequest(it[0], it[1], it[2].toBoolean()) else null
    })
    val request = mutableRequest.asStateFlow()

    fun begin(target: String) {
        if (request.value == null) store(VoiceRequest(UUID.randomUUID().toString(), target))
    }

    /** Persist consumption before launching: restoration must never reopen the recognizer. */
    fun markLaunched(token: String): Boolean {
        val current = request.value?.takeIf { it.token == token && !it.launched } ?: return false
        store(current.copy(launched = true))
        return true
    }

    /** Clear before changing a draft. Duplicates and callbacks from earlier requests are inert. */
    fun consume(token: String): String? {
        val target = request.value?.takeIf { it.token == token }?.target ?: return null
        cancel()
        return target
    }

    fun cancel() = store(null)

    private fun store(value: VoiceRequest?) {
        saved[key] = value?.let { arrayListOf(it.token, it.target, it.launched.toString()) }
        mutableRequest.value = value
    }
}

/** RecognizerIntent returns candidates in confidence order; append only the first usable one. */
internal fun bestVoiceCandidate(candidates: List<String>?): String? =
    candidates?.firstOrNull { it.isNotBlank() }?.trim()

internal fun exceedsVoiceTextLimit(value: String, limit: Int): Boolean =
    value.trim().let { it.codePointCount(0, it.length) > limit }
