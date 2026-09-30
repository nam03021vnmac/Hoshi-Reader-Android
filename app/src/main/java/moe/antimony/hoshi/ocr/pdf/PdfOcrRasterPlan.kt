package moe.antimony.hoshi.ocr.pdf

import kotlin.math.sqrt

internal data class PdfOcrRasterPlan(val width: Int, val height: Int, val scale: Float) {
    companion object {
        // Higher detail is opt-in, not a larger allocation for every scanned page.
        fun create(widthPt: Int, heightPt: Int, highRes: Boolean): PdfOcrRasterPlan {
            require(widthPt > 0 && heightPt > 0)
            val targetScale = (if (highRes) 300 else 200) / 72.0
            val maxPixels = if (highRes) 6_000_000.0 else 3_000_000.0
            val scale = minOf(targetScale, sqrt(maxPixels / (widthPt.toDouble() * heightPt)))
            return PdfOcrRasterPlan(
                (widthPt * scale).toInt().coerceAtLeast(1),
                (heightPt * scale).toInt().coerceAtLeast(1),
                scale.toFloat(),
            )
        }
    }
}
