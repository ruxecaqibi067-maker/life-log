package com.personal.lifelog.data.media

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.MealRecord
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.DataViolation
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.Clock

internal enum class PhotoFaultPoint { COPY_INPUT, NORMALIZED, BEFORE_PLACE, PLACED, BEFORE_DATABASE, COMMITTED, CLEANUP }

/** One application instance, sharing the exact WriteCoordinator used by repositories and backup. */
class PhotoStorage internal constructor(
    private val context: Context,
    private val db: AppDatabase,
    private val records: RecordRepository,
    private val writes: WriteCoordinator,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    root: File = context.filesDir,
    private val openSource: (Uri) -> InputStream = { uri ->
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
    },
    private val cameraUri: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    },
    private val syncDirectory: (File) -> Unit = ::syncMediaDirectory,
    private val fault: suspend (PhotoFaultPoint) -> Unit = {},
) {
    companion object {
        /** Abandoned drafts expire; IDs restored by the screen must be retained/touched first. */
        const val ABANDONED_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val files = PhotoFiles(root.absoluteFile)
    private val normalizer = PhotoNormalizer()
    // Sibling coroutines can inherit the same coordinator lease; serialize their file mutations too.
    private val mediaOperations = Mutex()

    /** Call on launch/resume with SavedStateHandle ticket IDs, before presenting drafts. */
    suspend fun maintain(retainedTickets: Set<PhotoTicket> = emptySet()): PhotoResult<PhotoCleanup> = operation {
        retainedTickets.forEach { ticket ->
            val state = files.read(ticket)
            files.write(state.copy(touchedAt = maxOf(clock.millis(), state.touchedAt)))
        }
        cleanup()
    }

    suspend fun beginCapture(): PhotoResult<CaptureRequest> = operation {
        val state = newState("capture")
        try {
            files.mkdir(files.captures)
            if (!files.capture(state.ticket).createNewFile()) throw IOException("capture collision")
            captureRequest(state.ticket)
        } catch (failure: Exception) {
            discardQuietly(state.ticket)
            throw failure
        }
    }

    /** Reconstruct exactly the same camera URI after Activity/process recreation. Does not launch it. */
    suspend fun resumeCapture(ticket: PhotoTicket): PhotoResult<CaptureRequest> = operation {
        val state = touch(ticket)
        if (state.kind != "capture" || state.asset != null || !files.capture(ticket).isFile) {
            throw PhotoException(PhotoError.INVALID_TICKET)
        }
        captureRequest(ticket)
    }

    /** false includes camera cancellation; a successful result still requires a real decodable file. */
    suspend fun finishCapture(ticket: PhotoTicket, succeeded: Boolean): PhotoResult<PreparedPhoto> = operation {
        val state = touch(ticket)
        if (state.kind != "capture") throw PhotoException(PhotoError.INVALID_TICKET)
        revokeCapture(ticket)
        if (!succeeded) {
            files.removeTicket(ticket)
            PhotoResult.Cancelled
        } else {
            PhotoResult.Success(state.asset?.let { prepared(state) } ?: normalize(state, files.capture(ticket)))
        }
    }.flatten()

    /** Copy immediately while the result grant is valid. No long-lived gallery permission is needed. */
    suspend fun importPhoto(uri: Uri?): PhotoResult<PreparedPhoto> {
        if (uri == null) return PhotoResult.Cancelled
        return operation {
            if (uri.scheme != "content") throw PhotoException(PhotoError.SOURCE_UNAVAILABLE)
            val state = newState("import")
            try {
                fault(PhotoFaultPoint.COPY_INPUT)
                openSource(uri).use { normalizer.copyInput(it, files.source(state.ticket)) }
                normalize(state, files.source(state.ticket))
            } catch (failure: Exception) {
                discardQuietly(state.ticket)
                throw failure
            }
        }
    }

    suspend fun resumePrepared(ticket: PhotoTicket): PhotoResult<PreparedPhoto> = operation { prepared(touch(ticket)) }

    /** Release on explicit cancel/remove, or after successful save. Referenced formal files survive. */
    suspend fun release(ticket: PhotoTicket): PhotoResult<PhotoCleanup> = operation {
        revokeCapture(ticket)
        files.removeTicket(ticket)
        cleanup()
    }

    /** A ticket replaces draft.photoId. Without a ticket the existing ID is retained/removed as supplied. */
    suspend fun saveMeal(draft: MealDraft, ticket: PhotoTicket? = null): PhotoResult<MealRecord> = operation {
        val state = ticket?.let { touch(it) }
        val asset = state?.asset ?: if (ticket != null) throw PhotoException(PhotoError.INVALID_TICKET) else null
        if (asset != null) {
            val destination = files.formal(asset)
            if (!destination.exists()) {
                verify(files.normalized(ticket!!), asset)
                files.mkdir(destination.parentFile!!)
                fault(PhotoFaultPoint.BEFORE_PLACE)
                // Same filesDir filesystem, unique UUID destination, protected by the shared writer lock.
                if (!files.normalized(ticket).renameTo(destination)) throw IOException("media placement failed")
                fault(PhotoFaultPoint.PLACED)
            }
            verify(destination, asset)
            syncDirectory(destination.parentFile!!)
            syncDirectory(files.media)
            syncDirectory(files.root)
        } else if (draft.photoId != null) {
            val existing = db.metadata().media(draft.photoId) ?: throw PhotoException(PhotoError.MISSING_PHOTO)
            verify(files.formal(existing), existing)
        }
        currentCoroutineContext().ensureActive()
        fault(PhotoFaultPoint.BEFORE_DATABASE)
        when (val result = records.saveMeal(if (asset == null) draft else draft.copy(photoId = asset.id), asset)) {
            is DataResult.Failure -> PhotoResult.Failure(PhotoError.DATABASE, result.error)
            is DataResult.Success -> {
                // Commit is the success boundary. Cleanup errors cannot turn a committed save into failure.
                afterCommit()
                PhotoResult.Success(result.value)
            }
        }
    }.flatten()

    /** Atomic ordered append/edit. Tickets are immutable prepared files; existing photos are not removed before commit. */
    suspend fun saveMealPhotos(draft: MealDraft, tickets: List<PhotoTicket>): PhotoResult<MealRecord> = operation {
        val ids = draft.photoIds ?: throw PhotoException(PhotoError.INVALID_TICKET)
        if (ids.size > 9 || ids.distinct().size != ids.size || tickets.distinct().size != tickets.size || tickets.any { it.id !in ids }) {
            throw PhotoException(PhotoError.INVALID_TICKET)
        }
        val prepared = tickets.map { ticket -> prepared(touch(ticket)) }
        ids.forEach { id ->
            val photo = prepared.find { it.asset.id == id }
            if (photo == null) {
                val existing = db.metadata().media(id) ?: throw PhotoException(PhotoError.MISSING_PHOTO)
                verify(files.formal(existing), existing)
            } else {
                val asset = photo.asset
                val destination = files.formal(asset)
                if (!destination.exists()) {
                    verify(files.normalized(photo.ticket), asset)
                    files.mkdir(destination.parentFile!!)
                    fault(PhotoFaultPoint.BEFORE_PLACE)
                    if (!files.normalized(photo.ticket).renameTo(destination)) throw IOException("media placement failed")
                    fault(PhotoFaultPoint.PLACED)
                }
                verify(destination, asset)
                syncDirectory(destination.parentFile!!)
            }
        }
        if (prepared.isNotEmpty()) { syncDirectory(files.media); syncDirectory(files.root) }
        currentCoroutineContext().ensureActive()
        fault(PhotoFaultPoint.BEFORE_DATABASE)
        when (val result = records.saveMealPhotos(draft.copy(photoId = ids.firstOrNull()), prepared.map { it.asset })) {
            is DataResult.Failure -> PhotoResult.Failure(PhotoError.DATABASE, result.error)
            is DataResult.Success -> { afterCommit(); PhotoResult.Success(result.value) }
        }
    }.flatten()

    suspend fun deleteMeal(id: String): PhotoResult<Boolean> = operation {
        when (val result = records.deleteMeal(id)) {
            is DataResult.Failure -> PhotoResult.Failure(PhotoError.DATABASE, result.error)
            is DataResult.Success -> {
                afterCommit()
                PhotoResult.Success(result.value)
            }
        }
    }.flatten()

    /** UI image libraries may load this private URI. UI never constructs file paths or reads DAOs. */
    suspend fun photoUri(photoId: String): PhotoResult<Uri> = operation {
        PhotoFiles.requireId(photoId)
        val asset = db.metadata().media(photoId) ?: throw PhotoException(PhotoError.MISSING_PHOTO)
        val file = files.formal(asset)
        verify(file, asset)
        Uri.fromFile(file)
    }

    suspend fun previewUri(ticket: PhotoTicket): PhotoResult<Uri> = operation {
        val photo = prepared(touch(ticket))
        val formal = files.formal(photo.asset)
        Uri.fromFile(if (formal.exists()) formal else files.normalized(ticket))
    }

    private fun newState(kind: String): PhotoState {
        val now = clock.millis()
        return PhotoState(PhotoTicket(RecordIds.newId()), RecordIds.newId(), now, now, kind).also(files::write)
    }

    private fun touch(ticket: PhotoTicket): PhotoState = files.read(ticket).let {
        it.copy(touchedAt = maxOf(clock.millis(), it.touchedAt)).also(files::write)
    }

    private fun captureRequest(ticket: PhotoTicket) = CaptureRequest(ticket, cameraUri(files.capture(ticket)))

    private fun revokeCapture(ticket: PhotoTicket) {
        // Use only locally generated IDs; never revoke arbitrary input URIs.
        if (!files.capture(ticket).exists()) return
        context.revokeUriPermission(captureRequest(ticket).uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private suspend fun normalize(state: PhotoState, source: File): PreparedPhoto {
        val output = files.normalized(state.ticket)
        val (width, height) = normalizer.normalize(source, output)
        currentCoroutineContext().ensureActive()
        fault(PhotoFaultPoint.NORMALIZED)
        val now = clock.millis()
        val asset = MediaAsset(state.ticket.id, state.generationId,
            "media/${state.generationId}/${state.ticket.id}.jpg", "image/jpeg", width, height,
            output.length(), PhotoNormalizer.sha256(output), now, now)
        val ready = state.copy(asset = asset, touchedAt = maxOf(clock.millis(), state.touchedAt))
        files.write(ready)
        // Once READY is durable, the source (including all metadata) is no longer needed.
        files.delete(source)
        return PreparedPhoto(state.ticket, asset)
    }

    private fun prepared(state: PhotoState): PreparedPhoto {
        val asset = state.asset ?: throw PhotoException(PhotoError.INVALID_TICKET)
        val formal = files.formal(asset)
        verify(if (formal.exists()) formal else files.normalized(state.ticket), asset)
        return PreparedPhoto(state.ticket, asset)
    }

    private fun verify(file: File, asset: MediaAsset) {
        if (!file.isFile || file.length() != asset.byteSize || PhotoNormalizer.sha256(file) != asset.sha256) {
            throw PhotoException(PhotoError.MISSING_PHOTO)
        }
    }

    private suspend fun afterCommit() = withContext(NonCancellable) {
        try {
            fault(PhotoFaultPoint.COMMITTED)
            cleanup()
        } catch (_: Exception) {
            // Includes damaged journals/permission/query failures. A committed save stays successful.
            // Retry through maintain; no raw exceptions or record content are logged.
        } catch (_: OutOfMemoryError) {
            // Optional cleanup may be deferred even if its allocation fails after the DB commit.
        }
    }

    private suspend fun discardQuietly(ticket: PhotoTicket) = withContext(NonCancellable) {
        try { files.removeTicket(ticket) } catch (_: IOException) { /* maintain retries */ }
    }

    private suspend fun cleanup(): PhotoCleanup {
        checkRecovery()
        fault(PhotoFaultPoint.CLEANUP)
        var deleted = 0
        var expired = 0
        val protectedPaths = mutableSetOf<String>()
        files.children(files.staging).filter { PhotoFiles.isId(it.name) }.forEach { dir ->
            files.child("photo-staging/${dir.name}")
            val ticket = PhotoTicket(dir.name)
            val state = try { files.read(ticket) } catch (bad: PhotoException) {
                // Partial journal from a killed first write: reap only after the same grace period.
                if (clock.millis() - dir.lastModified() > ABANDONED_AFTER_MS) null else throw bad
            }
            val age = clock.millis() - (state?.touchedAt ?: 0)
            if (state == null || age > ABANDONED_AFTER_MS) {
                revokeCapture(ticket)
                deleted += files.removeTicket(ticket)
                expired++
            } else state.asset?.let { protectedPaths += it.relativePath }
        }
        // Query/delete rows before deleting files. RESTRICT and the shared lock protect all live refs.
        var rows = 0
        db.metadata().unreferencedMedia().filter { it.relativePath !in protectedPaths }.forEach {
            rows += db.metadata().deleteUnreferencedMedia(it.id)
        }
        val retained = db.metadata().allMedia().mapTo(protectedPaths) { it.relativePath }
        files.children(files.media).filter { PhotoFiles.isId(it.name) }.forEach { generation ->
            files.child("media/${generation.name}")
            files.children(generation).forEach { photo ->
                if (photo.name.endsWith(".jpg") && PhotoFiles.isId(photo.name.removeSuffix(".jpg"))) {
                    val path = "media/${generation.name}/${photo.name}"
                    if (path !in retained && files.delete(files.child(path))) deleted++
                }
            }
            if (generation.list()?.isEmpty() == true) files.delete(generation)
        }
        // Capture files can outlive a partially created ticket. Never touch a live ticket's camera file.
        files.children(files.captures).forEach { photo ->
            val id = photo.name.removeSuffix(".jpg")
            if (photo.name.endsWith(".jpg") && PhotoFiles.isId(id) &&
                !files.directory(PhotoTicket(id)).exists() && clock.millis() - photo.lastModified() > ABANDONED_AFTER_MS) {
                revokeCapture(PhotoTicket(id))
                if (files.delete(files.capture(PhotoTicket(id)))) deleted++
            }
        }
        return PhotoCleanup(deleted, rows, expired)
    }

    private fun checkRecovery() {
        if (files.recoveryBarrier.exists()) throw PhotoException(PhotoError.RECOVERY_REQUIRED)
    }

    private suspend fun <T> operation(block: suspend () -> T): PhotoResult<T> = withContext(io) {
        try {
            writes.withExclusiveAccess {
                mediaOperations.withLock {
                    checkRecovery()
                    PhotoResult.Success(block())
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PhotoException) {
            PhotoResult.Failure(failure.error)
        } catch (failure: DataViolation) {
            PhotoResult.Failure(PhotoError.INVALID_TICKET, failure.error)
        } catch (_: android.database.sqlite.SQLiteFullException) {
            PhotoResult.Failure(PhotoError.STORAGE_FULL)
        } catch (_: SQLiteException) {
            PhotoResult.Failure(PhotoError.DATABASE)
        } catch (_: SecurityException) {
            PhotoResult.Failure(PhotoError.SOURCE_UNAVAILABLE)
        } catch (_: FileNotFoundException) {
            PhotoResult.Failure(PhotoError.SOURCE_UNAVAILABLE)
        } catch (failure: IOException) {
            PhotoResult.Failure(if (isNoSpace(failure)) PhotoError.STORAGE_FULL else PhotoError.IO)
        } catch (failure: ErrnoException) {
            PhotoResult.Failure(if (failure.errno == OsConstants.ENOSPC) PhotoError.STORAGE_FULL else PhotoError.IO)
        } catch (_: OutOfMemoryError) {
            PhotoResult.Failure(PhotoError.RESOURCE_LIMIT)
        }
    }

    private fun isNoSpace(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .filterIsInstance<ErrnoException>().any { it.errno == OsConstants.ENOSPC }

    private fun <T> PhotoResult<PhotoResult<T>>.flatten(): PhotoResult<T> = when (this) {
        is PhotoResult.Success -> value
        is PhotoResult.Failure -> PhotoResult.Failure(error, dataError)
        PhotoResult.Cancelled -> PhotoResult.Cancelled
    }
}

private fun syncMediaDirectory(directory: File) {
    val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
