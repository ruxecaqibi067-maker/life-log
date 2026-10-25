package com.personal.lifelog.feature.work

import android.app.Application
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.home.*
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
class WorkFlowTest {
    private lateinit var db: AppDatabase
    private lateinit var work: WorkRepository
    private lateinit var settings: SettingsRepository
    private val store = ViewModelStore()
    private var now = Instant.parse("2026-10-22T15:50:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant() = now
    }
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value() = (this as DataResult.Success).value
    private fun <T : ViewModel> vm(type: Class<T>, factory: () -> T): T = ViewModelProvider(store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <V : ViewModel> create(c: Class<V>): V = factory() as V
        })[id(), type]
    private fun commands() = vm(WorkViewModel::class.java) { WorkViewModel(work, clock) { zone } }
    private fun editor(row: String? = null, saved: SavedStateHandle = SavedStateHandle(), deleted: (com.personal.lifelog.data.local.entity.WorkRecord) -> Unit = {}) =
        vm(WorkEditorViewModel::class.java) { WorkEditorViewModel(work, clock, { zone }, row, saved, deleted) }
    private suspend fun WorkViewModel.ready() { withTimeout(5000) { status.first { it.loaded }; busy.first { !it } } }
    private suspend fun WorkEditorViewModel.ready() { withTimeout(5000) { state.first { !it.loading && !it.busy } } }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator()
        work = WorkRepository(db, writes, clock) { zone }
        settings = SettingsRepository(db, writes, clock)
    }
    @After fun close() { store.clear(); db.close(); Dispatchers.resetMain() }

    @Test fun startIsGuardedIdempotentSurvivesNewViewModelAndDoesNotTouchSleep() = runBlocking<Unit> {
        val model = commands()
        val key = id()
        model.start(key) // Initial loading cannot be interpreted as no active work.
        model.ready()
        model.start(key); model.start(key); model.ready()
        val active = work.activeWork().first()!!
        assertEquals(key, active.id)
        assertEquals(1, work.works().first().size)
        assertTrue(db.records().allSleeps().isEmpty())
        val reopened = commands(); reopened.ready()
        assertEquals(active, reopened.status.value.active)
    }

    @Test fun failedFinishKeepsActiveAndRetryUndoUsesOnlyWorkState() = runBlocking<Unit> {
        val model = commands(); model.ready(); model.start(id()); model.ready()
        val active = work.activeWork().first()!!
        now = now.plusSeconds(60)
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_work BEFORE UPDATE ON WorkRecord BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        model.finish(active); model.ready()
        assertEquals(active, work.work(active.id))
        assertNotNull(model.error.value)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_work")
        model.finish(active); model.ready()
        assertEquals(now.toEpochMilli(), work.work(active.id)!!.endedAtUtcMs)
        model.undoLast(); model.ready()
        assertNull(work.work(active.id)!!.endedAtUtcMs)
        assertTrue(db.records().allSleeps().isEmpty())
    }

    @Test fun longIntervalCancelDoesNotWriteAndConfirmKeepsCapturedInstant() = runBlocking<Unit> {
        val model = commands(); model.ready(); model.start(id()); model.ready()
        val active = work.activeWork().first()!!
        now = now.plusSeconds(48 * 3600)
        model.finish(active); model.ready()
        assertEquals(now.toEpochMilli(), model.longFinish.value!!.instant)
        model.dismissLong(); assertNull(work.work(active.id)!!.endedAtUtcMs)
        model.finish(active); model.ready()
        val captured = model.longFinish.value!!.instant
        now = now.plusSeconds(7200)
        model.confirmLong(); model.ready()
        assertEquals(captured, work.work(active.id)!!.endedAtUtcMs)
    }

    @Test fun editorRecreationPreservesDraftAndExactMillisecondsAndRejectsStaleRow() = runBlocking<Unit> {
        val start = now.toEpochMilli() + 123
        val row = work.saveWork(WorkDraft(id(), start, "America/New_York", start + 3_600_789, "America/Los_Angeles")).value()
        val saved = SavedStateHandle()
        val first = editor(row.id, saved); first.ready()
        val original = first.state.value.form!!
        assertEquals(start, original.draft(row.id).startedAtUtcMs)
        assertEquals("America/New_York", original.draft(row.id).startZoneId)
        assertEquals("America/Los_Angeles", original.draft(row.id).endZoneId)
        val picked = original.startAt(zone)
        assertEquals(original, original.pickStart(picked.toLocalDateTime(), zone, picked.offset))
        first.change(original.copy(hasEnd = false))
        val recreated = editor(row.id, saved); recreated.ready()
        assertFalse(recreated.state.value.form!!.hasEnd)
        assertTrue(recreated.state.value.dirty)
        work.editWork(WorkDraft(row.id, start, zone.id, start + 7_200_000, zone.id), row).value()
        val again = editor(row.id, saved); again.ready(); again.save(); again.ready()
        assertEquals(ErrorCode.NOT_FOUND, again.state.value.error)
        assertNotNull(work.work(row.id)!!.endedAtUtcMs)
    }

    @Test fun newDraftAndPickerCancellationSurviveRecreationWithoutCreatingRows() = runBlocking<Unit> {
        val saved = SavedStateHandle()
        val first = editor(saved = saved); first.ready()
        val original = first.state.value.form!!
        now = now.plusSeconds(300)
        val second = editor(saved = saved); second.ready()
        assertEquals(original, second.state.value.form)
        assertFalse(second.state.value.dirty)
        assertTrue(work.works().first().isEmpty())
        second.save(); second.ready() // Equal endpoints are rejected; input remains.
        assertEquals(ErrorCode.INVALID_INTERVAL, second.state.value.error)
        assertEquals(original, second.state.value.form)
        assertFalse(second.state.value.done)
    }

    @Test fun workOnlyDateMovesOnEditAndDeleteCanBeSafelyUndone() = runBlocking<Unit> {
        val row = work.saveWork(WorkForm("2026-10-22T23:50:00.123", zone.id,
            end = "2026-10-23T01:10:00.789", endZone = zone.id).draft(id())).value()
        val timeline = TimelineRepository(RecordRepository(db, WriteCoordinator(), clock) { zone },
            WorkoutRepository(db, WriteCoordinator(), clock), work)
        assertEquals(listOf("2026-10-23"), timeline.datesWithRecords().first())
        val entry = timeline.timelineOn("2026-10-23").first().single() as TimelineEntry.Work
        assertEquals(row.endedAtUtcMs, entry.sortUtcMs)
        assertEquals("work:${row.id}", entry.stableKey)
        val edit = editor(row.id); edit.ready()
        edit.change(edit.state.value.form!!.copy(end = "2026-10-24T01:10:00.789")); edit.save(); edit.ready()
        assertNotNull(edit.state.value.pendingLong)
        edit.confirmLong(); edit.ready()
        assertTrue(timeline.timelineOn("2026-10-23").first().isEmpty())
        assertEquals(listOf("2026-10-24"), timeline.datesWithRecords().first())
        val model = commands(); model.ready()
        val deleting = editor(row.id, deleted = model::deleted); deleting.ready(); deleting.delete(); deleting.ready()
        assertTrue(deleting.state.value.done)
        assertTrue(timeline.datesWithRecords().first().isEmpty())
        model.undoLast(); model.ready()
        assertEquals(listOf("2026-10-24"), timeline.datesWithRecords().first())
    }

    @Test fun goalsStayIndependentAndFailedSavePreservesInputForRetryAndClear() = runBlocking<Unit> {
        settings.setTargetSleepMinutes(420).value()
        val today = MutableStateFlow(LocalDate.of(2026, 10, 22))
        val home = vm(WorkHomeViewModel::class.java) { WorkHomeViewModel(today, WorkSummaryRepository(db)::observe, settings::setTargetWorkMinutes) }
        withTimeout(5000) { home.work.first { !it.loading } }
        home.editGoal(); home.goalHours("5"); home.goalMinutes("0"); home.cancelGoal()
        assertNull(settings.targetWorkMinutes().first())
        home.editGoal(); home.goalHours("0"); home.goalMinutes("0"); home.saveGoal()
        assertTrue(home.goal.value.invalid)
        home.goalHours("8"); home.goalMinutes("0")
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_goal BEFORE UPDATE ON AppSettings BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        home.saveGoal(); withTimeout(5000) { home.goal.first { !it.busy } }
        assertTrue(home.goal.value.saveFailed); assertTrue(home.goal.value.open)
        assertEquals("8", home.goal.value.hours)
        assertNull(settings.targetWorkMinutes().first())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_goal")
        home.saveGoal(); withTimeout(5000) { home.goal.first { !it.open } }
        assertEquals(480, settings.targetWorkMinutes().first())
        assertEquals(420, settings.targetSleepMinutes().first())
        withTimeout(5000) { home.work.first { it.content?.targetMinutes == 480 } }
        home.editGoal(); home.clearGoal(); withTimeout(5000) { home.goal.first { !it.open } }
        assertNull(settings.targetWorkMinutes().first())
        assertEquals(420, settings.targetSleepMinutes().first())
        assertEquals(0.625f, durationProgress(18_000_000, 480)!!, 0.000001f)
        assertEquals(1f, durationProgress(36_000_000, 480)!!)
        assertNull(durationProgress(null, 480))
        assertNull(durationProgress(18_000_000, null))
    }
}
