package com.personal.lifelog.data.aireview

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HttpDeepSeekTransportCancellationTest {
    @Test fun cancellationDisconnectsBlockedConnectionBeforeItsReadTimeout() = runBlocking {
        val reading = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : HttpsURLConnection(URL(DeepSeekReviewService.ENDPOINT)) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { disconnected.countDown() }
            override fun getCipherSuite() = "synthetic"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                reading.countDown()
                check(disconnected.await(5, TimeUnit.SECONDS)) { "Connection was not cancelled" }
                throw IOException("Synthetic connection closed")
            }
        }
        val transport = HttpDeepSeekTransport(openConnection = { connection })
        val job = launch(Dispatchers.IO) {
            transport.post(DeepSeekHttpRequest(DeepSeekReviewService.ENDPOINT, "Bearer synthetic-test", "{}"))
        }
        try {
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(disconnected.await(1, TimeUnit.SECONDS))
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
        } finally {
            connection.disconnect()
            job.cancel()
        }
    }
}
