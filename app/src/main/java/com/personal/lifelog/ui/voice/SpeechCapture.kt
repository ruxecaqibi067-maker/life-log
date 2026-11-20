package com.personal.lifelog.ui.voice

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

internal interface SpeechEngine {
    fun start(final: (List<String>?) -> Unit, error: (Int) -> Unit, ready: () -> Unit)
    fun stop()
    fun cancel()
    fun destroy()
}

/** One capture owns one engine. Invalidate before releasing: cancel/destroy can invoke callbacks. */
internal class SpeechCapture(
    private val engine: SpeechEngine,
    private val final: (String?) -> Unit,
    private val error: (Int) -> Unit,
    private val ready: () -> Unit,
) {
    private var started = false
    private var closed = false
    private var stopping = false
    fun start() {
        if (closed || started) return
        started = true
        try {
            engine.start({ candidates ->
                if (!closed) { release(); final(bestVoiceCandidate(candidates)) }
            }, { code ->
                if (!closed) { release(); error(code) }
            }, { if (!closed) ready() })
        } catch (_: Exception) { if (!closed) { release(); error(SpeechRecognizer.ERROR_CLIENT) } }
    }
    fun stop() {
        if (!started || closed || stopping) return
        stopping = true
        try { engine.stop() } catch (_: Exception) { release(); error(SpeechRecognizer.ERROR_CLIENT) }
    }
    fun cancel() = release()
    private fun release() {
        if (closed) return
        closed = true
        runCatching { engine.cancel() }
        runCatching { engine.destroy() }
    }
}

/** Explicit component only; never createSpeechRecognizer(context) or an implicit on-device default. */
internal class AndroidSpeechEngine(context: Context, component: ComponentName) : SpeechEngine {
    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(SpeechProviders.allowed(component.packageName, component.className))
    }
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
    override fun start(final: (List<String>?) -> Unit, error: (Int) -> Unit, ready: () -> Unit) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = ready()
            override fun onResults(results: Bundle?) = final(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            override fun onError(code: Int) = error(code)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(voiceRecognitionIntent())
    }
    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}
