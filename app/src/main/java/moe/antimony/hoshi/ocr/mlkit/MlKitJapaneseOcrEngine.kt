package moe.antimony.hoshi.ocr.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.antimony.hoshi.ocr.OcrDirection
import moe.antimony.hoshi.ocr.OcrEngine
import moe.antimony.hoshi.ocr.OcrPixelQuad
import moe.antimony.hoshi.ocr.OcrRawLine
import moe.antimony.hoshi.ocr.OcrRawResult
import moe.antimony.hoshi.ocr.OcrRawSymbol
import moe.antimony.hoshi.ocr.OcrRecognizeOutcome

/**
 * Bundled ML Kit Text Recognition v2 Japanese backend.
 *
 * Integration baseline per docs/OCR_ARCHITECTURE.md: supported Android API,
 * offline model, structured geometry. Retains element/symbol output where
 * available instead of flattening Text.text. Confidence is engine-specific
 * and must not be compared across backends.
 */
@Singleton
class MlKitJapaneseOcrEngine @Inject constructor() : OcrEngine {
    override val engineId: String = "mlkit-ja-v2"
    override val engineVersion: String = "16.0.1"

    private val lock = Any()
    private var recognizer = runCatching {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }.getOrNull()

    override suspend fun recognize(bitmap: Bitmap): OcrRecognizeOutcome {
        val client = synchronized(lock) { recognizer }
            ?: return OcrRecognizeOutcome.Unavailable("OcrEngine unavailable")
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrElse { error ->
            return OcrRecognizeOutcome.Failure(
                reason = error.message ?: "Invalid OCR image",
                retryable = false,
            )
        }
        val text = try {
            suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
                client.process(image)
                    .addOnSuccessListener { result -> if (!cont.isCompleted) cont.resume(result) }
                    .addOnFailureListener { error ->
                        if (!cont.isCompleted) cont.resumeWithException(error)
                    }
                // Do not cancel native inference on coroutine cancellation;
                // the caller retains its permit until completion.
                cont.invokeOnCancellation { /* keep native task running */ }
            }
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            val unavailable = message.contains("model", ignoreCase = true) &&
                (message.contains("download", ignoreCase = true) || message.contains("unavailable", ignoreCase = true))
            return if (unavailable) {
                OcrRecognizeOutcome.Unavailable(message.ifBlank { "OCR model unavailable" })
            } else {
                OcrRecognizeOutcome.Failure(message.ifBlank { "OCR failed" }, retryable = true)
            }
        }
        return OcrRecognizeOutcome.Success(toRawResult(text, bitmap.width, bitmap.height))
    }

    override fun close() {
        synchronized(lock) {
            runCatching { recognizer?.close() }
            recognizer = null
        }
    }

    private fun toRawResult(
        text: com.google.mlkit.vision.text.Text,
        imageWidth: Int,
        imageHeight: Int,
    ): OcrRawResult {
        val lines = mutableListOf<OcrRawLine>()
        var lineIndex = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineId = "mlkit-l$lineIndex"
                lineIndex += 1
                val verbatim = line.text
                if (verbatim.isEmpty()) continue
                val quad = line.cornerPoints?.toPixelQuad()
                    ?: line.boundingBox?.toPixelQuad()
                val symbols = line.elements.flatMap { element ->
                    element.symbols.map { symbol ->
                        OcrRawSymbol(
                            text = symbol.text,
                            quad = symbol.cornerPoints?.toPixelQuad()
                                ?: symbol.boundingBox?.toPixelQuad(),
                            confidence = symbol.confidence.takeIf { it.isFinite() && it >= 0f },
                        )
                    }.filter { it.text.isNotEmpty() }
                }
                lines += OcrRawLine(
                    id = lineId,
                    text = verbatim,
                    quad = quad,
                    direction = OcrDirection.UNKNOWN,
                    confidence = line.confidence.takeIf { it.isFinite() && it >= 0f },
                    symbols = symbols,
                )
            }
        }
        return OcrRawResult(lines = lines, imageWidth = imageWidth, imageHeight = imageHeight)
    }

    private fun android.graphics.Rect.toPixelQuad(): OcrPixelQuad =
        OcrPixelQuad(
            x0 = left.toFloat(), y0 = top.toFloat(),
            x1 = right.toFloat(), y1 = top.toFloat(),
            x2 = right.toFloat(), y2 = bottom.toFloat(),
            x3 = left.toFloat(), y3 = bottom.toFloat(),
        )

    private fun Array<android.graphics.Point>.toPixelQuad(): OcrPixelQuad? {
        if (size < 4) return null
        return OcrPixelQuad(
            x0 = this[0].x.toFloat(), y0 = this[0].y.toFloat(),
            x1 = this[1].x.toFloat(), y1 = this[1].y.toFloat(),
            x2 = this[2].x.toFloat(), y2 = this[2].y.toFloat(),
            x3 = this[3].x.toFloat(), y3 = this[3].y.toFloat(),
        )
    }
}
