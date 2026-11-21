package com.personal.lifelog.data.aireview

import android.content.Context
import com.personal.lifelog.domain.AiReviewSessionStore
import com.personal.lifelog.domain.DailyReviewSender
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.DeepSeekResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class DeepSeekHttpRequest(
    val url: String,
    val authorizationHeader: String,
    val bodyJson: String,
) {
    /** Never leak credentials or user payload via toString or crash messages. */
    override fun toString(): String = "DeepSeekHttpRequest(url=$url)"
}

data class DeepSeekHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface DeepSeekTransport {
    suspend fun post(request: DeepSeekHttpRequest): DeepSeekHttpResponse
}

class AiReviewAssets(
    private val openAsset: (String) -> InputStream,
) {
    constructor(context: Context) : this({ path -> context.applicationContext.assets.open(path) })

    fun systemPrompt(): Result<String> = readUtf8Normalized(SYSTEM_PROMPT_ASSET)
    fun sampleExampleJson(): Result<String> = readUtf8Normalized(SAMPLE_EXAMPLE_ASSET)

    private fun readUtf8Normalized(path: String): Result<String> = runCatching {
        openAsset(path).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText().replace("\r\n", "\n")
        }
    }

    companion object {
        const val SYSTEM_PROMPT_ASSET = "ai-review/daily-system-prompt.v1.txt"
        const val SAMPLE_EXAMPLE_ASSET = "ai-review/daily-example.v1.json"
    }
}

/**
 * Single-shot HTTPS transport with finite timeouts, disabled redirects, bounded reads,
 * active socket disconnect on coroutine cancellation, and zero request/response logging.
 */
class HttpDeepSeekTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (URL) -> HttpsURLConnection = { url ->
        (url.openConnection() as? HttpsURLConnection) ?: throw IOException("HTTPS connection unavailable")
    },
) : DeepSeekTransport {
    override suspend fun post(request: DeepSeekHttpRequest): DeepSeekHttpResponse = withContext(ioDispatcher) {
        val url = URL(request.url)
        require(url.protocol == "https") { "Only HTTPS endpoints are permitted" }
        val connection = openConnection(url)
        suspendCancellableCoroutine { continuation ->
            // Runs when cancellation starts, even if the HTTPS read has not returned.
            continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.useCaches = false
                connection.doInput = true
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", request.authorizationHeader)

                val bodyBytes = request.bodyJson.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { output ->
                    output.write(bodyBytes)
                    output.flush()
                }

                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseBody = if (stream != null) {
                    readBoundedUtf8(stream, MAX_RESPONSE_BYTES)
                } else {
                    ""
                }
                continuation.resume(DeepSeekHttpResponse(statusCode = statusCode, body = responseBody))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    private fun readBoundedUtf8(stream: InputStream, limitBytes: Int): String {
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limitBytes) throw IOException("Response exceeds size limit")
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 45_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}

class DeepSeekReviewService(
    override val session: AiReviewSessionStore,
    private val assets: AiReviewAssets,
    private val transport: DeepSeekTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DailyReviewSender {

    override fun sampleExampleJson(): Result<String> = assets.sampleExampleJson()

    override suspend fun sendSampleReview(): DeepSeekResult {
        val sample = assets.sampleExampleJson().getOrElse {
            return DeepSeekResult.Failure(DeepSeekError.ASSET_UNAVAILABLE)
        }
        return sendDailyReview(sample)
    }

    override suspend fun sendDailyReview(userContent: String): DeepSeekResult = withContext(ioDispatcher) {
        val apiKey = session.currentApiKey()
        if (apiKey.isEmpty()) {
            return@withContext DeepSeekResult.Failure(DeepSeekError.MISSING_API_KEY)
        }
        if (apiKey.any { it == '\r' || it == '\n' || it.code < 0x20 || it.code == 0x7F }) {
            return@withContext DeepSeekResult.Failure(DeepSeekError.INVALID_API_KEY)
        }
        val systemPrompt = assets.systemPrompt().getOrElse {
            return@withContext DeepSeekResult.Failure(DeepSeekError.ASSET_UNAVAILABLE)
        }
        val request = DeepSeekHttpRequest(
            url = ENDPOINT,
            authorizationHeader = "Bearer $apiKey",
            bodyJson = buildRequestBody(systemPrompt = systemPrompt, userContent = userContent),
        )
        try {
            val response = transport.post(request)
            parseResponse(response.statusCode, response.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            DeepSeekResult.Failure(DeepSeekError.TIMEOUT)
        } catch (_: IOException) {
            DeepSeekResult.Failure(DeepSeekError.NETWORK)
        }
    }

    companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        const val MODEL = "deepseek-flash"

        fun buildRequestBody(systemPrompt: String, userContent: String): String =
            JSONObject()
                .put("model", MODEL)
                .put("stream", false)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userContent)),
                )
                .toString()

        fun parseResponse(statusCode: Int, body: String): DeepSeekResult {
            return when (statusCode) {
                200 -> parseSuccessBody(body)
                401 -> DeepSeekResult.Failure(DeepSeekError.UNAUTHORIZED)
                402 -> DeepSeekResult.Failure(DeepSeekError.INSUFFICIENT_BALANCE)
                429 -> DeepSeekResult.Failure(DeepSeekError.RATE_LIMITED)
                400, 422 -> DeepSeekResult.Failure(DeepSeekError.BAD_REQUEST)
                in 300..399 -> DeepSeekResult.Failure(DeepSeekError.NETWORK)
                else -> DeepSeekResult.Failure(DeepSeekError.SERVER)
            }
        }

        private fun parseSuccessBody(body: String): DeepSeekResult {
            if (body.isBlank()) return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            val root = try {
                JSONObject(body)
            } catch (_: JSONException) {
                return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            }
            val choices = root.optJSONArray("choices")
                ?: return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            if (choices.length() == 0) {
                return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            }
            val firstChoice = choices.optJSONObject(0)
                ?: return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            val finishReason = firstChoice.opt("finish_reason") as? String
            when (finishReason) {
                "stop" -> Unit
                "length", "content_filter", "insufficient_system_resource" -> {
                    return DeepSeekResult.Failure(DeepSeekError.TRUNCATED)
                }
                else -> return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            }
            val message = firstChoice.optJSONObject("message")
                ?: return DeepSeekResult.Failure(DeepSeekError.MALFORMED_RESPONSE)
            val rawContent = message.opt("content") as? String
                ?: return DeepSeekResult.Failure(DeepSeekError.EMPTY_CONTENT)
            val content = rawContent.trim()
            if (content.isEmpty()) {
                return DeepSeekResult.Failure(DeepSeekError.EMPTY_CONTENT)
            }
            return DeepSeekResult.Success(content)
        }
    }
}
