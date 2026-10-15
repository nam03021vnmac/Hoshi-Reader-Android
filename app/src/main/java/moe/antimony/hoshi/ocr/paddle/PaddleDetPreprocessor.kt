package moe.antimony.hoshi.ocr.paddle

import android.graphics.Bitmap

/** Detection network input: CHW float tensor plus the resize geometry. */
data class DetNetworkInput(
    val tensor: FloatArray,
    val height: Int,
    val width: Int,
    val originalWidth: Int,
    val originalHeight: Int
)

/**
 * Detection preprocessing from the official PP-OCRv6 Android SDK without
 * OpenCV: BGR extraction, limit-side scaling snapped to multiples of 32,
 * bilinear resize, and `(x / 255 - mean) / std` normalization to CHW.
 */
object PaddleDetPreprocessor {
    private val mean = doubleArrayOf(0.485, 0.456, 0.406)
    private val std = doubleArrayOf(0.229, 0.224, 0.225)

    const val LIMIT_SIDE_LEN = 64
    const val MAX_SIDE_LIMIT = 4000
    const val MAX_INPUT_PIXELS = 3_000_000

    fun preprocess(bitmap: Bitmap): DetNetworkInput {
        val originalW = bitmap.width
        val originalH = bitmap.height
        require(originalW > 0 && originalH > 0)
        val pixels = IntArray(originalW * originalH)
        bitmap.getPixels(pixels, 0, originalW, 0, 0, originalW, originalH)
        val bgr = ByteArray(originalW * originalH * 3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            bgr[i * 3] = (argb and 0xFF).toByte()
            bgr[i * 3 + 1] = ((argb shr 8) and 0xFF).toByte()
            bgr[i * 3 + 2] = ((argb shr 16) and 0xFF).toByte()
        }
        return preprocessBgr(bgr, originalW, originalH)
    }

    /** Bitmap-free entry point for tests and reuse of an extracted plane. */
    fun preprocessBgr(
        bgr: ByteArray,
        originalW: Int,
        originalH: Int,
        resize: PaddleDetResize = PaddleDetResize.Limited,
    ): DetNetworkInput {
        val (scaledH, scaledW) = when (resize) {
            PaddleDetResize.Limited -> limitedSize(originalW, originalH)
            is PaddleDetResize.LongSide -> longSideSize(originalW, originalH, resize.side)
        }
        val newH = maxOf(roundHalfToEven(scaledH / 32.0) * 32, 32)
        val newW = maxOf(roundHalfToEven(scaledW / 32.0) * 32, 32)

        val resized = if (newW == originalW && newH == originalH) {
            bgr
        } else {
            resizeBgrBilinear(bgr, originalW, originalH, newW, newH)
        }
        val channelSize = newW * newH
        val tensor = FloatArray(3 * channelSize)
        for (c in 0..2) {
            val offset = c * channelSize
            for (i in 0 until channelSize) {
                val value = (resized[i * 3 + c].toInt() and 0xFF) / 255.0
                tensor[offset + i] = ((value - mean[c]) / std[c]).toFloat()
            }
        }
        return DetNetworkInput(
            tensor,
            newH,
            newW,
            originalW,
            originalH
        )
    }

    private fun limitedSize(originalW: Int, originalH: Int): Pair<Double, Double> {
        var ratio = if (minOf(originalH, originalW) < LIMIT_SIDE_LEN) {
            LIMIT_SIDE_LEN.toDouble() / minOf(originalH, originalW)
        } else {
            1.0
        }
        var newH = (originalH * ratio).toInt()
        var newW = (originalW * ratio).toInt()
        if (maxOf(newH, newW) > MAX_SIDE_LIMIT) {
            ratio = MAX_SIDE_LIMIT.toDouble() / maxOf(newH, newW)
            newH = (newH * ratio).toInt()
            newW = (newW * ratio).toInt()
        }
        // High-detail retries retain source detail for crops without doubling detector work.
        if (newH.toLong() * newW > MAX_INPUT_PIXELS) {
            val pixelRatio = kotlin.math.sqrt(MAX_INPUT_PIXELS.toDouble() / (newH.toDouble() * newW))
            newH = (newH * pixelRatio).toInt()
            newW = (newW * pixelRatio).toInt()
        }
        return newH.toDouble() to newW.toDouble()
    }

    private fun longSideSize(originalW: Int, originalH: Int, side: Int): Pair<Double, Double> {
        val longSide = maxOf(originalW, originalH).toDouble()
        val ratio = if (longSide / minOf(originalW, originalH) > 2.0) {
            minOf(1.0, kotlin.math.sqrt(0.75 * side * side / (originalW.toDouble() * originalH)))
        } else {
            side / longSide
        }
        return originalH * ratio to originalW * ratio
    }
}
