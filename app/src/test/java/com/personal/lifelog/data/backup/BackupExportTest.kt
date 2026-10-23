package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.export.*
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.*
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupExportTest {
    private lateinit var f: BackupFixture
    @Before fun before() { f = BackupFixture() }
    @After fun after() { f.close() }

    @Test fun completePortableSnapshotAndFixture() = runBlocking {
        val original = f.seed()
        val target = MemoryDestination()
        val result = f.exporter.export(target) as BackupResult.Success
        assertTrue(result.value.timestampRecorded)
        val zip = File(f.root, "result.zip").apply { writeBytes(target.stream.toByteArray()) }
        val checked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("verify"))
        assertEquals(original.meals, checked.data.meals)
        assertEquals(original.exercises, checked.data.exercises)
        assertEquals(original.sets, checked.data.sets)
        assertEquals(original.sleeps, checked.data.sleeps)
        assertEquals(original.settings, checked.data.settings)
        assertFalse(target.deleted)
        ZipFile(zip).use { archive ->
            val data = archive.getInputStream(archive.getEntry("data.json")).reader().readText()
            assertFalse(data.contains(f.root.path)); assertFalse(data.contains("generationId")); assertFalse(data.contains("relativePath"))
        }
        val artifact = File("../artifacts/t11/synthetic-backup.zip").apply { parentFile!!.mkdirs() }
        zip.copyTo(artifact, overwrite = true)
        File(artifact.parentFile, "synthetic-backup.sha256").writeText(digest(zip).sha256)
    }

    @Test fun streamCloseFailureAndNoSpaceNeverRecordSuccess() = runBlocking {
        f.seed(); val before = f.snapshots.read()
        for (message in listOf("ENOSPC", "write failed", "close failed")) {
            var deleted = false
            val bad = object : BackupDestination {
                override fun open(): OutputStream = object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) { if (message != "close failed") throw IOException(message); super.write(b, off, len) }
                    override fun close() { if (message == "close failed") throw IOException(message) }
                }
                override fun deleteIncomplete() { deleted = true }
            }
            val failure = f.exporter.export(bad) as BackupResult.Failure
            assertEquals(if (message == "ENOSPC") BackupError.STORAGE_FULL else BackupError.IO, failure.error)
            assertTrue(deleted); assertEquals(before, f.snapshots.read())
            assertEquals(0, File(f.root, "backup-export").listFiles()!!.size)
        }
        assertTrue(f.exporter.export(MemoryDestination()) is BackupResult.Success)
    }

    @Test fun cancellationReleasesWriterAndDeletesPartialDestination() = runBlocking {
        f.seed(); var deleted = false
        val destination = object : BackupDestination {
            override fun open(): OutputStream = object : OutputStream() {
                override fun write(b: Int) { throw CancellationException() }
            }
            override fun deleteIncomplete() { deleted = true }
        }
        try { f.exporter.export(destination); fail() } catch (_: CancellationException) { }
        assertTrue(deleted)
        assertTrue(f.exporter.export(MemoryDestination()) is BackupResult.Success)
    }

    @Test fun concurrentDeletionWaitsUntilExportHasClosed() = runBlocking {
        val old = f.seed(); val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val destination = object : BackupDestination {
            val bytes = ByteArrayOutputStream()
            override fun open(): OutputStream { entered.complete(Unit); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); return bytes }
            override fun deleteIncomplete() { }
        }
        val exporting = async(Dispatchers.IO) { f.exporter.export(destination) }
        entered.await()
        val deleting = async(Dispatchers.IO) { f.records.deleteMeal(old.meals.single().id) }
        delay(100); assertFalse(deleting.isCompleted)
        release.countDown(); assertTrue(exporting.await() is BackupResult.Success); deleting.await()
        val zip = File(f.root, "concurrent.zip").apply { writeBytes(destination.bytes.toByteArray()) }
        val checked = BackupArchive().verify(zip, BackupFiles(f.root).mkdir("concurrent"))
        assertEquals(old.meals, checked.data.meals); assertEquals(1, checked.data.media.size)
    }

    @Test fun missingPhotoAndExportLimitsStopBeforeDestinationWrite() = runBlocking {
        f.seed()
        val small = BackupExporter(f.snapshots, f.settings, f.writes, f.root, f.clock, "test", BackupArchive(BackupLimits(records = 2)))
        assertEquals(BackupError.LIMIT, (small.export(MemoryDestination()) as BackupResult.Failure).error)
        val media = f.snapshots.read().media.single(); BackupFiles(f.root).file(media.relativePath).delete()
        assertTrue(f.exporter.export(MemoryDestination()) is BackupResult.Failure)
        assertNull(f.db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
    }
}
