package com.personal.lifelog.data.media

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class PhotoStorageTest {
    private class TestClock(var now: Long = 1_792_670_400_000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
    private class ProcessDeath : Error()
    private val clock = TestClock()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var writes: WriteCoordinator
    private lateinit var records: RecordRepository
    private lateinit var photos: PhotoStorage
    private lateinit var databaseName: String
    private lateinit var files: PhotoFiles
    private var bytes = byteArrayOf()
    private var faultPoint: PhotoFaultPoint? = null
    private var deathPoint: PhotoFaultPoint? = null
    private var opener: (() -> InputStream)? = null
    private var checkpoint: (suspend (PhotoFaultPoint) -> Unit)? = null
    private val uri = Uri.parse("content://synthetic-gallery/photo")
    private fun id() = RecordIds.newId()
    private fun meal(id: String = id()) = MealDraft(id, clock.millis(), "Asia/Shanghai")
    private fun <T> PhotoResult<T>.value(): T = (this as? PhotoResult.Success)?.value ?: error("Unexpected: $this")
    private fun failure(expected: PhotoError, result: PhotoResult<*>) = assertEquals(expected, (result as PhotoResult.Failure).error)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "t04-${id()}.db"
        files = PhotoFiles(context.filesDir)
        db = AppDatabase.create(context, clock, databaseName)
        bytes = PhotoFixtures.jpeg()
        wire()
    }

    private fun wire() {
        writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        photos = PhotoStorage(context, db, records, writes, clock, openSource = {
            opener?.invoke() ?: ByteArrayInputStream(bytes)
        }, cameraUri = { file ->
            // AndroidX FileProvider assumes '/' internally and cannot map Windows canonical paths.
            check(file.toPath().startsWith(files.captures.toPath()))
            Uri.parse("content://${context.packageName}.photos/capture/${file.name}")
        }, syncDirectory = {
            // ShadowLinux.open cannot fsync Windows directories. Device tests use the real adapter.
            check(it.isDirectory)
        }, fault = {
            checkpoint?.invoke(it)
            if (it == deathPoint) throw ProcessDeath()
            if (it == faultPoint) throw IOException("injected $it")
        })
    }

    private fun restart() {
        db.close()
        db = AppDatabase.create(context, clock, databaseName)
        wire()
    }

    @After fun close() {
        db.close(); context.deleteDatabase(databaseName)
        // Robolectric's synthetic application directory only; never used by device tests.
        listOf(files.staging, files.captures, files.media, files.recoveryBarrier).forEach { it.deleteRecursively() }
    }

    private suspend fun saved(): MediaAsset {
        val photo = photos.importPhoto(uri).value()
        photos.saveMeal(meal(), photo.ticket).value()
        photos.release(photo.ticket).value()
        return photo.asset
    }

    private suspend fun assertReferences(label: String) {
        val assets = db.metadata().allMedia().associateBy { it.id }
        val meals = db.records().allMeals()
        meals.mapNotNull { it.photoId }.forEach {
            val asset = assets[it] ?: error("Missing row: $it")
            assertTrue(files.formal(asset).isFile)
            assertEquals(asset.sha256, PhotoNormalizer.sha256(files.formal(asset)))
        }
        val paths = context.filesDir.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(context.filesDir).invariantSeparatorsPath
        }.sorted().toList()
        println("T04_EVIDENCE $label meals=${meals.size} media=${assets.size} liveMissing=0 files=$paths")
    }

    @Test fun sourceDeletionCacheClearingAndDatabaseReopenDoNotLoseSavedPhoto() = runBlocking<Unit> {
        val gallery = File(context.cacheDir, "synthetic-source.jpg").apply { writeBytes(bytes) }
        opener = { gallery.inputStream() }
        val photo = photos.importPhoto(uri).value()
        val stored = photos.saveMeal(meal(), photo.ticket).value()
        photos.release(photo.ticket).value()
        assertTrue(gallery.delete())
        context.cacheDir.deleteRecursively()
        opener = { throw SecurityException("revoked") }
        restart()
        photos.maintain().value()
        assertEquals(stored, records.meal(stored.id))
        assertTrue(File(photos.photoUri(photo.asset.id).value().path!!).isFile)
        assertReferences("source-deleted-cache-cleared-reopened")
    }

    @Test fun captureTicketAndExactUriSurviveRecreationAndCancelCreatesNoRecord() = runBlocking<Unit> {
        val first = photos.beginCapture().value()
        files.capture(first.ticket).writeBytes(bytes)
        restart()
        assertEquals(first, photos.resumeCapture(first.ticket).value())
        photos.maintain(setOf(first.ticket)).value()
        assertTrue(files.capture(first.ticket).exists())
        assertEquals(PhotoResult.Cancelled, photos.finishCapture(first.ticket, false))
        assertFalse(files.capture(first.ticket).exists()); assertFalse(files.directory(first.ticket).exists())
        assertTrue(db.records().allMeals().isEmpty())
    }

    @Test fun cameraReturnRequiresRealOutputAndPhotoAloneCanBeSaved() = runBlocking<Unit> {
        val capture = photos.beginCapture().value()
        failure(PhotoError.INVALID_IMAGE, photos.finishCapture(capture.ticket, true))
        files.capture(capture.ticket).writeBytes(bytes)
        val prepared = photos.finishCapture(capture.ticket, true).value()
        val record = photos.saveMeal(meal(), prepared.ticket).value()
        assertNull(record.text); assertEquals(prepared.asset.id, record.photoId)
        photos.release(capture.ticket).value()
        assertReferences("camera-return-synthetic-provider")
    }

    @Test fun pickerCancelAndBadImageNeverCreateRecordsAndLeaveNoInputFiles() = runBlocking<Unit> {
        assertEquals(PhotoResult.Cancelled, photos.importPhoto(null))
        bytes = "not a picture".toByteArray()
        failure(PhotoError.INVALID_IMAGE, photos.importPhoto(uri))
        assertTrue(db.records().allMeals().isEmpty())
        assertTrue(files.children(files.staging).isEmpty())
    }

    @Test fun interruptedCopyCleansPartialFilesAndKeepsExistingRecord() = runBlocking<Unit> {
        saved()
        opener = { object : InputStream() {
            var calls = 0
            override fun read(): Int = throw IOException("copy failed")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (calls++ > 0) throw IOException("copy failed")
                buffer[offset] = 1; return 1
            }
        } }
        failure(PhotoError.IO, photos.importPhoto(uri))
        assertTrue(files.children(files.staging).isEmpty())
        assertReferences("copy-failure")
    }

    @Test fun failedReplacementTransactionRetainsOldPhotoAndNewTicketCanBeRetried() = runBlocking<Unit> {
        val old = saved()
        val original = db.records().allMeals().single()
        val next = photos.importPhoto(uri).value()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_t04 BEFORE UPDATE ON MealRecord BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val draft = meal(original.id)
        failure(PhotoError.DATABASE, photos.saveMeal(draft, next.ticket))
        assertEquals(original, records.meal(original.id)); assertTrue(files.formal(old).exists())
        assertNull(db.metadata().media(next.asset.id)); assertTrue(files.formal(next.asset).exists())
        photos.maintain(setOf(next.ticket)).value()
        assertReferences("database-failure-old-retained-new-awaiting-reference")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_t04")
        photos.saveMeal(draft, next.ticket).value()
        photos.release(next.ticket).value()
        assertFalse(files.formal(old).exists()); assertNull(db.metadata().media(old.id))
        assertReferences("database-retry-replacement")
    }

    @Test fun failureBeforePlacementIsRetryableAndCannotChangeOldReferences() = runBlocking<Unit> {
        saved()
        val next = photos.importPhoto(uri).value()
        faultPoint = PhotoFaultPoint.BEFORE_PLACE
        failure(PhotoError.IO, photos.saveMeal(meal(), next.ticket))
        assertFalse(files.formal(next.asset).exists()); assertTrue(files.normalized(next.ticket).exists())
        assertReferences("placement-failure")
        faultPoint = null
        photos.saveMeal(meal(), next.ticket).value()
        photos.release(next.ticket).value()
        assertReferences("placement-retry")
    }

    @Test fun processDeathAfterPlacementRestartsFromDurableTicketWithoutMissingOldPhoto() = runBlocking<Unit> {
        val old = saved()
        val original = db.records().allMeals().single()
        val photo = photos.importPhoto(uri).value()
        deathPoint = PhotoFaultPoint.PLACED
        try { photos.saveMeal(meal(original.id), photo.ticket); fail("death") } catch (_: ProcessDeath) { }
        deathPoint = null; restart()
        photos.maintain(setOf(photo.ticket)).value()
        assertEquals(photo, photos.resumePrepared(photo.ticket).value())
        assertTrue(files.formal(old).exists()); assertTrue(files.formal(photo.asset).exists())
        assertReferences("recreated-before-db-commit")
        photos.saveMeal(meal(original.id), photo.ticket).value()
        photos.release(photo.ticket).value()
        assertFalse(files.formal(old).exists())
        assertReferences("recreated-save-retry")
    }

    @Test fun processDeathAfterDatabaseCommitKeepsNewReferenceAndCleansOnlyOldPhoto() = runBlocking<Unit> {
        val old = saved()
        val original = db.records().allMeals().single()
        val photo = photos.importPhoto(uri).value()
        deathPoint = PhotoFaultPoint.COMMITTED
        try { photos.saveMeal(meal(original.id), photo.ticket); fail("death") } catch (_: ProcessDeath) { }
        deathPoint = null; restart()
        assertEquals(photo.asset.id, records.meal(original.id)!!.photoId)
        photos.maintain(setOf(photo.ticket)).value()
        assertFalse(files.formal(old).exists()); assertTrue(files.formal(photo.asset).exists())
        // Repeating the same meal ID and ticket cannot create a duplicate.
        photos.saveMeal(meal(original.id), photo.ticket).value()
        assertEquals(1, db.records().allMeals().size)
        photos.release(photo.ticket).value()
        assertReferences("recreated-after-db-commit")
    }

    @Test fun cleanupFailureAfterCommitIsSuccessAndMaintenanceRetriesIt() = runBlocking<Unit> {
        val old = saved()
        val original = db.records().allMeals().single()
        val photo = photos.importPhoto(uri).value()
        faultPoint = PhotoFaultPoint.CLEANUP
        assertEquals(photo.asset.id, photos.saveMeal(meal(original.id), photo.ticket).value().photoId)
        assertTrue(files.formal(old).exists())
        assertReferences("committed-cleanup-pending")
        faultPoint = null
        photos.release(photo.ticket).value()
        assertFalse(files.formal(old).exists())
        assertReferences("cleanup-retried")
    }

    @Test fun sharedPhotoLivesUntilLastReferenceIsRemoved() = runBlocking<Unit> {
        val asset = saved()
        val first = db.records().allMeals().single()
        val second = photos.saveMeal(meal().copy(photoId = asset.id)).value()
        photos.deleteMeal(first.id).value()
        assertTrue(files.formal(asset).exists()); assertNotNull(db.metadata().media(asset.id))
        photos.deleteMeal(second.id).value()
        assertFalse(files.formal(asset).exists()); assertNull(db.metadata().media(asset.id))
        assertReferences("last-reference-deleted")
    }

    @Test fun explicitCancelAfterDatabaseFailureReclaimsOnlyUnreferencedNewFile() = runBlocking<Unit> {
        val old = saved()
        val photo = photos.importPhoto(uri).value()
        faultPoint = PhotoFaultPoint.BEFORE_DATABASE
        failure(PhotoError.IO, photos.saveMeal(meal(), photo.ticket))
        assertTrue(files.formal(photo.asset).exists())
        faultPoint = null
        photos.release(photo.ticket).value()
        assertFalse(files.formal(photo.asset).exists()); assertTrue(files.formal(old).exists())
        assertReferences("cancel-after-failed-save")
    }

    @Test fun abandonedTicketsExpireButRetainedCameraAndPreparedTicketsAreProtected() = runBlocking<Unit> {
        val saved = saved()
        val abandoned = photos.importPhoto(uri).value()
        val retained = photos.importPhoto(uri).value()
        val camera = photos.beginCapture().value()
        clock.now += PhotoStorage.ABANDONED_AFTER_MS + 1
        val cleaned = photos.maintain(setOf(retained.ticket, camera.ticket)).value()
        assertEquals(1, cleaned.expiredTickets)
        assertFalse(files.directory(abandoned.ticket).exists())
        assertEquals(retained, photos.resumePrepared(retained.ticket).value())
        assertEquals(camera, photos.resumeCapture(camera.ticket).value())
        assertTrue(files.formal(saved).exists())
        assertReferences("expired-versus-retained")
    }

    @Test fun restoreBarrierBlocksAllMediaMutationsAndGarbageCollection() = runBlocking<Unit> {
        val old = saved()
        val original = db.records().allMeals().single()
        val pending = photos.importPhoto(uri).value()
        files.recoveryBarrier.writeText("reserved for T13")
        failure(PhotoError.RECOVERY_REQUIRED, photos.maintain())
        failure(PhotoError.RECOVERY_REQUIRED, photos.saveMeal(meal(original.id), pending.ticket))
        failure(PhotoError.RECOVERY_REQUIRED, photos.deleteMeal(original.id))
        failure(PhotoError.RECOVERY_REQUIRED, photos.release(pending.ticket))
        assertTrue(files.formal(old).exists()); assertTrue(files.normalized(pending.ticket).exists())
        assertReferences("restore-barrier")
        assertTrue(files.recoveryBarrier.delete())
        photos.release(pending.ticket).value()
    }

    @Test fun existingFormalFileIsNeverOverwrittenEvenOnHashMismatch() = runBlocking<Unit> {
        saved()
        val pending = photos.importPhoto(uri).value()
        val formal = files.formal(pending.asset)
        files.mkdir(formal.parentFile!!); formal.writeText("collision")
        failure(PhotoError.MISSING_PHOTO, photos.saveMeal(meal(), pending.ticket))
        assertEquals("collision", formal.readText())
        assertReferences("immutable-file-collision")
    }

    @Test fun cancellationPropagatesAndCleansInputWithoutKeepingWriteLock() = runBlocking<Unit> {
        opener = { throw CancellationException("cancel import") }
        try { photos.importPhoto(uri); fail("cancel") } catch (_: CancellationException) { }
        assertTrue(files.children(files.staging).isEmpty())
        opener = null
        withTimeout(5000) { photos.release(photos.importPhoto(uri).value().ticket).value() }
    }

    @Test fun concurrentSameMealSaveAndNestedExclusiveOperationAreIdempotent() = runBlocking<Unit> {
        val photo = photos.importPhoto(uri).value()
        val draft = meal()
        coroutineScope { (1..5).map { async { photos.saveMeal(draft, photo.ticket).value() } }.awaitAll() }
        assertEquals(1, db.records().allMeals().size); assertEquals(1, db.metadata().allMedia().size)
        withTimeout(5000) { writes.withExclusiveAccess { photos.saveMeal(draft, photo.ticket).value() } }
        photos.release(photo.ticket).value()
        assertReferences("concurrent-retry")
    }

    @Test fun invalidIdsAndMissingSourcePermissionsReturnErrors() = runBlocking<Unit> {
        failure(PhotoError.INVALID_TICKET, photos.resumePrepared(PhotoTicket("../escape")))
        failure(PhotoError.INVALID_TICKET, photos.resumePrepared(PhotoTicket(id())))
        failure(PhotoError.SOURCE_UNAVAILABLE, photos.importPhoto(Uri.parse("file:///untrusted")))
        opener = { throw SecurityException("revoked") }
        failure(PhotoError.SOURCE_UNAVAILABLE, photos.importPhoto(uri))
        assertTrue(files.children(files.staging).isEmpty())
    }

    @Test fun siblingReleaseCannotDeleteFileBetweenVerificationAndCommitUnderInheritedLease() = runBlocking<Unit> {
        val photo = photos.importPhoto(uri).value()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        checkpoint = {
            if (it == PhotoFaultPoint.BEFORE_DATABASE) { entered.complete(Unit); proceed.await() }
        }
        withTimeout(5000) {
            writes.withExclusiveAccess {
                coroutineScope {
                    val saving = async { photos.saveMeal(meal(), photo.ticket).value() }
                    entered.await()
                    val releasing = async { photos.release(photo.ticket).value() }
                    delay(50)
                    assertFalse(releasing.isCompleted)
                    proceed.complete(Unit)
                    saving.await(); releasing.await()
                }
            }
        }
        assertReferences("inherited-lease-file-mutations-serialized")
    }

    @Test fun damagedUnrelatedJournalCannotTurnCommittedSaveIntoFailure() = runBlocking<Unit> {
        val damaged = photos.importPhoto(uri).value()
        val journal = File(files.directory(damaged.ticket), "state.json")
        val json = org.json.JSONObject(journal.readText())
        json.getJSONObject("asset").put("width", 0)
        journal.writeText(json.toString())
        val valid = photos.importPhoto(uri).value()
        val meal = photos.saveMeal(meal(), valid.ticket).value()
        assertEquals(valid.asset.id, records.meal(meal.id)!!.photoId)
        photos.release(damaged.ticket).value()
        photos.release(valid.ticket).value()
        assertReferences("damaged-journal-after-commit")
    }

    @Test fun storageFullIsDistinguishedAndDoesNotChangeExistingRecord() = runBlocking<Unit> {
        saved()
        val pending = photos.importPhoto(uri).value()
        checkpoint = {
            if (it == PhotoFaultPoint.BEFORE_PLACE) {
                throw IOException(android.system.ErrnoException("injected write", android.system.OsConstants.ENOSPC))
            }
        }
        failure(PhotoError.STORAGE_FULL, photos.saveMeal(meal(), pending.ticket))
        assertTrue(files.normalized(pending.ticket).exists())
        assertReferences("storage-full")
    }
}
