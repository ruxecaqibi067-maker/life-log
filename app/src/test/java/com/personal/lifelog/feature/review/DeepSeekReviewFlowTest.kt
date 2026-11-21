package com.personal.lifelog.feature.review

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.BuildConfig
import com.personal.lifelog.data.aireview.AiReviewAssets
import com.personal.lifelog.data.aireview.DeepSeekHttpRequest
import com.personal.lifelog.data.aireview.DeepSeekHttpResponse
import com.personal.lifelog.data.aireview.DeepSeekReviewService
import com.personal.lifelog.data.aireview.DeepSeekTransport
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.DailyReviewRepository
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.SleepSummaryRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.data.repository.WorkSummaryRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.AiReviewSessionStore
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.DeepSeekError
import com.personal.lifelog.domain.DeepSeekResult
import com.personal.lifelog.domain.DailyReviewSender
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.SleepDraft
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.home.WorkHomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.feature.work.WorkViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeepSeekReviewFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val mutableInstant = AtomicReference(OffsetDateTime.parse("2026-11-18T16:30:00+08:00").toInstant())
    private val mutableClock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        override fun instant(): Instant = mutableInstant.get()
    }
    private val selectedDate = LocalDate.of(2026, 11, 18)

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var records: RecordRepository
    private lateinit var work: WorkRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var timeline: TimelineRepository
    private lateinit var photos: PhotoStorage
    private lateinit var dailyReview: DailyReviewRepository
    private lateinit var assets: AiReviewAssets

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        assets = AiReviewAssets(context)
        root = File(context.filesDir, "deepseek-flow-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(mutableClock))
            .build()
        val writes = WriteCoordinator()
        settings = SettingsRepository(db, writes, mutableClock)
        records = RecordRepository(db, writes, mutableClock) { zone }
        work = WorkRepository(db, writes, mutableClock) { zone }
        workouts = WorkoutRepository(db, writes, mutableClock)
        timeline = TimelineRepository(records, workouts, work)
        photos = PhotoStorage(
            context = context,
            db = db,
            records = records,
            writes = writes,
            clock = mutableClock,
            root = root,
            openSource = { error("unused") },
            cameraUri = { android.net.Uri.EMPTY },
            syncDirectory = {},
        )
        dailyReview = DailyReviewRepository(db, mutableClock, { zone })
    }

    @After
    fun tearDown() {
        runCatching { compose.waitForIdle() }
        db.close()
        root.deleteRecursively()
    }

    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value
    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) {
                org.junit.Assert.fail("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    private fun okResponse(content: String) = DeepSeekHttpResponse(
        statusCode = 200,
        body = JSONObject()
            .put(
                "choices",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("finish_reason", "stop")
                        .put("message", JSONObject().put("role", "assistant").put("content", content)),
                ),
            )
            .toString(),
    )

    @Test
    fun sampleTestIsIsolatedFromRoomAndRespectsCancelAndDoubleConfirm() {
        // Close Room to prove sample testing never touches Room at all.
        db.close()

        val session = AiReviewSessionStore()
        val fakeKey = "sk-synthetic-sample-key"
        val requests = mutableListOf<DeepSeekHttpRequest>()
        val pending = CompletableDeferred<DeepSeekHttpResponse>()

        val sender = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport { req ->
                requests += req
                pending.await()
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val vm = AiReviewTestViewModel(sender)

        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                AiReviewTestScreen(viewModel = vm, onBack = {})
            }
        }

        // Entering key, opening sample preview, and cancelling emits 0 requests.
        compose.onNodeWithTag("ai-review-key-input").performTextInput(fakeKey)
        compose.onNodeWithTag("ai-review-sample-open").performClick()
        compose.onNodeWithTag("ai-review-sample-json").assertExists()
        compose.onNodeWithTag("ai-review-sample-cancel").performClick()
        assertEquals(0, requests.size)

        // Open again and invoke double confirm: must send exactly 1 request.
        compose.onNodeWithTag("ai-review-sample-open").performClick()
        compose.onNodeWithTag("ai-review-sample-confirm").performClick()
        vm.confirmSampleSend()
        compose.waitUntil(5_000) { requests.size == 1 }
        assertEquals(1, requests.size)

        val req = requests.single()
        assertEquals("https://api.deepseek.com/chat/completions", req.url)
        assertTrue(req.authorizationHeader == "Bearer $fakeKey")
        val body = JSONObject(req.bodyJson)
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals(assets.systemPrompt().getOrThrow(), messages.getJSONObject(0).getString("content"))
        assertEquals(assets.sampleExampleJson().getOrThrow(), messages.getJSONObject(1).getString("content"))

        pending.complete(okResponse("这是虚构样例的中文测试回顾。"))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("ai-review-sample-result").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("虚构样例返回结果（非本机真实记录）").assertExists()
        compose.onNodeWithText("这是虚构样例的中文测试回顾。").assertExists()

        // Clearing key empties session memory.
        compose.onNodeWithTag("ai-review-key-clear").performScrollTo().performClick()
        assertFalse(session.hasApiKey)
    }

    @Test
    fun timeoutAdvanceClockRetrySendsIdenticalFrozenJsonAndDisplaysReviewOnce() {
        runBlocking {
            settings.setTargetSleepMinutes(480).ok()
            records.saveSleep(
                SleepDraft(
                    id = RecordIds.newId(),
                    startedAtUtcMs = ms("2026-11-17T23:00:00+08:00"),
                    startZoneId = "Asia/Shanghai",
                    endedAtUtcMs = ms("2026-11-18T07:00:00+08:00"),
                    endZoneId = "Asia/Shanghai",
                ),
            ).ok()
        }

        val session = AiReviewSessionStore().apply { setApiKey("sk-synthetic-retry-key") }
        val requests = mutableListOf<DeepSeekHttpRequest>()
        var attempt = 0
        val sender = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport { req ->
                requests += req
                attempt++
                if (attempt == 1) {
                    throw SocketTimeoutException("synthetic timeout")
                }
                okResponse("从前一天 23:00 到当天 07:00，记录了 8 小时就寝时段。")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val viewModel = ReviewViewModel(timeline, SavedStateHandle(), mutableClock, { zone }, dailyReview, sender)
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                ReviewScreen(
                    viewModel = viewModel,
                    photos = photos,
                    onOpenSettings = {},
                    onOpenMeal = {},
                    onOpenSleep = {},
                    onOpenWorkout = {},
                )
            }
        }

        // Open page, preview, open confirm, cancel -> 0 requests.
        compose.onNodeWithTag("review-ai-preview-button").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-preview-summary").fetchSemanticsNodes().isNotEmpty()
        }
        val frozenJson = viewModel.frozenAiReviewJson(selectedDate)!!
        compose.onNodeWithTag("review-ai-preview-toggle-json").performClick()
        compose.onNodeWithText(frozenJson).assertExists()
        compose.onNodeWithTag("review-ai-preview-toggle-json").performClick()

        compose.onNodeWithTag("review-ai-send-open").performClick()
        compose.onNodeWithText("接收方：DeepSeek（deepseek-flash）").assertExists()
        compose.onNodeWithTag("review-ai-send-cancel").performClick()
        assertEquals(0, requests.size)

        // Confirm + rapid second confirm call -> only 1 request (times out).
        compose.onNodeWithTag("review-ai-send-open").performClick()
        compose.onNodeWithTag("review-ai-send-confirm").performClick()
        viewModel.confirmSendAiReview()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-send-error").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, requests.size)

        // Advance clock by 15 minutes and tap manual retry -> must reuse identical frozen JSON (same snapshotAt).
        mutableInstant.set(mutableInstant.get().plus(Duration.ofMinutes(15)))
        compose.onNodeWithTag("review-ai-send-retry").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-send-result").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, requests.size)

        val firstUserContent = JSONObject(requests[0].bodyJson).getJSONArray("messages").getJSONObject(1).getString("content")
        val secondUserContent = JSONObject(requests[1].bodyJson).getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals(frozenJson, firstUserContent)
        assertEquals(frozenJson, secondUserContent)
        assertTrue(secondUserContent.contains("\"snapshotAt\": \"2026-11-18T16:30:00+08:00\""))

        // Returned review text is shown exactly once.
        compose.onAllNodesWithText("从前一天 23:00 到当天 07:00，记录了 8 小时就寝时段。").assertCountEquals(1)
    }

    @Test
    fun dateSwitchEditRegenerationAndLateResponseDiscardOldReviewAndBlockStaleRetry() {
        val session = AiReviewSessionStore().apply { setApiKey("sk-synthetic-race-key") }
        val deferreds = mutableListOf<CompletableDeferred<DeepSeekHttpResponse>>()
        val requests = mutableListOf<DeepSeekHttpRequest>()
        val sender = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport { req ->
                requests += req
                val d = CompletableDeferred<DeepSeekHttpResponse>()
                deferreds += d
                d.await()
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val vm = ReviewViewModel(timeline, SavedStateHandle(), mutableClock, { zone }, dailyReview, sender)
        awaitCondition { !vm.day.value.loading }

        // Case A: Confirm dialog open -> edit record -> preview becomes Stale, confirm closes and cannot send.
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        vm.requestSendAiReview()
        assertTrue(vm.aiReviewSend.value is DailyReviewSendState.Confirming)
        runBlocking {
            records.saveMeal(MealDraft(RecordIds.newId(), ms("2026-11-18T12:00:00+08:00"), "Asia/Shanghai", text = "午饭")).ok()
        }
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Stale }
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)
        vm.confirmSendAiReview()
        assertEquals(0, requests.size)

        // Case B: Confirm dialog open -> same-day regeneration -> old confirmation closes, new generationId assigned.
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        val firstGenId = (vm.aiReviewPreview.value as DailyReviewPreviewState.Ready).generationId
        vm.requestSendAiReview()
        assertTrue(vm.aiReviewSend.value is DailyReviewSendState.Confirming)
        vm.previewAiReview()
        awaitCondition {
            val s = vm.aiReviewPreview.value as? DailyReviewPreviewState.Ready
            s != null && s.generationId != firstGenId
        }
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)
        vm.confirmSendAiReview()
        assertEquals(0, requests.size)

        // Case C: Request in-flight -> switch date -> force old response late -> must be discarded.
        vm.requestSendAiReview()
        vm.confirmSendAiReview()
        awaitCondition { requests.size == 1 && vm.aiReviewSend.value is DailyReviewSendState.Sending }
        vm.previousDay()
        assertEquals(LocalDate.of(2026, 11, 17), vm.date.value)
        assertEquals(DailyReviewPreviewState.Idle, vm.aiReviewPreview.value)
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)
        deferreds[0].complete(okResponse("不应显示的旧日期文案"))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)

        // Case D: Request fails -> record edited (Stale) -> retry blocked until re-previewed.
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        vm.requestSendAiReview()
        vm.confirmSendAiReview()
        awaitCondition { requests.size == 2 }
        deferreds[1].complete(DeepSeekHttpResponse(503, ""))
        awaitCondition {
            (vm.aiReviewSend.value as? DailyReviewSendState.Failed)?.error == DeepSeekError.SERVER
        }
        runBlocking { settings.setTargetWorkMinutes(360).ok() }
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Stale }
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)
        vm.retrySendAiReview()
        assertEquals(2, requests.size)

        // Re-preview restores send entry and succeeds cleanly.
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        vm.requestSendAiReview()
        vm.confirmSendAiReview()
        awaitCondition { requests.size == 3 }
        deferreds[2].complete(okResponse("重新预览后的有效回顾。"))
        awaitCondition { vm.aiReviewSend.value is DailyReviewSendState.Succeeded }
        assertEquals("重新预览后的有效回顾。", (vm.aiReviewSend.value as DailyReviewSendState.Succeeded).content)
    }

    @Test
    fun leavingReviewCancelsInFlightRequestClearsResultAndKeepsProcessKey() {
        val session = AiReviewSessionStore().apply { setApiKey("sk-synthetic-nav-key") }
        val pending = CompletableDeferred<DeepSeekHttpResponse>()
        val sender = DeepSeekReviewService(
            session = session,
            assets = assets,
            transport = DeepSeekTransport { pending.await() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val writes = WriteCoordinator()
        val settingsVM = SettingsViewModel(SettingsRepository(db, writes, mutableClock))
        val homeVM = HomeViewModel(
            mutableClock,
            { zone },
            timeline,
            SleepSummaryRepository(db)::observe,
            settings::setTargetSleepMinutes,
        )
        val sleepVM = SleepViewModel(records, mutableClock) { zone }
        val workVM = WorkViewModel(work, mutableClock) { zone }
        val workHomeVM = WorkHomeViewModel(
            homeVM.today,
            WorkSummaryRepository(db)::observe,
            settings::setTargetWorkMinutes,
        )

        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                LifelogApp(
                    homeViewModel = homeVM,
                    settingsViewModel = settingsVM,
                    records = records,
                    photos = photos,
                    clock = mutableClock,
                    zoneIdProvider = { zone },
                    sleepViewModel = sleepVM,
                    workoutRepository = workouts,
                    timelineRepository = timeline,
                    workViewModel = workVM,
                    workHomeViewModel = workHomeVM,
                    dailyReviewSource = dailyReview,
                    dailyReviewSender = sender,
                )
            }
        }

        // Go to Review -> preview -> confirm send (request in-flight) -> leave to Settings -> late response arrives -> return.
        compose.onNodeWithText("回顾").performClick()
        compose.onNodeWithTag("review-ai-preview-button").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-send-open").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review-ai-send-open").performClick()
        compose.onNodeWithTag("review-ai-send-confirm").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-send-loading").fetchSemanticsNodes().isNotEmpty()
        }

        // Leave Review to Settings.
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("输入测试 Key 与样例验证").assertIsDisplayed()

        // Force late response while away.
        pending.complete(okResponse("离页后晚到的旧文案"))
        compose.waitForIdle()

        // Return to Review: no stale preview or old review text, and in-memory API key is still present.
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("review-ai-preview-button").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("review-ai-preview-card").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("离页后晚到的旧文案").fetchSemanticsNodes().isEmpty())
        assertTrue(session.hasApiKey)
    }

    @Test
    fun nonCooperatingLateSuccessCannotReplaceNewDatesPreview() {
        val pending = CompletableDeferred<DeepSeekResult>()
        var entered = false
        var returned = false
        val sender = object : DailyReviewSender {
            override val session = AiReviewSessionStore().apply { setApiKey("synthetic-local-key") }
            override fun sampleExampleJson() = Result.success("{}")
            override suspend fun sendSampleReview(): DeepSeekResult = error("Sample not used")
            override suspend fun sendDailyReview(userContent: String): DeepSeekResult = withContext(NonCancellable) {
                entered = true
                pending.await().also { returned = true }
            }
        }
        val vm = ReviewViewModel(timeline, SavedStateHandle(), mutableClock, { zone }, dailyReview, sender)
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        vm.requestSendAiReview()
        vm.confirmSendAiReview()
        awaitCondition { entered }
        vm.previousDay()
        vm.previewAiReview()
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        val current = vm.aiReviewPreview.value
        pending.complete(DeepSeekResult.Success("Synthetic stale response"))
        awaitCondition { returned }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(current, vm.aiReviewPreview.value)
        assertEquals(DailyReviewSendState.Idle, vm.aiReviewSend.value)
        vm.dismissAiReviewPreview()
    }

    @Test
    fun standardDebugBuildKeepsNetworkDisabledAndHidesTestSendControls() {
        assertFalse("Standard debug build must keep AI_REVIEW_NETWORK_ENABLED = false", BuildConfig.AI_REVIEW_NETWORK_ENABLED)
        val viewModel = ReviewViewModel(timeline, SavedStateHandle(), mutableClock, { zone }, dailyReview)
        assertFalse(viewModel.supportsAiReviewSend)
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                ReviewScreen(
                    viewModel = viewModel,
                    photos = photos,
                    onOpenSettings = {},
                    onOpenMeal = {},
                    onOpenSleep = {},
                    onOpenWorkout = {},
                )
            }
        }
        compose.onNodeWithTag("review-ai-preview-button").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-preview-summary").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithTag("review-ai-test-entry").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("review-ai-send-open").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("发送到 DeepSeek", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
