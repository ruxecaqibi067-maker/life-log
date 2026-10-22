package com.personal.lifelog.feature.exercise

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.repository.SnapshotRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
class ExerciseLibraryDataTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var databaseName: String
    private fun id() = RecordIds.newId()
    private fun <T> DataResult<T>.value(): T = (this as DataResult.Success).value
    private fun failure(code: ErrorCode, result: DataResult<*>) = assertEquals(code, (result as DataResult.Failure).error.code)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "t07-${id()}.db"
        reopen()
    }
    private fun reopen() {
        db = AppDatabase.create(context, clock, databaseName)
        repository = WorkoutRepository(db, WriteCoordinator(), clock)
    }
    @After fun close() { db.close(); context.deleteDatabase(databaseName) }

    @Test fun fixedSeedsAndUnusedHiddenCustomDefinitionsSurviveRepeatedReopens() = runBlocking<Unit> {
        val initial = db.workouts().allExercises()
        assertEquals(BuiltinExercises.entries.map { it.id }.toSet(), initial.map { it.id }.toSet())
        assertEquals(setOf("跑步", "卧推", "深蹲", "硬拉", "划船", "肩推", "俯卧撑", "拉伸"), initial.map { it.name }.toSet())
        val unused = repository.saveExercise(ExerciseDraft(id(), "虚构未使用项目", ExerciseType.OTHER)).value()
        repository.setExerciseHidden(unused.id, true).value()
        val runId = BuiltinExercises.entries[0].id
        repository.saveExercise(ExerciseDraft(runId, "虚构跑步别名", ExerciseType.CARDIO)).value()
        repository.setExerciseHidden(runId, true).value()
        val expected = SnapshotRepository(db).read().exercises
        repeat(3) {
            db.close(); reopen()
            assertEquals(expected, SnapshotRepository(db).read().exercises)
        }
        assertNull(expected.single { it.id == unused.id }.lastUsedAtUtcMs)
        assertTrue(expected.single { it.id == unused.id }.isHidden)
        // Machine-readable list evidence, containing only these test fixtures.
        println("T07 library evidence: " + expected.joinToString { "${it.id}:${it.name}:${it.type}:hidden=${it.isHidden}" })
    }

    @Test fun subsequentOpenDoesNotRefillAnIntentionallyReducedDefinitionLibrary() = runBlocking<Unit> {
        db.workouts().allExercises()
        // Models a complete restored library; startup must not 'repair' it by adding seeds again.
        db.openHelper.writableDatabase.execSQL("DELETE FROM ExerciseDefinition")
        val custom = repository.saveExercise(ExerciseDraft(id(), "虚构完整库", ExerciseType.OTHER)).value()
        db.close(); reopen()
        assertEquals(listOf(custom), SnapshotRepository(db).read().exercises)
    }

    @Test fun namesValidateCodePointsNormalizationAndConcurrentActiveUniqueness() = runBlocking<Unit> {
        listOf("", " \t\n　", "字".repeat(41), "😀".repeat(41)).forEach {
            failure(ErrorCode.INVALID_NAME, repository.saveExercise(ExerciseDraft(id(), it, ExerciseType.OTHER)))
        }
        val boundary = repository.saveExercise(ExerciseDraft(id(), "😀".repeat(40), ExerciseType.OTHER)).value()
        assertEquals(40, boundary.name.codePointCount(0, boundary.name.length))
        val results = listOf(" Ｐｒｅｓｓ ", "press").map { name ->
            async { repository.saveExercise(ExerciseDraft(id(), name, ExerciseType.STRENGTH)) }
        }.awaitAll()
        assertEquals(1, results.count { it is DataResult.Success })
        failure(ErrorCode.DUPLICATE_NAME, results.single { it is DataResult.Failure })
        assertEquals(ExerciseType.CARDIO,
            repository.saveExercise(ExerciseDraft(id(), "press", ExerciseType.CARDIO)).value().type)
        assertEquals(11, db.workouts().allExercises().size)
    }
}
