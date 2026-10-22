package com.personal.lifelog.data.media

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PhotoIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun oldAndroidUsesSingleImageOpenDocumentAndCancellationParsesAsNull() {
        val contract = PickMealPhoto()
        val intent = contract.createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("image/*", intent.type)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(Uri.parse("content://ignored"))))
        assertEquals(Uri.parse("content://selected"), contract.parseResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://selected"))))
    }

    @Test fun takePictureGrantsOnlyTheProvidedTemporaryUri() {
        val uri = Uri.parse("content://${context.packageName}.photos/capture/synthetic.jpg")
        val intent = TakeMealPhoto().createIntent(context, uri)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun providerManifestAndXmlExposeOnlyCaptureDirectory() {
        val authority = "${context.packageName}.photos"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        assertFalse(info.exported); assertTrue(info.grantUriPermissions)
        val parser = info.loadXmlMetaData(context.packageManager, "android.support.FILE_PROVIDER_PATHS")
        val paths = mutableListOf<Pair<String, String?>>()
        parser.use {
            while (it.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name != "paths") {
                    paths += it.name to it.getAttributeValue(null, "path")
                }
            }
        }
        assertEquals(listOf("files-path" to "photo-capture/"), paths)
    }
}
