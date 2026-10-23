package com.personal.lifelog.data.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.backup.export.*
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.BuiltinExercises
import com.personal.lifelog.data.local.entity.*
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.io.*
import java.time.*
import java.util.UUID

internal fun id() = UUID.randomUUID().toString()
internal class BackupFixture(val databaseName: String? = null) : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val clock = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC)
    val root = File(context.filesDir, "backup-test-${id()}").apply { mkdirs() }
    val db = (if (databaseName == null) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        else Room.databaseBuilder(context, AppDatabase::class.java, databaseName))
        .addCallback(AppDatabase.creationCallback(clock)).build()
    val writes = WriteCoordinator()
    val records = RecordRepository(db, writes, clock) { ZoneId.of("UTC") }
    val workouts = WorkoutRepository(db, writes, clock)
    val snapshots = SnapshotRepository(db)
    val settings = SettingsRepository(db, writes, clock)
    val exporter = BackupExporter(snapshots, settings, writes, root, clock, "0.1.0")
    suspend fun seed(text: String = "虚构午餐"): DataSnapshot {
        val gen = id(); val photo = id()
        val path = MediaRules.relativePath(gen, photo)
        val file = BackupFiles(root).file(path).apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
        val hash = digest(file)
        val asset = MediaAsset(photo, gen, path, "image/jpeg", 12, 8, hash.byteSize, hash.sha256, 1, 2)
        check(records.saveMeal(MealDraft(id(), clock.millis(), "UTC", text, Satiety.SEVEN, photo), asset) is DataResult.Success)
        check(records.startSleep(id(), clock.millis(), "UTC") is DataResult.Success)
        val custom = id()
        check(workouts.saveExercise(ExerciseDraft(custom, "虚构未使用项目", ExerciseType.OTHER)) is DataResult.Success)
        check(workouts.setExerciseHidden(custom, true) is DataResult.Success)
        check(workouts.saveWorkout(WorkoutDraft(id(), clock.millis(), "UTC", listOf(
            WorkoutItemDraft(id(), BuiltinExercises.entries[0].id, durationSeconds = 60, distanceMeters = 200),
            WorkoutItemDraft(id(), BuiltinExercises.entries[1].id, sets = listOf(
                WorkoutSetDraft(id(), 0, 10), WorkoutSetDraft(id(), 12500, null), WorkoutSetDraft(id(), null, 5)
            ))
        ), "虚构训练")) is DataResult.Success)
        check(settings.setTheme(ThemeMode.DARK) is DataResult.Success)
        return snapshots.read()
    }
    suspend fun zip(): File = writes.withExclusiveAccess { exporter.prepare(BackupFiles(root).mkdir("fixture-${id()}")) }
    override fun close() { db.close(); BackupFiles(root.parentFile!!).remove(root); databaseName?.let { context.deleteDatabase(it) } }
}
internal class MemoryDestination : BackupDestination {
    val stream = ByteArrayOutputStream()
    var deleted = false
    override fun open(): OutputStream = stream
    override fun deleteIncomplete() { deleted = true }
}
