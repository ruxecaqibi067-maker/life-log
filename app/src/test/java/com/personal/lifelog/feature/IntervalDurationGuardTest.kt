package com.personal.lifelog.feature

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.core.time.IntervalDurationPolicy as Policy
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.data.local.entity.WorkRecord
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.sleep.*
import com.personal.lifelog.feature.work.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class IntervalDurationGuardTest {
    private lateinit var db: AppDatabase
    private lateinit var sleeps: RecordRepository
    private lateinit var works: WorkRepository
    private var now = Instant.parse("2026-11-12T00:00:00.123Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = object : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = this@IntervalDurationGuardTest.zone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val models = mutableListOf<ViewModel>()
    private fun <T : ViewModel> keep(model: T) = model.also { models += it }
    private enum class Kind(val minimum: Long, val table: String) {
        SLEEP(Policy.MIN_SLEEP_MS, "SleepRecord"), WORK(Policy.MIN_WORK_MS, "WorkRecord")
    }
    private data class Row(val sleep: SleepRecord? = null, val work: WorkRecord? = null) {
        val id get() = sleep?.id ?: work!!.id
    }
    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value
    private fun DataResult<*>.fails(code: ErrorCode) = assertEquals(code, (this as DataResult.Failure).error.code)
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        sleeps = RecordRepository(db, writes, clock) { zone }
        works = WorkRepository(db, writes, clock) { zone }
    }
    @After fun close() = runBlocking {
        models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        db.close()
        Dispatchers.resetMain()
    }
    private suspend fun start(kind: Kind, at: Long = now.toEpochMilli()): Row = when (kind) {
        Kind.SLEEP -> Row(sleep = sleeps.startSleep(RecordIds.newId(), at, zone.id).ok())
        Kind.WORK -> Row(work = works.startWork(RecordIds.newId(), at, zone.id).ok())
    }
    private suspend fun complete(row: Row, end: Long, confirmed: Boolean = false): DataResult<*> =
        row.sleep?.let { sleeps.completeSleep(it, end, zone.id, confirmed) }
            ?: works.completeWork(row.work!!, end, zone.id, confirmed)
    private suspend fun exists(row: Row): Any? = row.sleep?.let { sleeps.sleep(it.id) } ?: works.work(row.id)
    private suspend fun save(kind: Kind, id: String, start: Long, end: Long, check: Boolean = true): DataResult<*> = when (kind) {
        Kind.SLEEP -> sleeps.saveSleep(SleepDraft(id, start, zone.id, end, zone.id), checkDuration = check)
        Kind.WORK -> works.saveWork(WorkDraft(id, start, zone.id, end, zone.id), checkDuration = check)
    }

    @Test fun immediateAndOneMillisecondBelowMinimumDiscardWhileExactMinimumPersists() = runBlocking {
        for (kind in Kind.entries) {
            val at = now.toEpochMilli()
            for (duration in listOf(0L, kind.minimum - 1)) {
                val row = start(kind, at)
                assertNull(complete(row, at + duration).ok())
                assertNull(exists(row))
            }
            val row = start(kind, at)
            assertNotNull(complete(row, at + kind.minimum).ok())
            assertNotNull(exists(row))
        }
    }

    @Test fun negativeEndAndInvalidZoneNeverDiscardTheActiveRecord() = runBlocking {
        for (kind in Kind.entries) {
            val row = start(kind)
            complete(row, now.toEpochMilli() - 1).fails(ErrorCode.INVALID_INTERVAL)
            val invalid = row.sleep?.let { sleeps.completeSleep(it, now.toEpochMilli(), "invalid/zone") }
                ?: works.completeWork(row.work!!, now.toEpochMilli(), "invalid/zone")
            invalid.fails(ErrorCode.INVALID_TIME_ZONE)
            assertNotNull(exists(row))
        }
    }

    @Test fun shortDiscardIsAtomicAndRetriesAfterStorageFailure() = runBlocking {
        for (kind in Kind.entries) {
            val row = start(kind)
            db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_discard BEFORE DELETE ON ${kind.table} BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            assertTrue(complete(row, now.toEpochMilli()) is DataResult.Failure)
            assertNotNull(exists(row))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_discard")
            assertNull(complete(row, now.toEpochMilli()).ok())
            assertNull(exists(row))
        }
    }

    @Test fun staleShortFinishCannotDeleteAnEditedRow() = runBlocking {
        for (kind in Kind.entries) {
            val row = start(kind)
            save(kind, row.id, now.toEpochMilli(), now.toEpochMilli() + kind.minimum).ok()
            complete(row, now.toEpochMilli()).fails(ErrorCode.NOT_FOUND)
            assertNotNull(exists(row))
        }
    }

    @Test fun twelveHoursIsAcceptedAndOneMillisecondOverRequiresConfirmation() = runBlocking {
        for (kind in Kind.entries) {
            val at = now.toEpochMilli()
            val exact = start(kind, at)
            assertNotNull(complete(exact, at + Policy.CONFIRM_AFTER_MS).ok())
            val long = start(kind, at + Policy.CONFIRM_AFTER_MS)
            val end = at + Policy.CONFIRM_AFTER_MS * 2 + 1
            complete(long, end).fails(ErrorCode.LONG_INTERVAL_CONFIRMATION)
            assertNotNull(exists(long))
            assertNotNull(complete(long, end, true).ok())
        }
    }

    @Test fun manualSaveRejectsShortOrLongInputWithoutChangingLegacyData() = runBlocking {
        for (kind in Kind.entries) {
            val at = now.toEpochMilli()
            val id = RecordIds.newId()
            save(kind, id, at, at + kind.minimum - 1).fails(ErrorCode.INTERVAL_TOO_SHORT)
            save(kind, id, at, at + Policy.CONFIRM_AFTER_MS + 1).fails(ErrorCode.LONG_INTERVAL_CONFIRMATION)
            // A pre-existing/imported short interval remains readable; interactive edits do not silently delete it.
            save(kind, id, at, at + 1000, check = false).ok()
            save(kind, id, at, at + 2000).fails(ErrorCode.INTERVAL_TOO_SHORT)
            when (kind) {
                Kind.SLEEP -> assertEquals(at + 1000, sleeps.sleep(id)!!.endedAtUtcMs)
                Kind.WORK -> assertEquals(at + 1000, works.work(id)!!.endedAtUtcMs)
            }
        }
    }

    @Test fun sleepShortFinishClearsUndoAndEmitsNoticeOnlyAfterCommittedDiscard() = runBlocking {
        val model = keep(SleepViewModel(sleeps, clock) { zone })
        model.start(RecordIds.newId())
        withTimeout(5000) { model.busy.first { !it } }
        val row = sleeps.activeSleep().first()!!
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_discard BEFORE DELETE ON SleepRecord BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        assertNotNull(model.error.value)
        assertNotNull(sleeps.sleep(row.id))
        assertNull(withTimeoutOrNull(50) { model.shortRecordNotices.first() })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_discard")
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        assertNull(sleeps.sleep(row.id))
        assertNull(model.undo.value)
        assertNull(model.bedtimeRequest.value)
        withTimeout(5000) { model.shortRecordNotices.first() }
    }

    @Test fun workShortFinishClearsUndoAndEmitsNotice() = runBlocking {
        val model = keep(WorkViewModel(works, clock) { zone })
        withTimeout(5000) { model.status.first { it.loaded } }
        model.start(RecordIds.newId()); withTimeout(5000) { model.busy.first { !it } }
        val row = works.activeWork().first()!!
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        assertNull(works.work(row.id))
        assertNull(model.undo.value)
        withTimeout(5000) { model.shortRecordNotices.first() }
    }

    @Test fun sleepLongFinishAllowsCorrectionKeepsCapturedBoundAndSafeUndo() = runBlocking {
        val model = keep(SleepViewModel(sleeps, clock) { zone })
        val row = sleeps.startSleep(RecordIds.newId(), now.minusSeconds(13 * 3600).toEpochMilli(), zone.id).ok()
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        val captured = model.longFinish.value!!
        now = now.plusSeconds(3600)
        model.finish(row) // Another callback must not replace the captured end.
        assertEquals(captured, model.longFinish.value)
        model.changeFinishEnd(captured.latestEnd + 1, zone.id)
        assertEquals(captured, model.longFinish.value)
        val actualEnd = row.startedAtUtcMs + 8 * 3_600_000
        model.changeFinishEnd(actualEnd, zone.id)
        assertNull(sleeps.sleep(row.id)!!.endedAtUtcMs)
        model.confirmLong(); withTimeout(5000) { model.busy.first { !it } }
        assertEquals(actualEnd, sleeps.sleep(row.id)!!.endedAtUtcMs)
        model.undoLast(); withTimeout(5000) { model.busy.first { !it } }
        assertNull(sleeps.sleep(row.id)!!.endedAtUtcMs)
    }

    @Test fun workLongCancelDoesNotWriteAndFailureRetainsCorrectedTimeForRetry() = runBlocking {
        val model = keep(WorkViewModel(works, clock) { zone })
        val row = works.startWork(RecordIds.newId(), now.minusSeconds(13 * 3600).toEpochMilli(), zone.id).ok()
        withTimeout(5000) { model.status.first { it.loaded } }
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        model.dismissLong()
        assertNull(works.work(row.id)!!.endedAtUtcMs)
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        val actualEnd = row.startedAtUtcMs + 6 * 3_600_000
        model.changeFinishEnd(actualEnd, zone.id)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_finish BEFORE UPDATE ON WorkRecord BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        model.confirmLong(); withTimeout(5000) { model.busy.first { !it } }
        assertEquals(actualEnd, model.longFinish.value!!.instant)
        assertNull(works.work(row.id)!!.endedAtUtcMs)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_finish")
        now = now.plusSeconds(3600)
        model.confirmLong(); withTimeout(5000) { model.busy.first { !it } }
        assertEquals(actualEnd, works.work(row.id)!!.endedAtUtcMs)
    }

    @Test fun confirmedCorrectionBelowMinimumDiscardsInsteadOfSavingNoise() = runBlocking {
        val model = keep(SleepViewModel(sleeps, clock) { zone })
        val row = sleeps.startSleep(RecordIds.newId(), now.minusSeconds(13 * 3600).toEpochMilli(), zone.id).ok()
        model.finish(row); withTimeout(5000) { model.busy.first { !it } }
        model.changeFinishEnd(row.startedAtUtcMs + Policy.MIN_SLEEP_MS - 1, zone.id)
        model.confirmLong(); withTimeout(5000) { model.busy.first { !it } }
        assertNull(sleeps.sleep(row.id))
        assertNull(model.undo.value)
    }

    @Test fun bothEditorsUseMinimumAndTwelveHourConfirmationWithoutWritingDrafts() = runBlocking {
        val sleep = keep(SleepEditorViewModel(sleeps, clock, { zone }, null, SavedStateHandle()))
        val work = keep(WorkEditorViewModel(works, clock, { zone }, null, SavedStateHandle()))
        withTimeout(5000) { sleep.state.first { !it.loading } }
        withTimeout(5000) { work.state.first { !it.loading } }
        sleep.change(SleepForm("2026-11-12T08:00", zone.id, end = "2026-11-12T08:09", endZone = zone.id))
        work.change(WorkForm("2026-11-12T08:00", zone.id, end = "2026-11-12T08:04", endZone = zone.id))
        sleep.save(); work.save()
        withTimeout(5000) { sleep.state.first { !it.busy } }
        withTimeout(5000) { work.state.first { !it.busy } }
        assertEquals(ErrorCode.INTERVAL_TOO_SHORT, sleep.state.value.error)
        assertEquals(ErrorCode.INTERVAL_TOO_SHORT, work.state.value.error)
        sleep.change(sleep.state.value.form!!.copy(end = "2026-11-12T21:00"))
        work.change(work.state.value.form!!.copy(end = "2026-11-12T21:00"))
        sleep.save(); work.save()
        withTimeout(5000) { sleep.state.first { !it.busy } }
        withTimeout(5000) { work.state.first { !it.busy } }
        assertNotNull(sleep.state.value.pendingLong)
        assertNotNull(work.state.value.pendingLong)
        assertTrue(sleeps.sleeps().first().isEmpty())
        assertTrue(works.works().first().isEmpty())
    }
}
