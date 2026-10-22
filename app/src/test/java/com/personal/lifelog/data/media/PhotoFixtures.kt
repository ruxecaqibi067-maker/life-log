package com.personal.lifelog.data.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic colors and EXIF only; no personal photos or location data. */
internal object PhotoFixtures {
    fun jpeg(width: Int = 120, height: Int = 80): ByteArray {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            val paint = Paint()
            val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
            colors.forEachIndexed { index, color ->
                paint.color = color
                val x = (index % 2) * width / 2f
                val y = (index / 2) * height / 2f
                canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
            }
            return ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.JPEG, 95, it))
                it.toByteArray()
            }
        } finally { image.recycle() }
    }

    /** Valid TIFF orientation and GPS IFD with deliberately fictitious coordinates (1, 2). */
    fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = ByteBuffer.allocate(140).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        tiff.putShort(2)
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(38).putInt(0)
        tiff.putShort(4)
        tiff.putShort(1).putShort(2).putInt(2).putInt('N'.code)
        tiff.putShort(2).putShort(5).putInt(3).putInt(92)
        tiff.putShort(3).putShort(2).putInt(2).putInt('E'.code)
        tiff.putShort(4).putShort(5).putInt(3).putInt(116).putInt(0)
        listOf(1, 0, 0, 2, 0, 0).forEach { tiff.putInt(it).putInt(1) }
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff.array()
        val length = payload.size + 2
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()) +
            payload + jpeg.copyOfRange(2, jpeg.size)
    }

    fun oversizedHeader(): ByteArray {
        val jpeg = jpeg()
        var index = 2
        while (index + 9 < jpeg.size) {
            val marker = jpeg[index + 1].toInt() and 255
            if (marker == 0xc0 || marker == 0xc2) {
                // 10001 x 10000 = 100,010,000 pixels, rejected before pixel allocation.
                jpeg[index + 5] = (10000 shr 8).toByte(); jpeg[index + 6] = 10000.toByte()
                jpeg[index + 7] = (10001 shr 8).toByte(); jpeg[index + 8] = 10001.toByte()
                return jpeg
            }
            val length = ((jpeg[index + 2].toInt() and 255) shl 8) or (jpeg[index + 3].toInt() and 255)
            index += 2 + length
        }
        error("JPEG SOF missing")
    }
}
