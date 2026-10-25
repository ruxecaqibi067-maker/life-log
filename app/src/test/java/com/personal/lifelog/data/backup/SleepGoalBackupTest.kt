package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.repository.SleepSummaryRepository
import com.personal.lifelog.domain.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SleepGoalBackupTest {
    private lateinit var f: BackupFixture
    @Before fun setup() { f = BackupFixture() }
    @After fun close() { f.close() }
    private fun <T> DataResult<T>.saved() = (this as DataResult.Success).value
    private fun evidence(name: String) = File("../artifacts/t18/$name").apply { parentFile!!.mkdirs() }

    private suspend fun legacy(zip: File, version: Int): File {
        val dataZip = editData(zip, File(f.root, "legacy-data-$version.zip")) { data ->
            data.put("dataVersion", version)
            data.getJSONObject("settings").remove("targetSleepMinutes")
            if (version == 1) {
                val meals = data.getJSONArray("meals")
                for (i in 0 until meals.length()) meals.getJSONObject(i).remove("satietyText")
            }
        }
        val entries = zipEntries(dataZip)
        entries["manifest.json"] = JSONObject(String(entries.getValue("manifest.json"), Charsets.UTF_8))
            .put("formatVersion", version).toString().toByteArray()
        return writeZip(File(f.root, "legacy-$version.zip"), entries)
    }

    @Test fun v3GoalRoundTripsAndSuccessfulExportKeepsGoal() = runBlocking<Unit> {
        val before = f.seed(targetSleepMinutes = 480)
        val destination = MemoryDestination()
        assertTrue(f.exporter.export(destination) is BackupResult.Success)
        val zip = File(f.root, "export.zip").apply { writeBytes(destination.stream.toByteArray()) }
        val checked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("v3-check"))
        assertEquals(before.settings, checked.data.settings)
        assertEquals(480, f.snapshots.read().settings.targetSleepMinutes)
        assertEquals(f.clock.millis(), f.snapshots.read().settings.lastSuccessfulExportAtUtcMs)
        val entries = zipEntries(zip)
        assertEquals(3, JSONObject(String(entries.getValue("manifest.json"))).getInt("formatVersion"))
        assertEquals(3, JSONObject(String(entries.getValue("data.json"))).getInt("dataVersion"))
        f.settings.setTargetSleepMinutes(null).saved()
        val unset = zipEntries(f.zip())
        assertTrue(JSONObject(String(unset.getValue("data.json"))).getJSONObject("settings").isNull("targetSleepMinutes"))
        zip.copyTo(evidence("v3-goal.zip"), overwrite = true)
    }

    @Test fun oldV1AndV2RestoreClearGoalAndRefreshLiveSummary() = runBlocking<Unit> {
        f.seed(targetSleepMinutes = 480)
        val completed = f.records.saveSleep(SleepDraft(id(), Instant.parse("2026-10-22T23:00:00Z").toEpochMilli(), "UTC",
            Instant.parse("2026-10-23T06:00:00Z").toEpochMilli(), "UTC")).saved()
        val original = f.zip()
        editData(original, File(f.root, "v3-null.zip")) {
            it.getJSONObject("settings").put("targetSleepMinutes", JSONObject.NULL)
        }.copyTo(evidence("v3-migrated.zip"), overwrite = true)
        for (version in listOf(1, 2)) {
            val zip = legacy(original, version)
            val importer = BackupImporter(f.root, f.clock)
            val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            importer.withValidated(ready.token) { assertNull(it.data.settings.targetSleepMinutes) }
            f.settings.setTargetSleepMinutes(600).saved()
            f.records.deleteSleep(completed).saved()
            val changes = Channel<SleepSummary>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                SleepSummaryRepository(f.db).observe(LocalDate.of(2026, 10, 23)).collect { changes.send(it.saved()) }
            }
            try {
                val old = withTimeout(10_000) { changes.receive() }
                assertEquals(600, old.targetSleepMinutes)
                assertEquals(0, old.completedSegmentCount)
                val engine = RestoreEngine(f.db, f.writes, f.snapshots, f.exporter, importer, f.root, f.clock,
                    syncDirectory = {}, availableBytes = { Long.MAX_VALUE })
                assertTrue(engine.restore(ready.token) is RestoreResult.Committed)
                val restored = withTimeout(10_000) {
                    var next = changes.receive()
                    while (next.targetSleepMinutes != null) next = changes.receive()
                    next
                }
                assertEquals(1, restored.completedSegmentCount)
                assertEquals(25_200_000L, restored.recordedDurationMs)
                assertTrue(restored.hasActiveSleep)
                assertNull(f.snapshots.read().settings.targetSleepMinutes)
            } finally { job.cancelAndJoin(); changes.close() }
            zip.copyTo(evidence("v$version-legacy.zip"), overwrite = true)
        }
    }

    @Test fun invalidGoalTypeRangeOverflowAndMissingV3FieldFailPreflightWithoutWrites() = runBlocking<Unit> {
        val before = f.seed(targetSleepMinutes = 480)
        val zip = f.zip()
        val badValues = listOf<Any>(0, -1, 1441, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE, 1.5, true, "480", JSONObject())
        for ((index, bad) in badValues.withIndex()) {
            val corrupt = editData(zip, File(f.root, "bad-goal-$index.zip")) {
                it.getJSONObject("settings").put("targetSleepMinutes", bad)
            }
            val importer = BackupImporter(f.root, f.clock)
            val result = importer.preflight(BackupSource { corrupt.inputStream() }) as BackupResult.Failure
            assertEquals("case $index", BackupError.INVALID_DATA, result.error)
            assertEquals(before, f.snapshots.read())
        }
        val missing = editData(zip, File(f.root, "missing-goal.zip")) { it.getJSONObject("settings").remove("targetSleepMinutes") }
        assertEquals(BackupError.INVALID_DATA, (BackupImporter(f.root, f.clock).preflight(BackupSource { missing.inputStream() }) as BackupResult.Failure).error)
        assertEquals(before, f.snapshots.read())
    }

    @Test fun currentFormatWithOldManifestIsRejected() = runBlocking<Unit> {
        f.seed()
        val entries = zipEntries(f.zip())
        entries["manifest.json"] = JSONObject(String(entries.getValue("manifest.json")))
            .put("formatVersion", 2).toString().toByteArray()
        val mismatch = writeZip(File(f.root, "mismatch.zip"), entries)
        assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { mismatch.inputStream() }) is BackupResult.Failure)
    }
}
