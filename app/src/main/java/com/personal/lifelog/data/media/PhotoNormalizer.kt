package com.personal.lifelog.data.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.core.graphics.createBitmap
import com.personal.lifelog.core.validation.Rules
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** API 28 decoder applies EXIF rotation AND mirroring, and scales before allocating pixels. */
internal class PhotoNormalizer {
    companion object {
        const val MAX_INPUT_BYTES = 100L * 1024 * 1024
        const val MAX_PIXELS = 100_000_000L
        const val MAX_EDGE = 2048
        const val JPEG_QUALITY = 85

        fun targetSize(width: Int, height: Int): Pair<Int, Int> {
            if (width <= 0 || height <= 0) throw PhotoException(PhotoError.INVALID_IMAGE)
            if (width.toLong() * height > MAX_PIXELS) throw PhotoException(PhotoError.PIXEL_LIMIT)
            val scale = minOf(1.0, MAX_EDGE.toDouble() / max(width, height))
            return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
        }

        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    hash.update(buffer, 0, read)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    /** Enforce actual streamed bytes, including providers with missing/incorrect size metadata. */
    suspend fun copyInput(input: InputStream, output: File, limit: Long = MAX_INPUT_BYTES) {
        FileOutputStream(output).use { destination ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw PhotoException(PhotoError.INPUT_TOO_LARGE)
                destination.write(buffer, 0, read)
            }
            destination.fd.sync()
            if (total == 0L) throw PhotoException(PhotoError.INVALID_IMAGE)
        }
    }

    fun normalize(source: File, output: File): Pair<Int, Int> {
        if (source.length() > MAX_INPUT_BYTES) throw PhotoException(PhotoError.INPUT_TOO_LARGE)
        if (source.length() == 0L) throw PhotoException(PhotoError.INVALID_IMAGE)
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                // The decoder checks the actual signature; URI MIME types and extensions are untrusted.
                if (!info.mimeType.startsWith("image/")) throw PhotoException(PhotoError.INVALID_IMAGE)
                val (width, height) = targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
        } catch (failure: ImageDecoder.DecodeException) {
            throw PhotoException(PhotoError.INVALID_IMAGE)
        }
        try {
            // Flatten transparent input onto white; compress creates fresh JPEG without source EXIF/GPS.
            val flattened = createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(decoded, 0f, 0f, null)
                }
                FileOutputStream(output).use {
                    if (!flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                        throw PhotoException(PhotoError.INVALID_IMAGE)
                    }
                    it.fd.sync()
                }
                if (output.length() !in 1..Rules.PHOTO_BYTES_LIMIT) {
                    throw PhotoException(PhotoError.OUTPUT_TOO_LARGE)
                }
                // Validate the encoded result, rather than trusting compress's boolean alone.
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(output.path, bounds)
                if (bounds.outMimeType != "image/jpeg" || bounds.outWidth != decoded.width || bounds.outHeight != decoded.height) {
                    throw PhotoException(PhotoError.INVALID_IMAGE)
                }
                return decoded.width to decoded.height
            } finally {
                flattened.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }
}
