package com.personal.lifelog.data.backup.restore

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.backup.format.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class RestorePhase { PREPARING_ROLLBACK, ROLLBACK_READY, PREPARING_MEDIA, MEDIA_READY, DATABASE_COMMITTED }
enum class RestoreFaultPoint { ROLLBACK_START, ROLLBACK_READY, GENERATION_START, PHOTO_PLACED,
    MEDIA_READY, BEFORE_DATABASE, IN_TRANSACTION, AFTER_COMMIT, CLEANUP }
internal data class RestoreJournal(val restoreId: String, val generationId: String,
    val oldGenerations: List<String>, val stagingId: String, val phase: RestorePhase)

internal class RestoreFiles(root: File, private val sync: (File) -> Unit) {
    val files = BackupFiles(root)
    private fun atomic(path: String) = AtomicFile(files.file(path)).also {
        files.file("$path.bak"); files.file("$path.new")
    }
    fun exists(path: String) = files.file(path).exists() || files.file("$path.bak").exists()
    fun write(path: String, json: JSONObject) {
        val file = files.file(path); file.parentFile!!.let { if (!it.isDirectory && !it.mkdirs()) throw IOException() }
        val atomic = atomic(path); val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        // Once finishWrite published the file, never call failWrite on its closed stream.
        sync(file.parentFile!!)
    }
    private fun read(path: String): JSONObject {
        val atomic = atomic(path)
        atomic.openRead().use { input ->
            val bytes = input.readBytesBounded(1024 * 1024)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    fun journal(): RestoreJournal? {
        if (!exists("media-recovery.pending")) return null
        val j = read("media-recovery.pending")
        requireBackup(j.getInt("version") == 1, BackupError.RECOVERY_REQUIRED)
        return RestoreJournal(Rules.id(j.getString("restoreId")), Rules.id(j.getString("generationId")),
            j.getJSONArray("oldGenerations").let { a -> (0 until a.length()).map { Rules.id(a.getString(it)) } },
            Rules.id(j.getString("stagingId")), RestorePhase.valueOf(j.getString("phase")))
    }
    fun journal(j: RestoreJournal) = write("media-recovery.pending", JSONObject().put("version", 1)
        .put("restoreId", j.restoreId).put("generationId", j.generationId).put("stagingId", j.stagingId)
        .put("oldGenerations", JSONArray(j.oldGenerations)).put("phase", j.phase.name))
    fun clearJournal() {
        atomic("media-recovery.pending").delete()
        requireBackup(!exists("media-recovery.pending"), BackupError.IO)
        sync(files.root)
    }
    fun latestRollbackId(): String? = if (exists("backup-rollback/latest.json"))
        Rules.id(read("backup-rollback/latest.json").getString("restoreId")) else null
    fun publishRollback(id: String) = write("backup-rollback/latest.json", JSONObject().put("restoreId", Rules.id(id)))
    fun rollback(id: String): File = files.file("backup-rollback/${Rules.id(id)}.zip")
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; requireBackup(out.size() + n <= limit, BackupError.RECOVERY_REQUIRED); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}

internal fun syncRestoreDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
