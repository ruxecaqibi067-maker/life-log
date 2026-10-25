package com.personal.lifelog.feature.settings

import android.app.Application
import com.personal.lifelog.data.backup.BackupFixture
import com.personal.lifelog.data.backup.MemoryDestination
import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.format.BackupArchive
import com.personal.lifelog.data.backup.format.BackupError
import com.personal.lifelog.data.backup.format.BackupFiles
import com.personal.lifelog.data.backup.format.BackupJson
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.data.backup.importer.BackupImporter
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.data.backup.restore.RecoveryStatus
import com.personal.lifelog.data.backup.restore.RestoreEngine
import com.personal.lifelog.data.backup.restore.RestoreFaultPoint
import com.personal.lifelog.data.backup.restore.RestoreResult
import com.personal.lifelog.data.repository.SleepSummaryRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.domain.TimelineEntry
import com.personal.lifelog.feature.home.HomeViewModel
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * Host-side checks for the settings backup state machine. They use the real T11
 * exporter, T12 importer and T13 engine with synthetic records and a real SQLite
 * file; the Compose screen is covered separately. SAF providers and real process
 * termination remain device checks (T15).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupRestoreControllerTest {

    private lateinit var f: BackupFixture
    private lateinit var g: BackupFixture
    private lateinit var importer: BackupImporter
    private lateinit var scope: CoroutineScope
    private lateinit var controller: BackupRestoreController

    @Before
    fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        f = BackupFixture()
        g = BackupFixture()
        importer = BackupImporter(f.root, f.clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = controller(engine())
    }

    @After
    fun after() {
        scope.cancel()
        Dispatchers.resetMain()
        f.close()
        g.close()
    }

    private fun engine(
        space: Long = Long.MAX_VALUE,
        fault: suspend (RestoreFaultPoint) -> Unit = {},
    ) = RestoreEngine(
        f.db,
        f.writes,
        f.snapshots,
        f.exporter,
        importer,
        f.root,
        f.clock,
        syncDirectory = {},
        availableBytes = { space },
        fault = fault,
    )

    private fun controller(engine: RestoreEngine) =
        BackupRestoreController(f.exporter, importer, engine, f.clock, scope)

    private fun source(zip: File) = BackupSource { zip.inputStream() }

    private suspend fun awaitState(
        target: BackupRestoreController = controller,
        predicate: (BackupRestoreState) -> Boolean,
    ): BackupRestoreState = withTimeout(15_000) { target.state.first(predicate) }

    private fun semantic(snapshot: com.personal.lifelog.data.repository.DataSnapshot): String =
        BackupJson.encode(snapshot).toString()

    @Test
    fun previewShowsCountsAndOnlyExplicitConfirmationReplacesData() = runBlocking {
        val old = f.seed("虚构旧记录")
        val incoming = g.seed("虚构新记录")
        val zip = g.zip()

        controller.selectBackup(source(zip))
        val preview = awaitState { it is BackupRestoreState.Preview } as BackupRestoreState.Preview
        assertEquals(incoming.meals.size, preview.prepared.preview.counts["meals"])
        assertEquals(1, preview.prepared.preview.counts["media"])
        assertTrue(preview.prepared.preview.hasActiveSleep)
        assertNotNull(preview.prepared.preview.earliestUtcMs)
        // Selection and preview never touch the live database.
        assertEquals(semantic(old), semantic(f.snapshots.read()))

        controller.confirmRestore()
        val result = awaitState { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertTrue(result.outcome is BackupOutcome.RestoreSucceeded)
        assertEquals(semantic(incoming), semantic(f.snapshots.read()))
        assertTrue(File(f.root, "backup-rollback").listFiles()!!.any { it.extension == "zip" })
        assertFalse(File(f.root, "media-recovery.pending").exists())
    }

    @Test
    fun cancellingPreviewLeavesDataUntouchedAndRemovesStaging() = runBlocking {
        val old = f.seed()
        g.seed()
        val zip = g.zip()

        controller.selectBackup(source(zip))
        awaitState { it is BackupRestoreState.Preview }
        val staging = File(f.root, "backup-import")
        assertTrue(staging.listFiles()!!.isNotEmpty())

        controller.dismissPreview()
        val result = awaitState { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertEquals(BackupOutcome.PreviewCancelled, result.outcome)
        assertEquals(semantic(old), semantic(f.snapshots.read()))
        assertTrue(!staging.exists() || staging.listFiles()!!.isEmpty())
    }

    @Test
    fun invalidArchiveIsRejectedWithoutTouchingData() = runBlocking {
        val old = f.seed()
        val bad = File(f.root, "bad-${System.nanoTime()}.zip").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        controller.selectBackup(source(bad))
        val result = awaitState { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertTrue((result.outcome as BackupOutcome.PreflightFailed).error in setOf(
            BackupError.INVALID_ARCHIVE,
            BackupError.INVALID_DATA,
        ))
        assertEquals(semantic(old), semantic(f.snapshots.read()))
        assertTrue(!File(f.root, "backup-import").exists() ||
            File(f.root, "backup-import").listFiles()!!.isEmpty())
    }

    @Test
    fun failureBeforeCommitReportsOldDataUnchanged() = runBlocking {
        val old = f.seed()
        g.seed()
        val zip = g.zip()
        val failing = controller(engine(space = 0))

        failing.selectBackup(source(zip))
        awaitState(failing) { it is BackupRestoreState.Preview }
        failing.confirmRestore()
        val result = awaitState(failing) { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertEquals(BackupError.STORAGE_FULL, (result.outcome as BackupOutcome.RestoreFailed).error)
        assertEquals(semantic(old), semantic(f.snapshots.read()))
    }

    @Test
    fun exportSuccessRecordsTimestamp() {
        runBlocking {
            f.seed()
            val destination = MemoryDestination()

            controller.export(destination)
            val result = awaitState { it is BackupRestoreState.Result } as BackupRestoreState.Result
            val success = result.outcome as BackupOutcome.ExportSucceeded
            assertTrue(success.timestampRecorded)
            assertNotNull(f.db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)

            val zip = File(f.root, "exported-${System.nanoTime()}.zip").apply {
                writeBytes(destination.stream.toByteArray())
            }
            BackupArchive().verify(zip, BackupFiles(f.root).mkdir("verify-${System.nanoTime()}"))
        }
    }

    @Test
    fun cancellingExportDeletesPartialDestinationAndNeverRecordsSuccess() = runBlocking {
        f.seed()
        assertNull(f.db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
        val destination = BlockingDestination()

        controller.export(destination)
        withTimeout(10_000) { destination.started.await() }
        controller.cancelCurrent()
        destination.release.complete(Unit)

        val result = awaitState { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertEquals(BackupOutcome.ExportCancelled, result.outcome)
        assertTrue(destination.deleted)
        assertNull(f.db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
    }

    @Test
    fun duplicateConfirmationStartsOnlyOneRestore() = runBlocking {
        f.seed()
        g.seed()
        val zip = g.zip()
        val starts = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controlled = controller(engine(fault = { point ->
            if (point == RestoreFaultPoint.ROLLBACK_START) {
                starts.incrementAndGet()
                entered.complete(Unit)
                release.await()
            }
        }))

        controlled.selectBackup(source(zip))
        awaitState(controlled) { it is BackupRestoreState.Preview }
        controlled.confirmRestore()
        withTimeout(10_000) { entered.await() }
        controlled.confirmRestore()
        release.complete(Unit)

        val result = awaitState(controlled) { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertTrue(result.outcome is BackupOutcome.RestoreSucceeded)
        assertEquals(1, starts.get())
    }

    @Test
    fun recreatedControllerQueriesCommittedButUncleanedJournal() = runBlocking {
        f.seed()
        g.seed()
        val raw = engine(fault = { point ->
            if (point == RestoreFaultPoint.AFTER_COMMIT) throw IOException("injected cleanup failure")
        })
        val ready = (importer.preflight(source(g.zip())) as BackupResult.Success).value
        assertTrue(raw.restore(ready.token) is RestoreResult.CommittedCleanupPending)
        assertTrue(File(f.root, "media-recovery.pending").exists())

        // A new page/process only has the durable journal; it must report the real
        // committed state and finish cleanup, never offer to undo committed data.
        val fresh = controller(raw)
        fresh.refreshRecoveryState()
        val result = awaitState(fresh) { it is BackupRestoreState.Result } as BackupRestoreState.Result
        assertEquals(
            RecoveryStatus.NEW_STATE_RETAINED,
            (result.outcome as BackupOutcome.RecoveryCompleted).status,
        )
        assertFalse(File(f.root, "media-recovery.pending").exists())
    }

    @Test
    fun completedRestoreEmitsNewTimelineToExistingHomeViewModel() = runBlocking {
        f.seed("虚构旧记录")
        g.seed("虚构新记录")
        val zip = g.zip()
        val home = HomeViewModel(f.clock, { ZoneId.of("UTC") }, TimelineRepository(f.records, f.workouts), SleepSummaryRepository(f.db)::observe, f.settings::setTargetSleepMinutes)
        withTimeout(10_000) {
            home.timeline.first { state ->
                state.entries.any { it is TimelineEntry.Meal && it.text == "虚构旧记录" }
            }
        }

        controller.selectBackup(source(zip))
        awaitState { it is BackupRestoreState.Preview }
        controller.confirmRestore()
        awaitState { it is BackupRestoreState.Result }

        val updated = withTimeout(10_000) {
            home.timeline.first { state ->
                state.entries.any { it is TimelineEntry.Meal && it.text == "虚构新记录" }
            }
        }
        assertTrue(updated.entries.any { it is TimelineEntry.Meal && it.text == "虚构新记录" })
        assertFalse(updated.entries.any { it is TimelineEntry.Meal && it.text == "虚构旧记录" })
    }

    private class BlockingDestination : BackupDestination {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        @Volatile var deleted = false

        override fun open(): OutputStream = object : OutputStream() {
            private var blocked = false
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!blocked) {
                    blocked = true
                    started.complete(Unit)
                    runBlocking { release.await() }
                }
            }
        }

        override fun deleteIncomplete() {
            deleted = true
        }
    }
}
