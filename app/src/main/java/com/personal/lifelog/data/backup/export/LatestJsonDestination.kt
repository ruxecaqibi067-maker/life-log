package com.personal.lifelog.data.backup.export

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

interface LatestJsonDestination {
    fun read(): String?
    fun write(json: String)
}

/** Owns only Download/lifelog/latest.json. All methods are called on Dispatchers.IO. */
class SharedLatestJsonDestination(context: Context) : LatestJsonDestination {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override fun read(): String? {
        if (Build.VERSION.SDK_INT >= 29) {
            val entry = findDownload() ?: return null
            if (entry.pending) return null
            return try {
                resolver.openInputStream(entry.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (_: FileNotFoundException) { null }
        }
        requireLegacyPermission()
        return legacyFile().takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }

    override fun write(json: String) {
        if (Build.VERSION.SDK_INT >= 29) writeDownload(json) else writeLegacy(json)
    }

    @RequiresApi(29)
    @Suppress("DEPRECATION") // Required for querying an interrupted pending row on API 29.
    private fun findDownload(): DownloadEntry? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Include our pending row, so an interrupted process reuses it on the next attempt.
        val query = MediaStore.setIncludePending(collection)
        resolver.query(query, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.IS_PENDING),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
            arrayOf("$RELATIVE_PATH/", FILE_NAME, context.packageName), null)?.use {
            if (it.moveToFirst()) return DownloadEntry(ContentUris.withAppendedId(collection, it.getLong(0)), it.getInt(1) != 0)
        }
        return null
    }

    @RequiresApi(29)
    private fun writeDownload(json: String) {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val existing = findDownload()
        val previous = read()
        val uri = existing?.uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: throw IOException("Cannot create latest JSON")
        try {
            // Providers may disambiguate a foreign-owned file to latest (1).json. Never accept that path.
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                check(it.moveToFirst() && it.getString(0) == FILE_NAME) { "Latest JSON name is occupied" }
            } ?: throw IOException("Cannot query latest JSON")
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }, null, null) == 1)
            // The complete JSON has already been serialized before making this row pending.
            writeContent(uri, json)
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null) == 1)
        } catch (e: Exception) {
            if (existing == null) {
                runCatching { resolver.delete(uri, null, null) }
            } else if (previous != null) {
                // Restore the last complete document on recoverable write failures.
                runCatching {
                    writeContent(uri, previous)
                    check(resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null) == 1)
                }
            }
            // If storage still refuses writes, leave the row pending for a later repair.
            throw e
        }
    }

    private fun writeContent(uri: android.net.Uri, json: String) {
        val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: throw IOException("Cannot open latest JSON")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {
            it.write(json.toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
    }

    private fun requireLegacyPermission() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED) { "Storage permission required" }
    }

    @Suppress("DEPRECATION")
    private fun legacyFile() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "lifelog/$FILE_NAME",
    )

    private fun writeLegacy(json: String) {
        requireLegacyPermission()
        val target = legacyFile()
        val directory = target.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create export directory")
        val temporary = File(directory, ".$FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(json.toByteArray(Charsets.UTF_8))
                it.flush()
                it.fd.sync()
            }
            // Same-directory rename replaces the old inode without a delete/truncate gap.
            if (!temporary.renameTo(target)) throw IOException("Cannot replace latest JSON")
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val RELATIVE_PATH = "Download/lifelog"
        const val FILE_NAME = "latest.json"
    }

    private data class DownloadEntry(val uri: android.net.Uri, val pending: Boolean)
}
