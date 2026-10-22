package com.personal.lifelog.data.media

import android.util.AtomicFile
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.local.entity.MediaAsset
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal data class PhotoState(
    val ticket: PhotoTicket,
    val generationId: String,
    val createdAt: Long,
    val touchedAt: Long,
    val kind: String,
    val asset: MediaAsset? = null,
)

/** All paths are generated locally. Neither external URIs nor JSON can supply a filesystem path. */
internal class PhotoFiles(root: File) {
    val root: File = root.canonicalFile
    companion object {
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun isId(value: String) = UUID_PATTERN.matches(value)
        fun requireId(value: String): String = value.also {
            if (!isId(it)) throw PhotoException(PhotoError.INVALID_TICKET)
        }
    }

    val staging get() = child("photo-staging")
    val captures get() = child("photo-capture")
    val media get() = child("media")
    // T13 must create this durable barrier under WriteCoordinator BEFORE preparing restore generations,
    // and remove it only after its log/RestoreState reconciliation. T04 never clears it.
    val recoveryBarrier get() = child("media-recovery.pending")

    fun child(path: String): File {
        val candidate = File(root, path).absoluteFile
        val base = root.canonicalFile
        if (candidate.canonicalFile != candidate || !candidate.toPath().startsWith(base.toPath()) || candidate == base) {
            throw PhotoException(PhotoError.IO)
        }
        return candidate
    }

    fun directory(ticket: PhotoTicket) = child("photo-staging/${requireId(ticket.id)}")
    fun source(ticket: PhotoTicket) = child("photo-staging/${requireId(ticket.id)}/source")
    fun normalized(ticket: PhotoTicket) = child("photo-staging/${requireId(ticket.id)}/normalized.jpg")
    fun capture(ticket: PhotoTicket) = child("photo-capture/${requireId(ticket.id)}.jpg")
    fun formal(asset: MediaAsset): File {
        MediaRules.validate(asset)
        return child(MediaRules.relativePath(asset.generationId, asset.id))
    }

    fun mkdir(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw IOException("media directory unavailable")
    }

    private fun journal(ticket: PhotoTicket): AtomicFile {
        listOf("state.json", "state.json.bak", "state.json.new").forEach {
            child("photo-staging/${requireId(ticket.id)}/$it")
        }
        return AtomicFile(child("photo-staging/${ticket.id}/state.json"))
    }

    fun write(state: PhotoState) {
        mkdir(directory(state.ticket))
        val json = JSONObject().put("version", 1).put("id", state.ticket.id)
            .put("generation", state.generationId).put("created", state.createdAt)
            .put("touched", state.touchedAt).put("kind", state.kind)
        state.asset?.let {
            json.put("asset", JSONObject().put("width", it.width).put("height", it.height)
                .put("bytes", it.byteSize).put("sha256", it.sha256)
                .put("created", it.createdAtUtcMs).put("updated", it.updatedAtUtcMs))
        }
        val atomic = journal(state.ticket)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    fun read(ticket: PhotoTicket): PhotoState {
        requireId(ticket.id)
        try {
            val json = journal(ticket).openRead().use { input ->
                // Internal journals are tiny; don't let a damaged journal allocate arbitrary memory.
                val bytes = ByteArray(8193)
                var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                }
                if (total > 8192) throw PhotoException(PhotoError.INVALID_TICKET)
                JSONObject(String(bytes, 0, total, Charsets.UTF_8))
            }
            if (json.getInt("version") != 1 || json.getString("id") != ticket.id) {
                throw PhotoException(PhotoError.INVALID_TICKET)
            }
            val generation = requireId(json.getString("generation"))
            val kind = json.getString("kind")
            if (kind !in setOf("capture", "import")) throw PhotoException(PhotoError.INVALID_TICKET)
            val asset = json.optJSONObject("asset")?.let {
                MediaAsset(ticket.id, generation, MediaRules.relativePath(generation, ticket.id), "image/jpeg",
                    it.getInt("width"), it.getInt("height"), it.getLong("bytes"), it.getString("sha256"),
                    it.getLong("created"), it.getLong("updated")).also(MediaRules::validate)
            }
            return PhotoState(ticket, generation, json.getLong("created"), json.getLong("touched"), kind, asset)
        } catch (failure: java.io.FileNotFoundException) {
            throw PhotoException(PhotoError.INVALID_TICKET)
        } catch (failure: org.json.JSONException) {
            throw PhotoException(PhotoError.INVALID_TICKET)
        }
    }

    /** Delete only our fixed filenames; never recursively follow external/symlink content. */
    fun removeTicket(ticket: PhotoTicket): Int {
        var removed = 0
        val dir = directory(ticket)
        val paths = listOf(source(ticket), normalized(ticket), capture(ticket)) +
            listOf("state.json", "state.json.bak", "state.json.new").map { child("photo-staging/${ticket.id}/$it") }
        paths.forEach { if (delete(it)) removed++ }
        if (dir.isDirectory && dir.list()?.isEmpty() == true) delete(dir)
        return removed
    }

    fun delete(file: File): Boolean {
        // Revalidate at deletion; no recursive deletion and no unchecked external path.
        child(file.relativeTo(root).invariantSeparatorsPath)
        if (!file.exists()) return false
        if (!file.delete()) throw IOException("media cleanup pending")
        return true
    }

    fun children(directory: File): List<File> = if (!directory.exists()) emptyList() else
        directory.listFiles()?.toList() ?: throw IOException("media directory unreadable")
}
