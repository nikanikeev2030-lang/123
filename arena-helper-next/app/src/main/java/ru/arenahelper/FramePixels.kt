package ru.arenahelper
import java.nio.ByteBuffer

object FramePixels {
    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): IntArray? {
        if (width <= 0 || height <= 0 || pixelStride < 4 || rowStride.toLong() < width.toLong() * pixelStride) return null
        val required = (height - 1L) * rowStride + (width - 1L) * pixelStride + 4
        if (required > buffer.limit() || width.toLong() * height > 16_000_000L) return null
        val result = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * rowStride + x * pixelStride
            val r = buffer.get(i).toInt() and 255; val g = buffer.get(i + 1).toInt() and 255
            val b = buffer.get(i + 2).toInt() and 255; val a = buffer.get(i + 3).toInt() and 255
            result[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return result
    }
}
