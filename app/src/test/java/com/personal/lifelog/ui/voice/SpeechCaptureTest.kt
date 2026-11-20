package com.personal.lifelog.ui.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechCaptureTest {
    private class Engine : SpeechEngine {
        lateinit var final: (List<String>?) -> Unit
        lateinit var error: (Int) -> Unit
        lateinit var ready: () -> Unit
        var starts = 0; var stops = 0; var cancels = 0; var destroys = 0
        var throwStart = false
        override fun start(final: (List<String>?) -> Unit, error: (Int) -> Unit, ready: () -> Unit) {
            this.final = final; this.error = error; this.ready = ready; starts++
            if (throwStart) throw IllegalStateException("synthetic failure")
        }
        override fun stop() { stops++ }
        override fun cancel() { cancels++; if (::final.isInitialized) final(listOf("取消时的迟到结果")) }
        override fun destroy() { destroys++ }
    }
    @Test fun finalCandidateIsDeliveredOnceAndEngineIsReleasedBeforeCallback() {
        val engine = Engine(); val output = mutableListOf<String?>()
        val capture = SpeechCapture(engine, { assertEquals(1, engine.destroys); output += it }, {}, {})
        capture.start(); capture.start(); engine.final(listOf("第一候选", "第二候选")); engine.final(listOf("重复")); engine.error(1)
        assertEquals(listOf("第一候选"), output); assertEquals(1, engine.starts)
        assertEquals(1, engine.cancels); assertEquals(1, engine.destroys)
    }
    @Test fun cancellationNeverAppendsAndLateReadyErrorsAreInert() {
        val engine = Engine(); val output = mutableListOf<String?>(); var ready = 0; var errors = 0
        val capture = SpeechCapture(engine, { output += it }, { errors++ }, { ready++ })
        capture.start(); capture.cancel(); capture.cancel(); engine.ready(); engine.final(listOf("迟到")); engine.error(2)
        assertTrue(output.isEmpty()); assertEquals(0, ready); assertEquals(0, errors); assertEquals(1, engine.destroys)
    }
    @Test fun stoppingRepeatedlyDoesNotSendRepeatedCommandsAndCanStillReturnFinal() {
        val engine = Engine(); val output = mutableListOf<String?>()
        val capture = SpeechCapture(engine, { output += it }, {}, {})
        capture.start(); capture.stop(); capture.stop(); engine.final(listOf("结束识别"))
        assertEquals(1, engine.stops); assertEquals(listOf("结束识别"), output)
    }
    @Test fun serviceFailureReleasesAndCannotBecomeASuccessLater() {
        val engine = Engine(); var failures = 0; val output = mutableListOf<String?>()
        val capture = SpeechCapture(engine, { output += it }, { failures++ }, {})
        capture.start(); engine.error(7); engine.error(7); engine.final(listOf("迟到"))
        assertEquals(1, failures); assertTrue(output.isEmpty()); assertEquals(1, engine.destroys)
    }
    @Test fun startExceptionReleasesWithoutLeakingOrRetrying() {
        val engine = Engine().apply { throwStart = true }; var failures = 0
        val capture = SpeechCapture(engine, { fail() }, { failures++ }, {})
        capture.start(); capture.start(); assertEquals(1, failures); assertEquals(1, engine.starts); assertEquals(1, engine.destroys)
    }
    @Test fun emptyAndBlankCandidatesRemainEmptyForTheUiToOfferRetry() {
        val engine = Engine(); val output = mutableListOf<String?>()
        val capture = SpeechCapture(engine, { output += it }, {}, {})
        capture.start(); engine.final(listOf(" ", "")); assertEquals(listOf<String?>(null), output)
    }
}
