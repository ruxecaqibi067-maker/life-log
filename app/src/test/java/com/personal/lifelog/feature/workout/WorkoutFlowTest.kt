package com.personal.lifelog.feature.workout

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
class WorkoutFlowTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:04:05.123Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val models = mutableListOf<ViewModel>()
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun model(saved: SavedStateHandle = SavedStateHandle(), recordId: String? = null) =
        WorkoutEditorViewModel(repository, saved, clock, { zone }, recordId).also { models += it }
    private suspend fun ready(vm: WorkoutEditorViewModel): WorkoutForm = vm.state.first { !it.loading }.form!!
    private suspend fun definition(index: Int) = db.workouts().exercise(BuiltinExercises.entries[index].id)!!
    private suspend fun save(vm: WorkoutEditorViewModel) {
        vm.save(); vm.state.first { !it.busy }
        assertTrue(vm.state.value.toString(), vm.state.value.done)
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = WorkoutRepository(db, WriteCoordinator(), clock)
    }
    @After fun close() {
        models.forEach { it.viewModelScope.cancel() }
        db.close(); Dispatchers.resetMain()
    }

    @Test fun runOrBenchAloneSavesWithoutInventingQuantityAndDoubleSaveIsGuarded() = runTest {
        for (index in listOf(0, 1)) {
            val vm = model(); ready(vm)
            vm.add(definition(index))
            vm.save(); vm.save()
            vm.state.first { it.done }
            vm.save()
            val record = repository.workout(vm.state.value.form!!.id)!!
            val item = record.items.single()
            assertNull(item.item.durationSeconds); assertNull(item.item.distanceMeters)
            assertTrue(item.sets.isEmpty()); assertNull(record.session.note)
            assertEquals(clock.millis(), record.session.occurredAtUtcMs)
        }
        assertEquals(2, db.workouts().allSessions().size)
        assertEquals(2, db.workouts().allItems().size)
    }

    @Test fun decimalsConvertExactlyAndEitherQuantityCanBeOmitted() = runTest {
        val vm = model(); ready(vm); vm.add(definition(0))
        val row = vm.state.value.form!!.items.single()
        vm.changeItem(row.copy(minutes = "30.5", kilometers = "5.025")); save(vm)
        val stored = repository.workout(vm.state.value.form!!.id)!!
        assertEquals(1830L, stored.items.single().item.durationSeconds)
        assertEquals(5025L, stored.items.single().item.distanceMeters)
        val edit = model(recordId = stored.session.id); val form = ready(edit)
        edit.changeItem(form.items.single().copy(minutes = "", seconds = "", kilometers = "1")); save(edit)
        assertNull(repository.workout(form.id)!!.items.single().item.durationSeconds)
        val next = model(recordId = form.id); val nextForm = ready(next)
        next.changeItem(nextForm.items.single().copy(minutes = "0", seconds = "45", kilometers = "")); save(next)
        assertEquals(45L, repository.workout(form.id)!!.items.single().item.durationSeconds)
        assertNull(repository.workout(form.id)!!.items.single().item.distanceMeters)
    }

    @Test fun invalidNumbersBlankSessionAndLongNotesKeepInputAndDoNotWrite() = runTest {
        val vm = model(); ready(vm); vm.save()
        assertEquals(ErrorCode.EMPTY_WORKOUT, vm.state.value.error)
        vm.add(definition(0))
        val row = vm.state.value.form!!.items.single()
        for (bad in listOf("0", "-1", "abc", "1e3", "NaN", "0.0001", "9".repeat(65), "9223372036854775807")) {
            vm.changeItem(row.copy(kilometers = bad)); vm.save()
            assertEquals(bad, ErrorCode.INVALID_NUMBER, vm.state.value.error)
            assertEquals(row.id, vm.state.value.errorItemId)
            assertEquals(bad, vm.state.value.form!!.items.single().kilometers)
        }
        for (bad in listOf("-1", "0", "0.001", "abc", "9".repeat(65))) {
            vm.changeItem(row.copy(minutes = bad)); vm.save()
            assertEquals(bad, ErrorCode.INVALID_NUMBER, vm.state.value.error)
        }
        vm.changeItem(row.copy(minutes = "1", seconds = "60")); vm.save()
        assertEquals(ErrorCode.INVALID_NUMBER, vm.state.value.error)
        vm.changeItem(row)
        vm.change(vm.state.value.form!!.copy(note = "字".repeat(1001))); vm.save()
        assertEquals(ErrorCode.TEXT_TOO_LONG, vm.state.value.error)
        assertTrue(db.workouts().allSessions().isEmpty())
    }

    @Test fun multiItemBackdateReorderRemoveAndHistoryFlowUseRoomDates() = runTest {
        val vm = model(); ready(vm)
        vm.add(definition(0)); vm.add(definition(1)); vm.add(definition(0))
        val form = vm.state.value.form!!
        assertEquals(3, form.items.map { it.id }.distinct().size)
        vm.change(form.atLocal(LocalDateTime.parse("2026-10-22T22:30:00")).copy(note = "虚构多项目"))
        vm.changeItem(form.items[0].copy(minutes = "20", kilometers = "3.2"))
        vm.move(form.items[1].id, -1)
        save(vm)
        val day = WorkoutDayViewModel(repository, SavedStateHandle(), clock) { zone }.also { models += it }
        assertTrue(day.state.first { !it.loading }.rows.isEmpty())
        day.selectDate(LocalDate.parse("2026-10-22"))
        val record = day.state.first { !it.loading }.rows.single()
        assertEquals(listOf("卧推", "跑步", "跑步"), record.items.map { it.item.nameSnapshot })
        assertEquals(listOf(0, 1, 2), record.items.map { it.item.sortOrder })
        assertEquals("虚构多项目", record.session.note)
        val edit = model(recordId = form.id); val old = ready(edit)
        edit.remove(old.items.last().id)
        edit.change(edit.state.value.form!!.atLocal(LocalDateTime.parse("2026-10-23T08:00:00")))
        save(edit)
        day.state.first { !it.loading && it.rows.isEmpty() }
        assertEquals(2, db.workouts().allItems().size)
        assertTrue(db.workouts().allSets().isEmpty())
        assertEquals(1, repository.workoutsOn("2026-10-23").first().size)
    }

    @Test fun snapshotsHiddenHistoryAndExistingSetsSurviveBasicEditAndParcelRecreation() = runTest {
        val run = definition(0); val bench = definition(1)
        val record = repository.saveWorkout(WorkoutDraft(id(), clock.millis(), zone.id, listOf(
            WorkoutItemDraft(id(), run.id, durationSeconds = 61, distanceMeters = 1),
            WorkoutItemDraft(id(), bench.id, sets = listOf(WorkoutSetDraft(id(), 0, 8), WorkoutSetDraft(id(), repetitions = 5))),
        ))).value()
        repository.saveExercise(ExerciseDraft(run.id, "更名跑步", run.type)).value()
        repository.setExerciseHidden(run.id, true).value()
        val handle = SavedStateHandle(); val vm = model(handle, record.session.id)
        val form = ready(vm)
        assertEquals("跑步", form.items.first().name)
        vm.change(form.copy(note = "保留输入")); vm.move(form.items.last().id, -1)
        val parcel = Parcel.obtain()
        val savedBundle = Bundle().apply {
            handle.keys().forEach { key -> when (val value = handle.get<Any>(key)) {
                is Bundle -> putBundle(key, value)
                is Boolean -> putBoolean(key, value)
                else -> error("Unexpected test saved-state type")
            } }
        }
        savedBundle.writeToParcel(parcel, 0); parcel.setDataPosition(0)
        val restored = Bundle.CREATOR.createFromParcel(parcel); parcel.recycle()
        vm.viewModelScope.cancel()
        @Suppress("DEPRECATION")
        val restoredValues = restored.keySet().associateWith { restored.get(it) }
        val recreated = model(SavedStateHandle(restoredValues), record.session.id)
        val recreatedForm = ready(recreated)
        assertTrue(recreated.state.value.dirty)
        assertEquals("保留输入", recreatedForm.note)
        assertEquals(form.items.last().sets, recreatedForm.items.first().sets)
        save(recreated)
        val stored = repository.workout(record.session.id)!!
        assertEquals(record.items.last().sets, stored.items.first().sets)
        assertEquals(61L, stored.items.last().item.durationSeconds)
        assertEquals("跑步", stored.items.last().item.nameSnapshot)
        assertEquals(clock.millis(), stored.session.occurredAtUtcMs)
        assertEquals(record.items.map { it.item.id }.toSet(), stored.items.map { it.item.id }.toSet())
    }

    @Test fun newDraftIdentityInputAndOriginalSurviveSavedStateRecreation() = runTest {
        val handle = SavedStateHandle(); val vm = model(handle); val initial = ready(vm)
        vm.add(definition(1)); vm.change(vm.state.value.form!!.copy(note = "草稿"))
        vm.openPicker()
        vm.viewModelScope.cancel()
        val restored = model(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        val form = ready(restored)
        assertEquals(initial.id, form.id); assertEquals("草稿", form.note)
        assertTrue(restored.state.value.dirty)
        assertTrue(restored.picking.value)
        restored.closePicker()
        save(restored)
        assertEquals(form.id, db.workouts().allSessions().single().id)
    }

    @Test fun failedNewTrainingRollsBackSessionItemsAndRecentThenRetriesSameIdentity() = runTest {
        val vm = model(); ready(vm); vm.add(definition(0)); vm.add(definition(1))
        val original = vm.state.value.form!!
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_item BEFORE INSERT ON WorkoutItem WHEN NEW.sortOrder = 1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        vm.save(); vm.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, vm.state.value.error)
        assertEquals(original, vm.state.value.form)
        assertTrue(db.workouts().allSessions().isEmpty()); assertTrue(db.workouts().allItems().isEmpty())
        assertTrue(db.workouts().allExercises().all { it.lastUsedAtUtcMs == null })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_item")
        save(vm)
        assertEquals(original.id, db.workouts().allSessions().single().id)
        assertEquals(original.items.map { it.id }, db.workouts().allItems().map { it.id })
    }

    @Test fun failedEditRollsBackDeletedChildrenAndChangedSessionThenDeleteIsAtomic() = runTest {
        val record = repository.saveWorkout(WorkoutDraft(id(), clock.millis(), zone.id, listOf(
            WorkoutItemDraft(id(), definition(1).id, sets = listOf(WorkoutSetDraft(id(), 1000, 8))),
            WorkoutItemDraft(id(), definition(0).id, durationSeconds = 75),
        ), "原备注")).value()
        val vm = model(recordId = record.session.id); val form = ready(vm)
        vm.change(form.copy(note = "失败不落库"))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_item BEFORE INSERT ON WorkoutItem WHEN NEW.sortOrder = 1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        vm.save(); vm.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, vm.state.value.error)
        assertEquals(record, repository.workout(record.session.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_item")
        val detail = WorkoutDetailViewModel(repository, record.session.id).also { models += it }
        detail.state.first { !it.loading }
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_delete BEFORE DELETE ON WorkoutItem BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        detail.delete(); detail.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, detail.state.value.error)
        assertEquals(record, repository.workout(record.session.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
        detail.delete(); detail.state.first { it.deleted }
        assertTrue(db.workouts().allSessions().isEmpty()); assertTrue(db.workouts().allItems().isEmpty())
        assertTrue(db.workouts().allSets().isEmpty()); assertEquals(8, db.workouts().allExercises().size)
    }

    @Test fun dstGapRejectsOverlapRequiresChoiceAndUnchangedHistoricTimeKeepsOffset() = runTest {
        val zone = ZoneId.of("America/New_York")
        val vm = WorkoutEditorViewModel(repository, SavedStateHandle(), clock, { zone }).also { models += it }
        ready(vm); vm.add(definition(1))
        vm.change(vm.state.value.form!!.atLocal(LocalDateTime.parse("2026-03-08T02:30:00")))
        vm.save(); assertEquals(ErrorCode.INVALID_LOCAL_TIME, vm.state.value.error)
        vm.change(vm.state.value.form!!.atLocal(LocalDateTime.parse("2026-11-01T01:30:00")))
        vm.save(); assertEquals(ErrorCode.AMBIGUOUS_LOCAL_TIME, vm.state.value.error)
        vm.change(vm.state.value.form!!.copy(offset = "-05:00")); save(vm)
        val record = repository.workout(vm.state.value.form!!.id)!!
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), record.session.occurredAtUtcMs)
        val edit = model(recordId = record.session.id); val form = ready(edit)
        assertEquals(zone.id, form.zoneId); assertEquals("-05:00", form.offset)
        edit.change(form.copy(note = "保留原偏移")); save(edit)
        assertEquals(record.session.occurredAtUtcMs, repository.workout(form.id)!!.session.occurredAtUtcMs)
    }

    @Test fun removingItemCascadesItsSetsAndDeletingSessionLeavesOtherTrainingUntouched() = runTest {
        val run = definition(0); val bench = definition(1)
        val first = repository.saveWorkout(WorkoutDraft(id(), clock.millis(), zone.id, listOf(
            WorkoutItemDraft(id(), bench.id, sets = listOf(WorkoutSetDraft(id(), repetitions = 5))),
            WorkoutItemDraft(id(), run.id),
        ))).value()
        val other = repository.saveWorkout(WorkoutDraft(id(), clock.millis(), zone.id, listOf(
            WorkoutItemDraft(id(), bench.id, sets = listOf(WorkoutSetDraft(id(), repetitions = 8))),
        ))).value()
        val vm = model(recordId = first.session.id); ready(vm)
        vm.remove(first.items.first().item.id); save(vm)
        assertEquals(listOf(other.items.single().sets.single().id), db.workouts().allSets().map { it.id })
        assertEquals(other, repository.workout(other.session.id))
        repository.deleteWorkout(first.session.id).value()
        assertEquals(other, repository.workout(other.session.id))
        assertEquals(listOf(other.items.single().item), db.workouts().allItems())
        assertEquals(8, db.workouts().allExercises().size)
    }
}
