package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.domain.*
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class TodoBackupTest {
    private lateinit var f: BackupFixture
    private lateinit var todos: TodoRepository
    private val date = LocalDate.of(2026, 10, 23)
    @Before fun setup() { f = BackupFixture(); todos = repository(f) }
    @After fun close() { f.close() }
    private fun repository(fixture: BackupFixture) = TodoRepository(fixture.db, fixture.writes, fixture.clock) { ZoneOffset.UTC }
    private fun <T> DataResult<T>.saved(): T = (this as DataResult.Success).value
    private fun semantic(d: com.personal.lifelog.data.repository.DataSnapshot) = BackupJson.encode(d).toString()
    private fun engine(importer: BackupImporter, fault: suspend (RestoreFaultPoint) -> Unit = {}) =
        RestoreEngine(f.db, f.writes, f.snapshots, f.exporter, importer, f.root, f.clock,
            syncDirectory = {}, availableBytes = { Long.MAX_VALUE }, fault = fault)
    private suspend fun populate(repo: TodoRepository = todos, title: String = "虚构旧习惯") {
        val daily = repo.saveHabit(HabitDraft(id(), title, date, 480)).saved()
        repo.saveHabit(HabitDraft(id(), "虚构间隔", date, frequency = HabitFrequency.INTERVAL, intervalDays = 365)).saved()
        val paused = repo.saveHabit(HabitDraft(id(), "虚构周五", date, frequency = HabitFrequency.WEEKLY, weekdaysMask = 16)).saved()
        repo.generateFor().saved()
        val generated = repo.observeOn(date).first().single { it.habitId == daily.id }
        repo.setCompleted(generated.id, true).saved()
        repo.saveTodo(TodoDraft(generated.id, "独立快照", date.plusDays(1), 900)).saved()
        repo.deleteTodo(generated.id).saved()
        repo.setHabitStatus(paused.id, HabitStatus.PAUSED).saved()
        val archived = repo.saveHabit(HabitDraft(id(), "虚构已结束", date.minusDays(30))).saved()
        repo.setHabitStatus(archived.id, HabitStatus.ARCHIVED).saved()
        val manual = repo.saveTodo(TodoDraft(id(), "虚构手动", date.minusDays(2))).saved()
        repo.setCompleted(manual.id, true).saved()
    }

    @Test fun fullSixRoundTripPreviewRestoreAndRollbackPreserveSnapshotsCompletionAndTombstones() = runBlocking<Unit> {
        f.seedWithWork(targetSleepMinutes = 480)
        populate()
        val before = f.snapshots.read()
        val zip = f.zip()
        val checked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("check-six"))
        assertEquals(7, BackupJson.VERSION)
        assertEquals(semantic(before), semantic(checked.data))
        val importer = BackupImporter(f.root, f.clock)
        val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
        assertEquals(before.todos.size, ready.preview.todoCount)
        assertEquals(4, ready.preview.habitCount)
        assertEquals(before.todos.size, ready.preview.counts["todos"])
        assertEquals(date.minusDays(30).toString(), ready.preview.earliestLocalDate)
        assertEquals(date.plusDays(1).toString(), ready.preview.latestLocalDate)
        assertTrue(ready.preview.hasActiveSleep); assertTrue(ready.preview.hasActiveWork)
        assertEquals(480, ready.preview.targetSleepMinutes)
        val extra = todos.saveTodo(TodoDraft(id(), "稍后添加", date)).saved()
        val old = f.snapshots.read()
        val service = engine(importer)
        assertTrue(service.restore(ready.token) is RestoreResult.Committed)
        assertEquals(semantic(before), semantic(f.snapshots.read()))
        assertNull(todos.todo(extra.id))
        assertEquals(0, todos.generateFor().saved()) // Deleted habit instance is still excluded after restore.
        val rollback = (service.preflightRollback() as BackupResult.Success).value
        assertEquals(old.todos.size, rollback.preview.todoCount)
        assertTrue(service.restore(rollback.token) is RestoreResult.Committed)
        assertEquals(semantic(old), semantic(f.snapshots.read()))
        val evidence = File("../artifacts/t22/comparison/v6-full.zip").apply { parentFile!!.mkdirs() }
        zip.copyTo(evidence, overwrite = true)
    }

    /** Produce genuine old fields/counts, not merely a v6 ZIP with a lower version label. */
    private suspend fun legacy(zip: File, version: Int): File {
        val edited = editData(zip, File(f.root, "legacy-data-$version.zip")) { data ->
            data.put("dataVersion", version); data.remove("todos"); data.remove("habits")
            if (version < 5) data.getJSONArray("sessions").objects().forEach { it.remove("effort"); it.remove("effortText") }
            if (version < 4) { data.remove("workRecords"); data.getJSONObject("settings").remove("targetWorkMinutes") }
            if (version < 3) data.getJSONObject("settings").remove("targetSleepMinutes")
            if (version == 1) data.getJSONArray("meals").objects().forEach { it.remove("satietyText") }
        }
        val entries = zipEntries(edited)
        val manifest = JSONObject(String(entries.getValue("manifest.json"))).put("formatVersion", version)
        manifest.getJSONObject("counts").apply {
            remove("todos"); remove("habits"); if (version < 4) remove("workRecords")
        }
        entries["manifest.json"] = manifest.toString().toByteArray()
        return writeZip(File(f.root, "legacy-$version.zip"), entries)
    }

    @Test fun genuineOneThroughFiveDefaultEmptyAndFullRestoreClearsThenRollbackRecoversNewTables() = runBlocking<Unit> {
        f.seed(targetSleepMinutes = 480)
        val current = f.zip()
        for (version in 1..5) {
            populate()
            val before = f.snapshots.read()
            val zip = legacy(current, version)
            val importer = BackupImporter(f.root, f.clock)
            val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            assertEquals(0, ready.preview.todoCount); assertEquals(0, ready.preview.habitCount)
            assertEquals(0, ready.preview.counts["todos"]); assertEquals(0, ready.preview.counts["habits"])
            assertNull(ready.preview.earliestLocalDate)
            val service = engine(importer)
            assertTrue(service.restore(ready.token) is RestoreResult.Committed)
            assertTrue(f.db.todos().allTodos().isEmpty()); assertTrue(f.db.todos().allHabits().isEmpty())
            assertEquals(0, todos.generateFor().saved())
            val evidence = File("../artifacts/t22/comparison/v$version-legacy.zip").apply { parentFile!!.mkdirs() }
            zip.copyTo(evidence, overwrite = true)
            f.zip().copyTo(File(evidence.parentFile, "v$version-to-v6.zip"), overwrite = true)
            val rollback = (service.preflightRollback() as BackupResult.Success).value
            assertTrue(service.restore(rollback.token) is RestoreResult.Committed)
            assertEquals(semantic(before), semantic(f.snapshots.read()))
        }
    }

    @Test fun cancellationFailureAndLateInsertFailureKeepAllOldTables() = runBlocking<Unit> {
        f.seedWithWork("旧四类", 480); populate()
        val before = f.snapshots.read()
        val incoming = BackupFixture()
        try {
            incoming.seedWithWork("新四类", 600); populate(repository(incoming), "触发失败")
            val zip = incoming.zip()
            for (cancel in listOf(false, true)) {
                val importer = BackupImporter(f.root, f.clock)
                val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
                val service = engine(importer) { if (it == RestoreFaultPoint.IN_TRANSACTION) {
                    if (cancel) throw CancellationException("synthetic") else throw IOException("synthetic")
                } }
                if (cancel) {
                    try { service.restore(ready.token); fail("cancellation expected") } catch (_: CancellationException) { }
                } else assertTrue(service.restore(ready.token) is RestoreResult.FailedBeforeCommit)
                assertEquals(semantic(before), semantic(f.snapshots.read()))
            }
            f.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_test_restore_todo BEFORE INSERT ON TodoRecord WHEN NEW.habitId IS NOT NULL BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            val importer = BackupImporter(f.root, f.clock)
            val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            assertTrue(engine(importer).restore(ready.token) is RestoreResult.FailedBeforeCommit)
            assertEquals(semantic(before), semantic(f.snapshots.read()))
        } finally { incoming.close() }
    }

    @Test fun malformedCurrentFieldsReferencesGenerationKeysDatesCountsAndResourceLimitsRejectPreflight() = runBlocking<Unit> {
        populate()
        val before = f.snapshots.read()
        val zip = f.zip()
        val edits = listOf<(JSONObject) -> Unit>(
            { it.remove("todos") }, { it.remove("habits") },
            { it.getJSONArray("todos").getJSONObject(0).put("localDate", "2026-02-30") },
            { it.getJSONArray("todos").getJSONObject(0).put("localDate", "2200-01-01") },
            { it.getJSONArray("todos").getJSONObject(0).put("scheduledMinute", 1440) },
            { it.getJSONArray("todos").getJSONObject(0).put("scheduledMinute", true) },
            { it.getJSONArray("todos").getJSONObject(0).put("completedAtUtcMs", "12") },
            { it.getJSONArray("todos").getJSONObject(0).put("title", " ") },
            { j -> j.getJSONArray("todos").objects().first { !it.isNull("habitId") }.put("habitId", id()) },
            { j -> j.getJSONArray("todos").objects().first { !it.isNull("habitId") }.put("habitDate", JSONObject.NULL) },
            { j -> val rows = j.getJSONArray("todos"); rows.put(JSONObject(rows.objects().first { !it.isNull("habitId") }.toString()).put("id", id())) },
            { it.getJSONArray("habits").getJSONObject(0).put("status", "UNKNOWN") },
            { it.getJSONArray("habits").getJSONObject(0).put("frequency", "WEEKLY").put("weekdaysMask", 0) },
            { it.getJSONArray("habits").getJSONObject(0).put("frequency", "INTERVAL").put("intervalDays", 366) },
        )
        edits.forEachIndexed { index, edit ->
            val bad = editData(zip, File(f.root, "bad-$index.zip"), edit)
            assertTrue("edit $index", BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
            assertEquals(before, f.snapshots.read())
        }
        for (key in listOf("todos", "habits")) {
            val entries = zipEntries(zip)
            val manifest = JSONObject(String(entries.getValue("manifest.json")))
            manifest.getJSONObject("counts").remove(key)
            entries["manifest.json"] = manifest.toString().toByteArray()
            val bad = writeZip(File(f.root, "missing-count-$key.zip"), entries)
            assertTrue(BackupImporter(f.root, f.clock).preflight(BackupSource { bad.inputStream() }) is BackupResult.Failure)
        }
        val count = before.todos.size + before.habits.size
        BackupValidation.validate(before, BackupLimits(records = count))
        assertEquals(BackupError.LIMIT, assertThrows(BackupException::class.java) {
            BackupValidation.validate(before, BackupLimits(records = count - 1))
        }.code)
    }
}
