package com.personal.lifelog.data.backup.format

import android.system.ErrnoException
import android.system.OsConstants
import com.personal.lifelog.domain.DataViolation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.security.MessageDigest

enum class BackupError { IO, STORAGE_FULL, ACCESS_DENIED, LIMIT, INVALID_ARCHIVE, UNSUPPORTED_VERSION,
    INVALID_DATA, HASH_MISMATCH, INVALID_PHOTO, EXPIRED_TOKEN, RECOVERY_REQUIRED, DATABASE }
class BackupException(val code: BackupError) : IOException(code.name)
sealed interface BackupResult<out T> {
    data class Success<T>(val value: T) : BackupResult<T>
    data class Failure(val error: BackupError) : BackupResult<Nothing>
}
fun fail(code: BackupError): Nothing = throw BackupException(code)
fun requireBackup(value: Boolean, code: BackupError = BackupError.INVALID_DATA) { if (!value) fail(code) }
fun backupError(t: Exception): BackupError = when (t) {
    is BackupException -> t.code
    is SecurityException -> BackupError.ACCESS_DENIED
    is DataViolation, is IllegalArgumentException, is org.json.JSONException -> BackupError.INVALID_DATA
    is android.database.sqlite.SQLiteFullException -> BackupError.STORAGE_FULL
    is android.database.sqlite.SQLiteException -> BackupError.DATABASE
    else -> if (generateSequence<Throwable>(t) { it.cause }.any {
        it is ErrnoException && it.errno == OsConstants.ENOSPC || it.message?.contains("ENOSPC") == true
    }) BackupError.STORAGE_FULL else BackupError.IO
}
suspend fun <T> backupOperation(block: suspend () -> T): BackupResult<T> = try {
    BackupResult.Success(block())
} catch (e: CancellationException) { throw e
} catch (e: Exception) { BackupResult.Failure(backupError(e)) }

data class BackupLimits(
    val zipBytes: Long = 2L * 1024 * 1024 * 1024,
    val expandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val dataBytes: Long = 32L * 1024 * 1024,
    val manifestBytes: Long = 4L * 1024 * 1024,
    val photoBytes: Long = 8L * 1024 * 1024,
    val entries: Int = 10002,
    val records: Int = 10000,
    val exercises: Int = 1000,
    val photos: Int = 10000,
    val itemsPerSession: Int = 100,
    val setsPerItem: Int = 100,
    val sets: Int = 100000,
    val imagePixels: Long = 100_000_000,
)
data class FileDigest(val path: String, val byteSize: Long, val sha256: String)
data class BackupManifest(val appVersion: String, val exportedAtUtcMs: Long,
    val counts: Map<String, Int>, val files: List<FileDigest>)

/** Actual bytes, never a ZIP header's claimed size. Also used for SAF copy and cancellation. */
suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        requireBackup(total <= limit, BackupError.LIMIT)
        output.write(buffer, 0, count)
    }
}
suspend fun digest(file: File, path: String = file.name): FileDigest {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            hash.update(buffer, 0, n)
        }
    }
    return FileDigest(path, file.length(), hash.digest().joinToString("") { "%02x".format(it) })
}

/** Private, generated paths only. Never follow symlinks, including during cleanup. */
class BackupFiles(root: File) {
    val root = root.canonicalFile
    fun file(path: String): File {
        val f = File(root, path).absoluteFile
        requireBackup(f == f.canonicalFile && f.toPath().startsWith(root.toPath()) && f != root,
            BackupError.INVALID_ARCHIVE)
        return f
    }
    fun mkdir(path: String): File = file(path).also { if (!it.isDirectory && !it.mkdirs()) throw IOException() }
    fun remove(file: File) {
        val checked = file(file.relativeTo(root).invariantSeparatorsPath)
        if (checked.isDirectory) (checked.listFiles() ?: throw IOException()).forEach { remove(it) }
        if (checked.exists() && !checked.delete()) throw IOException()
    }
}
