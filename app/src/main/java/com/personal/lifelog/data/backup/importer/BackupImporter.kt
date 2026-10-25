package com.personal.lifelog.data.backup.importer

import android.content.ContentResolver
import android.net.Uri
import com.personal.lifelog.data.backup.format.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.time.Clock
import java.util.UUID

fun interface BackupSource { fun open(): InputStream }
class SafBackupSource(private val resolver: ContentResolver, private val uri: Uri) : BackupSource {
    override fun open(): InputStream {
        requireBackup(uri.scheme == "content", BackupError.ACCESS_DENIED)
        return resolver.openInputStream(uri) ?: throw FileNotFoundException()
    }
}
/** Opaque, process-local capability. UI cannot supply paths or construct a valid registry entry. */
class StagingToken internal constructor(internal val id: String)
data class BackupPreview(val counts: Map<String, Int>, val earliestUtcMs: Long?, val latestUtcMs: Long?,
    val hasActiveSleep: Boolean, val exportedAtUtcMs: Long, val appVersion: String,
    val replacesAllRecordsAndSettings: Boolean = true,
    val hasActiveWork: Boolean = false,
    val targetSleepMinutes: Int? = null,
    val targetWorkMinutes: Int? = null,
    /** Includes deletion tombstones, which are restored to prevent automatic resurrection. */
    val todoCount: Int = 0,
    val habitCount: Int = 0,
    val earliestLocalDate: String? = null,
    val latestLocalDate: String? = null)
data class PreparedBackup(val token: StagingToken, val preview: BackupPreview, val expiresAtUtcMs: Long)

/** Only owns backup-import/<UUID>. No reference to a business DAO, repository, or current media writer. */
class BackupImporter(private val filesRoot: File, private val clock: Clock,
    private val archive: BackupArchive = BackupArchive()) {
    companion object { const val TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000 }
    private data class Entry(val token: StagingToken, val directory: File, val digest: FileDigest,
        val created: Long, val expires: Long)
    private val tokens = mutableMapOf<String, Entry>()
    private val mutex = Mutex()
    private val files = BackupFiles(filesRoot)

    suspend fun preflight(source: BackupSource): BackupResult<PreparedBackup> = withContext(Dispatchers.IO) {
        backupOperation {
            mutex.withLock {
                expire()
                val token = StagingToken(UUID.randomUUID().toString())
                val directory = files.mkdir("backup-import/${token.id}")
                var retained = false
                try {
                    val zip = File(directory, "source.zip")
                    source.open().use { input -> zip.outputStream().use { copyBounded(input, it, archive.limits.zipBytes) } }
                    val verified = archive.verify(zip, File(directory, "verified").apply { mkdirs() })
                    val now = clock.millis()
                    val times = verified.data.meals.map { it.occurredAtUtcMs } +
                        verified.data.sessions.map { it.occurredAtUtcMs } +
                        verified.data.sleeps.flatMap { listOfNotNull(it.startedAtUtcMs, it.endedAtUtcMs) } +
                        verified.data.workRecords.flatMap { listOfNotNull(it.startedAtUtcMs, it.endedAtUtcMs) }
                    val localDates = verified.data.todos.map { it.localDate } + verified.data.habits.map { it.startDate }
                    val preview = BackupPreview(verified.manifest.counts.toMap(), times.minOrNull(), times.maxOrNull(),
                        verified.data.sleeps.any { it.endedAtUtcMs == null }, verified.manifest.exportedAtUtcMs,
                        verified.manifest.appVersion,
                        hasActiveWork = verified.data.workRecords.any { it.endedAtUtcMs == null },
                        targetSleepMinutes = verified.data.settings.targetSleepMinutes,
                        targetWorkMinutes = verified.data.settings.targetWorkMinutes,
                        todoCount = verified.data.todos.size, habitCount = verified.data.habits.size,
                        earliestLocalDate = localDates.minOrNull(), latestLocalDate = localDates.maxOrNull())
                    val entry = Entry(token, directory, digest(zip), now, now + TOKEN_LIFETIME_MS)
                    tokens[token.id] = entry
                    retained = true
                    PreparedBackup(token, preview, entry.expires)
                } finally {
                    if (!retained) withContext(NonCancellable) { files.remove(directory) }
                }
            }
        }
    }

    suspend fun discard(token: StagingToken): BackupResult<Unit> = withContext(Dispatchers.IO) {
        backupOperation { mutex.withLock { tokens.remove(token.id)?.let { files.remove(it.directory) }; Unit } }
    }

    /** Lock out discard/expiry, rehash the staged source and re-run every check immediately before use. */
    internal suspend fun <T> withValidated(token: StagingToken, block: suspend (VerifiedArchive) -> T): T = mutex.withLock {
        val entry = tokens[token.id]?.takeIf { it.token === token && it.directory.isDirectory } ?: fail(BackupError.EXPIRED_TOKEN)
        if (clock.millis() !in entry.created until entry.expires) {
            tokens.remove(token.id); files.remove(entry.directory); fail(BackupError.EXPIRED_TOKEN)
        }
        val zip = File(entry.directory, "source.zip")
        requireBackup(digest(zip) == entry.digest, BackupError.HASH_MISMATCH)
        val verified = File(entry.directory, "verified")
        files.remove(verified); requireBackup(verified.mkdirs(), BackupError.IO)
        block(archive.verify(zip, verified))
    }

    /** Called at process startup before UI produces tokens. Retain live tokens in the current process. */
    internal suspend fun cleanAbandoned() = mutex.withLock {
        val root = files.file("backup-import")
        if (root.exists()) (root.listFiles() ?: throw IOException()).forEach {
            if (it.name !in tokens) files.remove(it)
        }
        expire()
    }
    private fun expire() {
        val expired = tokens.values.filter { clock.millis() !in it.created until it.expires }
        expired.forEach { tokens.remove(it.token.id); files.remove(it.directory) }
    }
}
