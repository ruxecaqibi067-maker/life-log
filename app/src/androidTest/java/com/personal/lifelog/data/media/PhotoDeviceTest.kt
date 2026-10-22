package com.personal.lifelog.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.ZoneId

/** Real decoder, FileProvider and directory fsync; synthetic inputs and isolated DB/files only. */
@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.systemUTC()
    private val databaseName = "t04-device-${RecordIds.newId()}.db"
    private val root = File(context.filesDir, "t04-device-${RecordIds.newId()}")
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var writes: WriteCoordinator
    private lateinit var photos: PhotoStorage
    private val gallery = File(context.cacheDir, "t04-gallery-${RecordIds.newId()}.jpg")

    @Before fun setup() {
        check(root.mkdirs())
        gallery.writeBytes(jpeg(120, 80))
        db = AppDatabase.create(context, clock, databaseName)
        wire()
    }

    private fun wire() {
        writes = WriteCoordinator()
        records = RecordRepository(db, writes, clock) { ZoneId.of("Asia/Shanghai") }
        photos = PhotoStorage(context, db, records, writes, clock, root = root,
            openSource = { gallery.inputStream() })
    }

    @After fun close() {
        db.close(); context.deleteDatabase(databaseName)
        root.deleteRecursively(); gallery.delete()
    }

    private fun <T> PhotoResult<T>.value(): T = (this as? PhotoResult.Success)?.value ?: error("Unexpected $this")
    private fun draft(id: String = RecordIds.newId()) = MealDraft(id, clock.millis(), "Asia/Shanghai")

    @Test fun realProviderRejectsOtherDirectoriesAndCameraReturnValidatesRealOutput() = runBlocking<Unit> {
        // Only this test's capture ticket is created in the provider's production directory.
        // Never run global cleanup against production files with this isolated test database.
        val camera = PhotoStorage(context, db, records, writes, clock)
        val request = camera.beginCapture().value()
        try {
            assertEquals(request, camera.resumeCapture(request.ticket).value())
            listOf(context.filesDir, File(context.filesDir, "media/test.jpg"),
                context.getDatabasePath("life-log.db"), context.cacheDir).forEach {
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(context, "${context.packageName}.photos", it)
                }
            }
            context.contentResolver.openOutputStream(request.uri)!!.use { it.write(gallery.readBytes()) }
            val prepared = camera.finishCapture(request.ticket, true).value()
            assertEquals(120, prepared.asset.width); assertEquals(80, prepared.asset.height)
            assertTrue(db.records().allMeals().isEmpty())
        } finally {
            assertEquals(PhotoResult.Cancelled, camera.finishCapture(request.ticket, false))
        }
    }

    @Test fun realDecoderRotatesLargeImageStripsExifAndRejectsPartialInput() {
        val original = jpeg(6000, 4000)
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1)
            .putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0).putInt(0).array()
        val exif = "Exif\u0000\u0000".toByteArray() + tiff
        val input = File(root, "rotated.jpg").apply {
            writeBytes(original.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) +
                exif + original.copyOfRange(2, original.size))
        }
        val output = File(root, "output.jpg")
        assertEquals(1365 to 2048, PhotoNormalizer().normalize(input, output))
        val decoded = BitmapFactory.decodeFile(output.path)
        assertTrue(Color.blue(decoded.getPixel(decoded.width / 4, decoded.height / 4)) > 200)
        decoded.recycle()
        assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
        input.writeBytes(original.copyOf(original.size / 2))
        assertEquals(PhotoError.INVALID_IMAGE,
            assertThrows(PhotoException::class.java) { PhotoNormalizer().normalize(input, output) }.error)
    }

    @Test fun actualFilesystemAndSqliteKeepOldReferencesOnFailureAndRetryAfterReopen() = runBlocking<Unit> {
        val uri = Uri.parse("content://synthetic-device-gallery/photo")
        val first = photos.importPhoto(uri).value()
        val meal = photos.saveMeal(draft(), first.ticket).value()
        photos.release(first.ticket).value()
        val second = photos.importPhoto(uri).value()
        assertTrue(gallery.delete())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_t04_device BEFORE UPDATE ON MealRecord BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertEquals(PhotoError.DATABASE, (photos.saveMeal(draft(meal.id), second.ticket) as PhotoResult.Failure).error)
        assertEquals(first.asset.id, records.meal(meal.id)!!.photoId)
        assertTrue(File(root, first.asset.relativePath).isFile)
        db.close(); db = AppDatabase.create(context, clock, databaseName); wire()
        photos.maintain(setOf(second.ticket)).value()
        assertEquals(second, photos.resumePrepared(second.ticket).value())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_t04_device")
        photos.saveMeal(draft(meal.id), second.ticket).value()
        photos.release(second.ticket).value()
        assertFalse(File(root, first.asset.relativePath).exists())
        assertEquals(second.asset.sha256, PhotoNormalizer.sha256(File(root, second.asset.relativePath)))
        assertEquals(1, db.records().allMeals().size)
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint()
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                paint.color = color
                val x = (index % 2) * width / 2f; val y = (index / 2) * height / 2f
                canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
            }
            return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
