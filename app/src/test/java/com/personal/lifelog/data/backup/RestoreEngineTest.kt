package com.personal.lifelog.data.backup

import android.app.Application
import androidx.room.Room
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.export.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoreEngineTest {
    private lateinit var f: BackupFixture
    private lateinit var g: BackupFixture
    private lateinit var importer: BackupImporter
    @Before fun before() { f = BackupFixture(); g = BackupFixture(); importer = BackupImporter(f.root, f.clock) }
    @After fun after() { f.close(); g.close() }
    private fun engine(fault: suspend (RestoreFaultPoint) -> Unit = {}, space: Long = Long.MAX_VALUE) =
        RestoreEngine(f.db, f.writes, f.snapshots, f.exporter, importer, f.root, f.clock,
            syncDirectory = {}, availableBytes = { space }, fault = fault)
    private suspend fun token(zip: File) = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value.token
    private fun semantic(d: DataSnapshot) = BackupJson.encode(d).toString()
    private suspend fun assertComplete(db: AppDatabase = f.db) {
        val data = SnapshotRepository(db).read()
        BackupValidation.validate(data, BackupLimits())
        data.media.forEach { BackupValidation.photo(BackupFiles(f.root).file(it.relativePath), it, BackupLimits()) }
    }
    private fun afterCommit(point: RestoreFaultPoint) = point in listOf(RestoreFaultPoint.AFTER_COMMIT, RestoreFaultPoint.CLEANUP)

    @Test fun notFullSatietySurvivesExportPreflightAndRestore() = runBlocking {
        f.seedWithWork()
        val mealId = id()
        assertTrue(
            f.records.saveMeal(
                MealDraft(mealId, f.clock.millis(), "UTC", "下午加餐", Satiety.NOT_FULL),
            ) is DataResult.Success,
        )
        val zip = f.zip()
        val target = BackupFixture()
        try {
            val imp = BackupImporter(target.root, target.clock)
            val ready = (imp.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            val service = RestoreEngine(
                target.db, target.writes, target.snapshots, target.exporter, imp,
                target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE },
            )
            assertTrue(service.restore(ready.token) is RestoreResult.Committed)
            assertEquals(
                Satiety.NOT_FULL,
                target.snapshots.read().meals.single { it.id == mealId }.satiety,
            )
        } finally {
            target.close()
        }
    }

    @Test fun customSatietyTextSurvivesExportPreflightAndRestore() = runBlocking {
        f.seedWithWork()
        val mealId = id()
        assertTrue(
            f.records.saveMeal(
                MealDraft(
                    mealId, f.clock.millis(), "UTC", "自定义饱腹感", Satiety.CUSTOM,
                    satietyText = "七分饱但还想吃",
                ),
            ) is DataResult.Success,
        )
        val zip = f.zip()
        val target = BackupFixture()
        try {
            val imp = BackupImporter(target.root, target.clock)
            val ready = (imp.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
            val service = RestoreEngine(
                target.db, target.writes, target.snapshots, target.exporter, imp,
                target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE },
            )
            assertTrue(service.restore(ready.token) is RestoreResult.Committed)
            val restored = target.snapshots.read().meals.single { it.id == mealId }
            assertEquals(Satiety.CUSTOM, restored.satiety)
            assertEquals("七分饱但还想吃", restored.satietyText)
        } finally {
            target.close()
        }
    }

    @Test fun completeRestoreRepeatAndRollbackUseFullPreflight() = runBlocking {
        val old = f.seedWithWork("虚构旧记录"); val incoming = g.seedWithWork("虚构新记录"); val zip = g.zip(); val service = engine()
        assertTrue(service.restore(token(zip)) is RestoreResult.Committed)
        assertEquals(semantic(incoming), semantic(f.snapshots.read())); assertComplete()
        old.media.forEach { assertFalse(BackupFiles(f.root).file(it.relativePath).exists()) }
        val rollback = (service.preflightRollback() as BackupResult.Success).value
        importer.withValidated(rollback.token) { assertEquals(semantic(old), semantic(it.data)) }
        importer.discard(rollback.token)
        val destination = MemoryDestination()
        assertTrue(service.exportRollback(destination) is BackupResult.Success)
        assertTrue(destination.stream.size() > 0)
        assertTrue(service.restore(token(zip)) is RestoreResult.Committed)
        assertEquals(semantic(incoming), semantic(f.snapshots.read())); assertComplete()
        // The second restore's rollback is the first restored state, not the initial dataset.
        val latest = (service.preflightRollback() as BackupResult.Success).value
        assertTrue(service.restore(latest.token) is RestoreResult.Committed)
        assertEquals(semantic(incoming), semantic(f.snapshots.read())); assertComplete()
        assertEquals(1, File(f.root, "backup-rollback").listFiles()!!.count { it.extension == "zip" })
    }

    @Test fun rollbackActuallyRestoresPreviousContentAndPhotos() = runBlocking {
        val old = f.seedWithWork("旧", 480); g.seedWithWork("新", 600); val service = engine()
        assertTrue(service.restore(token(g.zip())) is RestoreResult.Committed)
        val rollback = (service.preflightRollback() as BackupResult.Success).value
        assertTrue(service.restore(rollback.token) is RestoreResult.Committed)
        assertEquals(semantic(old), semantic(f.snapshots.read())); assertComplete()
    }

    @Test fun exceptionMatrixPreservesOldBeforeCommitAndNewAfterCommit() = runBlocking {
        g.seedWithWork("新", 600); val zip = g.zip()
        val evidence = JSONArray()
        for (point in RestoreFaultPoint.entries) {
            val target = BackupFixture()
            try {
                val old = target.seedWithWork("旧", 480); val imp = BackupImporter(target.root, target.clock)
                val ready = (imp.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
                val service = RestoreEngine(target.db, target.writes, target.snapshots, target.exporter, imp,
                    target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE },
                    fault = { if (it == point) throw IOException("injected") })
                val result = service.restore(ready.token)
                if (afterCommit(point)) assertTrue(point.name, result is RestoreResult.CommittedCleanupPending)
                else assertTrue(point.name, result is RestoreResult.FailedBeforeCommit)
                val expected = if (afterCommit(point)) g.snapshots.read() else old
                assertEquals(point.name, semantic(expected), semantic(target.snapshots.read()))
                val fresh = RestoreEngine(target.db, target.writes, target.snapshots, target.exporter, imp,
                    target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE })
                assertTrue(fresh.recoverPending() is BackupResult.Success)
                assertFalse(File(target.root, "media-recovery.pending").exists())
                target.snapshots.read().media.forEach { BackupValidation.photo(BackupFiles(target.root).file(it.relativePath), it, BackupLimits()) }
                evidence.put(JSONObject().put("point", point.name).put("result", result.javaClass.simpleName).put("completeState", if (afterCommit(point)) "new" else "old"))
            } finally { target.close() }
        }
        File("../artifacts/t20/restore").mkdirs()
        File("../artifacts/t20/restore/exception-matrix.json").writeText(evidence.toString(2))
    }

    private class AbruptStop : Error()
    @Test fun interruptedOperationsReopenPersistentRoomAndReconcileAtEveryBoundary() = runBlocking {
        g.seedWithWork("新", 600); val zip = g.zip(); val incoming = g.snapshots.read(); val evidence = JSONArray()
        for (point in RestoreFaultPoint.entries) {
            val target = BackupFixture("restore-reopen-${id()}.db")
            var reopened: AppDatabase? = null
            try {
                val old = target.seedWithWork("旧", 480); val imp = BackupImporter(target.root, target.clock)
                val ready = (imp.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
                val service = RestoreEngine(target.db, target.writes, target.snapshots, target.exporter, imp,
                    target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE },
                    fault = { if (it == point) throw AbruptStop() })
                try { service.restore(ready.token); fail("fault not reached: $point") } catch (_: AbruptStop) { }
                assertTrue(File(target.root, "media-recovery.pending").exists())
                target.db.close()
                val open = Room.databaseBuilder(target.context, AppDatabase::class.java, target.databaseName!!)
                    .addCallback(AppDatabase.creationCallback(target.clock)).build()
                reopened = open
                val snapshots = SnapshotRepository(open); val writes = WriteCoordinator()
                val exporter = BackupExporter(snapshots, SettingsRepository(open, writes, target.clock), writes, target.root, target.clock, "test")
                val recovery = RestoreEngine(open, writes, snapshots, exporter, BackupImporter(target.root, target.clock),
                    target.root, target.clock, syncDirectory = {}, availableBytes = { Long.MAX_VALUE })
                assertTrue(recovery.recoverPending() is BackupResult.Success)
                assertEquals(point.name, semantic(if (afterCommit(point)) incoming else old), semantic(snapshots.read()))
                snapshots.read().media.forEach { BackupValidation.photo(BackupFiles(target.root).file(it.relativePath), it, BackupLimits()) }
                assertFalse(File(target.root, "media-recovery.pending").exists())
                evidence.put(JSONObject().put("point", point.name).put("databaseReopened", true).put("completeState", if (afterCommit(point)) "new" else "old"))
            } finally { reopened?.close(); target.close() }
        }
        File("../artifacts/t20/restore").mkdirs()
        File("../artifacts/t20/restore/reopen-matrix.json").writeText(evidence.toString(2))
    }

    @Test fun noSpaceAndInvalidTokenNeverStartReplacement() = runBlocking {
        val old = f.seedWithWork(); g.seedWithWork(); val zip = g.zip()
        val result = engine(space = 0).restore(token(zip)) as RestoreResult.FailedBeforeCommit
        assertEquals(BackupError.STORAGE_FULL, result.error)
        assertEquals(semantic(old), semantic(f.snapshots.read())); assertComplete()
        assertFalse(File(f.root, "media-recovery.pending").exists())
        val ready = token(zip); importer.discard(ready)
        assertTrue(engine().restore(ready) is RestoreResult.FailedBeforeCommit)
        assertEquals(semantic(old), semantic(f.snapshots.read()))
    }

    @Test fun writerWaitsThroughRestoreAndStartupGuardRunsBeforeOrdinaryWrites() = runBlocking {
        f.seedWithWork(); g.seedWithWork(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val service = engine({ if (it == RestoreFaultPoint.MEDIA_READY) { entered.complete(Unit); release.await() } })
        val restoring = async(Dispatchers.IO) { service.restore(token(g.zip())) }
        entered.await()
        val writing = async(Dispatchers.IO) { f.records.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "恢复后写入")) }
        delay(100); assertFalse(writing.isCompleted)
        release.complete(Unit); assertTrue(restoring.await() is RestoreResult.Committed)
        assertTrue(writing.await() is DataResult.Success); assertEquals(2, f.snapshots.read().meals.size)
        var checked = false
        val guarded = WriteCoordinator { checked = true; throw BackupException(BackupError.RECOVERY_REQUIRED) }
        try { guarded.withWrite { fail("writer passed barrier") }; fail() } catch (_: BackupException) { }
        assertTrue(checked)
    }

    @Test fun cancellationBeforeCommitPreservesOldAndCorruptRollbackIsRejected() = runBlocking {
        val old = f.seedWithWork(targetSleepMinutes = 480); g.seedWithWork(targetSleepMinutes = 600); val zip = g.zip()
        try { engine({ if (it == RestoreFaultPoint.MEDIA_READY) throw CancellationException() }).restore(token(zip)); fail() }
        catch (_: CancellationException) { }
        assertEquals(semantic(old), semantic(f.snapshots.read())); assertComplete()
        val service = engine(); assertTrue(service.restore(token(zip)) is RestoreResult.Committed)
        val pointer = JSONObject(File(f.root, "backup-rollback/latest.json").readText()).getString("restoreId")
        File(f.root, "backup-rollback/$pointer.zip").writeBytes(byteArrayOf(1))
        val before = semantic(f.snapshots.read())
        assertTrue(service.preflightRollback() is BackupResult.Failure)
        assertEquals(before, semantic(f.snapshots.read())); assertComplete()
    }

    @Test fun ordinaryRepositoryWriteReconcilesInterruptedRestoreAndReportsBlockedStorage() = runBlocking {
        val old = f.seedWithWork("旧"); val incoming = g.seedWithWork("新")
        val blocked = WriteCoordinator { throw BackupException(BackupError.RECOVERY_REQUIRED) }
        val blockedRecords = RecordRepository(f.db, blocked, f.clock) { java.time.ZoneId.of("UTC") }
        val failure = blockedRecords.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "应拦截")) as DataResult.Failure
        assertEquals(ErrorCode.STORAGE_UNAVAILABLE, failure.error.code)
        assertEquals(semantic(old), semantic(f.snapshots.read()))
        try { engine({ if (it == RestoreFaultPoint.AFTER_COMMIT) throw AbruptStop() }).restore(token(g.zip())); fail() }
        catch (_: AbruptStop) { }
        lateinit var resumed: RestoreEngine
        val guarded = WriteCoordinator { resumed.ensureRecovered() }
        val resumedImporter = BackupImporter(f.root, f.clock)
        val resumedExporter = BackupExporter(f.snapshots, SettingsRepository(f.db, guarded, f.clock), guarded, f.root, f.clock, "test")
        resumed = RestoreEngine(f.db, guarded, f.snapshots, resumedExporter, resumedImporter, f.root, f.clock,
            syncDirectory = {}, availableBytes = { Long.MAX_VALUE })
        val records = RecordRepository(f.db, guarded, f.clock) { java.time.ZoneId.of("UTC") }
        assertTrue(records.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "重启协调后保存")) is DataResult.Success)
        val actual = f.snapshots.read()
        assertEquals(2, actual.meals.size)
        assertTrue(actual.meals.any { it.id == incoming.meals.single().id })
        assertFalse(actual.meals.any { it.id == old.meals.single().id })
        assertFalse(File(f.root, "media-recovery.pending").exists()); assertComplete()
    }
}
