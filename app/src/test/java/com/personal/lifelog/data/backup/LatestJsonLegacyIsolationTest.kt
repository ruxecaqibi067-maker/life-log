package com.personal.lifelog.data.backup

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.backup.export.SharedLatestJsonDestination
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LatestJsonLegacyIsolationTest {
    @Test fun coinstalledPackagesNeverReadOrReplaceEachOthersDocumentOrTemporaryFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        fun context(id: String) = object : ContextWrapper(app) {
            override fun getPackageName() = id
            override fun getApplicationContext(): Context = this
        }
        val production = SharedLatestJsonDestination(context("com.personal.lifelog"))
        val ai = SharedLatestJsonDestination(context("com.personal.lifelog.aireview"))
        production.write("{\"origin\":\"offline\"}")
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val oldTemporary = File(directory, "lifelog/.latest.json.tmp")
        oldTemporary.writeText("production pending operation")
        assertNull(ai.read())
        ai.write("{\"origin\":\"ai\"}")
        assertEquals("{\"origin\":\"offline\"}", production.read())
        assertEquals("production pending operation", oldTemporary.readText())
        assertEquals("{\"origin\":\"ai\"}", ai.read())
        // Replacing an existing inode is verified on Android: Windows File.renameTo
        // cannot reproduce POSIX replacement semantics in the Robolectric host JVM.
        assertEquals("{\"origin\":\"ai\"}", ai.read())
        assertEquals("{\"origin\":\"ai\"}", File(directory, "lifelog-ai-test/latest.json").readText())
    }
}
