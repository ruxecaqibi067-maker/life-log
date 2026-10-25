package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.feature.workout.WorkoutForm
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class WorkoutEffortBackupTest {
    private lateinit var f: BackupFixture
    @Before fun setup() { f = BackupFixture() }
    @After fun close() { f.close() }

    @Test fun allEffortsAndCustomTextExportPreflightRestoreWithoutChangingOtherData() = runBlocking<Unit> {
        f.seedWithWork(targetSleepMinutes = 480)
        val record = f.db.workouts().allSessions().single()
        val draft = WorkoutForm.from(f.workouts.workout(record.id)!!).draft()
        for (effort in WorkoutEffort.entries) {
            assertTrue(f.workouts.saveWorkout(draft.copy(effort = effort,
                effortText = if (effort == WorkoutEffort.CUSTOM) "  虚构：全身很累  " else null)) is DataResult.Success)
            val before = f.snapshots.read()
            val zip = f.zip()
            val importer = BackupImporter(f.root, f.clock)
            val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            importer.withValidated(ready.token) { assertEquals(before.sessions, it.data.sessions) }
            assertTrue(f.workouts.saveWorkout(draft.copy(effort = null)) is DataResult.Success)
            val engine = RestoreEngine(f.db, f.writes, f.snapshots, f.exporter, importer, f.root, f.clock,
                syncDirectory = {}, availableBytes = { Long.MAX_VALUE })
            assertTrue(engine.restore(ready.token) is RestoreResult.Committed)
            val after = f.snapshots.read()
            assertEquals(before.copy(media = emptyList()), after.copy(media = emptyList()))
            assertEquals(before.media.map { it.sha256 }, after.media.map { it.sha256 })
        }
    }

    @Test fun oldV4IsUnratedAndBadOrMissingEffortFailsWithoutWrites() = runBlocking<Unit> {
        val before = f.seedWithWork()
        val zip = f.zip()
        for (edit in listOf<(JSONObject) -> Unit>(
            { it.put("effort", "UNKNOWN") },
            { it.remove("effort") },
            { it.remove("effortText") },
            { it.put("effort", "CUSTOM").put("effortText", " ") },
            { it.put("effort", "CUSTOM").put("effortText", "字".repeat(41)) },
            { it.put("effort", "HARD").put("effortText", "多余文字") },
            { it.put("effort", 1) },
        )) {
            val bad = editData(zip, File(f.root, "bad-${id()}.zip")) { edit(it.getJSONArray("sessions").getJSONObject(0)) }
            val result = BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() })
            assertEquals(BackupError.INVALID_DATA, (result as BackupResult.Failure).error)
            assertEquals(before, f.snapshots.read())
        }
        val old = zipEntries(zip)
        old["data.json"] = JSONObject(String(old.getValue("data.json"))).put("dataVersion", 4).also {
            it.getJSONArray("sessions").getJSONObject(0).also { row -> row.remove("effort"); row.remove("effortText") }
        }.toString().toByteArray()
        val data = old.getValue("data.json")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        old["manifest.json"] = JSONObject(String(old.getValue("manifest.json"))).put("formatVersion", 4).also { manifest ->
            val files = manifest.getJSONArray("files")
            for (i in 0 until files.length()) if (files.getJSONObject(i).getString("path") == "data.json") {
                files.getJSONObject(i).put("byteSize", data.size).put("sha256", hash)
            }
        }.toString().toByteArray()
        val legacy = writeZip(File(f.root, "v4.zip"), old)
        val verified = BackupArchive().verify(legacy, BackupFiles(f.root).mkdir("legacy-verify"))
        assertEquals(before, verified.data.copy(media = before.media))
        assertTrue(verified.data.sessions.all { it.effort == null && it.effortText == null })
    }
}
