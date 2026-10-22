package com.personal.lifelog.data.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoNormalizerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val normalizer = PhotoNormalizer()
    private fun normalize(bytes: ByteArray): File {
        val input = temporary.newFile().apply { writeBytes(bytes) }
        return temporary.newFile().also { normalizer.normalize(input, it) }
    }

    @Test fun ordinaryJpegKeepsDimensionsAndFreshEncodingIsReadable() {
        val output = normalize(PhotoFixtures.jpeg())
        val bitmap = BitmapFactory.decodeFile(output.path)
        assertEquals(120, bitmap.width); assertEquals(80, bitmap.height)
        assertTrue(output.length() in 1..8L * 1024 * 1024)
        assertEquals(64, PhotoNormalizer.sha256(output).length)
        bitmap.recycle()
    }

    @Test fun allEightExifTransformsCorrectPixelsAndRemoveGpsAndOrientation() {
        val r = Color.RED; val g = Color.GREEN; val b = Color.BLUE; val y = Color.YELLOW
        val expected = listOf(listOf(r,g,b,y), listOf(g,r,y,b), listOf(y,b,g,r), listOf(b,y,r,g),
            listOf(r,b,g,y), listOf(b,r,y,g), listOf(y,g,b,r), listOf(g,y,r,b))
        expected.forEachIndexed { index, colors ->
            val output = normalize(PhotoFixtures.withExif(PhotoFixtures.jpeg(), index + 1))
            val bitmap = BitmapFactory.decodeFile(output.path)
            assertEquals(if (index < 4) 120 else 80, bitmap.width)
            assertEquals(if (index < 4) 80 else 120, bitmap.height)
            val actual = listOf(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
            actual.zip(colors).forEach { (pixel, color) ->
                assertTrue("orientation ${index + 1}", kotlin.math.abs(Color.red(pixel)-Color.red(color)) < 35 &&
                    kotlin.math.abs(Color.green(pixel)-Color.green(color)) < 35 && kotlin.math.abs(Color.blue(pixel)-Color.blue(color)) < 35)
            }
            assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
            bitmap.recycle()
        }
    }

    @Test fun largePhotoIsDecodedTo2048EdgeAndAspectRatioIsPreserved() {
        val output = normalize(PhotoFixtures.jpeg(6000, 4000))
        val bitmap = BitmapFactory.decodeFile(output.path)
        assertEquals(2048, bitmap.width); assertEquals(1365, bitmap.height)
        bitmap.recycle()
    }

    @Test fun transparentPngBecomesWhiteJpeg() {
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
        }
        bitmap.recycle()
        val output = normalize(bytes)
        val decoded = BitmapFactory.decodeFile(output.path)
        assertEquals(Color.WHITE, decoded.getPixel(10, 15)); decoded.recycle()
    }

    @Test fun damagedAndNonImageBytesAreRejectedInsteadOfProducingPhotos() {
        listOf(byteArrayOf(), "not an image".toByteArray(), PhotoFixtures.jpeg().copyOf(100),
            PhotoFixtures.jpeg().let { it.copyOf(it.size / 2) }).forEach {
            val failure = assertThrows(PhotoException::class.java) { normalize(it) }
            assertEquals(PhotoError.INVALID_IMAGE, failure.error)
        }
    }

    @Test fun pixelBombIsRejectedAtHeaderAndTargetBoundariesAreInclusive() {
        assertEquals(2048 to 2048, PhotoNormalizer.targetSize(10000, 10000))
        assertEquals(2048 to 1, PhotoNormalizer.targetSize(100_000_000, 1))
        assertEquals(PhotoError.PIXEL_LIMIT,
            assertThrows(PhotoException::class.java) { normalize(PhotoFixtures.oversizedHeader()) }.error)
    }

    @Test fun streamLimitUsesActualBytesAndDoesNotTrustAvailable() = runBlocking<Unit> {
        val exact = temporary.newFile()
        normalizer.copyInput(ByteArrayInputStream(ByteArray(5)), exact, 5)
        assertEquals(5L, exact.length())
        val stream = object : ByteArrayInputStream(ByteArray(6)) { override fun available() = 0 }
        try { normalizer.copyInput(stream, temporary.newFile(), 5); fail("limit") }
        catch (failure: PhotoException) { assertEquals(PhotoError.INPUT_TOO_LARGE, failure.error) }
    }

    @Test fun real100MiBFileLimitIsCheckedBeforeDecode() {
        val source = temporary.newFile()
        RandomAccessFile(source, "rw").use { it.setLength(PhotoNormalizer.MAX_INPUT_BYTES + 1) }
        val error = assertThrows(PhotoException::class.java) { normalizer.normalize(source, temporary.newFile()) }
        assertEquals(PhotoError.INPUT_TOO_LARGE, error.error)
        RandomAccessFile(source, "rw").use { it.setLength(PhotoNormalizer.MAX_INPUT_BYTES) }
        // Exactly at the input limit reaches signature decoding and fails as a non-image.
        assertEquals(PhotoError.INVALID_IMAGE,
            assertThrows(PhotoException::class.java) { normalizer.normalize(source, temporary.newFile()) }.error)
    }
}
