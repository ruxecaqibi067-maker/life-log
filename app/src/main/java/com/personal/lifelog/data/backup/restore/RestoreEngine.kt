package com.personal.lifelog.data.backup.restore

import androidx.room.withTransaction
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.backup.export.*
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import kotlinx.coroutines.*
import java.io.*
import java.time.Clock
import java.util.UUID

sealed interface RestoreResult {
    data class FailedBeforeCommit(val error: BackupError) : RestoreResult
    data class Committed(val restoreId: String) : RestoreResult
    data class CommittedCleanupPending(val restoreId: String) : RestoreResult
    /** Commit marker could not be read. Preserve everything and require startup reconciliation. */
    data class RecoveryRequired(val error: BackupError) : RestoreResult
}
enum class RecoveryStatus { NO_PENDING_RESTORE, OLD_STATE_RETAINED, NEW_STATE_RETAINED }

class RestoreEngine(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val snapshots: SnapshotRepository,
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    filesRoot: File,
    private val clock: Clock,
    private val syncDirectory: (File) -> Unit = ::syncRestoreDirectory,
    private val availableBytes: (File) -> Long = { it.usableSpace },
    private val fault: suspend (RestoreFaultPoint) -> Unit = {},
) {
    private val storage = RestoreFiles(filesRoot, syncDirectory)
    private val files = storage.files
    private var startupCleaned = false

    /** Installed as WriteCoordinator.beforeWrite; already under that coordinator's lease. */
    internal suspend fun ensureRecovered() {
        recoverUnlocked()
        if (!startupCleaned) {
            importer.cleanAbandoned()
            cleanDirectory("backup-export")
            cleanRollbackWork()
            startupCleaned = true
        }
    }
    suspend fun recoverPending(): BackupResult<RecoveryStatus> = withContext(Dispatchers.IO) {
        backupOperation { writes.withExclusiveAccess { val result = recoverUnlocked(); ensureRecovered(); result } }
    }

    /** UI confirmation belongs to T14. Selecting/preflighting a document never calls this method. */
    suspend fun restore(token: StagingToken): RestoreResult = withContext(Dispatchers.IO) {
        try { writes.withExclusiveAccess { restoreLocked(token) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { RestoreResult.RecoveryRequired(backupError(e)) }
    }

    private suspend fun restoreLocked(token: StagingToken): RestoreResult {
        var journal: RestoreJournal? = null
        return try {
            ensureRecovered()
            importer.withValidated(token) { verified ->
                val old = snapshots.read()
                // Conservative peak: rollback copies + archive + self-check, incoming generation and reserve.
                val oldBytes = old.media.sumOf { it.byteSize } + BackupJson.encode(old).toString().toByteArray().size
                val needed = oldBytes * 4 + verified.data.media.sumOf { it.byteSize } + 32L * 1024 * 1024
                requireBackup(availableBytes(files.root) >= needed, BackupError.STORAGE_FULL)
                val restoreId = UUID.randomUUID().toString()
                val generation = UUID.randomUUID().toString()
                var current = RestoreJournal(restoreId, generation,
                    db.metadata().allMedia().map { it.generationId }.distinct(), token.id, RestorePhase.PREPARING_ROLLBACK)
                journal = current
                // Durable barrier precedes creation of every new generation and any destructive cleanup.
                storage.journal(current)
                fault(RestoreFaultPoint.ROLLBACK_START)
                createRollback(current)
                current = current.copy(phase = RestorePhase.ROLLBACK_READY); storage.journal(current)
                fault(RestoreFaultPoint.ROLLBACK_READY)
                current = current.copy(phase = RestorePhase.PREPARING_MEDIA); storage.journal(current)
                fault(RestoreFaultPoint.GENERATION_START)
                val newData = verified.data.copy(media = verified.data.media.map { asset ->
                    asset.copy(generationId = generation, relativePath = MediaRules.relativePath(generation, asset.id))
                })
                val destination = files.file("media/$generation")
                requireBackup(!destination.exists(), BackupError.IO)
                files.mkdir("media/$generation")
                for (asset in newData.media) {
                    currentCoroutineContext().ensureActive()
                    val source = BackupFiles(verified.directory).file("media/${asset.id}.jpg")
                    val target = files.file(asset.relativePath)
                    requireBackup(target.createNewFile(), BackupError.IO)
                    FileOutputStream(target).use { out ->
                        source.inputStream().use { copyBounded(it, out, exporter.archive.limits.photoBytes) }
                        out.fd.sync()
                    }
                    BackupValidation.photo(target, asset, exporter.archive.limits)
                    fault(RestoreFaultPoint.PHOTO_PLACED)
                }
                syncDirectory(destination); syncDirectory(files.file("media")); syncDirectory(files.root)
                current = current.copy(phase = RestorePhase.MEDIA_READY); storage.journal(current)
                fault(RestoreFaultPoint.MEDIA_READY)
                currentCoroutineContext().ensureActive()
                fault(RestoreFaultPoint.BEFORE_DATABASE)
                replaceDatabase(newData, restoreId)
                // From this point cancellation/cleanup failures must report a committed restore.
                withContext(NonCancellable) {
                    fault(RestoreFaultPoint.AFTER_COMMIT)
                    current = current.copy(phase = RestorePhase.DATABASE_COMMITTED); storage.journal(current)
                    cleanup(current, committed = true)
                }
            }
            importer.discard(token)
            RestoreResult.Committed(journal!!.restoreId)
        } catch (failure: Exception) {
            // Exceptions, including cancellation, reconcile against the DB marker, never the last log phase.
            withContext(NonCancellable) {
                try {
                    val active = journal
                    if (active == null) {
                        if (failure is CancellationException) throw failure
                        RestoreResult.FailedBeforeCommit(backupError(failure))
                    } else {
                        val committed = db.metadata().restoreState().lastCommittedRestoreId == active.restoreId
                        if (committed) RestoreResult.CommittedCleanupPending(active.restoreId)
                        else {
                            try { cleanup(active, committed = false) } catch (_: Exception) { /* durable barrier blocks writes until retry */ }
                            if (failure is CancellationException) throw failure
                            RestoreResult.FailedBeforeCommit(backupError(failure))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (uncertain: Exception) { RestoreResult.RecoveryRequired(backupError(uncertain)) }
            }
        }
    }

    private suspend fun createRollback(j: RestoreJournal) {
        val work = files.mkdir("backup-rollback/work-${j.restoreId}")
        val generated = exporter.prepare(work) // Complete archive self-check; no export-time settings write.
        val destination = storage.rollback(j.restoreId)
        requireBackup(destination.createNewFile(), BackupError.IO)
        FileOutputStream(destination).use { out ->
            generated.inputStream().use { copyBounded(it, out, exporter.archive.limits.zipBytes) }
            out.fd.sync()
        }
        requireBackup(digest(generated).copy(path = destination.name) == digest(destination), BackupError.HASH_MISMATCH)
        val check = files.mkdir("backup-rollback/check-${j.restoreId}")
        exporter.archive.verify(destination, check)
        syncDirectory(destination.parentFile!!)
        // Publish only after validation. Failure before this leaves the preceding valid rollback intact.
        storage.publishRollback(j.restoreId)
        files.remove(check); files.remove(work)
        cleanRollbackWork()
    }

    private suspend fun replaceDatabase(data: DataSnapshot, restoreId: String) = db.withTransaction {
        db.restore().clearMeals(); db.restore().clearSleeps(); db.restore().clearWorkRecords(); db.restore().clearSessions()
        db.restore().clearExercises(); db.restore().clearMedia()
        fault(RestoreFaultPoint.IN_TRANSACTION)
        data.media.forEach { db.metadata().insertMedia(it) }
        data.exercises.forEach { db.workouts().insertExercise(it) }
        data.meals.forEach { db.records().putMeal(it) }; data.sleeps.forEach { db.records().insertSleep(it) }
        data.workRecords.forEach { db.work().insertWork(it) }
        data.sessions.forEach { db.workouts().putSession(it) }
        db.workouts().insertItems(data.items); db.workouts().insertSets(data.sets)
        db.metadata().putSettings(data.settings)
        val old = db.metadata().restoreState()
        db.metadata().putRestoreState(old.copy(lastCommittedRestoreId = restoreId,
            updatedAtUtcMs = maxOf(clock.millis(), old.updatedAtUtcMs)))
    }

    private suspend fun recoverUnlocked(): RecoveryStatus {
        val j = storage.journal() ?: return RecoveryStatus.NO_PENDING_RESTORE
        val committed = db.metadata().restoreState().lastCommittedRestoreId == j.restoreId
        // Validate all currently referenced files before discarding anything else.
        snapshots.read().media.forEach { BackupValidation.photo(files.file(it.relativePath), it, exporter.archive.limits) }
        cleanup(j, committed)
        return if (committed) RecoveryStatus.NEW_STATE_RETAINED else RecoveryStatus.OLD_STATE_RETAINED
    }
    private suspend fun cleanup(j: RestoreJournal, committed: Boolean) {
        fault(RestoreFaultPoint.CLEANUP)
        val referenced = db.metadata().allMedia().map { it.generationId }.toSet()
        val discard = if (committed) j.oldGenerations else listOf(j.generationId)
        for (generation in discard) {
            if (generation !in referenced) files.remove(files.file("media/$generation"))
        }
        // Empty generations are irrelevant to a photograph-free restored dataset.
        if (committed && j.generationId !in referenced) files.remove(files.file("media/${j.generationId}"))
        files.remove(files.file("backup-import/${j.stagingId}"))
        files.remove(files.file("backup-rollback/work-${j.restoreId}"))
        files.remove(files.file("backup-rollback/check-${j.restoreId}"))
        cleanRollbackWork()
        if (files.file("media").exists()) syncDirectory(files.file("media"))
        storage.clearJournal()
    }

    private fun cleanDirectory(path: String) {
        val dir = files.file(path)
        if (dir.exists()) (dir.listFiles() ?: throw IOException()).forEach { files.remove(it) }
    }
    private fun cleanRollbackWork() {
        val latest = storage.latestRollbackId()
        val root = files.file("backup-rollback")
        if (root.exists()) (root.listFiles() ?: throw IOException()).forEach {
            if ((it.name.startsWith("work-") || it.name.startsWith("check-") || it.name.endsWith(".zip")) && it.name != "$latest.zip") files.remove(it)
        }
    }

    suspend fun preflightRollback(): BackupResult<PreparedBackup> = withContext(Dispatchers.IO) {
        try {
            writes.withExclusiveAccess {
                ensureRecovered()
                val latest = storage.latestRollbackId() ?: fail(BackupError.EXPIRED_TOKEN)
                importer.preflight(BackupSource { storage.rollback(latest).inputStream() })
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { BackupResult.Failure(backupError(e)) }
    }
    suspend fun exportRollback(destination: BackupDestination): BackupResult<FileDigest> = withContext(Dispatchers.IO) {
        var complete = false
        try {
            backupOperation {
                writes.withExclusiveAccess {
                    ensureRecovered()
                    val latest = storage.latestRollbackId() ?: fail(BackupError.EXPIRED_TOKEN)
                    val zip = storage.rollback(latest)
                    val check = files.mkdir("backup-rollback/check-${UUID.randomUUID()}")
                    try {
                        exporter.archive.verify(zip, check)
                        val hash = digest(zip)
                        destination.open().use { out -> zip.inputStream().use { copyBounded(it, out, exporter.archive.limits.zipBytes) }; out.flush() }
                        complete = true
                        hash
                    } finally { withContext(NonCancellable) { files.remove(check) } }
                }
            }
        } finally {
            if (!complete) withContext(NonCancellable) { try { destination.deleteIncomplete() } catch (_: Exception) { } }
        }
    }
}
