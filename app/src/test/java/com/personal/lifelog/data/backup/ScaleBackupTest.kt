package com.personal.lifelog.data.backup

import android.app.Application
import android.graphics.Bitmap
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Reliability at the acceptance dataset size; host timings are not phone performance evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScaleBackupTest {
    @Test fun thousandMixedRecordsAndTwoHundredPhotosSurviveReplaceRepeatAndRollback() = runBlocking {
        BackupFixture().use { source -> BackupFixture().use { target ->
            val base = source.clock.millis() - 250L * 86_400_000
            val generation = id()
            repeat(599) { index ->
                val photo = if (index < 200) id() else null
                val media = photo?.let {
                    val path = MediaRules.relativePath(generation, it)
                    val file = BackupFiles(source.root).file(path).apply { parentFile!!.mkdirs() }
                    val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(0xff000000.toInt() or (index * 17011))
                    file.outputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) }
                    bitmap.recycle()
                    val hash = digest(file)
                    MediaAsset(it, generation, path, "image/jpeg", 120, 80, hash.byteSize, hash.sha256, 1, 1)
                }
                assertTrue(source.records.saveMeal(MealDraft(id(), base + index * 3_600_000L,
                    "Asia/Shanghai", "synthetic-meal-$index", photoId = photo), media) is DataResult.Success)
            }
            repeat(200) { index ->
                val at = base + index * 86_400_000L
                assertTrue(source.records.saveSleep(SleepDraft(id(), at, "Asia/Shanghai",
                    at + 26_400_000, "Asia/Shanghai")) is DataResult.Success)
                assertTrue(source.workouts.saveWorkout(WorkoutDraft(id(), at, "Asia/Shanghai", listOf(
                    WorkoutItemDraft(id(), BuiltinExercises.entries[0].id),
                    WorkoutItemDraft(id(), BuiltinExercises.entries[1].id, sets = listOf(
                        WorkoutSetDraft(id(), 20000, 8), WorkoutSetDraft(id(), null, 6))),
                ))) is DataResult.Success)
            }
            assertTrue(source.records.startSleep(id()) is DataResult.Success)
            val unused = id()
            assertTrue(source.workouts.saveExercise(ExerciseDraft(unused, "synthetic-unused", ExerciseType.OTHER)) is DataResult.Success)
            assertTrue(source.workouts.setExerciseHidden(unused, true) is DataResult.Success)
            assertTrue(source.settings.setTheme(ThemeMode.DARK) is DataResult.Success)
            val expected = BackupJson.encode(source.snapshots.read()).toString()
            val zip = source.zip()
            // Synthetic-only fixture for optimized-build SAF/device checks; ignored by Git.
            File("../artifacts/t15").mkdirs()
            zip.copyTo(File("../artifacts/t15/scale-fixture.zip"), overwrite = true)
            target.seed("synthetic-before-replace")
            val previous = BackupJson.encode(target.snapshots.read()).toString()
            val importer = BackupImporter(target.root, target.clock)
            val engine = RestoreEngine(target.db, target.writes, target.snapshots, target.exporter,
                importer, target.root, target.clock, syncDirectory = {})
            suspend fun restoreSource() {
                val preview = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
                assertTrue(engine.restore(preview.token) is RestoreResult.Committed)
                val actual = target.snapshots.read()
                assertEquals(expected, BackupJson.encode(actual).toString())
                assertEquals(1000, actual.meals.size + actual.sleeps.size + actual.sessions.size)
                assertEquals(200, actual.media.size)
                assertEquals(400, actual.items.size)
                assertEquals(400, actual.sets.size)
                actual.media.forEach { BackupValidation.photo(BackupFiles(target.root).file(it.relativePath), it, BackupLimits()) }
                val timeline = TimelineRepository(target.records, target.workouts)
                val count = timeline.datesWithRecords().first().sumOf { timeline.timelineOn(it).first().size }
                assertEquals(1000, count)
            }
            restoreSource()
            val rollback = (engine.preflightRollback() as BackupResult.Success).value
            assertTrue(engine.restore(rollback.token) is RestoreResult.Committed)
            assertEquals(previous, BackupJson.encode(target.snapshots.read()).toString())
            restoreSource()
            restoreSource()
        } }
    }
}
