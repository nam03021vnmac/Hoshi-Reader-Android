package moe.antimony.hoshi.ocr.pdf

import kotlin.math.sqrt

internal data class PdfOcrRasterPlan(val width: Int, val height: Int, val scale: Float) {
    companion object {
        private const val TARGET_DPI = 200
        private const val MAX_PIXELS = 3_000_000.0

        fun create(widthPt: Int, heightPt: Int): PdfOcrRasterPlan {
            require(widthPt > 0 && heightPt > 0)
            val scale = minOf(TARGET_DPI / 72.0, sqrt(MAX_PIXELS / (widthPt.toDouble() * heightPt)))
            return PdfOcrRasterPlan(
                (widthPt * scale).toInt().coerceAtLeast(1),
                (heightPt * scale).toInt().coerceAtLeast(1),
                scale.toFloat(),
            )
        }
    }
}
