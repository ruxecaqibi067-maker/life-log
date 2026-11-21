package com.personal.lifelog.data.aireview

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.domain.AiReviewSessionStore
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.DeepSeekResult
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeepSeekClientTest {

    private fun locateRepoFile(relativePath: String): File {
        val candidates = listOf(File(relativePath), File("../$relativePath"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relativePath from ${File(".").absolutePath}")
    }

    private fun extractSectionOneSystemPrompt(rulesMarkdown: String): String {
        val normalized = rulesMarkdown.replace("\r\n", "\n")
        val match = Regex("```text\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(normalized)
            ?: error("System prompt code block missing from daily-rules.v1.md")
        return match.groupValues[1]
    }

    @Test
    fun packagedAssetsMatchAcceptedProtocolDocsCharacterForCharacter() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = AiReviewAssets(context)

        val expectedPrompt = extractSectionOneSystemPrompt(
            locateRepoFile("docs/ai-review/daily-rules.v1.md").readText(Charsets.UTF_8),
        )
        val expectedExample = locateRepoFile("docs/ai-review/daily-example.v1.json")
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")

        assertEquals(expectedPrompt, assets.systemPrompt().getOrThrow())
        assertEquals(expectedExample, assets.sampleExampleJson().getOrThrow())
    }

    @Test
    fun requestEncodingPreservesEndpointAuthHeaderModelAndExactTwoMessages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = AiReviewAssets(context)
        val session = AiReviewSessionStore()
        val fakeKey = "sk-synthetic-unit-test-only"
        session.setApiKey("  $fakeKey  ")

        val captured = mutableListOf<DeepSeekHttpRequest>()
        val service = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport { req ->
                captured += req
                DeepSeekHttpResponse(
                    statusCode = 200,
                    body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"这是一段测试回顾。"}}]}""",
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = service.sendSampleReview()
        assertEquals(DeepSeekResult.Success("这是一段测试回顾。"), result)
        assertEquals(1, captured.size)

        val req = captured.single()
        assertEquals("https://api.deepseek.com/chat/completions", req.url)
        assertTrue("Authorization header must carry Bearer token", req.authorizationHeader == "Bearer $fakeKey")
        assertFalse("Request toString must never leak credentials", req.toString().contains(fakeKey))
        assertFalse("Session toString must never leak credentials", session.toString().contains(fakeKey))

        val decoded = JSONObject(req.bodyJson)
        assertEquals("deepseek-flash", decoded.getString("model"))
        assertFalse(decoded.getBoolean("stream"))
        assertEquals("disabled", decoded.getJSONObject("thinking").getString("type"))

        val messages = decoded.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(assets.systemPrompt().getOrThrow(), messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(assets.sampleExampleJson().getOrThrow(), messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun mapsHttpErrorsTimeoutsNetworkAndMalformedOrTruncatedSuccessResponses() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = AiReviewAssets(context)
        val session = AiReviewSessionStore()
        var transportCalls = 0
        var nextBehavior: () -> DeepSeekHttpResponse = { error("unset") }

        val service = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport {
                transportCalls++
                nextBehavior()
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        // Blank or malformed key never invokes transport.
        assertEquals(DeepSeekResult.Failure(DeepSeekError.MISSING_API_KEY), service.sendDailyReview("{}"))
        session.setApiKey("bad\nkey")
        assertEquals(DeepSeekResult.Failure(DeepSeekError.INVALID_API_KEY), service.sendDailyReview("{}"))
        assertEquals(0, transportCalls)

        session.setApiKey("sk-synthetic-unit-test-only")

        // HTTP status mappings.
        val statusCases = listOf(
            401 to DeepSeekError.UNAUTHORIZED,
            402 to DeepSeekError.INSUFFICIENT_BALANCE,
            429 to DeepSeekError.RATE_LIMITED,
            400 to DeepSeekError.BAD_REQUEST,
            422 to DeepSeekError.BAD_REQUEST,
            302 to DeepSeekError.NETWORK,
            500 to DeepSeekError.SERVER,
            503 to DeepSeekError.SERVER,
        )
        for ((code, expectedError) in statusCases) {
            nextBehavior = { DeepSeekHttpResponse(code, """{"error":{"message":"err"}}""") }
            assertEquals("HTTP $code mismatch", DeepSeekResult.Failure(expectedError), service.sendDailyReview("{}"))
        }

        // Transport timeout & IO errors.
        nextBehavior = { throw SocketTimeoutException("read timed out") }
        assertEquals(DeepSeekResult.Failure(DeepSeekError.TIMEOUT), service.sendDailyReview("{}"))
        nextBehavior = { throw IOException("offline") }
        assertEquals(DeepSeekResult.Failure(DeepSeekError.NETWORK), service.sendDailyReview("{}"))

        // Malformed / empty / truncated HTTP 200 responses.
        val bad200Bodies = listOf(
            "" to DeepSeekError.MALFORMED_RESPONSE,
            "{not-json" to DeepSeekError.MALFORMED_RESPONSE,
            """{"choices":[]}""" to DeepSeekError.MALFORMED_RESPONSE,
            """{"choices":[{"message":{"content":"无结束原因"}}]}""" to DeepSeekError.MALFORMED_RESPONSE,
            """{"choices":[{"finish_reason":"length","message":{"content":"被截断"}}]}""" to DeepSeekError.TRUNCATED,
            """{"choices":[{"finish_reason":"content_filter","message":{"content":"被过滤"}}]}""" to DeepSeekError.TRUNCATED,
            """{"choices":[{"finish_reason":"stop","message":{"content":""}}]}""" to DeepSeekError.EMPTY_CONTENT,
            """{"choices":[{"finish_reason":"stop","message":{"content":"   "}}]}""" to DeepSeekError.EMPTY_CONTENT,
            """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""" to DeepSeekError.EMPTY_CONTENT,
        )
        for ((body, expectedError) in bad200Bodies) {
            nextBehavior = { DeepSeekHttpResponse(200, body) }
            assertEquals("Body failure mismatch for $body", DeepSeekResult.Failure(expectedError), service.sendDailyReview("{}"))
        }
    }
}
