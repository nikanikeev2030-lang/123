package ru.arenahelper

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

/** Tesseract is owned and called only by the capture worker. */
object OcrEngine {
    fun recognize(api: TessBaseAPI, crop: Bitmap, matcher: CardMatcher, onPass: ((String) -> Unit)? = null): Match? {
        val pixels = IntArray(crop.width * crop.height)
        crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
        val candidates = mutableListOf<Match>()
        // Hearthstone's name ribbon is curved; straighten it before text layout detection.
        val passes = listOf(140 to 0.20f, 140 to 0.15f, 160 to 0.20f, 120 to 0.20f, 140 to 0f, 180 to 0f)
        for ((threshold, curve) in passes) {
            val data = TitlePixels.prepare(pixels, crop.width, crop.height, threshold, curve)
            val binary = Bitmap.createBitmap(data, crop.width, crop.height, Bitmap.Config.ARGB_8888)
            val targetWidth = (crop.width * 4).coerceIn(600, 1200)
            val scaled = Bitmap.createScaledBitmap(binary, targetWidth, (crop.height * targetWidth / crop.width).coerceAtLeast(1), true)
            try {
                api.pageSegMode = if (curve > 0) TessBaseAPI.PageSegMode.PSM_SINGLE_LINE else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                api.setImage(scaled)
                val raw = api.utF8Text.orEmpty()
                onPass?.invoke("$threshold/$curve: $raw")
                val match = matcher.match(raw)
                if (match != null) candidates.add(match)
                if (match?.confidence == 1.0) return match
            } finally {
                api.clear()
                if (scaled !== binary) scaled.recycle()
                binary.recycle()
            }
        }
        val ranked = candidates.groupBy { it.card.name }.map { (_, matches) -> matches.maxBy { it.confidence } }.sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (ranked.getOrNull(1)?.let { best.confidence - it.confidence < 0.06 } == true) return null
        return best
    }
}
