package moe.antimony.hoshi.ocr

import android.graphics.Bitmap

/** Reading direction evidence for a raw OCR line. */
enum class OcrDirection {
    HORIZONTAL,
    VERTICAL,
    UNKNOWN,
}

/** Quad in engine-pixel coordinates (bitmap space). */
data class OcrPixelQuad(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
)

data class OcrRawSymbol(
    val text: String,
    val quad: OcrPixelQuad?,
    val confidence: Float?,
)

data class OcrRawLine(
    val id: String,
    val text: String,
    val quad: OcrPixelQuad?,
    val direction: OcrDirection,
    val confidence: Float?,
    val symbols: List<OcrRawSymbol> = emptyList(),
)

data class OcrRawResult(
    val lines: List<OcrRawLine>,
    val imageWidth: Int,
    val imageHeight: Int,
)

/** Engine outcome distinguishes empty success from failure/unavailable. */
sealed interface OcrRecognizeOutcome {
    data class Success(val result: OcrRawResult) : OcrRecognizeOutcome
    data class Failure(val reason: String, val retryable: Boolean = true) : OcrRecognizeOutcome
    data class Unavailable(val reason: String) : OcrRecognizeOutcome
}

/**
 * Backend interface. Implementations reuse a recognizer/session and never
 * create one per Composable or line. Cancellation of the calling coroutine
 * does not imply native inference has stopped; adapters retain permits
 * until completion.
 */
interface OcrEngine {
    val engineId: String
    val engineVersion: String
    suspend fun recognize(bitmap: Bitmap): OcrRecognizeOutcome
    fun close()
}
