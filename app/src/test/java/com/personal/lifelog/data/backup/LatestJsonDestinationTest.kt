package com.personal.lifelog.data.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.backup.export.SharedLatestJsonDestination
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** MediaStore protocol checks. Real provider/path behavior is covered by LatestJsonDeviceTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class LatestJsonDestinationTest {
    private lateinit var provider: DownloadProvider
    private lateinit var destination: SharedLatestJsonDestination

    @Before fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider = DownloadProvider(File(context.cacheDir, "latest-json-provider"))
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "media"
            exported = true
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
        destination = SharedLatestJsonDestination(context)
    }

    @Test fun overwritesOneRowWithPendingThenPublishesCompleteUtf8() {
        destination.write("{\"text\":\"synthetic long content\"}")
        destination.write("{\"text\":\"中文\"}")
        assertEquals("{\"text\":\"中文\"}", destination.read())
        assertEquals(1, provider.inserts)
        assertFalse(provider.pending)
        assertTrue(provider.pendingWhileOpen.all { it })
        assertEquals("Download/lifelog", provider.relativePath)
    }

    @Test fun failureRestoresPreviousCompleteDocument() {
        destination.write("{\"old\":true}")
        provider.failNextWriteOpen = true
        assertThrows(FileNotFoundException::class.java) { destination.write("{\"new\":true}") }
        assertEquals("{\"old\":true}", destination.read())
        assertFalse(provider.pending)
    }

    @Test fun interruptedPendingRowIsRepairedAndReused() {
        destination.write("{\"old\":true}")
        provider.pending = true
        assertNull(destination.read())
        destination.write("{\"repaired\":true}")
        assertEquals("{\"repaired\":true}", destination.read())
        assertEquals(1, provider.inserts)
    }

    @Test fun failedFirstWriteDeletesIncompleteRow() {
        provider.failNextWriteOpen = true
        assertThrows(FileNotFoundException::class.java) { destination.write("{}") }
        assertFalse(provider.exists)
    }

    @Test fun filenameCollisionIsReportedAndNumberedFileIsRemoved() {
        provider.insertedName = "latest (1).json"
        assertThrows(IllegalStateException::class.java) { destination.write("{}") }
        assertFalse(provider.exists)
    }

    @Test fun filenameCollisionAfterPublicationIsReportedAndNumberedFileIsRemoved() {
        provider.publishedName = "latest (1).json"
        assertThrows(IllegalStateException::class.java) { destination.write("{}") }
        assertEquals("latest (1).json", provider.insertedName)
        assertFalse(provider.exists)
        assertEquals(1, provider.inserts)
    }

    private class DownloadProvider(private val file: File) : ContentProvider() {
        var exists = false
        var pending = false
        var inserts = 0
        var failNextWriteOpen = false
        var insertedName = "latest.json"
        var publishedName: String? = null
        var relativePath = ""
        val pendingWhileOpen = mutableListOf<Boolean>()
        private val uri = Uri.parse("content://media/external_primary/downloads/1")

        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts++
            exists = true
            pending = values!!.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1
            relativePath = values.getAsString(MediaStore.Downloads.RELATIVE_PATH)
            return this.uri
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) : Int {
            pending = values!!.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1
            if (!pending) publishedName?.let { insertedName = it }
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            exists = false
            file.delete()
            return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
            val columns = projection!!
            return MatrixCursor(columns).apply {
                if (exists) addRow(columns.map { column ->
                    when (column) {
                        MediaStore.Downloads._ID -> 1L
                        MediaStore.Downloads.IS_PENDING -> if (pending) 1 else 0
                        MediaStore.Downloads.DISPLAY_NAME -> insertedName
                        MediaStore.Downloads.RELATIVE_PATH -> relativePath
                        else -> error("Unexpected projection")
                    }
                })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode != "r" && failNextWriteOpen) {
                failNextWriteOpen = false
                throw FileNotFoundException("synthetic failure")
            }
            if (mode != "r") pendingWhileOpen.add(pending)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
    }
}
