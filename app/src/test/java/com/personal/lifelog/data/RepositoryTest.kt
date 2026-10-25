package com.personal.lifelog.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.*
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class RepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T15:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var workouts: WorkoutRepository
    private lateinit var writes: WriteCoordinator
    private val running = BuiltinExercises.entries[0].id
    private val bench = BuiltinExercises.entries[1].id
    private fun id() = RecordIds.newId()
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private fun <T> DataResult<T>.value(): T = (this as? DataResult.Success)?.value ?: error("Unexpected result: $this")
    private fun failure(code: ErrorCode, result: DataResult<*>) = assertEquals(code, (result as DataResult.Failure).error.code)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        workouts = WorkoutRepository(db, writes, clock)
    }

    @After fun close() { db.close() }

    @Test fun firstCreationSeedsLibraryAndSingletonSettings() = runBlocking<Unit> {
        assertEquals(8, db.workouts().allExercises().size)
        assertEquals(ThemeMode.SYSTEM, db.metadata().settingsNow().themeMode)
        assertNull(db.metadata().restoreState().lastCommittedRestoreId)
        assertTrue(db.workouts().allExercises().all { it.isBuiltin && it.createdAtUtcMs == clock.millis() })
        assertThrows(Exception::class.java) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO AppSettings (id,themeMode,lastSuccessfulExportAtUtcMs,createdAtUtcMs,updatedAtUtcMs) VALUES (2,'SYSTEM',NULL,1,1)")
        }
    }

    @Test fun compiledDatabaseIdentityMatchesExportedV3Schema() = runBlocking<Unit> {
        db.metadata().settingsNow()
        val schema = JSONObject(File("schemas/com.personal.lifelog.data.local.AppDatabase/3.json").readText())
        val expected = schema.getJSONObject("database").getString("identityHash")
        db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            assertTrue(it.moveToFirst()); assertEquals(expected, it.getString(0))
        }
        assertEquals(3, db.openHelper.readableDatabase.version)
    }

    @Test fun mealAllowsOnlyTextOrPhotoAndDatesMoveOnEdit() = runBlocking<Unit> {
        val mealId = id()
        failure(ErrorCode.EMPTY_MEAL, records.saveMeal(MealDraft(mealId, clock.millis(), "Asia/Shanghai", "  ", Satiety.SEVEN)))
        val first = records.saveMeal(MealDraft(mealId, clock.millis(), "Asia/Shanghai", " 一碗面 ")).value()
        assertEquals("一碗面", first.text)
        assertNull(first.photoId)
        val updated = records.saveMeal(MealDraft(mealId, utc("2026-10-20T01:00:00Z"), "Asia/Shanghai", "面和蛋")).value()
        assertEquals(first.createdAtUtcMs, updated.createdAtUtcMs)
        assertTrue(records.mealsOn("2026-10-22").first().isEmpty())
        assertEquals(listOf(mealId), records.mealsOn("2026-10-20").first().map { it.id })
        assertEquals(1, db.records().allMeals().size)
    }

    @Test fun customSatietyRequiresShortTextAndPresetsRejectText() = runBlocking<Unit> {
        val id = id()
        failure(ErrorCode.INVALID_SATIETY, records.saveMeal(MealDraft(id, clock.millis(), "UTC", "记录", Satiety.CUSTOM)))
        failure(
            ErrorCode.INVALID_SATIETY,
            records.saveMeal(
                MealDraft(id, clock.millis(), "UTC", "记录", Satiety.CUSTOM, satietyText = "字".repeat(41)),
            ),
        )
        failure(
            ErrorCode.INVALID_SATIETY,
            records.saveMeal(MealDraft(id, clock.millis(), "UTC", "记录", Satiety.SEVEN, satietyText = "不允许")),
        )
        failure(
            ErrorCode.INVALID_SATIETY,
            records.saveMeal(MealDraft(id, clock.millis(), "UTC", "记录", satietyText = "不允许")),
        )
        val saved = records.saveMeal(
            MealDraft(id, clock.millis(), "UTC", "记录", Satiety.CUSTOM, satietyText = "  七分饱但还想吃  "),
        ).value()
        assertEquals("七分饱但还想吃", saved.satietyText)
        assertEquals("七分饱但还想吃", records.meal(id)?.satietyText)
        assertEquals(1, db.records().allMeals().size)
    }

    @Test fun photoReferenceAndMetadataCommitTogetherAndAreImmutable() = runBlocking<Unit> {
        val photoId = id()
        val generation = id()
        val photo = MediaAsset(photoId, generation, MediaRules.relativePath(generation, photoId), "image/jpeg", 100, 100, 200, "a".repeat(64), 1, 1)
        val meal = MealDraft(id(), clock.millis(), "UTC", photoId = photoId)
        failure(ErrorCode.INVALID_MEDIA, records.saveMeal(meal))
        assertEquals(photoId, records.saveMeal(meal, photo).value().photoId)
        failure(ErrorCode.IMMUTABLE_MEDIA, records.saveMeal(meal, photo.copy(sha256 = "b".repeat(64))))
        assertEquals(photo, db.metadata().media(photoId))
        assertEquals(0, db.metadata().deleteUnreferencedMedia(photoId))
        records.deleteMeal(meal.id).value()
        assertEquals(listOf(photoId), db.metadata().unreferencedMedia().map { it.id })
    }

    @Test fun concurrentStartAndRepeatedFinishAreIdempotent() = runBlocking<Unit> {
        val results = coroutineScope {
            (1..12).map { async(Dispatchers.Default) { records.startSleep(id(), 1000, "UTC").value() } }.awaitAll()
        }
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(1, db.records().allSleeps().size)
        val active = results.first()
        val finished = records.finishSleep(active.id, 2000, "UTC").value()
        assertEquals(finished, records.finishSleep(active.id, 3000, "UTC").value())
        assertEquals(finished, records.startSleep(active.id, 4000, "UTC").value())
        assertNull(records.activeSleep().first())
    }

    @Test fun databaseRejectsSecondActiveSlotAndIncorrectDerivedSlot() = runBlocking<Unit> {
        val active = records.startSleep(id(), 1000, "UTC").value()
        try { db.records().putSleep(active.copy(id = id())); fail("Expected unique constraint") } catch (_: android.database.SQLException) { }
        try { db.records().putSleep(active.copy(id = id(), activeSlot = 2)); fail("Expected slot guard") } catch (_: android.database.SQLException) { }
        assertEquals(1, db.records().allSleeps().size)
    }

    @Test fun crossMidnightArchiveAndZoneSwitchUseRecordedDates() = runBlocking<Unit> {
        val sleepId = id()
        records.startSleep(sleepId, utc("2026-12-31T15:50:00Z"), "Asia/Shanghai").value()
        assertEquals("2026-12-31", records.activeSleep().first()!!.archiveLocalDate)
        val done = records.finishSleep(sleepId, utc("2026-12-31T23:20:00Z"), "Asia/Shanghai").value()
        assertEquals("2027-01-01", done.archiveLocalDate)
        assertTrue(records.sleepsOn("2026-12-31").first().isEmpty())
        assertEquals(sleepId, records.sleepsOn("2027-01-01").first().single().id)
        val edited = records.saveSleep(SleepDraft(sleepId, done.startedAtUtcMs, done.startZoneId, done.endedAtUtcMs, "America/Los_Angeles")).value()
        assertEquals("2026-12-31", edited.archiveLocalDate)
    }

    @Test fun overlappingSleepIsRejectedButAdjacentIntervalAndSelfEditWork() = runBlocking<Unit> {
        val first = records.saveSleep(SleepDraft(id(), 10, "UTC", 20, "UTC")).value()
        records.saveSleep(SleepDraft(id(), 20, "UTC", 30, "UTC")).value()
        failure(ErrorCode.OVERLAPPING_SLEEP, records.saveSleep(SleepDraft(id(), 19, "UTC", 21, "UTC")))
        records.saveSleep(SleepDraft(first.id, 10, "UTC", 20, "UTC")).value()
        failure(ErrorCode.INVALID_INTERVAL, records.saveSleep(SleepDraft(id(), 50, "UTC", 40, "UTC")))
        assertEquals(2, db.records().allSleeps().size)
    }

    @Test fun unusuallyLongIntervalNeedsConfirmation() = runBlocking<Unit> {
        val draft = SleepDraft(id(), 0, "UTC", 86_401_000, "UTC")
        failure(ErrorCode.LONG_INTERVAL_CONFIRMATION, records.saveSleep(draft))
        assertTrue(db.records().allSleeps().isEmpty())
        records.saveSleep(draft.copy(confirmLongInterval = true)).value()
    }

    @Test fun optionalTrainingNumbersAndBlankSetsRemainAbsent() = runBlocking<Unit> {
        val draft = WorkoutDraft(id(), clock.millis(), "UTC", listOf(
            WorkoutItemDraft(id(), running),
            WorkoutItemDraft(id(), bench, sets = listOf(
                WorkoutSetDraft(id()), WorkoutSetDraft(id(), weightGrams = 0), WorkoutSetDraft(id(), repetitions = 8),
            )),
        ))
        val stored = workouts.saveWorkout(draft).value()
        assertNull(stored.items[0].item.durationSeconds)
        assertNull(stored.items[0].item.distanceMeters)
        assertEquals(2, stored.items[1].sets.size)
        assertEquals(0L, stored.items[1].sets[0].weightGrams)
        assertNull(stored.items[1].sets[0].repetitions)
        assertNull(stored.items[1].sets[1].weightGrams)
        assertEquals(8, stored.items[1].sets[1].repetitions)
        assertEquals(listOf(0, 1), stored.items.map { it.item.sortOrder })
    }

    @Test fun historyKeepsSnapshotsAfterRenameHideAndEdit() = runBlocking<Unit> {
        val item = WorkoutItemDraft(id(), bench)
        val draft = WorkoutDraft(id(), clock.millis(), "UTC", listOf(item))
        workouts.saveWorkout(draft).value()
        workouts.saveExercise(ExerciseDraft(bench, "卧推改名", ExerciseType.STRENGTH)).value()
        workouts.setExerciseHidden(bench, true).value()
        val edited = workouts.saveWorkout(draft.copy(note = "修改备注")).value()
        assertEquals("卧推", edited.items.single().item.nameSnapshot)
        assertFalse(workouts.exercises().first().any { it.id == bench })
        failure(ErrorCode.HIDDEN_EXERCISE, workouts.saveWorkout(draft.copy(id = id(), items = listOf(item.copy(id = id())))))
        failure(ErrorCode.USED_EXERCISE_TYPE, workouts.saveExercise(ExerciseDraft(bench, "卧推改名", ExerciseType.CARDIO)))
    }

    @Test fun explicitExerciseReplacementCapturesNewSnapshot() = runBlocking<Unit> {
        val custom = workouts.saveExercise(ExerciseDraft(id(), "自定义力量", ExerciseType.STRENGTH)).value()
        val item = WorkoutItemDraft(id(), bench)
        val draft = WorkoutDraft(id(), clock.millis(), "UTC", listOf(item))
        workouts.saveWorkout(draft).value()
        val replaced = workouts.saveWorkout(draft.copy(items = listOf(item.copy(exerciseId = custom.id)))).value()
        assertEquals("自定义力量", replaced.items.single().item.nameSnapshot)
    }

    @Test fun availableNamesAreUniqueWhileHiddenDuplicatesCanBePreserved() = runBlocking<Unit> {
        val first = workouts.saveExercise(ExerciseDraft(id(), " Ｐｒｅｓｓ ", ExerciseType.STRENGTH)).value()
        failure(ErrorCode.DUPLICATE_NAME, workouts.saveExercise(ExerciseDraft(id(), "press", ExerciseType.STRENGTH)))
        workouts.setExerciseHidden(first.id, true).value()
        val second = workouts.saveExercise(ExerciseDraft(id(), "press", ExerciseType.STRENGTH)).value()
        workouts.setExerciseHidden(second.id, true).value()
        workouts.saveExercise(ExerciseDraft(id(), "press", ExerciseType.STRENGTH)).value()
        failure(ErrorCode.DUPLICATE_NAME, workouts.setExerciseHidden(first.id, false))
        assertEquals(11, db.workouts().allExercises().size)
    }

    @Test fun invalidFieldsAndForeignItemIdsDoNotChangeExistingTraining() = runBlocking<Unit> {
        val item = WorkoutItemDraft(id(), running)
        val draft = WorkoutDraft(id(), 1000, "UTC", listOf(item))
        val original = workouts.saveWorkout(draft).value()
        failure(ErrorCode.INVALID_NUMBER, workouts.saveWorkout(draft.copy(items = listOf(item.copy(distanceMeters = 0)))))
        failure(ErrorCode.INVALID_FIELDS_FOR_TYPE, workouts.saveWorkout(draft.copy(items = listOf(item.copy(sets = listOf(WorkoutSetDraft(id(), repetitions = 3)))))))
        failure(ErrorCode.DUPLICATE_ID, workouts.saveWorkout(draft.copy(id = id())))
        failure(ErrorCode.EMPTY_WORKOUT, workouts.saveWorkout(draft.copy(items = emptyList())))
        assertEquals(original, workouts.workout(draft.id))
        assertEquals(1, db.workouts().allSessions().size)
    }

    @Test fun lateDatabaseFailureRollsBackWholeTrainingReplacement() = runBlocking<Unit> {
        val item = WorkoutItemDraft(id(), bench, sets = listOf(WorkoutSetDraft(id(), repetitions = 5)))
        val draft = WorkoutDraft(id(), 1000, "UTC", listOf(item), "原备注")
        val original = workouts.saveWorkout(draft).value()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_test_set BEFORE INSERT ON WorkoutSet WHEN NEW.repetitions = 99 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val failing = draft.copy(note = "不应留下", items = listOf(item.copy(sets = listOf(WorkoutSetDraft(id(), repetitions = 99)))))
        failure(ErrorCode.STORAGE, workouts.saveWorkout(failing))
        assertEquals(original, workouts.workout(draft.id))
    }

    @Test fun cascadesDeleteChildrenAndDefinitionReferencesAreRestricted() = runBlocking<Unit> {
        val draft = WorkoutDraft(id(), 1000, "UTC", listOf(WorkoutItemDraft(id(), bench, sets = listOf(WorkoutSetDraft(id(), repetitions = 5)))))
        workouts.saveWorkout(draft).value()
        assertThrows(Exception::class.java) { db.openHelper.writableDatabase.execSQL("DELETE FROM ExerciseDefinition WHERE id = '$bench'") }
        workouts.deleteWorkout(draft.id).value()
        assertTrue(db.workouts().allItems().isEmpty())
        assertTrue(db.workouts().allSets().isEmpty())
        failure(ErrorCode.USED_EXERCISE_TYPE, workouts.saveExercise(ExerciseDraft(bench, "卧推", ExerciseType.CARDIO)))
    }

    @Test fun backupSnapshotIncludesUnusedHiddenLibraryActiveSleepAndPreferences() = runBlocking<Unit> {
        val unused = workouts.saveExercise(ExerciseDraft(id(), "未使用项目", ExerciseType.OTHER)).value()
        workouts.setExerciseHidden(unused.id, true).value()
        records.startSleep(id(), 1000, "UTC").value()
        SettingsRepository(db, writes, clock).setTheme(ThemeMode.DARK).value()
        val snapshot = SnapshotRepository(db).read()
        assertEquals(9, snapshot.exercises.size)
        assertTrue(snapshot.exercises.single { it.id == unused.id }.isHidden)
        assertNull(snapshot.sleeps.single().endedAtUtcMs)
        assertEquals(ThemeMode.DARK, snapshot.settings.themeMode)
        assertTrue(snapshot.sessions.isEmpty())
    }

    @Test fun reopenDoesNotReseedOrOverwriteRenamedHiddenBuiltins() = runBlocking<Unit> {
        db.close()
        val name = "test-${id()}.db"
        db = AppDatabase.create(context, clock, name)
        writes = WriteCoordinator()
        workouts = WorkoutRepository(db, writes, clock)
        workouts.saveExercise(ExerciseDraft(bench, "保留更名", ExerciseType.STRENGTH)).value()
        workouts.setExerciseHidden(bench, true).value()
        db.close()
        db = AppDatabase.create(context, clock, name)
        val reopened = db.workouts().allExercises()
        assertEquals(8, reopened.size)
        assertEquals("保留更名", reopened.single { it.id == bench }.name)
        assertTrue(reopened.single { it.id == bench }.isHidden)
    }

    @Test fun directTransactionFailureLeavesNoPartialMeal() = runBlocking<Unit> {
        val meal = MealRecord(id(), 1000, "UTC", "1970-01-01", "虚构内容", null, null, 1, 1)
        try { db.withTransaction { db.records().putMeal(meal); throw IllegalStateException("test rollback") } } catch (_: IllegalStateException) { }
        assertNull(db.records().meal(meal.id))
    }

    @Test fun coordinatedFilePhaseCanCommitThroughRepositoryWithoutReleasingWriteProtection() = runBlocking<Unit> {
        val meal = MealDraft(id(), 1000, "UTC", "虚构文字")
        withTimeout(5000) {
            writes.withExclusiveAccess {
                records.saveMeal(meal).value()
                SettingsRepository(db, writes, clock).recordSuccessfulExport(1000).value()
            }
        }
        assertEquals(meal.id, records.observeMeal(meal.id).first()!!.id)
        assertEquals(1000L, db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
    }

    @Test fun photoMetadataIsRolledBackWhenMealInsertFails() = runBlocking<Unit> {
        db.metadata().settingsNow()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_test_meal BEFORE INSERT ON MealRecord BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val photoId = id()
        val generation = id()
        val photo = MediaAsset(photoId, generation, MediaRules.relativePath(generation, photoId),
            "image/jpeg", 10, 10, 1, "a".repeat(64), 1, 1)
        val draft = MealDraft(id(), 1000, "UTC", photoId = photoId)
        failure(ErrorCode.STORAGE, records.saveMeal(draft, photo))
        assertNull(db.metadata().media(photoId))
        assertNull(db.records().meal(draft.id))
    }

    @Test fun currentZoneChangesDoNotRewriteStoredDates() = runBlocking<Unit> {
        var zone = ZoneId.of("Asia/Shanghai")
        val startClock = Clock.fixed(Instant.parse("2026-12-31T18:00:00Z"), ZoneOffset.UTC)
        val repository = RecordRepository(db, writes, startClock) { zone }
        val active = repository.startSleep(id()).value()
        assertEquals("2027-01-01", active.archiveLocalDate)
        zone = ZoneId.of("America/Los_Angeles")
        assertEquals("2027-01-01", repository.sleep(active.id)!!.archiveLocalDate)
        val finished = repository.finishSleep(active.id, utc("2027-01-01T00:00:00Z")).value()
        assertEquals("Asia/Shanghai", finished.startZoneId)
        assertEquals("America/Los_Angeles", finished.endZoneId)
        assertEquals("2026-12-31", finished.archiveLocalDate)
        assertEquals(listOf("2026-12-31"), repository.datesWithRecords().first())
    }

    @Test fun itemAndSetLimitsAreInclusiveAndRejectedEditsLeaveTheOldGraph() = runBlocking<Unit> {
        val manyItems = WorkoutDraft(id(), 1000, "UTC", (1..100).map { WorkoutItemDraft(id(), running) })
        val stored = workouts.saveWorkout(manyItems).value()
        assertEquals((0..99).toList(), stored.items.map { it.item.sortOrder })
        failure(ErrorCode.TOO_MANY_ITEMS, workouts.saveWorkout(manyItems.copy(items = manyItems.items + WorkoutItemDraft(id(), running))))
        assertEquals(stored, workouts.workout(manyItems.id))
        val item = WorkoutItemDraft(id(), bench, sets = (1..100).map { WorkoutSetDraft(id(), repetitions = it) })
        val manySets = WorkoutDraft(id(), 1000, "UTC", listOf(item))
        val previous = workouts.saveWorkout(manySets).value()
        assertEquals(100, previous.items.single().sets.size)
        failure(ErrorCode.TOO_MANY_SETS, workouts.saveWorkout(manySets.copy(items = listOf(item.copy(sets = item.sets + WorkoutSetDraft(id(), repetitions = 1))))))
        assertEquals(previous, workouts.workout(manySets.id))
        // A blank UI placeholder is not a stored group and does not consume the 100-group limit.
        assertEquals(100, workouts.saveWorkout(manySets.copy(items = listOf(item.copy(sets = item.sets + WorkoutSetDraft(id()))))).value().items.single().sets.size)
    }
}
