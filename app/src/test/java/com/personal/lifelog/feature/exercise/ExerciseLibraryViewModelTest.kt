package com.personal.lifelog.feature.exercise

import android.app.Application
import android.content.Context
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
class ExerciseLibraryViewModelTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val models = mutableListOf<ExerciseLibraryViewModel>()
    private val writes = WriteCoordinator()
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun model(saved: SavedStateHandle = SavedStateHandle(), selecting: Boolean = false) =
        ExerciseLibraryViewModel(repository, saved, selecting).also { models += it }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = WorkoutRepository(db, writes, clock)
    }

    @After fun close() {
        models.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    @Test fun searchNormalizesWidthCaseWhitespaceAndDisambiguatesTypes() = runTest {
        val strength = repository.saveExercise(ExerciseDraft(id(), " Press ", ExerciseType.STRENGTH)).value()
        repository.saveExercise(ExerciseDraft(id(), "Press", ExerciseType.OTHER)).value()
        repository.saveExercise(ExerciseDraft(id(), "Press up", ExerciseType.STRENGTH)).value()
        val vm = model()
        vm.search(" ＰＲＥＳＳ ")
        val rows = vm.state.first { !it.loading && it.rows.size == 3 }.rows
        assertEquals(setOf(ExerciseType.STRENGTH, ExerciseType.OTHER), rows.map { it.type }.toSet())
        assertEquals(3, rows.map { it.id }.distinct().size)
        vm.toggleHidden(strength)
        vm.busy.first { !it }
        assertEquals(2, vm.state.first { it.rows.size == 2 }.rows.size)
        vm.showHidden(true)
        assertTrue(vm.state.first { it.rows.size == 3 }.rows.single { it.id == strength.id }.isHidden)
        vm.search("不存在的项目")
        assertTrue(vm.state.first { it.query == "不存在的项目" }.rows.isEmpty())
    }

    @Test fun recentUsesPersistedLastUseAndOmitsUnusedOrHiddenDefinitions() = runTest {
        val run = BuiltinExercises.entries[0].id
        val bench = BuiltinExercises.entries[1].id
        repository.saveWorkout(WorkoutDraft(id(), clock.millis(), "UTC", listOf(WorkoutItemDraft(id(), run)))).value()
        val later = WorkoutRepository(db, writes, Clock.offset(clock, java.time.Duration.ofMinutes(1)))
        later.saveWorkout(WorkoutDraft(id(), clock.millis(), "UTC", listOf(WorkoutItemDraft(id(), bench)))).value()
        val vm = model()
        vm.showRecent(true)
        assertEquals(listOf(bench, run), vm.state.first { !it.loading && it.rows.size == 2 }.rows.map { it.id })
        val benchRow = vm.state.value.rows.first()
        vm.toggleHidden(benchRow)
        assertEquals(listOf(run), vm.state.first { it.rows.size == 1 }.rows.map { it.id })
    }

    @Test fun creationRequiresTypeThenValidNameAndRetainsFailedInput() = runTest {
        val vm = model()
        vm.add()
        val draftId = vm.editor.value!!.id
        vm.changeName("   ")
        vm.save()
        assertTrue(vm.typeRequired.value)
        vm.chooseType(ExerciseType.CARDIO)
        vm.save()
        assertEquals(ErrorCode.INVALID_NAME, vm.error.first { it != null })
        assertEquals("   ", vm.editor.value!!.name)
        vm.changeName("  虚构有氧  ")
        vm.search("旧搜索")
        vm.showRecent(true)
        val created = CompletableDeferred<String>()
        vm.save { created.complete(it.id) }
        assertEquals(draftId, created.await())
        assertNull(vm.editor.value)
        assertNull(vm.error.value)
        assertEquals("虚构有氧", vm.state.first { it.rows.any { row -> row.id == draftId } }.rows.single { it.id == draftId }.name)
        assertFalse(vm.state.value.recentOnly)
        assertEquals("", vm.state.value.query)
    }

    @Test fun duplicateSaveKeepsEditorAndCanRetryWithoutProducingExtraDefinitions() = runTest {
        val vm = model()
        vm.add(); vm.changeName("跑步"); vm.chooseType(ExerciseType.CARDIO); vm.save()
        assertEquals(ErrorCode.DUPLICATE_NAME, vm.error.first { it != null })
        val originalId = vm.editor.value!!.id
        vm.changeName("虚构跑步变式")
        var callbacks = 0
        vm.save { callbacks++ }
        vm.save { callbacks++ }
        vm.busy.first { !it }
        assertEquals(1, callbacks)
        assertEquals(9, db.workouts().allExercises().size)
        assertEquals("虚构跑步变式", db.workouts().exercise(originalId)!!.name)
    }

    @Test fun renameIsNameOnlyAndRetainsHistoricalSnapshots() = runTest {
        val bench = BuiltinExercises.entries[1].id
        val item = WorkoutItemDraft(id(), bench)
        val draft = WorkoutDraft(id(), clock.millis(), "UTC", listOf(item))
        repository.saveWorkout(draft).value()
        val vm = model()
        val row = vm.state.first { !it.loading }.rows.single { it.id == bench }
        vm.edit(row); vm.chooseType(ExerciseType.OTHER); vm.changeName("虚构卧推改名"); vm.save()
        vm.busy.first { !it }
        assertEquals(ExerciseType.STRENGTH, db.workouts().exercise(bench)!!.type)
        vm.toggleHidden(db.workouts().exercise(bench)!!)
        vm.busy.first { !it }
        val history = repository.saveWorkout(draft.copy(note = "虚构备注")).value()
        assertEquals("卧推", history.items.single().item.nameSnapshot)
        assertEquals(ExerciseType.STRENGTH, history.items.single().item.typeSnapshot)
        vm.toggleHidden(db.workouts().exercise(bench)!!)
        vm.busy.first { !it }
        assertFalse(db.workouts().exercise(bench)!!.isHidden)
    }

    @Test fun reenableConflictPreservesHiddenRowAndRenameResolvesIt() = runTest {
        val first = repository.saveExercise(ExerciseDraft(id(), "虚构同名", ExerciseType.OTHER)).value()
        repository.setExerciseHidden(first.id, true).value()
        repository.saveExercise(ExerciseDraft(id(), "虚构同名", ExerciseType.OTHER)).value()
        val vm = model()
        vm.toggleHidden(db.workouts().exercise(first.id)!!)
        assertEquals(ErrorCode.DUPLICATE_NAME, vm.error.first { it != null })
        assertTrue(db.workouts().exercise(first.id)!!.isHidden)
        vm.edit(db.workouts().exercise(first.id)!!); vm.changeName("虚构同名二"); vm.save()
        vm.busy.first { !it }
        vm.toggleHidden(db.workouts().exercise(first.id)!!)
        vm.busy.first { !it }
        assertFalse(db.workouts().exercise(first.id)!!.isHidden)
    }

    @Test fun draftAndFiltersSurviveSavedStateRecreationAndPickerExcludesHidden() = runTest {
        val saved = SavedStateHandle()
        val vm = model(saved)
        vm.search("虚构"); vm.showRecent(true); vm.showHidden(true)
        vm.add(); vm.changeName("虚构未保存"); vm.chooseType(ExerciseType.OTHER)
        val reloaded = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(vm.editor.value, reloaded.editor.value)
        assertTrue(reloaded.editor.value!!.dirty)
        assertTrue(reloaded.state.first { !it.loading }.includeHidden)
        assertEquals("虚构", reloaded.state.value.query)
        assertTrue(reloaded.state.value.recentOnly)
        val row = db.workouts().allExercises().first()
        repository.setExerciseHidden(row.id, true).value()
        val picker = model(SavedStateHandle(mapOf("hidden" to true)), selecting = true)
        assertEquals(7, picker.state.first { !it.loading }.rows.size)
        assertFalse(picker.state.value.includeHidden)
        assertFalse(picker.state.value.rows.any { it.isHidden })
    }

    @Test fun storageFailureKeepsDraftAndRetryUsesSameId() = runTest {
        db.workouts().allExercises()
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_t07 BEFORE INSERT ON ExerciseDefinition BEGIN SELECT RAISE(ABORT, 'test'); END")
        val vm = model()
        vm.add(); vm.changeName("虚构失败重试"); vm.chooseType(ExerciseType.OTHER)
        val draftId = vm.editor.value!!.id
        vm.save()
        assertEquals(ErrorCode.STORAGE, vm.error.first { it != null })
        assertEquals(draftId, vm.editor.value!!.id)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_t07")
        vm.save(); vm.busy.first { !it }
        assertNotNull(db.workouts().exercise(draftId))
        assertEquals(9, db.workouts().allExercises().size)
    }
}
