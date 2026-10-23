package com.personal.lifelog.data.backup.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.DataResult
import kotlinx.coroutines.*
import java.io.*
import java.time.Clock
import java.util.UUID

interface BackupDestination {
    /** Must truncate/create. close failures are export failures. */
    fun open(): OutputStream
    fun deleteIncomplete()
}
class SafBackupDestination(private val resolver: ContentResolver, private val uri: Uri) : BackupDestination {
    override fun open(): OutputStream {
        requireBackup(uri.scheme == "content", BackupError.ACCESS_DENIED)
        return resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException()
    }
    override fun deleteIncomplete() { DocumentsContract.deleteDocument(resolver, uri) }
}
data class ExportReceipt(val exportedAtUtcMs: Long, val zipSha256: String, val bytes: Long,
    val timestampRecorded: Boolean)

class BackupExporter(
    private val snapshots: SnapshotRepository,
    private val settings: SettingsRepository,
    private val writes: WriteCoordinator,
    private val filesRoot: File,
    private val clock: Clock,
    private val appVersion: String,
    val archive: BackupArchive = BackupArchive(),
) {
    /** Caller holds the same write lease through this snapshot and every referenced-file copy. */
    internal suspend fun prepare(directory: File): File = archive.create(snapshots.read(),
        { BackupFiles(filesRoot).file(it) }, directory, appVersion, clock.millis())

    suspend fun export(destination: BackupDestination): BackupResult<ExportReceipt> = withContext(Dispatchers.IO) {
        backupOperation {
            writes.withExclusiveAccess {
                val files = BackupFiles(filesRoot)
                requireBackup(!files.file("media-recovery.pending").exists(), BackupError.RECOVERY_REQUIRED)
                val directory = files.mkdir("backup-export/${UUID.randomUUID()}")
                var complete = false
                try {
                    val zip = prepare(directory)
                    val hash = digest(zip)
                    destination.open().use { out -> zip.inputStream().use { copyBounded(it, out, archive.limits.zipBytes) }; out.flush() }
                    // No cancellation gap between successful destination close and recording the result.
                    withContext(NonCancellable) {
                        complete = true
                        val at = clock.millis()
                        val saved = settings.recordSuccessfulExport(at) is DataResult.Success
                        ExportReceipt(at, hash.sha256, hash.byteSize, saved)
                    }
                } finally {
                    withContext(NonCancellable) {
                        if (!complete) try { destination.deleteIncomplete() } catch (_: Exception) { /* provider may refuse deletion */ }
                        try { files.remove(directory) } catch (_: Exception) { /* next maintenance may retry */ }
                    }
                }
            }
        }
    }
}
