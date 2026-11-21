package com.personal.lifelog.feature.review

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.DailyReviewRepository
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DailyReviewIntervalSection
import com.personal.lifelog.domain.DailyReviewJson
import com.personal.lifelog.domain.DailyReviewMealsSection
import com.personal.lifelog.domain.DailyReviewPayload
import com.personal.lifelog.domain.DailyReviewSnapshot
import com.personal.lifelog.domain.DailyReviewSource
import com.personal.lifelog.domain.DailyReviewWorkoutsSection
import com.personal.lifelog.domain.DataError
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.SleepDraft
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.domain.WorkDraft
import com.personal.lifelog.domain.WorkoutDraft
import com.personal.lifelog.domain.WorkoutEffort
import com.personal.lifelog.domain.WorkoutItemDraft
import com.personal.lifelog.domain.WorkoutSetDraft
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewAiPreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Asia/Shanghai")
    private val snapshotInstant = OffsetDateTime.parse("2026-11-18T16:30:00+08:00").toInstant()
    private val clock = Clock.fixed(snapshotInstant, ZoneOffset.UTC)
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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "review-ai-preview-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
        val writes = WriteCoordinator()
        settings = SettingsRepository(db, writes, clock)
        records = RecordRepository(db, writes, clock) { zone }
        work = WorkRepository(db, writes, clock) { zone }
        workouts = WorkoutRepository(db, writes, clock)
        timeline = TimelineRepository(records, workouts, work)
        photos = PhotoStorage(
            context = context,
            db = db,
            records = records,
            writes = writes,
            clock = clock,
            root = root,
            openSource = { error("unused") },
            cameraUri = { android.net.Uri.EMPTY },
            syncDirectory = {},
        )
        dailyReview = DailyReviewRepository(db, clock, { zone })
    }

    @After
    fun tearDown() {
        runCatching { compose.waitForIdle() }
        db.close()
        root.deleteRecursively()
    }

    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value
    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun emptySnapshotFor(date: LocalDate, tagTime: String = "16:30:00"): DailyReviewSnapshot {
        val payload = DailyReviewPayload(
            selectedDate = date.toString(),
            timeZone = "Asia/Shanghai",
            snapshotAt = "${date}T$tagTime+08:00",
            hasAnyRecord = false,
            sleep = DailyReviewIntervalSection(null, 0, null, false, null, emptyList()),
            work = DailyReviewIntervalSection(null, 0, null, false, null, emptyList()),
            meals = DailyReviewMealsSection(0, 0, 0, emptyList()),
            workouts = DailyReviewWorkoutsSection(0, 0, emptyList()),
        )
        return DailyReviewSnapshot(payload, DailyReviewJson.encode(payload))
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) {
                org.junit.Assert.fail("Condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun dateSwitchAndRecordOrGoalChangeInvalidateOldPreviewAndNeverPersistPayload() {
        val saved = SavedStateHandle()
        val viewModel = ReviewViewModel(timeline, saved, clock, { zone }, dailyReview)
        awaitCondition { !viewModel.day.value.loading }

        viewModel.previewAiReview()
        awaitCondition { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        val firstJson = viewModel.frozenAiReviewJson(selectedDate)
        assertNotNull(firstJson)
        // SavedStateHandle only tracks selected date, never the JSON payload.
        assertEquals(emptySet<String>(), saved.keys())

        // Changing target sleep goal invalidates the in-memory preview via Room InvalidationTracker.
        runBlocking { settings.setTargetSleepMinutes(450).ok() }
        awaitCondition { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Stale }
        assertNull(viewModel.frozenAiReviewJson(selectedDate))

        // Regenerating picks up the updated target value.
        viewModel.previewAiReview()
        awaitCondition { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        val updatedJson = viewModel.frozenAiReviewJson(selectedDate)!!
        assertTrue(updatedJson.contains("\"targetMinutes\": 450"))

        // Adding a record on the selected date also invalidates the old preview.
        runBlocking {
            records.saveMeal(MealDraft(RecordIds.newId(), ms("2026-11-18T12:00:00+08:00"), "Asia/Shanghai", text = "午饭")).ok()
        }
        awaitCondition { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Stale }
        assertNull(viewModel.frozenAiReviewJson(selectedDate))

        viewModel.previewAiReview()
        awaitCondition { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        assertTrue(viewModel.frozenAiReviewJson(selectedDate)!!.contains("\"recordCount\": 1"))

        // Switching date clears old preview immediately and only stores the date key in SavedStateHandle.
        viewModel.previousDay()
        assertEquals(DailyReviewPreviewState.Idle, viewModel.aiReviewPreview.value)
        assertNull(viewModel.frozenAiReviewJson())
        assertEquals(setOf(ReviewViewModel.KEY_DATE), saved.keys())
    }

    @Test
    fun interruptedObservationDisablesFrozenJsonAndRetryRequiresANewSubscription() {
        val disconnect = CompletableDeferred<Unit>()
        val source = object : DailyReviewSource {
            var subscriptions = 0
            var reads = 0

            override fun observeChanges(): Flow<Unit> = flow {
                when (++subscriptions) {
                    1 -> {
                        emit(Unit)
                        disconnect.await()
                        error("Simulated tracker failure")
                    }
                    2 -> return@flow // Disconnected before Room confirmed registration.
                    else -> emitAll(dailyReview.observeChanges())
                }
            }

            override suspend fun generate(date: LocalDate): DataResult<DailyReviewSnapshot> {
                reads++
                return dailyReview.generate(date)
            }
        }
        val vm = ReviewViewModel(timeline, SavedStateHandle(), clock, { zone }, source)
        vm.previewAiReview()
        awaitCondition { vm.frozenAiReviewJson() != null }
        disconnect.complete(Unit)
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Failed }
        assertNull(vm.frozenAiReviewJson())

        vm.previewAiReview()
        awaitCondition { source.subscriptions == 2 && vm.aiReviewPreview.value is DailyReviewPreviewState.Failed }
        assertEquals(1, source.reads)
        assertNull(vm.frozenAiReviewJson())

        vm.previewAiReview()
        awaitCondition { vm.frozenAiReviewJson() != null }
        assertEquals(2, source.reads)
        runBlocking {
            records.saveMeal(MealDraft(RecordIds.newId(), clock.millis(), zone.id, text = "测试餐")).ok()
        }
        awaitCondition { vm.aiReviewPreview.value is DailyReviewPreviewState.Stale }
        assertNull(vm.frozenAiReviewJson())
    }

    @Test
    fun staleSlowRequestCannotOverwriteNewerDateOrRetry() {
        val requests = mutableListOf<Pair<LocalDate, CompletableDeferred<DataResult<DailyReviewSnapshot>>>>()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val fakeSource = object : DailyReviewSource {
            override suspend fun generate(date: LocalDate): DataResult<DailyReviewSnapshot> {
                val deferred = CompletableDeferred<DataResult<DailyReviewSnapshot>>()
                requests += date to deferred
                return deferred.await()
            }

            override fun observeChanges(): Flow<Unit> = changes.onSubscription { emit(Unit) }
        }
        val emptyTimeline = TimelineRepository(
            datesWithRecordsFlow = { flowOf(emptyList()) },
            mealsOn = { flowOf(emptyList()) },
            sleepsOn = { flowOf(emptyList()) },
            workoutsOn = { flowOf(emptyList()) },
        )
        val viewModel = ReviewViewModel(emptyTimeline, SavedStateHandle(), clock, { zone }, fakeSource)

        // Start request 1 on 2026-11-18, then switch to 2026-11-17 before request 1 completes.
        viewModel.previewAiReview()
        compose.waitUntil(5_000) { requests.size == 1 }
        viewModel.previousDay()
        assertEquals(LocalDate.of(2026, 11, 17), viewModel.date.value)
        assertEquals(DailyReviewPreviewState.Idle, viewModel.aiReviewPreview.value)

        // Start request 2 on 2026-11-17 and complete it first.
        viewModel.previewAiReview()
        compose.waitUntil(5_000) { requests.size == 2 }
        val expectedDay17 = emptySnapshotFor(LocalDate.of(2026, 11, 17), "16:31:00")
        requests[1].second.complete(DataResult.Success(expectedDay17))
        compose.waitUntil(5_000) { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Ready }

        // Complete stale request 1 for 2026-11-18 late: must NOT overwrite 2026-11-17.
        requests[0].second.complete(DataResult.Success(emptySnapshotFor(LocalDate.of(2026, 11, 18), "16:30:00")))
        compose.waitForIdle()
        val ready = viewModel.aiReviewPreview.value as DailyReviewPreviewState.Ready
        assertEquals(LocalDate.of(2026, 11, 17), ready.date)
        assertEquals(expectedDay17.rawJson, viewModel.frozenAiReviewJson(LocalDate.of(2026, 11, 17)))

        // Rapid retry on same date: earlier failed retry finishing late cannot overwrite newer success.
        viewModel.previewAiReview()
        compose.waitUntil(5_000) { requests.size == 3 }
        viewModel.previewAiReview()
        compose.waitUntil(5_000) { requests.size == 4 }
        val latestRetry = emptySnapshotFor(LocalDate.of(2026, 11, 17), "16:32:00")
        requests[3].second.complete(DataResult.Success(latestRetry))
        compose.waitUntil(5_000) { viewModel.aiReviewPreview.value is DailyReviewPreviewState.Ready }
        requests[2].second.complete(DataResult.Failure(DataError(ErrorCode.STORAGE)))
        compose.waitForIdle()
        assertEquals(latestRetry.rawJson, viewModel.frozenAiReviewJson(LocalDate.of(2026, 11, 17)))
    }

    @Test
    fun reviewScreenShowsSummaryExpandableFrozenJsonAndClearsWhenSwitchingDate() {
        runBlocking {
            settings.setTargetSleepMinutes(480).ok()
            settings.setTargetWorkMinutes(480).ok()
            records.saveSleep(
                SleepDraft(
                    id = RecordIds.newId(),
                    startedAtUtcMs = ms("2026-11-17T23:00:00+08:00"),
                    startZoneId = "Asia/Shanghai",
                    endedAtUtcMs = ms("2026-11-18T07:00:00+08:00"),
                    endZoneId = "Asia/Shanghai",
                ),
            ).ok()
            work.saveWork(
                WorkDraft(
                    id = RecordIds.newId(),
                    startedAtUtcMs = ms("2026-11-18T09:00:00+08:00"),
                    startZoneId = "Asia/Shanghai",
                    endedAtUtcMs = ms("2026-11-18T12:00:00+08:00"),
                    endZoneId = "Asia/Shanghai",
                ),
            ).ok()
            work.startWork(RecordIds.newId(), ms("2026-11-18T15:00:00+08:00"), "Asia/Shanghai").ok()
            val gen = RecordIds.newId()
            val p1 = RecordIds.newId()
            val p2 = RecordIds.newId()
            records.saveMealPhotos(
                MealDraft(RecordIds.newId(), ms("2026-11-18T12:30:00+08:00"), "Asia/Shanghai", photoIds = listOf(p1, p2)),
                listOf(
                    MediaAsset(p1, gen, "media/$gen/$p1.jpg", "image/jpeg", 100, 100, 100, "a".repeat(64), clock.millis(), clock.millis()),
                    MediaAsset(p2, gen, "media/$gen/$p2.jpg", "image/jpeg", 100, 100, 100, "b".repeat(64), clock.millis(), clock.millis()),
                ),
            ).ok()
            workouts.saveWorkout(
                WorkoutDraft(
                    id = RecordIds.newId(),
                    occurredAtUtcMs = ms("2026-11-18T13:30:00+08:00"),
                    zoneId = "Asia/Shanghai",
                    effort = WorkoutEffort.MODERATE,
                    items = listOf(
                        WorkoutItemDraft(RecordIds.newId(), BuiltinExercises.entries[0].id, durationSeconds = 1800L),
                        WorkoutItemDraft(
                            RecordIds.newId(),
                            BuiltinExercises.entries[1].id,
                            sets = listOf(WorkoutSetDraft(RecordIds.newId(), 60_000L, 8)),
                        ),
                    ),
                ),
            ).ok()
        }

        val viewModel = ReviewViewModel(timeline, SavedStateHandle(), clock, { zone }, dailyReview)
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

        compose.onNodeWithText("预览 AI 回顾数据").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-preview-summary").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("所选日期：2026-11-18").assertIsDisplayed()
        compose.onNodeWithText("已完成 1 段，共 480 分钟", substring = true).assertExists()
        compose.onNodeWithText("已完成 1 段，共 180 分钟；另有 1 段进行中（15:00 起）", substring = true).assertExists()
        compose.onNodeWithText("共 1 条（纯照片 1 条，附 2 张照片）", substring = true).assertExists()
        compose.onNodeWithText("共 1 次，2 个项目（跑步、卧推）", substring = true).assertExists()

        // Expand raw JSON and verify it matches the frozen ViewModel string.
        val frozen = viewModel.frozenAiReviewJson(selectedDate)!!
        compose.onNodeWithTag("review-ai-preview-toggle-json").performClick()
        compose.onNodeWithTag("review-ai-preview-json").assertExists()
        compose.onNodeWithText(frozen).assertExists()

        // No send button or API key input in Task 2.
        assertTrue(compose.onAllNodesWithText("发送", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("API Key", substring = true).fetchSemanticsNodes().isEmpty())

        // Switching date invalidates and clears the old preview card.
        compose.onNodeWithContentDescription("前一天").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("review-ai-preview-card").fetchSemanticsNodes().isEmpty()
        }
        assertNull(viewModel.frozenAiReviewJson())
    }
}
