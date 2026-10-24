package com.personal.lifelog.feature.timeline
import com.personal.lifelog.feature.sleep.pickSleepTime

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.media.PhotoFixtures
import com.personal.lifelog.data.media.PhotoNormalizer
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.SleepDraft
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.domain.WorkoutDraft
import com.personal.lifelog.domain.WorkoutItemDraft
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Host-side real-Room closed loop for T10: one unified timeline on 今天/回顾,
 * cross-midnight sleep shown on its wake-up date, meal thumbnails resolved lazily,
 * quick jump to recorded dates, and a sleep edit moving the record between days.
 * Screenshots are host Compose drawings, not Honor device screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var timeline: TimelineRepository
    private lateinit var photos: PhotoStorage
    private lateinit var sleepId: String
    private lateinit var mealId: String
    private lateinit var workoutId: String

    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "t10-ui-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
        val writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { zone }
        workouts = WorkoutRepository(db, writes, clock)
        timeline = TimelineRepository(records, workouts)
        photos = PhotoStorage(
            context = context,
            db = db,
            records = records,
            writes = writes,
            clock = clock,
            root = root,
            openSource = { throw IllegalStateException("no photo import in this test") },
            cameraUri = { file -> android.net.Uri.parse("content://synthetic-camera/${file.name}") },
            syncDirectory = { check(it.isDirectory) },
        )
        seed()
        val settings = SettingsViewModel(SettingsRepository(db, WriteCoordinator(), clock))
        val home = HomeViewModel(clock, { zone }, timeline)
        val sleep = SleepViewModel(records, clock) { zone }
        compose.setContent {
            LifeLogTheme(ThemeMode.LIGHT) {
                LifelogApp(
                    homeViewModel = home,
                    settingsViewModel = settings,
                    sleepViewModel = sleep,
                    workoutRepository = workouts,
                    timelineRepository = timeline,
                    records = records,
                    photos = photos,
                    clock = clock,
                    zoneIdProvider = { zone },
                )
            }
        }
    }

    private fun seed() = runBlocking<Unit> {
        // Bedtime 23:50 on Oct 22, wake-up 07:10 on Oct 23 (Asia/Shanghai).
        sleepId = RecordIds.newId()
        records.saveSleep(
            SleepDraft(
                id = sleepId,
                startedAtUtcMs = utc("2026-10-22T15:50:00Z"),
                startZoneId = zone.id,
                endedAtUtcMs = utc("2026-10-22T23:10:00Z"),
                endZoneId = zone.id,
            ),
        ).value()

        mealId = seedPhotoMeal(
            text = "牛肉面",
            occurredAtUtcMs = utc("2026-10-23T04:10:00Z"),
            satiety = Satiety.NOT_FULL,
        )

        workoutId = RecordIds.newId()
        workouts.saveWorkout(
            WorkoutDraft(
                id = workoutId,
                occurredAtUtcMs = utc("2026-10-23T10:00:00Z"),
                zoneId = zone.id,
                items = listOf(
                    WorkoutItemDraft(RecordIds.newId(), BuiltinExercises.entries[1].id),
                    WorkoutItemDraft(RecordIds.newId(), BuiltinExercises.entries[0].id),
                ),
            ),
        ).value()

        records.saveMeal(
            MealDraft(
                id = RecordIds.newId(),
                occurredAtUtcMs = utc("2026-10-22T04:00:00Z"),
                zoneId = zone.id,
                text = "昨天的午饭",
            ),
        ).value()
    }

    /** Creates a synthetic JPEG, its immutable metadata row and the referencing meal. */
    private suspend fun seedPhotoMeal(text: String, occurredAtUtcMs: Long, satiety: Satiety?): String {
        val generation = RecordIds.newId()
        val photoId = RecordIds.newId()
        val directory = File(root, "media/$generation").apply { mkdirs() }
        val file = File(directory, "$photoId.jpg").apply { writeBytes(PhotoFixtures.jpeg(120, 80)) }
        val asset = MediaAsset(
            id = photoId,
            generationId = generation,
            relativePath = "media/$generation/$photoId.jpg",
            mimeType = "image/jpeg",
            width = 120,
            height = 80,
            byteSize = file.length(),
            sha256 = PhotoNormalizer.sha256(file),
            createdAtUtcMs = clock.millis(),
            updatedAtUtcMs = clock.millis(),
        )
        db.metadata().insertMedia(asset)
        val id = RecordIds.newId()
        records.saveMeal(MealDraft(id, occurredAtUtcMs, zone.id, text, satiety, photoId)).value()
        return id
    }

    @After
    fun tearDown() {
        runCatching { compose.waitForIdle() }
        db.close()
        root.deleteRecursively()
    }

    private fun waitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(path: String) {
        val evidence = File(path).absoluteFile
        evidence.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            evidence.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun homeAndReviewShowOneSavedTimelineWithQuickJumpAndEmptyPastDates() {
        waitText("牛肉面")
        compose.onNodeWithText("没吃饱").assertExists()
        compose.onNodeWithText("饮食 1 条 · 作息 1 段 · 训练 1 场 · 2 个动作").assertExists()
        compose.onNodeWithText("卧床 7小时20分", substring = true).assertExists()
        compose.onNodeWithText("卧推、跑步").assertExists()
        compose.onAllNodesWithContentDescription("饮食照片").assertCountEquals(1)

        // 18:00 workout > 12:10 meal > 07:10 wake-up.
        val workoutTop = compose.onNodeWithText("卧推、跑步").fetchSemanticsNode().boundsInRoot.top
        val mealTop = compose.onNodeWithText("牛肉面").fetchSemanticsNode().boundsInRoot.top
        val sleepTop = compose.onNodeWithText("作息").fetchSemanticsNode().boundsInRoot.top
        assertTrue(workoutTop < mealTop)
        assertTrue(mealTop < sleepTop)
        screenshot("../artifacts/t10/home-timeline-host.png")

        // Every unified row opens the existing detail/edit/delete path.
        compose.onNodeWithText("牛肉面").performClick()
        waitText("饮食详情")
        waitText("没吃饱")
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("今日时间线")

        compose.onNodeWithText("作息").performClick()
        waitText("就寝日期时间")
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("今日时间线")

        compose.onNodeWithText("卧推、跑步").performClick()
        waitText("训练详情")
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("今日时间线")

        // 回顾 reuses the same timeline for the selected stored date.
        compose.onNodeWithText("回顾").performClick()
        waitText("回到今天")
        waitText("牛肉面")
        compose.onNodeWithText("训练 · 2 个动作").assertExists()
        screenshot("../artifacts/t10/review-timeline-host.png")

        // Quick jump to a recorded date, then an actually empty past date.
        compose.onNodeWithText("有记录的日期").performClick()
        compose.onNodeWithTag("recorded-date-2026-10-22").performClick()
        waitText("昨天的午饭")
        compose.onNodeWithContentDescription("后一天").performClick()
        waitText("牛肉面")
        compose.onNodeWithContentDescription("后一天").performClick()
        waitText("这一天还没有记录")
        compose.onNodeWithText("回到今天").performClick()
        waitText("牛肉面")
    }

    @Test
    fun editingSleepDatesMovesTheEntryWithoutDuplicatingIt() {
        compose.onNodeWithText("回顾").performClick()
        waitText("回到今天")
        waitText("作息")

        compose.onNodeWithText("作息").performScrollTo().performClick()
        waitText("就寝日期时间")
        compose.pickSleepTime("就寝日期时间", "2026-10-20T23:50")
        compose.pickSleepTime("起床日期时间", "2026-10-21T07:10")
        compose.onNodeWithText("保存作息").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { db.records().allSleeps().single().archiveLocalDate == "2026-10-21" }
        }
        waitText("回到今天")
        awaitGone("作息")

        compose.onNodeWithContentDescription("前一天").performClick()
        waitText("昨天的午饭")
        compose.onNodeWithContentDescription("前一天").performClick()
        waitText("卧床 7小时20分")
        compose.onNodeWithText("作息").assertExists()
        assertEquals(1, runBlocking { db.records().allSleeps().size })
        assertEquals(listOf(sleepId), runBlocking { db.records().allSleeps().map { it.id } })
    }
}
