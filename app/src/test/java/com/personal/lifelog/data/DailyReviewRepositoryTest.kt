package com.personal.lifelog.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.DailyReviewRepository
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ExerciseDraft
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.domain.SleepDraft
import com.personal.lifelog.domain.WorkDraft
import com.personal.lifelog.domain.WorkoutDraft
import com.personal.lifelog.domain.WorkoutEffort
import com.personal.lifelog.domain.WorkoutItemDraft
import com.personal.lifelog.domain.WorkoutSetDraft
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class DailyReviewRepositoryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val snapshotInstant = OffsetDateTime.parse("2026-11-18T16:30:00+08:00").toInstant()
    private val clock = Clock.fixed(snapshotInstant, ZoneOffset.UTC)
    private val selectedDate = LocalDate.of(2026, 11, 18)

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var records: RecordRepository
    private lateinit var work: WorkRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var dailyReview: DailyReviewRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
        val writes = WriteCoordinator()
        settings = SettingsRepository(db, writes, clock)
        records = RecordRepository(db, writes, clock) { zone }
        work = WorkRepository(db, writes, clock) { zone }
        workouts = WorkoutRepository(db, writes, clock)
        dailyReview = DailyReviewRepository(db, clock, { zone }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value
    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun locateExampleJson(): File {
        val candidates = listOf(
            File("docs/ai-review/daily-example.v1.json"),
            File("../docs/ai-review/daily-example.v1.json"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate docs/ai-review/daily-example.v1.json from ${File(".").absolutePath}")
    }

    private fun mediaAsset(id: String, generation: String, sha: String): MediaAsset = MediaAsset(
        id = id,
        generationId = generation,
        relativePath = "media/$generation/$id.jpg",
        mimeType = "image/jpeg",
        width = 1440,
        height = 1080,
        byteSize = 412800L,
        sha256 = sha,
        createdAtUtcMs = clock.millis(),
        updatedAtUtcMs = clock.millis(),
    )

    @Test
    fun v1FictionalRecordsMatchExampleJsonStructurallyAndExcludeLocalFields() = runBlocking {
        // Seed exact v1 records from docs/ai-review/daily-rules.v1.md §5.1.
        settings.setTargetSleepMinutes(480).ok()
        settings.setTargetWorkMinutes(480).ok()

        val sleepId = "a1000000-0000-4000-8000-000000000001"
        records.saveSleep(
            SleepDraft(
                id = sleepId,
                startedAtUtcMs = ms("2026-11-17T23:00:00+08:00"),
                startZoneId = "Asia/Shanghai",
                endedAtUtcMs = ms("2026-11-18T07:00:00+08:00"),
                endZoneId = "Asia/Shanghai",
            ),
        ).ok()

        val work1Id = "b2000000-0000-4000-8000-000000000001"
        val work2Id = "b2000000-0000-4000-8000-000000000002"
        work.saveWork(
            WorkDraft(
                id = work1Id,
                startedAtUtcMs = ms("2026-11-18T09:00:00+08:00"),
                startZoneId = "Asia/Shanghai",
                endedAtUtcMs = ms("2026-11-18T12:00:00+08:00"),
                endZoneId = "Asia/Shanghai",
            ),
        ).ok()
        work.startWork(
            id = work2Id,
            startedAtUtcMs = ms("2026-11-18T15:00:00+08:00"),
            zoneId = "Asia/Shanghai",
        ).ok()

        val mealId = "c3000000-0000-4000-8000-000000000001"
        val genId = "11111111-1111-4111-8111-111111111111"
        val photo1Id = "f1000000-0000-4000-8000-000000000001"
        val photo2Id = "f1000000-0000-4000-8000-000000000002"
        val sha1 = "a".repeat(64)
        val sha2 = "b".repeat(64)
        records.saveMealPhotos(
            draft = MealDraft(
                id = mealId,
                occurredAtUtcMs = ms("2026-11-18T12:30:00+08:00"),
                zoneId = "Asia/Shanghai",
                text = null,
                satiety = null,
                photoIds = listOf(photo1Id, photo2Id),
            ),
            preparedPhotos = listOf(
                mediaAsset(photo1Id, genId, sha1),
                mediaAsset(photo2Id, genId, sha2),
            ),
        ).ok()

        val sessionId = "d4000000-0000-4000-8000-000000000001"
        val runItemId = "e5000000-0000-4000-8000-000000000001"
        val benchItemId = "e5000000-0000-4000-8000-000000000002"
        workouts.saveWorkout(
            WorkoutDraft(
                id = sessionId,
                occurredAtUtcMs = ms("2026-11-18T13:30:00+08:00"),
                zoneId = "Asia/Shanghai",
                note = "午后训练状态平稳，左肩热身充分",
                effort = WorkoutEffort.MODERATE,
                items = listOf(
                    WorkoutItemDraft(
                        id = runItemId,
                        exerciseId = BuiltinExercises.entries[0].id,
                        durationSeconds = 1800L,
                        distanceMeters = null,
                        note = "公园慢跑，未开手表记距离",
                    ),
                    WorkoutItemDraft(
                        id = benchItemId,
                        exerciseId = BuiltinExercises.entries[1].id,
                        sets = listOf(
                            WorkoutSetDraft("f6000000-0000-4000-8000-000000000001", 60_000L, 8),
                            WorkoutSetDraft("f6000000-0000-4000-8000-000000000002", 60_000L, 8),
                            WorkoutSetDraft("f6000000-0000-4000-8000-000000000003", 60_000L, 8),
                        ),
                    ),
                ),
            ),
        ).ok()

        val snapshot = dailyReview.generate(selectedDate).ok()
        val expected = JSONObject(locateExampleJson().readText(Charsets.UTF_8))
        val actual = JSONObject(snapshot.rawJson)

        assertJsonStructurallyEqual(expected, actual)

        // Key business assertions from the task brief.
        assertEquals(480, snapshot.payload.sleep.completedDurationMinutes)
        assertEquals(180, snapshot.payload.work.completedDurationMinutes)
        assertTrue(snapshot.payload.work.hasActiveSegment)
        assertEquals("2026-11-18T15:00:00+08:00", snapshot.payload.work.activeStartedAt)
        assertEquals("2026-11-18T16:30:00+08:00", snapshot.payload.snapshotAt)
        assertEquals(1, snapshot.payload.meals.recordCount)
        assertEquals(2, snapshot.payload.meals.totalPhotoCount)
        assertEquals(1, snapshot.payload.workouts.sessionCount)
        assertEquals(2, snapshot.payload.workouts.itemCount)
        assertNull(snapshot.payload.workouts.sessions.single().items[0].distanceMeters)

        // Privacy assertions: local IDs, paths, hashes and notes never appear in JSON.
        val forbidden = listOf(
            sleepId, work1Id, work2Id, mealId, genId, photo1Id, photo2Id,
            sessionId, runItemId, benchItemId, sha1, sha2, "media/",
            "午后训练状态平稳", "公园慢跑",
        )
        forbidden.forEach { secret ->
            assertFalse("Forbidden local value leaked into JSON: $secret", snapshot.rawJson.contains(secret))
        }
    }

    @Test
    fun customExerciseNamesAndRenamedBuiltinStayOnDevice() = runBlocking {
        // Rename a builtin exercise locally; output must still use the fixed standard name "跑步".
        val builtinRun = BuiltinExercises.entries[0]
        workouts.saveExercise(ExerciseDraft(builtinRun.id, "私有晨跑名称", ExerciseType.CARDIO)).ok()

        val customCardio = workouts.saveExercise(ExerciseDraft(RecordIds.newId(), "河边间歇跑", ExerciseType.CARDIO)).ok()
        val customStrength = workouts.saveExercise(ExerciseDraft(RecordIds.newId(), "上斜哑铃秘籍", ExerciseType.STRENGTH)).ok()
        val customOther = workouts.saveExercise(ExerciseDraft(RecordIds.newId(), "左肩康复拉伸", ExerciseType.OTHER)).ok()

        workouts.saveWorkout(
            WorkoutDraft(
                id = RecordIds.newId(),
                occurredAtUtcMs = ms("2026-11-18T14:00:00+08:00"),
                zoneId = "Asia/Shanghai",
                effort = WorkoutEffort.CUSTOM,
                effortText = "偏累但可控",
                items = listOf(
                    WorkoutItemDraft(RecordIds.newId(), builtinRun.id, durationSeconds = 600L),
                    WorkoutItemDraft(RecordIds.newId(), customCardio.id, durationSeconds = 900L, distanceMeters = 2500L),
                    WorkoutItemDraft(
                        RecordIds.newId(),
                        customStrength.id,
                        sets = listOf(WorkoutSetDraft(RecordIds.newId(), 0L, 12)),
                    ),
                    WorkoutItemDraft(RecordIds.newId(), customOther.id, durationSeconds = 300L),
                ),
            ),
        ).ok()

        val snapshot = dailyReview.generate(selectedDate).ok()
        val session = snapshot.payload.workouts.sessions.single()
        assertEquals("CUSTOM", session.effort)
        assertEquals(
            listOf("跑步", "自定义有氧项目", "自定义力量项目", "自定义其他项目"),
            session.items.map { it.name },
        )
        // Bodyweight 0g is preserved distinctly from null.
        assertEquals(0L, session.items[2].sets.single().weightGrams)

        listOf("私有晨跑名称", "河边间歇跑", "上斜哑铃秘籍", "左肩康复拉伸", "偏累但可控").forEach { secret ->
            assertFalse("Local custom text leaked: $secret", snapshot.rawJson.contains(secret))
        }
    }

    @Test
    fun multiSegmentRoundingUsesRawMillisecondSumAndIsolatesActiveBySelectedDate() = runBlocking {
        // Two work segments of 5 min 40 sec (340_000 ms) each -> each segment 5 min, daily total 11 min.
        val t0 = ms("2026-11-18T09:00:00+08:00")
        work.saveWork(WorkDraft(RecordIds.newId(), t0, "Asia/Shanghai", t0 + 340_000L, "Asia/Shanghai")).ok()
        val t1 = ms("2026-11-18T10:00:00+08:00")
        work.saveWork(WorkDraft(RecordIds.newId(), t1, "Asia/Shanghai", t1 + 340_000L, "Asia/Shanghai")).ok()

        // Active work on 2026-11-18 must NOT leak into historical 2026-11-17.
        work.startWork(RecordIds.newId(), ms("2026-11-18T15:00:00+08:00"), "Asia/Shanghai").ok()

        // Legacy short completed sleep (45 seconds) on 2026-11-17 -> completedDurationMinutes = 0 (not null).
        val s0 = ms("2026-11-17T08:00:00+08:00")
        records.saveSleep(SleepDraft(RecordIds.newId(), s0, "Asia/Shanghai", s0 + 45_000L, "Asia/Shanghai")).ok()

        // Active-only sleep on 2026-11-18 (0 completed segments) -> completedDurationMinutes = null, hasActiveSegment = true.
        records.startSleep(RecordIds.newId(), ms("2026-11-18T16:00:00+08:00"), "Asia/Shanghai").ok()

        val day18 = dailyReview.generate(LocalDate.of(2026, 11, 18)).ok()
        assertEquals(2, day18.payload.work.completedSegmentCount)
        assertEquals(listOf(5, 5), day18.payload.work.completedSegments.map { it.durationMinutes })
        assertEquals(11, day18.payload.work.completedDurationMinutes)
        assertTrue(day18.payload.work.hasActiveSegment)
        assertEquals(0, day18.payload.sleep.completedSegmentCount)
        assertNull(day18.payload.sleep.completedDurationMinutes)
        assertTrue(day18.payload.sleep.hasActiveSegment)
        assertTrue(JSONObject(day18.rawJson).getJSONObject("sleep").isNull("completedDurationMinutes"))

        val day17 = dailyReview.generate(LocalDate.of(2026, 11, 17)).ok()
        assertFalse(day17.payload.work.hasActiveSegment)
        assertNull(day17.payload.work.activeStartedAt)
        assertNull(day17.payload.work.completedDurationMinutes)
        assertFalse(day17.payload.sleep.hasActiveSegment)
        assertEquals(1, day17.payload.sleep.completedSegmentCount)
        assertEquals(0, day17.payload.sleep.completedDurationMinutes)
    }

    @Test
    fun emptyDayIsSuccessAndDatabaseReadFailureReturnsErrorWithoutJson() = runBlocking {
        val emptySnapshot = dailyReview.generate(LocalDate.of(2026, 11, 10)).ok()
        assertFalse(emptySnapshot.payload.hasAnyRecord)
        assertNull(emptySnapshot.payload.sleep.completedDurationMinutes)
        assertNull(emptySnapshot.payload.work.completedDurationMinutes)
        assertEquals(0, emptySnapshot.payload.meals.recordCount)
        assertEquals(0, emptySnapshot.payload.workouts.sessionCount)

        val emptyJson = JSONObject(emptySnapshot.rawJson)
        assertTrue(emptyJson.getJSONObject("sleep").isNull("completedDurationMinutes"))
        assertTrue(emptyJson.getJSONObject("sleep").isNull("targetMinutes"))
        assertEquals(0, emptyJson.getJSONObject("sleep").getJSONArray("completedSegments").length())
        assertEquals(0, emptyJson.getJSONObject("meals").getJSONArray("records").length())
        assertEquals(0, emptyJson.getJSONObject("workouts").getJSONArray("sessions").length())

        // Missing AppSettings row simulates storage corruption/read failure: must fail closed, never return empty day.
        val savedSettings = settings.settings().first()
        db.openHelper.writableDatabase.execSQL("DELETE FROM AppSettings")
        val failed = dailyReview.generate(LocalDate.of(2026, 11, 10))
        assertTrue(failed is DataResult.Failure)
        assertEquals(ErrorCode.STORAGE, (failed as DataResult.Failure).error.code)

        // Restoring settings allows retry to succeed.
        db.metadata().putSettings(savedSettings)
        assertTrue(dailyReview.generate(LocalDate.of(2026, 11, 10)) is DataResult.Success)
    }

    @Test
    fun mealTextNotesStayLocalAndLegacyPhotoIdFallbackCountsOnePhoto() = runBlocking {
        // Text-only meal with custom satiety text.
        records.saveMeal(
            MealDraft(
                id = RecordIds.newId(),
                occurredAtUtcMs = ms("2026-11-18T08:00:00+08:00"),
                zoneId = "Asia/Shanghai",
                text = "全麦吐司和黑咖啡",
                satiety = Satiety.CUSTOM,
                satietyText = "刚好八分饱",
            ),
        ).ok()

        // Legacy compatibility row: photoId populated on MealRecord with no MealPhoto links.
        val legacyPhotoId = RecordIds.newId()
        val gen = RecordIds.newId()
        db.metadata().insertMedia(mediaAsset(legacyPhotoId, gen, "c".repeat(64)))
        db.records().putMeal(
            MealRecord(
                id = RecordIds.newId(),
                occurredAtUtcMs = ms("2026-11-18T12:00:00+08:00"),
                zoneId = "Asia/Shanghai",
                localDate = "2026-11-18",
                text = null,
                satiety = Satiety.SEVEN,
                photoId = legacyPhotoId,
                createdAtUtcMs = clock.millis(),
                updatedAtUtcMs = clock.millis(),
            ),
        )

        val snapshot = dailyReview.generate(selectedDate).ok()
        assertEquals(2, snapshot.payload.meals.recordCount)
        assertEquals(1, snapshot.payload.meals.photoOnlyRecordCount)
        assertEquals(1, snapshot.payload.meals.totalPhotoCount)
        assertTrue(snapshot.payload.meals.records[0].hasText)
        assertEquals(0, snapshot.payload.meals.records[0].photoCount)
        assertEquals("CUSTOM", snapshot.payload.meals.records[0].satiety)
        assertFalse(snapshot.payload.meals.records[1].hasText)
        assertEquals(1, snapshot.payload.meals.records[1].photoCount)
        assertEquals("SEVEN", snapshot.payload.meals.records[1].satiety)
        assertFalse(snapshot.rawJson.contains("全麦吐司"))
        assertFalse(snapshot.rawJson.contains("刚好八分饱"))
    }

    private fun assertJsonStructurallyEqual(expected: Any?, actual: Any?, path: String = "$") {
        when {
            expected === JSONObject.NULL -> {
                assertTrue("Expected explicit JSON null at $path, got $actual", actual === JSONObject.NULL)
            }
            expected is JSONObject -> {
                assertTrue("Expected JSONObject at $path, got $actual", actual is JSONObject)
                actual as JSONObject
                val expectedKeys = expected.keys().asSequence().toSet()
                val actualKeys = actual.keys().asSequence().toSet()
                assertEquals("Mismatched keys at $path", expectedKeys, actualKeys)
                for (key in expectedKeys) {
                    assertJsonStructurallyEqual(expected.get(key), actual.get(key), "$path.$key")
                }
            }
            expected is JSONArray -> {
                assertTrue("Expected JSONArray at $path, got $actual", actual is JSONArray)
                actual as JSONArray
                assertEquals("Mismatched array length at $path", expected.length(), actual.length())
                for (i in 0 until expected.length()) {
                    assertJsonStructurallyEqual(expected.get(i), actual.get(i), "$path[$i]")
                }
            }
            expected is Number && actual is Number -> {
                assertEquals("Mismatched number at $path", expected.toLong(), actual.toLong())
            }
            expected is Boolean && actual is Boolean -> {
                assertEquals("Mismatched boolean at $path", expected, actual)
            }
            expected is String && actual is String -> {
                assertEquals("Mismatched string at $path", expected, actual)
            }
            else -> fail("Type or value mismatch at $path: expected=$expected (${expected?.javaClass}), actual=$actual (${actual?.javaClass})")
        }
    }
}
