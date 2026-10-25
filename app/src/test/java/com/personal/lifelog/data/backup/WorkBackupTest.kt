package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.repository.WorkSummaryRepository
import com.personal.lifelog.domain.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkBackupTest {
    private lateinit var f: BackupFixture
    @Before fun setup() { f = BackupFixture() }
    @After fun close() { f.close() }
    private fun <T> DataResult<T>.saved() = (this as DataResult.Success).value
    private fun evidence(name: String) = File("../artifacts/t20/comparison/$name").apply { parentFile!!.mkdirs() }
    private fun engine(importer: BackupImporter) = RestoreEngine(f.db, f.writes, f.snapshots, f.exporter,
        importer, f.root, f.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE })

    /** Remove all fields absent from the actual historical schema, then recompute the data hash. */
    private suspend fun legacy(zip: File, version: Int): File {
        val dataZip = editData(zip, File(f.root, "legacy-data-$version.zip")) { data ->
            data.put("dataVersion", version)
            data.remove("workRecords")
            data.getJSONObject("settings").remove("targetWorkMinutes")
            if (version < 3) data.getJSONObject("settings").remove("targetSleepMinutes")
            if (version == 1) {
                val meals = data.getJSONArray("meals")
                for (i in 0 until meals.length()) meals.getJSONObject(i).remove("satietyText")
            }
        }
        val entries = zipEntries(dataZip)
        entries["manifest.json"] = JSONObject(String(entries.getValue("manifest.json")))
            .put("formatVersion", version).also { it.getJSONObject("counts").remove("workRecords") }.toString().toByteArray()
        return writeZip(File(f.root, "legacy-$version.zip"), entries)
    }

    @Test fun v4RoundTripIncludesOpenCompletedWorkBothGoalsPhotosAndPreview() = runBlocking<Unit> {
        val before = f.seedWithWork(targetSleepMinutes = 480)
        val zip = f.zip()
        val destination = MemoryDestination()
        assertTrue(f.exporter.export(destination) is BackupResult.Success)
        assertEquals(before.settings.targetWorkMinutes, f.snapshots.read().settings.targetWorkMinutes)
        val checked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("roundtrip"))
        assertEquals(BackupJson.encode(before).toString(), BackupJson.encode(checked.data).toString())
        val importer = BackupImporter(f.root, f.clock)
        val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
        assertEquals(2, ready.preview.counts["workRecords"])
        assertTrue(ready.preview.hasActiveWork)
        assertTrue(ready.preview.hasActiveSleep) // Both can coexist.
        assertEquals(1000L, ready.preview.earliestUtcMs)
        assertEquals(f.clock.millis(), ready.preview.latestUtcMs)
        assertEquals(480, ready.preview.targetSleepMinutes)
        assertEquals(495, ready.preview.targetWorkMinutes)
        for (row in before.workRecords) f.work.deleteWork(row).saved()
        f.settings.setTargetWorkMinutes(null).saved()
        assertTrue(engine(importer).restore(ready.token) is RestoreResult.Committed)
        assertEquals(BackupJson.encode(before).toString(), BackupJson.encode(f.snapshots.read()).toString())
        zip.copyTo(evidence("v4-full.zip"), overwrite = true)
    }

    @Test fun workOnlyArchiveIsNonemptyHasRangeAndConsumesRecordLimit() = runBlocking<Unit> {
        f.work.saveWork(WorkDraft(id(), 1000, "UTC", 2000, "UTC")).saved()
        f.work.startWork(id(), 3000, "UTC").saved()
        val snapshot = f.snapshots.read()
        val zip = f.zip()
        val importer = BackupImporter(f.root, f.clock)
        val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
        assertEquals(2, ready.preview.counts["workRecords"])
        assertEquals(0, ready.preview.counts["meals"])
        assertEquals(1000L, ready.preview.earliestUtcMs)
        assertEquals(3000L, ready.preview.latestUtcMs)
        assertTrue(ready.preview.hasActiveWork)
        assertFalse(ready.preview.hasActiveSleep)
        assertNull(ready.preview.targetWorkMinutes)
        assertThrows(BackupException::class.java) { BackupValidation.validate(snapshot, BackupLimits(records = 1)) }
        BackupValidation.validate(snapshot, BackupLimits(records = 2))
        assertTrue(engine(importer).restore(ready.token) is RestoreResult.Committed)
        assertEquals(snapshot, f.snapshots.read())
        zip.copyTo(evidence("v4-work-only.zip"), overwrite = true)
    }

    @Test fun genuineV1V2V3RestoreClearWorkAndPublishConsistentSummaryThenRollback() = runBlocking<Unit> {
        f.seed(targetSleepMinutes = 480)
        val current = f.zip()
        for (version in 1..3) {
            val zip = legacy(current, version)
            val importer = BackupImporter(f.root, f.clock)
            val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            assertEquals(0, ready.preview.counts["workRecords"])
            assertFalse(ready.preview.hasActiveWork)
            assertNull(ready.preview.targetWorkMinutes)
            f.work.saveWork(WorkDraft(id(), 1000, "UTC", 2000, "UTC")).saved()
            f.settings.setTargetWorkMinutes(600).saved()
            val old = f.snapshots.read()
            val changes = Channel<WorkSummary>(Channel.UNLIMITED)
            val job = launch(Dispatchers.Default) {
                WorkSummaryRepository(f.db).observe(LocalDate.of(1970, 1, 1)).collect { changes.send(it.saved()) }
            }
            try {
                assertEquals(600, withTimeout(10_000) { changes.receive() }.targetWorkMinutes)
                val service = engine(importer)
                assertTrue(service.restore(ready.token) is RestoreResult.Committed)
                val next = withTimeout(10_000) { changes.receive() }
                assertNull(next.targetWorkMinutes)
                assertEquals(0, next.completedSegmentCount)
                assertNull(next.recordedDurationMs)
                assertFalse(next.hasActiveWork)
                val restored = f.snapshots.read()
                assertTrue(restored.workRecords.isEmpty())
                assertNull(restored.settings.targetWorkMinutes)
                assertEquals(if (version == 3) 480 else null, restored.settings.targetSleepMinutes)
                zip.copyTo(evidence("v$version-legacy.zip"), overwrite = true)
                f.zip().copyTo(evidence("v$version-to-v4.zip"), overwrite = true)
                val rollback = (service.preflightRollback() as BackupResult.Success).value
                assertTrue(service.restore(rollback.token) is RestoreResult.Committed)
                assertEquals(BackupJson.encode(old).toString(), BackupJson.encode(f.snapshots.read()).toString())
                for (row in f.snapshots.read().workRecords) f.work.deleteWork(row).saved()
            } finally { job.cancelAndJoin(); changes.close() }
        }
    }

    @Test fun invalidGoalsAndMissingCurrentFieldsFailPreflightWithoutWrites() = runBlocking<Unit> {
        val before = f.seedWithWork(targetSleepMinutes = 480)
        val zip = f.zip()
        val edits = listOf<(JSONObject) -> Unit>(
            { it.remove("workRecords") }, { it.put("workRecords", JSONObject.NULL) },
            { it.put("workRecords", "bad") }, { it.getJSONObject("settings").remove("targetWorkMinutes") },
        ) + listOf<Any>(0, -1, 1441, Int.MAX_VALUE.toLong() + 1, 1.5, true, "480", JSONObject()).map { bad ->
            { j: JSONObject -> j.getJSONObject("settings").put("targetWorkMinutes", bad); Unit }
        }
        for ((i, edit) in edits.withIndex()) {
            val bad = editData(zip, File(f.root, "bad-field-$i.zip"), edit)
            val result = BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) as BackupResult.Failure
            assertEquals(BackupError.INVALID_DATA, result.error)
            assertEquals(before, f.snapshots.read())
        }
    }

    @Test fun invalidIntervalsIdsZonesDatesOverlapAndMultipleActiveAreRejected() = runBlocking<Unit> {
        val before = f.seedWithWork(targetSleepMinutes = 480)
        val zip = f.zip()
        val edits = listOf<(JSONObject) -> Unit>(
            { it.put("endedAtUtcMs", 1000) }, { it.put("endZoneId", JSONObject.NULL) },
            { it.put("startZoneId", "bad") }, { it.put("archiveLocalDate", "2020-01-01") },
            { it.put("startedAtUtcMs", "1000") }, { it.put("id", "bad") },
        )
        for ((i, edit) in edits.withIndex()) {
            val bad = editData(zip, File(f.root, "bad-row-$i.zip")) { edit(it.getJSONArray("workRecords").objects().first { row -> !row.isNull("endedAtUtcMs") }) }
            assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
        }
        for (active in listOf(false, true)) {
            val bad = editData(zip, File(f.root, "overlap-$active.zip")) {
                val rows = it.getJSONArray("workRecords")
                val row = JSONObject(rows.objects().first { row -> row.isNull("endedAtUtcMs") == active }.toString()).put("id", id())
                rows.put(row)
            }
            assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
        }
        assertEquals(before, f.snapshots.read())
    }

    @Test fun missingOrMismatchedWorkCountsAndUnknownVersionsOrEnumsAreRejected() = runBlocking<Unit> {
        val before = f.seedWithWork()
        val zip = f.zip()
        for (count in listOf<Any?>(null, -1, 1, "2", 2.5)) {
            val entries = zipEntries(zip)
            val manifest = JSONObject(String(entries.getValue("manifest.json")))
            if (count == null) manifest.getJSONObject("counts").remove("workRecords")
            else manifest.getJSONObject("counts").put("workRecords", count)
            entries["manifest.json"] = manifest.toString().toByteArray()
            val bad = writeZip(File(f.root, "bad-count-${id()}.zip"), entries)
            assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
        }
        for (edit in listOf<(JSONObject) -> Unit>({ it.put("dataVersion", 5) },
            { it.getJSONObject("settings").put("themeMode", "UNKNOWN") })) {
            val bad = editData(zip, File(f.root, "bad-version-${id()}.zip"), edit)
            assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
        }
        assertEquals(before, f.snapshots.read())
    }
}
