package com.personal.lifelog.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class WorkRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 11, 1)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var records: WorkRepository
    private lateinit var summaries: WorkSummaryRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        settings = SettingsRepository(db, writes, clock)
        records = WorkRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        summaries = WorkSummaryRepository(db)
    }
    @After fun close() { db.close() }
    private fun <T> DataResult<T>.saved(): T = (this as DataResult.Success).value
    private suspend fun summary(day: LocalDate = date) = summaries.observe(day).first().saved()
    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()
    private suspend fun interval(start: Long, end: Long, zone: String = "Asia/Shanghai", id: String = RecordIds.newId()) =
        records.saveWork(WorkDraft(id, start, zone, end, zone, confirmLongInterval = true)).saved()
    private suspend fun night() = interval(ms("2026-10-31T15:50:00Z"), ms("2026-10-31T23:10:00Z"))

    @Test fun freshUnsetAndBoundaryGoalsPreserveSettingsAndAllRecords() = runBlocking<Unit> {
        night()
        settings.setTargetSleepMinutes(600).saved()
        RecordRepository(db, WriteCoordinator(), clock) { ZoneId.of("UTC") }.startSleep(RecordIds.newId(), 1000, "UTC").saved()
        settings.setTheme(ThemeMode.DARK).saved()
        settings.recordSuccessfulExport(77).saved()
        val before = SnapshotRepository(db).read()
        assertNull(settings.targetWorkMinutes().first())
        for (goal in listOf(1, 480, 1440, null)) {
            settings.setTargetWorkMinutes(goal).saved()
            assertEquals(goal, settings.targetWorkMinutes().first())
            assertEquals(goal, summary().targetWorkMinutes)
            val after = SnapshotRepository(db).read()
            assertEquals(before, after.copy(settings = before.settings))
            assertEquals(before.settings.copy(targetWorkMinutes = goal), after.settings)
        }
    }

    @Test fun invalidGoalsAndStorageFailureNeverChangeTheOriginalValue() = runBlocking<Unit> {
        settings.setTargetWorkMinutes(480).saved()
        val before = SnapshotRepository(db).read()
        for (goal in listOf(0, -1, 1441, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val failure = settings.setTargetWorkMinutes(goal) as DataResult.Failure
            assertEquals(DataError(ErrorCode.INVALID_NUMBER, "targetWorkMinutes"), failure.error)
            assertEquals(before, SnapshotRepository(db).read())
        }
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_goal BEFORE UPDATE ON AppSettings BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(settings.setTargetWorkMinutes(600) is DataResult.Failure)
        assertEquals(before, SnapshotRepository(db).read())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_goal")
        settings.setTargetWorkMinutes(600).saved()
        assertEquals(600, settings.targetWorkMinutes().first())
    }

    @Test fun themeExportAndConcurrentWritesKeepTheGoalAndOtherFields() = runBlocking<Unit> {
        settings.setTargetWorkMinutes(480).saved()
        settings.recordSuccessfulExport(77).saved()
        settings.setTargetSleepMinutes(600).saved()
        val theme = async(Dispatchers.Default) { settings.setTheme(ThemeMode.DARK).saved() }
        val goal = async(Dispatchers.Default) { settings.setTargetWorkMinutes(600).saved() }
        awaitAll(theme, goal)
        val saved = settings.settings().first()
        assertEquals(600, saved.targetWorkMinutes)
        assertEquals(600, saved.targetSleepMinutes)
        assertEquals(ThemeMode.DARK, saved.themeMode)
        assertEquals(77L, saved.lastSuccessfulExportAtUtcMs)
    }

    @Test fun databaseGuardsRejectOutOfRangeAndFractionalGoals() = runBlocking<Unit> {
        settings.setTargetWorkMinutes(480).saved()
        for (bad in listOf<Any>(0, -1, 1441, 1.5)) {
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                db.openHelper.writableDatabase.execSQL("UPDATE AppSettings SET targetWorkMinutes = ? WHERE id = 1", arrayOf(bad))
            }
        }
        assertEquals(480, settings.targetWorkMinutes().first())
    }

    @Test fun crossNightUsesWholeIntervalOnSavedRiseDateAndSumsBeforeRounding() = runBlocking<Unit> {
        night()
        assertNull(summary(date.minusDays(1)).recordedDurationMs)
        val nap = ms("2026-11-01T04:00:00Z")
        interval(nap, nap + 600)
        interval(nap + 1_000, nap + 1_700)
        settings.setTargetWorkMinutes(1).saved()
        val sum = summary()
        assertEquals(3, sum.completedSegmentCount)
        assertEquals(26_401_300L, sum.recordedDurationMs)
        assertEquals(1, sum.targetWorkMinutes) // Over-goal totals remain real, not clamped.
        assertFalse(sum.hasActiveWork)
    }

    @Test fun ongoingYesterdayIsGlobalAndNeverContributesToCompletedTotal() = runBlocking<Unit> {
        records.startWork(RecordIds.newId(), ms("2026-10-31T15:00:00Z"), "Asia/Shanghai").saved()
        settings.setTargetWorkMinutes(480).saved()
        val sum = summary()
        assertTrue(sum.hasActiveWork)
        assertEquals(0, sum.completedSegmentCount)
        assertNull(sum.recordedDurationMs)
        assertTrue(summary(date.plusDays(1)).hasActiveWork)
    }

    @Test fun noRecordsAndNoTargetAreSuccessfulAbsences() = runBlocking<Unit> {
        val sum = summary()
        assertEquals(date, sum.date)
        assertNull(sum.targetWorkMinutes)
        assertNull(sum.recordedDurationMs)
        assertEquals(0, sum.completedSegmentCount)
        assertFalse(sum.hasActiveWork)
    }

    @Test fun dstRepeatedHourAndSpringGapUseElapsedMilliseconds() = runBlocking<Unit> {
        val zone = "America/New_York"
        interval(ms("2026-11-01T05:30:00Z"), ms("2026-11-01T06:30:00Z"), zone)
        interval(ms("2026-03-08T06:30:00Z"), ms("2026-03-08T07:30:00Z"), zone)
        assertEquals(3_600_000L, summary().recordedDurationMs)
        assertEquals(3_600_000L, summary(LocalDate.of(2026, 3, 8)).recordedDurationMs)
    }

    @Test fun changingCurrentZoneDoesNotReassignPersistedArchiveDates() = runBlocking<Unit> {
        interval(ms("2026-10-31T15:50:00Z"), ms("2026-10-31T23:10:00Z"), "America/Los_Angeles")
        val shanghaiToday = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))
        val laToday = LocalDate.now(clock.withZone(ZoneId.of("America/Los_Angeles")))
        assertEquals(date, shanghaiToday)
        assertEquals(date.minusDays(1), laToday)
        assertNull(summary(shanghaiToday).recordedDurationMs)
        assertEquals(26_400_000L, summary(laToday).recordedDurationMs)
        assertEquals("2026-10-31", db.work().allWorks().single().archiveLocalDate)
    }

    @Test fun activeFlowRefreshesAfterTargetSaveDeleteAndArchiveDateEdit() = runBlocking<Unit> {
        val changes = Channel<WorkSummary>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { summaries.observe(date).collect { changes.send(it.saved()) } }
        suspend fun await(predicate: (WorkSummary) -> Boolean): WorkSummary = withTimeout(10_000) {
            var next = changes.receive()
            while (!predicate(next)) next = changes.receive()
            next
        }
        try {
            await { it.completedSegmentCount == 0 }
            settings.setTargetWorkMinutes(480).saved()
            await { it.targetWorkMinutes == 480 }
            val first = night()
            await { it.completedSegmentCount == 1 }
            records.deleteWork(first).saved()
            await { it.completedSegmentCount == 0 }
            val second = night()
            await { it.completedSegmentCount == 1 }
            records.editWork(WorkDraft(second.id, second.startedAtUtcMs, second.startZoneId,
                ms("2026-11-01T23:10:00Z"), second.endZoneId, confirmLongInterval = true), second).saved()
            assertNull(await { it.completedSegmentCount == 0 }.recordedDurationMs)
            assertEquals(1, summary(date.plusDays(1)).completedSegmentCount)
        } finally { job.cancelAndJoin(); changes.close() }
    }

    @Test fun readFailureIsNotEmptyAndCanBeRetriedByRecollecting() = runBlocking<Unit> {
        val old = settings.settings().first()
        db.openHelper.writableDatabase.execSQL("DELETE FROM AppSettings")
        val failed = summaries.observe(date).first() as DataResult.Failure
        assertEquals(ErrorCode.STORAGE, failed.error.code)
        db.metadata().putSettings(old)
        assertEquals(0, summary().completedSegmentCount)
    }

    @Test fun simultaneousStartsAndRetriesAreIdempotentAndFinishedIdsNeverReopen() = runBlocking<Unit> {
        val firstId = RecordIds.newId()
        val rows = (1..8).map { async(Dispatchers.Default) { records.startWork(firstId, 1000, "UTC").saved() } }.awaitAll()
        assertEquals(1, rows.distinct().size)
        assertEquals(rows.first(), records.startWork(RecordIds.newId(), 2000, "UTC").saved())
        val finished = records.finishWork(firstId, 3000, "UTC").saved()
        assertEquals(finished, records.finishWork(firstId, 5000, "UTC").saved())
        assertEquals(finished, records.startWork(firstId, 6000, "UTC").saved())
        assertEquals(1, records.works().first().size)
        assertNull(records.activeWork().first())
        val newStarts = (1..2).map { async(Dispatchers.Default) { records.startWork(RecordIds.newId(), 4000, "UTC").saved() } }.awaitAll()
        assertEquals(1, newStarts.distinct().size)
        assertEquals(2, records.works().first().size)
    }

    @Test fun adjacentAllowedButOverlapInvalidZoneAndReversedIntervalsKeepOriginals() = runBlocking<Unit> {
        val first = interval(1000, 2000, "UTC")
        interval(2000, 3000, "UTC")
        val before = SnapshotRepository(db).read()
        for (draft in listOf(WorkDraft(RecordIds.newId(), 1999, "UTC", 2001, "UTC"),
            WorkDraft(first.id, 1000, "UTC", 3000, "UTC"),
            WorkDraft(RecordIds.newId(), 4000, "UTC", 4000, "UTC"),
            WorkDraft(RecordIds.newId(), 4000, "UTC", 3999, "UTC"),
            WorkDraft(RecordIds.newId(), 4000, "bad-zone"),
            WorkDraft(RecordIds.newId(), 4000, "UTC", null, "UTC"),
            WorkDraft(RecordIds.newId(), Long.MIN_VALUE, "UTC", Long.MAX_VALUE, "UTC", true))) {
            assertTrue(records.saveWork(draft) is DataResult.Failure)
            assertEquals(before, SnapshotRepository(db).read())
        }
        val active = records.startWork(RecordIds.newId(), 3000, "UTC").saved()
        assertTrue(records.saveWork(WorkDraft(RecordIds.newId(), 4000, "UTC")) is DataResult.Failure)
        assertEquals(active, records.activeWork().first())
    }

    @Test fun longFinishRequiresConfirmationOfCapturedTimeAndFailedSaveStaysActive() = runBlocking<Unit> {
        val active = records.startWork(RecordIds.newId(), 1000, "UTC").saved()
        val captured = 86_401_001L
        val failure = records.finishWork(active.id, captured, "UTC", expected = active) as DataResult.Failure
        assertEquals(ErrorCode.LONG_INTERVAL_CONFIRMATION, failure.error.code)
        assertEquals(active, records.work(active.id))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_finish BEFORE UPDATE ON WorkRecord BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(records.finishWork(active.id, captured, "UTC", true, active) is DataResult.Failure)
        assertEquals(active, records.work(active.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_finish")
        val finished = records.finishWork(active.id, captured, "UTC", true, active).saved()
        assertEquals(captured, finished.endedAtUtcMs)
        assertEquals("1970-01-02", finished.archiveLocalDate)
        assertEquals(86_400_001L, summary(LocalDate.of(1970, 1, 2)).recordedDurationMs)
        records.undoWork(finished, active).saved()
        val exact = records.finishWork(active.id, 86_401_000, "UTC").saved()
        assertEquals(86_401_000L, exact.endedAtUtcMs) // Exactly 24 h needs no confirmation.
    }

    @Test fun safeEditDeleteAndUndoRejectStaleRowsAndRefreshSummary() = runBlocking<Unit> {
        val active = records.startWork(RecordIds.newId(), 1000, "UTC").saved()
        val finished = records.finishWork(active.id, 2000, "UTC").saved()
        assertTrue(records.editWork(WorkDraft(active.id, 1000, "UTC"), active) is DataResult.Failure)
        assertTrue(records.deleteWork(active) is DataResult.Failure)
        assertTrue(records.undoWork(active, null) is DataResult.Failure)
        records.undoWork(finished, active).saved()
        assertNull(summary(LocalDate.of(1970, 1, 1)).recordedDurationMs)
        val restoredActive = records.work(active.id)!!
        records.undoWork(restoredActive, null).saved()
        assertTrue(records.works().first().isEmpty())
        val completed = interval(1000, 3000, "UTC")
        records.deleteWork(completed).saved()
        assertNull(summary(LocalDate.of(1970, 1, 1)).recordedDurationMs)
        records.restoreDeletedWork(completed).saved()
        assertEquals(2000L, summary(LocalDate.of(1970, 1, 1)).recordedDurationMs)
        assertTrue(records.restoreDeletedWork(completed) is DataResult.Failure)
        val changed = records.editWork(WorkDraft(completed.id, 1000, "UTC", 4000, "UTC"), records.work(completed.id)!!).saved()
        assertTrue(records.deleteWork(completed) is DataResult.Failure)
        assertEquals(changed, records.work(changed.id))
        records.deleteWork(changed).saved()
        interval(2000, 5000, "UTC")
        assertTrue(records.restoreDeletedWork(completed) is DataResult.Failure)
    }

    @Test fun openWorkSurvivesDatabaseReopenAndKeepsStartZoneWhenEndingElsewhere() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "t20-reopen-${RecordIds.newId()}.db"
        db.close()
        db = AppDatabase.create(context, clock, name)
        records = WorkRepository(db, WriteCoordinator(), clock) { ZoneId.of("Asia/Shanghai") }
        val active = records.startWork(RecordIds.newId(), ms("2026-10-31T23:30:00Z"), "America/Los_Angeles").saved()
        db.close()
        db = AppDatabase.create(context, clock, name)
        records = WorkRepository(db, WriteCoordinator(), clock) { ZoneId.of("Asia/Shanghai") }
        try {
            assertEquals(active, records.activeWork().first())
            assertEquals(listOf("2026-10-31"), records.datesWithRecords().first())
            assertEquals(listOf(active), records.worksOn("2026-10-31").first())
            val finished = records.finishWork(active.id, ms("2026-11-01T00:30:00Z"), "Asia/Shanghai").saved()
            assertEquals("2026-11-01", finished.archiveLocalDate)
            assertEquals("America/Los_Angeles", finished.startZoneId)
            assertEquals(listOf("2026-11-01"), records.datesWithRecords().first())
            assertTrue(records.worksOn("2026-10-31").first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
