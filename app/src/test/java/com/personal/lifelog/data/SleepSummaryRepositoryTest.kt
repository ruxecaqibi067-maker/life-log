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
class SleepSummaryRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.of(2026, 11, 1)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var records: RecordRepository
    private lateinit var summaries: SleepSummaryRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        settings = SettingsRepository(db, writes, clock)
        records = RecordRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        summaries = SleepSummaryRepository(db)
    }
    @After fun close() { db.close() }
    private fun <T> DataResult<T>.saved(): T = (this as DataResult.Success).value
    private suspend fun summary(day: LocalDate = date) = summaries.observe(day).first().saved()
    private fun ms(instant: String) = Instant.parse(instant).toEpochMilli()
    private suspend fun interval(start: Long, end: Long, zone: String = "Asia/Shanghai", id: String = RecordIds.newId()) =
        records.saveSleep(SleepDraft(id, start, zone, end, zone, confirmLongInterval = true)).saved()
    private suspend fun night() = interval(ms("2026-10-31T15:50:00Z"), ms("2026-10-31T23:10:00Z"))

    @Test fun freshUnsetAndBoundaryGoalsPreserveSettingsAndAllRecords() = runBlocking<Unit> {
        night()
        settings.setTheme(ThemeMode.DARK).saved()
        settings.recordSuccessfulExport(77).saved()
        val before = SnapshotRepository(db).read()
        assertNull(settings.targetSleepMinutes().first())
        for (goal in listOf(1, 480, 1440, null)) {
            settings.setTargetSleepMinutes(goal).saved()
            assertEquals(goal, settings.targetSleepMinutes().first())
            assertEquals(goal, summary().targetSleepMinutes)
            val after = SnapshotRepository(db).read()
            assertEquals(before, after.copy(settings = before.settings))
            assertEquals(before.settings.copy(targetSleepMinutes = goal), after.settings)
        }
    }

    @Test fun invalidGoalsAndStorageFailureNeverChangeTheOriginalValue() = runBlocking<Unit> {
        settings.setTargetSleepMinutes(480).saved()
        val before = SnapshotRepository(db).read()
        for (goal in listOf(0, -1, 1441, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val failure = settings.setTargetSleepMinutes(goal) as DataResult.Failure
            assertEquals(DataError(ErrorCode.INVALID_NUMBER, "targetSleepMinutes"), failure.error)
            assertEquals(before, SnapshotRepository(db).read())
        }
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_goal BEFORE UPDATE ON AppSettings BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(settings.setTargetSleepMinutes(600) is DataResult.Failure)
        assertEquals(before, SnapshotRepository(db).read())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_goal")
        settings.setTargetSleepMinutes(600).saved()
        assertEquals(600, settings.targetSleepMinutes().first())
    }

    @Test fun themeExportAndConcurrentWritesKeepTheGoalAndOtherFields() = runBlocking<Unit> {
        settings.setTargetSleepMinutes(480).saved()
        settings.recordSuccessfulExport(77).saved()
        val theme = async(Dispatchers.Default) { settings.setTheme(ThemeMode.DARK).saved() }
        val goal = async(Dispatchers.Default) { settings.setTargetSleepMinutes(600).saved() }
        awaitAll(theme, goal)
        val saved = settings.settings().first()
        assertEquals(600, saved.targetSleepMinutes)
        assertEquals(ThemeMode.DARK, saved.themeMode)
        assertEquals(77L, saved.lastSuccessfulExportAtUtcMs)
    }

    @Test fun databaseGuardsRejectOutOfRangeAndFractionalGoals() = runBlocking<Unit> {
        settings.setTargetSleepMinutes(480).saved()
        for (bad in listOf<Any>(0, -1, 1441, 1.5)) {
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                db.openHelper.writableDatabase.execSQL("UPDATE AppSettings SET targetSleepMinutes = ? WHERE id = 1", arrayOf(bad))
            }
        }
        assertEquals(480, settings.targetSleepMinutes().first())
    }

    @Test fun crossNightUsesWholeIntervalOnSavedRiseDateAndSumsBeforeRounding() = runBlocking<Unit> {
        night()
        assertNull(summary(date.minusDays(1)).recordedDurationMs)
        val nap = ms("2026-11-01T04:00:00Z")
        interval(nap, nap + 600)
        interval(nap + 1_000, nap + 1_700)
        settings.setTargetSleepMinutes(1).saved()
        val sum = summary()
        assertEquals(3, sum.completedSegmentCount)
        assertEquals(26_401_300L, sum.recordedDurationMs)
        assertEquals(1, sum.targetSleepMinutes) // Over-goal totals remain real, not clamped.
        assertFalse(sum.hasActiveSleep)
    }

    @Test fun ongoingYesterdayIsGlobalAndNeverContributesToCompletedTotal() = runBlocking<Unit> {
        records.startSleep(RecordIds.newId(), ms("2026-10-31T15:00:00Z"), "Asia/Shanghai").saved()
        settings.setTargetSleepMinutes(480).saved()
        val sum = summary()
        assertTrue(sum.hasActiveSleep)
        assertEquals(0, sum.completedSegmentCount)
        assertNull(sum.recordedDurationMs)
        assertTrue(summary(date.plusDays(1)).hasActiveSleep)
    }

    @Test fun noRecordsAndNoTargetAreSuccessfulAbsences() = runBlocking<Unit> {
        val sum = summary()
        assertEquals(date, sum.date)
        assertNull(sum.targetSleepMinutes)
        assertNull(sum.recordedDurationMs)
        assertEquals(0, sum.completedSegmentCount)
        assertFalse(sum.hasActiveSleep)
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
        assertEquals("2026-10-31", db.records().allSleeps().single().archiveLocalDate)
    }

    @Test fun activeFlowRefreshesAfterTargetSaveDeleteAndArchiveDateEdit() = runBlocking<Unit> {
        val changes = Channel<SleepSummary>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { summaries.observe(date).collect { changes.send(it.saved()) } }
        suspend fun await(predicate: (SleepSummary) -> Boolean): SleepSummary = withTimeout(10_000) {
            var next = changes.receive()
            while (!predicate(next)) next = changes.receive()
            next
        }
        try {
            await { it.completedSegmentCount == 0 }
            settings.setTargetSleepMinutes(480).saved()
            await { it.targetSleepMinutes == 480 }
            val first = night()
            await { it.completedSegmentCount == 1 }
            records.deleteSleep(first).saved()
            await { it.completedSegmentCount == 0 }
            val second = night()
            await { it.completedSegmentCount == 1 }
            records.editSleep(SleepDraft(second.id, second.startedAtUtcMs, second.startZoneId,
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
}
