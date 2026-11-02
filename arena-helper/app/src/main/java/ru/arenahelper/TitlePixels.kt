package ru.arenahelper

/** Pure pixel transformation, shared by the Android OCR and JVM regression tests. */
object TitlePixels {
    fun prepare(pixels: IntArray, width: Int, height: Int, threshold: Int, curve: Float): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val output = IntArray(pixels.size) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            if (curve > 0f && (y < height * 0.23f || y >= height * 0.68f)) continue
            val u = x.toFloat() / width - 0.62f
            val sourceY = (y + (width * curve * u * u).toInt()).coerceIn(0, height - 1)
            val p = pixels[sourceY * width + x]
            val value = minOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255)
            output[y * width + x] = if (value > threshold) 0xff000000.toInt() else -1
        }
        return output
    }
}
