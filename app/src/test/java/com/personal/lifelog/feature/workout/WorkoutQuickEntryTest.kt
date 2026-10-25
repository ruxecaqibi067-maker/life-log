package com.personal.lifelog.feature.workout

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.time.*
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
class WorkoutQuickEntryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-26T03:04:05.123Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val models = mutableListOf<WorkoutEditorViewModel>()
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun model(saved: SavedStateHandle = SavedStateHandle(), id: String? = null) =
        WorkoutEditorViewModel(repository, saved, clock, { ZoneId.of("Asia/Shanghai") }, id).also { models += it }
    private suspend fun ready(vm: WorkoutEditorViewModel) = vm.state.first { !it.loading }.form!!
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = WorkoutRepository(db, WriteCoordinator(), clock)
    }
    @After fun close() { models.forEach { it.viewModelScope.cancel() }; db.close(); Dispatchers.resetMain() }

    @Test fun quickThreeSetsSaveOnceAsIndependentStableSetsWithOptionalZeroWeight() = runTest {
        val handle = SavedStateHandle()
        val vm = model(handle); ready(vm)
        vm.add(db.workouts().exercise(BuiltinExercises.entries[6].id)!!)
        val item = vm.state.value.form!!.items.single()
        vm.changeBatch(item.id) { it.withCount("3").copy(repetitions = "12", kilograms = "0") }
        val draft = vm.state.value.form!!.draft()
        val restored = WorkoutForm.restore(vm.state.value.form!!.saved())
        assertEquals(draft, restored.draft())
        vm.change(vm.state.value.form!!.copy(effort = WorkoutEffort.HARD))
        vm.save(); vm.state.first { it.done }
        val record = repository.workout(draft.id)!!
        assertEquals(WorkoutEffort.HARD, record.session.effort)
        assertEquals(listOf(0L, 0L, 0L), record.items.single().sets.map { it.weightGrams })
        assertEquals(listOf(12, 12, 12), record.items.single().sets.map { it.repetitions })
        assertEquals(draft.items.single().sets.map { it.id }, record.items.single().sets.map { it.id })
        val edit = model(id = draft.id); val form = ready(edit)
        assertEquals("3", form.items.single().batch!!.count)
        edit.editIndividualSets(item.id)
        edit.changeSetRepetitions(item.id, record.items.single().sets[1].id, "8")
        edit.save(); edit.state.first { it.done }
        val varied = repository.workout(draft.id)!!
        assertEquals(listOf(12, 8, 12), varied.items.single().sets.map { it.repetitions })
        assertNull(WorkoutForm.from(varied).items.single().batch)
        assertEquals(record.items.single().sets.map { it.id }, varied.items.single().sets.map { it.id })
    }

    @Test fun emptyQuickInputIsNoQuantityAndInvalidInputsNeverSilentlyDropOrWrite() = runTest {
        assertTrue(StrengthBatch().sets().isEmpty())
        assertEquals(1, StrengthBatch(repetitions = "8").sets().size)
        for (bad in listOf("0", "-1", "1.5", "101", "999999999999999999999999999")) {
            assertThrows(DataViolation::class.java) { StrengthBatch(repetitions = "8").withCount(bad).sets() }
        }
        assertThrows(DataViolation::class.java) { StrengthBatch().withCount("3").sets() }
        val vm = model(); ready(vm); vm.add(db.workouts().exercise(BuiltinExercises.entries[1].id)!!)
        val item = vm.state.value.form!!.items.single()
        vm.changeBatch(item.id) { it.withCount("3").copy(kilograms = "-2", repetitions = "8") }
        vm.save()
        assertEquals(ErrorCode.INVALID_NUMBER, vm.state.value.error)
        assertEquals(item.id, vm.state.value.errorItemId)
        assertEquals("-2", vm.state.value.form!!.items.single().batch!!.kilograms)
        assertTrue(db.workouts().allSessions().isEmpty())
    }

    @Test fun switchingModesAndShrinkingBatchPreserveRetainedIdsAndVariableHistory() {
        val batch = StrengthBatch(repetitions = "6", kilograms = "12.125").withCount("3")
        assertEquals(batch.sets().take(2).map { it.id }, batch.withCount("2").sets().map { it.id })
        assertEquals(batch.ids, batch.withCount("1").withCount("3").ids)
        val sets = batch.sets()
        assertEquals(sets, StrengthBatch.from(sets)!!.sets())
        assertNull(StrengthBatch.from(sets.mapIndexed { i, set -> if (i == 1) set.copy(kilograms = "13") else set }))
    }

    @Test fun workoutWheelConfirmPreservesExactInstantAndCanChooseBothDstOccurrences() {
        val first = Instant.parse("2026-11-01T05:30:19.123Z").toEpochMilli()
        val form = WorkoutForm.at(id(), first, "America/New_York")
        val minute = form.local.withSecond(0).withNano(0)
        assertEquals(form, form.pickTime(minute, ZoneOffset.of("-04:00")))
        assertEquals(first, form.pickTime(minute, ZoneOffset.of("-04:00")).draft().occurredAtUtcMs)
        assertEquals(Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(), form.pickTime(minute, ZoneOffset.of("-05:00")).draft().occurredAtUtcMs)
        assertEquals("America/New_York", form.pickTime(minute, ZoneOffset.of("-05:00")).zoneId)
    }

    @Test fun customEffortMustValidateAndFailureRetryKeepsBatchIdsAndDescription() = runTest {
        val vm = model(); ready(vm); vm.add(db.workouts().exercise(BuiltinExercises.entries[1].id)!!)
        val item = vm.state.value.form!!.items.single()
        vm.changeBatch(item.id) { it.withCount("2").copy(repetitions = "9") }
        vm.change(vm.state.value.form!!.copy(effort = WorkoutEffort.CUSTOM, effortText = " "))
        vm.save(); assertEquals(ErrorCode.INVALID_EFFORT, vm.state.value.error)
        vm.change(vm.state.value.form!!.copy(effortText = "  虚构：腿部很累  "))
        val expected = vm.state.value.form!!.draft()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_polish BEFORE INSERT ON WorkoutSet BEGIN SELECT RAISE(ABORT, 'test'); END")
        vm.save(); vm.state.first { !it.busy }
        assertEquals(ErrorCode.STORAGE, vm.state.value.error)
        assertEquals(expected, vm.state.value.form!!.draft())
        assertTrue(db.workouts().allSessions().isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_polish")
        vm.save(); vm.state.first { it.done }
        assertEquals("虚构：腿部很累", repository.workout(expected.id)!!.session.effortText)
        assertEquals(expected.items.single().sets.map { it.id }, db.workouts().allSets().map { it.id })
    }
}
