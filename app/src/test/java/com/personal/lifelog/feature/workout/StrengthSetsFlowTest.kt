package com.personal.lifelog.feature.workout

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
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
class StrengthSetsFlowTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:04:05.123Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val writes = WriteCoordinator()
    private val models = mutableListOf<WorkoutEditorViewModel>()
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun model(saved: SavedStateHandle = SavedStateHandle(), recordId: String? = null) =
        WorkoutEditorViewModel(repository, saved, clock, { java.time.ZoneId.of("UTC") }, recordId).also { models += it }
    private suspend fun ready(vm: WorkoutEditorViewModel) = vm.state.first { !it.loading }.form!!
    private suspend fun strength(vm: WorkoutEditorViewModel): WorkoutItemForm {
        ready(vm); vm.add(db.workouts().exercise(BuiltinExercises.entries[1].id)!!)
        return vm.state.value.form!!.items.last()
    }
    private fun row(vm: WorkoutEditorViewModel) = vm.state.value.form!!.items.first()
    private suspend fun save(vm: WorkoutEditorViewModel) {
        vm.save(); vm.state.first { !it.busy }; assertTrue(vm.state.value.toString(), vm.state.value.done)
    }
    private fun input(vm: WorkoutEditorViewModel, itemId: String, setId: String, kg: String, reps: String) {
        vm.changeSetWeight(itemId, setId, kg); vm.changeSetRepetitions(itemId, setId, reps)
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = WorkoutRepository(db, writes, clock)
    }
    @After fun close() { models.forEach { it.viewModelScope.cancel() }; db.close(); Dispatchers.resetMain() }

    @Test fun threeIndependentSetsRoundTripExactWeightPartialNumbersAndOrder() = runTest {
        val vm = model(); val item = strength(vm)
        assertTrue(item.sets.isEmpty())
        vm.addSets(item.id, 3)
        val sets = row(vm).sets
        assertEquals(3, sets.map { it.id }.distinct().size)
        input(vm, item.id, sets[0].id, "40.125", "10")
        input(vm, item.id, sets[1].id, "0", "12")
        input(vm, item.id, sets[2].id, "", "8")
        save(vm)
        val stored = repository.workout(vm.state.value.form!!.id)!!.items.single().sets
        assertEquals(listOf(40125L, 0L, null), stored.map { it.weightGrams })
        assertEquals(listOf(10, 12, 8), stored.map { it.repetitions })
        assertEquals(listOf(0, 1, 2), stored.map { it.sortOrder })
        assertEquals(sets.map { it.id }, stored.map { it.id })
        val edit = model(recordId = vm.state.value.form!!.id)
        val reopened = ready(edit).items.single().sets
        assertEquals(listOf("40.125", "0", ""), reopened.map { it.kilograms })
        assertEquals(listOf("10", "12", "8"), reopened.map { it.repetitions })
        assertFalse(edit.state.value.dirty)
    }

    @Test fun blankPlaceholdersAreOmittedButZeroAndWeightOnlyAreRealData() = runTest {
        val vm = model(); val item = strength(vm)
        vm.addSets(item.id, 3)
        val sets = row(vm).sets
        input(vm, item.id, sets[0].id, "  ", "  ")
        input(vm, item.id, sets[1].id, "0.000", "")
        input(vm, item.id, sets[2].id, "0.001", "")
        save(vm)
        val stored = repository.workout(vm.state.value.form!!.id)!!.items.single().sets
        assertEquals(listOf(sets[1].id, sets[2].id), stored.map { it.id })
        assertEquals(listOf(0L, 1L), stored.map { it.weightGrams })
        assertTrue(stored.all { it.repetitions == null })
        assertEquals(listOf(0, 1), stored.map { it.sortOrder })
        val blank = model(); val other = strength(blank); blank.addSets(other.id, 3); save(blank)
        assertTrue(repository.workout(blank.state.value.form!!.id)!!.items.single().sets.isEmpty())
        val fresh = model(); val freshItem = strength(fresh)
        assertTrue(freshItem.sets.isEmpty()) // No quantities copied from the saved history.
    }

    @Test fun copyingIsExplicitNewIdentityEditableAndRemovingMiddleRenumbersOnlySurvivors() = runTest {
        val vm = model(); val item = strength(vm); vm.addSets(item.id)
        val first = row(vm).sets.single()
        vm.copyLastSet(item.id); assertEquals(1, row(vm).sets.size) // Blank copy is a no-op.
        input(vm, item.id, first.id, "40", "10")
        vm.copySet(item.id, first.id)
        val second = row(vm).sets.last()
        assertNotEquals(first.id, second.id)
        input(vm, item.id, second.id, "45.5", "6")
        assertEquals("40", row(vm).sets.first().kilograms)
        vm.copyLastSet(item.id)
        val third = row(vm).sets.last()
        assertNotEquals(second.id, third.id)
        input(vm, item.id, third.id, "", "8")
        vm.removeSet(item.id, second.id)
        vm.moveSet(item.id, third.id, -1)
        save(vm)
        val stored = repository.workout(vm.state.value.form!!.id)!!.items.single().sets
        assertEquals(listOf(third.id, first.id), stored.map { it.id })
        assertEquals(listOf(null, 40000L), stored.map { it.weightGrams })
        assertEquals(listOf(8, 10), stored.map { it.repetitions })
        assertEquals(listOf(0, 1), stored.map { it.sortOrder })
        assertNull(db.workouts().set(second.id))
    }

    @Test fun invalidTextNeverBecomesNullOrZeroAndReportsExactRepeatedItemAndSet() = runTest {
        val vm = model(); strength(vm); val item = strength(vm); vm.addSets(item.id)
        val set = vm.state.value.form!!.items.last().sets.single()
        for (bad in listOf("-1", "abc", "NaN", "1e3", "0.0001", "9223372036854775.808", "9".repeat(65))) {
            input(vm, item.id, set.id, bad, "8"); vm.save()
            assertEquals(bad, ErrorCode.INVALID_NUMBER, vm.state.value.error)
            assertEquals(item.id, vm.state.value.errorItemId); assertEquals(set.id, vm.state.value.errorSetId)
            assertEquals(bad, vm.state.value.form!!.items.last().sets.single().kilograms)
        }
        for (bad in listOf("0", "-1", "1.5", "abc", "1e2", "2147483648", "9".repeat(65))) {
            input(vm, item.id, set.id, "0", bad); vm.save()
            assertEquals(bad, ErrorCode.INVALID_NUMBER, vm.state.value.error)
            assertEquals(set.id, vm.state.value.errorSetId)
        }
        assertTrue(db.workouts().allSessions().isEmpty()); assertTrue(db.workouts().allSets().isEmpty())
        input(vm, item.id, set.id, "9223372036854775.807", "2147483647"); save(vm)
        assertEquals(Long.MAX_VALUE, db.workouts().allSets().single().weightGrams)
        assertEquals(Int.MAX_VALUE, db.workouts().allSets().single().repetitions)
    }

    @Test fun rawUnfinishedInputAndStableIdsSurviveParcelAndCancellationLeavesOriginalUntouched() = runTest {
        val record = repository.saveWorkout(WorkoutDraft(id(), clock.millis(), "UTC", listOf(
            WorkoutItemDraft(id(), BuiltinExercises.entries[1].id, sets = listOf(WorkoutSetDraft(id(), 2000, 5))),
        ))).value()
        val handle = SavedStateHandle(); val vm = model(handle, record.session.id); val form = ready(vm)
        val item = form.items.single(); vm.copyLastSet(item.id)
        val copied = row(vm).sets.last()
        input(vm, item.id, copied.id, "4.", "invalid")
        vm.addSets(item.id)
        val draft = vm.state.value.form!!
        val parcel = Parcel.obtain()
        val bundle = Bundle().apply {
            putBundle("form", handle["form"]); putBundle("original", handle["original"])
            putBoolean("picking", handle["picking"] ?: false)
        }
        parcel.writeBundle(bundle); parcel.setDataPosition(0)
        val restored = parcel.readBundle(javaClass.classLoader)!!; parcel.recycle()
        vm.viewModelScope.cancel()
        val next = model(SavedStateHandle(mapOf("form" to restored.getBundle("form"),
            "original" to restored.getBundle("original"), "picking" to restored.getBoolean("picking"))), record.session.id)
        assertEquals(draft, ready(next)); assertTrue(next.state.value.dirty)
        next.save(); assertEquals(copied.id, next.state.value.errorSetId)
        next.viewModelScope.cancel() // Discarding this UI state performs no repository mutation.
        val cancelled = model(recordId = record.session.id)
        assertEquals(WorkoutForm.from(record), ready(cancelled))
        assertEquals(record, repository.workout(record.session.id))
    }

    @Test fun legacyT08LightweightSetStateStillLoadsWithNullAndZeroDistinct() {
        val zero = Bundle().apply { putString("id", id()); putLong("weight", 0); putInt("reps", 8) }
        val partial = Bundle().apply { putString("id", id()); putInt("reps", 6) }
        assertEquals("0", WorkoutSetForm.restore(zero).kilograms)
        assertEquals("", WorkoutSetForm.restore(partial).kilograms)
        assertEquals(WorkoutSetDraft(partial.getString("id")!!, repetitions = 6), WorkoutSetForm.restore(partial).draft())
    }

    @Test fun lateSetInsertFailureRollsBackNewAndEditedGraphAndRetriesSameIds() = runTest {
        val vm = model(); val item = strength(vm); vm.addSets(item.id, 3)
        val sets = row(vm).sets
        sets.forEachIndexed { index, set -> input(vm, item.id, set.id, "${index + 1}.125", if (index == 2) "99" else "8") }
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_set BEFORE INSERT ON WorkoutSet WHEN NEW.repetitions = 99 BEGIN SELECT RAISE(ABORT, 'synthetic T09 failure'); END")
        val draft = vm.state.value.form!!
        vm.save(); vm.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, vm.state.value.error); assertEquals(draft, vm.state.value.form)
        assertTrue(db.workouts().allSessions().isEmpty()); assertTrue(db.workouts().allItems().isEmpty()); assertTrue(db.workouts().allSets().isEmpty())
        assertTrue(db.workouts().allExercises().all { it.lastUsedAtUtcMs == null })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_set"); save(vm)
        val original = repository.workout(draft.id)!!
        assertEquals(sets.map { it.id }, original.items.single().sets.map { it.id })
        val edit = model(recordId = draft.id); ready(edit)
        edit.removeSet(item.id, sets[1].id); edit.copySet(item.id, sets[0].id)
        edit.change(edit.state.value.form!!.copy(note = "不应留下"))
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_set BEFORE INSERT ON WorkoutSet WHEN NEW.repetitions = 99 BEGIN SELECT RAISE(ABORT, 'synthetic T09 failure'); END")
        edit.save(); edit.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, edit.state.value.error); assertEquals(original, repository.workout(draft.id))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_set"); save(edit)
        assertEquals(1, db.workouts().allSessions().size); assertNull(db.workouts().set(sets[1].id))
        println("T09 rollback: new session/items/sets/recent all absent; failed edit restored original complete graph; same IDs retry succeeded")
    }

    @Test fun groupLimitsRejectWholeBatchAndRapidActionsKeepUniqueIdsAndSeparateItems() = runTest {
        val vm = model(); val item = strength(vm)
        repeat(99) { vm.addSets(item.id) }
        val before = row(vm).sets
        vm.addSets(item.id, 3)
        assertEquals(ErrorCode.TOO_MANY_SETS, vm.state.value.error); assertEquals(before, row(vm).sets)
        vm.addSets(item.id); assertEquals(100, row(vm).sets.size)
        input(vm, item.id, before[0].id, "0", "8")
        vm.copySet(item.id, before[0].id); assertEquals(100, row(vm).sets.size)
        vm.addSets(item.id); assertEquals(100, row(vm).sets.size)
        val second = strength(vm); vm.addSets(second.id, 3)
        assertEquals(103, vm.state.value.form!!.items.flatMap { it.sets }.map { it.id }.distinct().size)
        save(vm)
        val record = repository.workout(vm.state.value.form!!.id)!!
        assertEquals(1, record.items[0].sets.size); assertTrue(record.items[1].sets.isEmpty())
    }

    @Test fun writesInFlightAndCompletedWritesRejectGroupMutationsAndDoubleSave() = runTest {
        val vm = model(); val item = strength(vm); vm.addSets(item.id)
        val set = row(vm).sets.single(); input(vm, item.id, set.id, "10.5", "8")
        val original = vm.state.value.form!!
        val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lockJob = launch(Dispatchers.Default) { writes.withExclusiveAccess { locked.complete(Unit); release.await() } }
        locked.await()
        try {
            vm.save(); vm.save(); assertTrue(vm.state.value.busy)
            vm.addSets(item.id, 3); vm.copySet(item.id, set.id); vm.removeSet(item.id, set.id)
            input(vm, item.id, set.id, "99", "99")
            assertEquals(original, vm.state.value.form)
        } finally { release.complete(Unit); lockJob.join() }
        vm.state.first { it.done }
        vm.addSets(item.id); vm.save(); assertEquals(original, vm.state.value.form)
        assertEquals(1, db.workouts().allSessions().size); assertEquals(1, db.workouts().allSets().size)
    }
}
