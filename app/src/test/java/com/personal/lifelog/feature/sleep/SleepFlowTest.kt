package com.personal.lifelog.feature.sleep

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SleepFlowTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var repository: RecordRepository
    private val stores = mutableListOf<ViewModelStore>()
    private var now = Instant.parse("2026-10-22T15:50:00Z")
    private var zone = ZoneId.of("Asia/Shanghai")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun failure(code: ErrorCode, result: DataResult<*>) = assertEquals(code, (result as DataResult.Failure).error.code)
    private fun utc(value: String) = Instant.parse(value).toEpochMilli()
    private fun id() = RecordIds.newId()
    private fun <T : ViewModel> managed(type: Class<T>, factory: () -> T): T {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(modelClass: Class<V>): V = factory() as V
        })[type]
    }
    private fun viewModel() = managed(SleepViewModel::class.java) { SleepViewModel(repository, clock) { zone } }
    private fun editor(recordId: String? = null, saved: SavedStateHandle = SavedStateHandle()) =
        managed(SleepEditorViewModel::class.java) { SleepEditorViewModel(repository, clock, { zone }, recordId, saved) }
    private suspend fun SleepViewModel.idle() = withTimeout(5000) { busy.first { !it } }
    private suspend fun SleepEditorViewModel.ready() = withTimeout(5000) { state.first { !it.loading && !it.busy } }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        repository = RecordRepository(db, WriteCoordinator(), clock) { zone }
    }

    @After fun close() {
        stores.forEach { it.clear() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun exactSevenHoursTwentyAcrossDayMonthAndYearAreArchivedAtWakeDate() = runBlocking {
        listOf("2026-10-22" to "2026-10-23", "2026-10-31" to "2026-11-01", "2026-12-31" to "2027-01-01").forEach { (startDate, endDate) ->
            val form = SleepForm("${startDate}T23:50", "Asia/Shanghai", end = "${endDate}T07:10", endZone = "Asia/Shanghai")
            val record = repository.saveSleep(form.draft(id())).value()
            assertEquals(26_400L, RecordTime.durationSeconds(record.startedAtUtcMs, record.endedAtUtcMs!!))
            assertEquals(endDate, record.archiveLocalDate)
            assertEquals(record, repository.sleepsOn(endDate).first().single())
        }
    }

    @Test fun startIsDurableInFileDatabaseAfterClosingAndRecreatingAllDependencies() = runBlocking {
        db.close()
        val name = "sleep-t06-${id()}.db"
        db = AppDatabase.create(context, clock, name)
        repository = RecordRepository(db, WriteCoordinator(), clock) { zone }
        val active = repository.startSleep(id()).value()
        // Observe stored columns, then discard the DB/repository/VM instances entirely.
        db.openHelper.readableDatabase.query("SELECT endedAtUtcMs, activeSlot, archiveLocalDate FROM SleepRecord").use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(1, it.getInt(1)); assertEquals("2026-10-22", it.getString(2))
        }
        db.close()
        now = Instant.parse("2026-10-23T01:00:00Z")
        zone = ZoneId.of("America/Los_Angeles")
        db = AppDatabase.create(context, clock, name)
        repository = RecordRepository(db, WriteCoordinator(), clock) { zone }
        val reloaded = viewModel()
        assertEquals(active, withTimeout(5000) { reloaded.status.first { it.loaded }.active })
        assertNull(repository.sleep(active.id)!!.endedAtUtcMs)
        reloaded.finish(active); reloaded.idle()
        val finished = repository.sleep(active.id)!!
        assertEquals("Asia/Shanghai", finished.startZoneId)
        assertEquals("America/Los_Angeles", finished.endZoneId)
        assertEquals("2026-10-22", finished.archiveLocalDate)
        assertEquals(33_000L, RecordTime.durationSeconds(finished.startedAtUtcMs, finished.endedAtUtcMs!!))
    }

    @Test fun rapidStartAndFinishCallbacksDoNotDuplicateRowsAndUndoRestoresOpenState() = runBlocking {
        val vm = viewModel()
        withTimeout(5000) { vm.status.first { it.loaded } }
        val operationId = id()
        repeat(12) { vm.start(operationId) }; vm.idle()
        val active = repository.activeSleep().first()!!
        assertEquals(1, repository.sleeps().first().size)
        now = Instant.parse("2026-10-22T23:10:00Z")
        repeat(12) { vm.finish(active) }; vm.idle()
        val completed = repository.sleep(active.id)!!
        assertNotNull(completed.endedAtUtcMs)
        assertEquals(1, repository.sleeps().first().size)
        assertEquals(26_400L, RecordTime.durationSeconds(completed.startedAtUtcMs, completed.endedAtUtcMs!!))
        vm.undoLast(); vm.idle()
        assertEquals(active.id, repository.activeSleep().first()!!.id)
        assertNull(repository.sleep(active.id)!!.endedAtUtcMs)
    }

    @Test fun undoStartDeletesOnlyItsOwnUnchangedActiveRecord() = runBlocking {
        val active = repository.startSleep(id()).value()
        repository.undoSleep(active, null).value()
        assertNull(repository.activeSleep().first())
        assertTrue(repository.sleeps().first().isEmpty())
        failure(ErrorCode.NOT_FOUND, repository.undoSleep(active, null))
    }

    @Test fun staleUndoOrEditorCannotOverwriteLaterChangesOrResurrectDeletion() = runBlocking {
        val active = repository.startSleep(id(), 1000, "UTC").value()
        val done = repository.finishSleep(active.id, 2000, "UTC").value()
        val moved = repository.editSleep(SleepDraft(done.id, 1100, "UTC", 2200, "UTC"), done).value()
        failure(ErrorCode.NOT_FOUND, repository.undoSleep(done, active))
        failure(ErrorCode.NOT_FOUND, repository.finishSleep(active.id, 3000, "UTC", expected = active))
        assertEquals(moved, repository.sleep(done.id))
        repository.deleteSleep(moved).value()
        failure(ErrorCode.NOT_FOUND, repository.editSleep(SleepDraft(moved.id, 10, "UTC"), moved))
        assertNull(repository.sleep(moved.id))
    }

    @Test fun undoFinishCannotCreateAnOverlapWithANewActiveRecord() = runBlocking {
        val active = repository.startSleep(id(), 1000, "UTC").value()
        val done = repository.finishSleep(active.id, 2000, "UTC").value()
        val next = repository.startSleep(id(), 3000, "UTC").value()
        failure(ErrorCode.OVERLAPPING_SLEEP, repository.undoSleep(done, active))
        assertEquals(done, repository.sleep(done.id))
        assertEquals(next, repository.activeSleep().first())
    }

    @Test fun napAndNightCoexistAndEditingWakeDateMovesArchiveWithoutDuplicates() = runBlocking {
        val nap = repository.saveSleep(SleepForm("2026-10-22T12:30", "Asia/Shanghai", end = "2026-10-22T13:00", endZone = "Asia/Shanghai").draft(id())).value()
        val night = repository.saveSleep(SleepForm("2026-10-21T23:50", "Asia/Shanghai", end = "2026-10-22T07:10", endZone = "Asia/Shanghai").draft(id())).value()
        assertEquals(2, repository.sleepsOn("2026-10-22").first().size)
        repository.editSleep(SleepForm("2026-10-20T23:50", "Asia/Shanghai", end = "2026-10-21T07:10", endZone = "Asia/Shanghai").draft(night.id), night).value()
        assertEquals(listOf(nap.id), repository.sleepsOn("2026-10-22").first().map { it.id })
        assertEquals(night.id, repository.sleepsOn("2026-10-21").first().single().id)
        assertEquals(2, repository.sleeps().first().size)
    }

    @Test fun editorRejectsNegativeDurationAndOverlapLeavingOriginalRowsIntact() = runBlocking {
        val original = repository.saveSleep(SleepDraft(id(), 1000, "UTC", 2000, "UTC")).value()
        val adjacent = repository.saveSleep(SleepDraft(id(), 2000, "UTC", 3000, "UTC")).value()
        val vm = editor(original.id); vm.ready()
        vm.change(SleepForm("1970-01-01T00:00:02", "UTC", end = "1970-01-01T00:00:01", endZone = "UTC"))
        vm.save(); vm.ready()
        assertEquals(ErrorCode.INVALID_INTERVAL, vm.state.value.error)
        assertEquals(original, repository.sleep(original.id))
        vm.change(SleepForm("1970-01-01T00:00:01", "UTC", end = "1970-01-01T00:00:02.500", endZone = "UTC"))
        vm.save(); vm.ready()
        assertEquals(ErrorCode.OVERLAPPING_SLEEP, vm.state.value.error)
        assertEquals(adjacent, repository.sleep(adjacent.id))
        assertEquals(original, repository.sleep(original.id))
    }

    @Test fun longIntervalCancelKeepsDatabaseUntouchedAndConfirmationUsesCapturedInstants() = runBlocking {
        val vm = viewModel()
        val active = repository.startSleep(id()).value()
        now = now.plusSeconds(25 * 3600)
        vm.finish(active); vm.idle()
        val pending = vm.longFinish.value!!
        assertNull(repository.sleep(active.id)!!.endedAtUtcMs)
        vm.dismissLong()
        assertNull(vm.longFinish.value)
        assertEquals(active, repository.activeSleep().first())
        vm.finish(active); vm.idle()
        now = now.plusSeconds(3600)
        vm.confirmLong(); vm.idle()
        assertEquals(pending.instant, repository.sleep(active.id)!!.endedAtUtcMs)
        assertNull(repository.activeSleep().first())
    }

    @Test fun supplementLongIntervalRequiresExplicitConfirmationAndSupportsDelete() = runBlocking {
        val vm = editor(); vm.ready()
        vm.change(SleepForm("2026-10-20T23:50", "Asia/Shanghai", end = "2026-10-22T07:10", endZone = "Asia/Shanghai"))
        vm.save(); vm.ready()
        assertNotNull(vm.state.value.pendingLong)
        assertTrue(repository.sleeps().first().isEmpty())
        vm.cancelLong()
        assertTrue(vm.state.value.dirty)
        assertTrue(repository.sleeps().first().isEmpty())
        vm.save(); vm.ready(); vm.confirmLong(); vm.ready()
        assertTrue(vm.state.value.done)
        val record = repository.sleeps().first().single()
        val edit = editor(record.id); edit.ready(); edit.delete(); edit.ready()
        assertTrue(repository.sleeps().first().isEmpty())
    }

    @Test fun savedStateRecreatesUnsavedFieldsWithoutWritingUntilSave() = runBlocking {
        val saved = SavedStateHandle()
        val vm = editor(saved = saved); vm.ready()
        val form = SleepForm("2026-10-22T23:50", "Asia/Shanghai", end = "2026-10-23T07:10", endZone = "Asia/Shanghai")
        vm.change(form)
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val recreated = editor(saved = restoredHandle); recreated.ready()
        assertEquals(form, recreated.state.value.form)
        assertTrue(recreated.state.value.dirty)
        assertTrue(repository.sleeps().first().isEmpty())
        recreated.save(); recreated.ready()
        assertEquals("2026-10-23", repository.sleeps().first().single().archiveLocalDate)
    }

    @Test fun formPreservesMillisecondsAndDstChoiceAndRejectsGapOrUnchosenOverlap() {
        val source = com.personal.lifelog.data.local.entity.SleepRecord(id(), utc("2026-11-01T05:30:00.123Z"), "America/New_York", utc("2026-11-01T06:30:00.456Z"), "America/New_York", "2026-11-01", null, 1, 1)
        val form = SleepForm.from(source, now.toEpochMilli(), zone.id)
        val draft = form.draft(source.id)
        assertEquals(source.startedAtUtcMs, draft.startedAtUtcMs)
        assertEquals(source.endedAtUtcMs, draft.endedAtUtcMs)
        assertEquals(ErrorCode.AMBIGUOUS_LOCAL_TIME, assertThrows(DataViolation::class.java) { form.copy(startOffset = "").draft(source.id) }.error.code)
        assertEquals(ErrorCode.INVALID_LOCAL_TIME, assertThrows(DataViolation::class.java) { form.copy(start = "2026-03-08T02:30", startOffset = "").draft(source.id) }.error.code)
    }
}
