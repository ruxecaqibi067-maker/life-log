package com.personal.lifelog.data.backup.format

import com.personal.lifelog.data.repository.DataSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.*
import java.util.zip.*

data class VerifiedArchive(val manifest: BackupManifest, val data: DataSnapshot, val directory: File)

/** Shared export self-check and import verification. No business DB or current-media writes. */
class BackupArchive(val limits: BackupLimits = BackupLimits()) {
    companion object {
        private val photoPath = Regex("media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        fun allowed(path: String) = path == "manifest.json" || path == "data.json" || photoPath.matches(path)
    }
    suspend fun create(snapshot: DataSnapshot, source: (String) -> File, directory: File,
        appVersion: String, exportedAt: Long): File {
        BackupValidation.validate(snapshot, limits)
        val files = BackupFiles(directory)
        files.mkdir("media")
        val data = files.file("data.json")
        val json = BackupJson.encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        requireBackup(json.size <= limits.dataBytes, BackupError.LIMIT)
        data.outputStream().use { it.write(json) }
        val entries = mutableListOf(digest(data, "data.json"))
        var expanded = data.length()
        requireBackup(expanded <= limits.expandedBytes, BackupError.LIMIT)
        snapshot.media.forEach { asset ->
            val path = "media/${asset.id}.jpg"
            val target = files.file(path)
            source(asset.relativePath).inputStream().use { input ->
                target.outputStream().use { expanded += copyBounded(input, it, minOf(limits.photoBytes, limits.expandedBytes - expanded)) }
            }
            BackupValidation.photo(target, asset, limits)
            entries += digest(target, path)
        }
        val manifest = BackupJson.encodeManifest(BackupManifest(appVersion, exportedAt, BackupValidation.counts(snapshot), entries))
            .toString().toByteArray(Charsets.UTF_8)
        requireBackup(manifest.size <= limits.manifestBytes && entries.size + 1 <= limits.entries, BackupError.LIMIT)
        files.file("manifest.json").writeBytes(manifest)
        requireBackup(entries.sumOf { it.byteSize } + manifest.size <= limits.expandedBytes, BackupError.LIMIT)
        val archive = files.file("backup.zip")
        FileOutputStream(archive).use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                (listOf("manifest.json") + entries.map { it.path }).forEach { path ->
                    zip.putNextEntry(ZipEntry(path))
                    files.file(path).inputStream().use { copyBounded(it, zip, limits.expandedBytes) }
                    zip.closeEntry()
                }
                zip.finish(); zip.flush(); raw.fd.sync()
            }
        }
        requireBackup(archive.length() <= limits.zipBytes, BackupError.LIMIT)
        // Re-read the generated archive rather than trusting in-memory JSON or source files.
        verify(archive, files.mkdir("self-check"))
        files.remove(files.file("self-check"))
        return archive
    }

    suspend fun verify(archive: File, directory: File): VerifiedArchive {
        requireBackup(archive.length() <= limits.zipBytes, BackupError.LIMIT)
        checkCentralDirectory(archive)
        val files = BackupFiles(directory)
        files.mkdir("media")
        val found = linkedMapOf<String, FileDigest>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = iterator.nextElement()
                requireBackup(allowed(entry.name) && !entry.isDirectory && !found.containsKey(entry.name), BackupError.INVALID_ARCHIVE)
                requireBackup(found.size < limits.entries, BackupError.LIMIT)
                val limit = when (entry.name) {
                    "manifest.json" -> limits.manifestBytes
                    "data.json" -> limits.dataBytes
                    else -> limits.photoBytes
                }
                val file = files.file(entry.name)
                val crc = CRC32()
                val count = zip.getInputStream(entry).use { input ->
                    CheckedInputStream(input, crc).use { checked ->
                        file.outputStream().use { copyBounded(checked, it, minOf(limit, limits.expandedBytes - total)) }
                    }
                }
                total += count
                requireBackup(entry.crc == crc.value && count == entry.size, BackupError.INVALID_ARCHIVE)
                found[entry.name] = digest(file, entry.name)
            }
        }
        requireBackup("manifest.json" in found && "data.json" in found, BackupError.INVALID_ARCHIVE)
        val manifestJson = BackupJson.read(files.file("manifest.json"))
        var manifest = BackupJson.decodeManifest(manifestJson)
        requireBackup(manifest.appVersion.length in 1..100)
        requireBackup(manifest.files.map { it.path }.distinct().size == manifest.files.size)
        requireBackup(manifest.files.map { it.path }.toSet() == found.keys - "manifest.json", BackupError.HASH_MISMATCH)
        manifest.files.forEach { requireBackup(it == found[it.path], BackupError.HASH_MISMATCH) }
        val dataJson = BackupJson.read(files.file("data.json"))
        requireBackup(manifestJson.int("formatVersion") == dataJson.int("dataVersion"))
        val data = BackupJson.decode(dataJson)
        BackupValidation.validate(data, limits)
        if (manifestJson.int("formatVersion") < 7) manifest = manifest.copy(counts = manifest.counts + ("mealPhotos" to data.mealPhotos.size))
        requireBackup(manifest.counts == BackupValidation.counts(data))
        requireBackup(found.keys == (data.media.map { "media/${it.id}.jpg" } + listOf("manifest.json", "data.json")).toSet())
        data.media.forEach { BackupValidation.photo(files.file("media/${it.id}.jpg"), it, limits) }
        return VerifiedArchive(manifest, data, directory)
    }

    /** Java ZipFile omits Unix file modes. Inspect them to reject links and ZIP64/multi-volume ambiguity. */
    private fun checkCentralDirectory(file: File) {
        RandomAccessFile(file, "r").use { r ->
            fun u16(): Int = r.readUnsignedByte() or (r.readUnsignedByte() shl 8)
            fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
            val length = r.length()
            requireBackup(length >= 22, BackupError.INVALID_ARCHIVE)
            var end = length - 22
            var found = false
            while (end >= maxOf(0, length - 65557)) {
                r.seek(end)
                if (u32() == 0x06054b50L) { r.seek(end + 20); if (end + 22 + u16() == length) { found = true; break } }
                end--
            }
            requireBackup(found, BackupError.INVALID_ARCHIVE)
            r.seek(end + 4)
            requireBackup(u16() == 0 && u16() == 0, BackupError.INVALID_ARCHIVE)
            val onDisk = u16(); val count = u16()
            requireBackup(onDisk == count && count != 65535, BackupError.INVALID_ARCHIVE)
            requireBackup(count <= limits.entries, BackupError.LIMIT)
            val size = u32(); val offset = u32()
            requireBackup(offset + size == end, BackupError.INVALID_ARCHIVE)
            r.seek(offset)
            repeat(count) {
                val start = r.filePointer
                requireBackup(u32() == 0x02014b50L, BackupError.INVALID_ARCHIVE)
                r.seek(start + 8)
                val flags = u16(); val method = u16()
                requireBackup(flags and 1 == 0 && method in listOf(0, 8), BackupError.INVALID_ARCHIVE)
                r.seek(start + 28)
                val name = u16(); val extra = u16(); val comment = u16(); val disk = u16()
                r.seek(start + 38)
                val attrs = u32(); val localOffset = u32()
                val kind = ((attrs shr 16).toInt() and 0xf000)
                requireBackup((kind == 0 || kind == 0x8000) && disk == 0 && localOffset < offset, BackupError.INVALID_ARCHIVE)
                requireBackup(start + 46 + name + extra + comment <= end, BackupError.INVALID_ARCHIVE)
                r.seek(start + 46 + name + extra + comment)
            }
            requireBackup(r.filePointer == end, BackupError.INVALID_ARCHIVE)
        }
    }
}
