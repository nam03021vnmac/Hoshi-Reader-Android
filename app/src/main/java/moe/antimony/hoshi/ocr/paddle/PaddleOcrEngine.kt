package moe.antimony.hoshi.ocr.paddle

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ocr.OcrDirection
import moe.antimony.hoshi.ocr.OcrEngine
import moe.antimony.hoshi.ocr.OcrPixelQuad
import moe.antimony.hoshi.ocr.OcrRawLine
import moe.antimony.hoshi.ocr.OcrRawResult
import moe.antimony.hoshi.ocr.OcrRecognizeOutcome
import kotlin.coroutines.coroutineContext
import kotlin.math.hypot
import kotlin.math.max

private const val PaddleOcrTag = "PaddleOcr"

/**
 * On-device PP-OCRv6_small backend (detection + CTC recognition on
 * ONNX Runtime, dependency-free image math, bundled official weights).
 *
 * Detection boxes are perspective-cropped per line; tall crops are rotated
 * before recognition, which also yields real HORIZONTAL/VERTICAL direction
 * evidence for the Japanese postprocessor. Character-level geometry is not
 * produced by the CTC head, so lines carry line quads only and downstream
 * geometry stays marked as line estimates.
 */
@Singleton
class PaddleOcrEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : OcrEngine {
    override val engineId: String = "paddle-ppocrv6-small"
    override val engineVersion: String = "20260618"

    private val mutex = Mutex()
    private var sessions: OrtSessions? = null
    private var characters: List<String>? = null

    private data class OrtSessions(
        val env: ai.onnxruntime.OrtEnvironment,
        val det: ai.onnxruntime.OrtSession,
        val detInputName: String,
        val rec: ai.onnxruntime.OrtSession,
        val recInputName: String,
    )

    override suspend fun recognize(bitmap: Bitmap): OcrRecognizeOutcome = withContext(ioDispatcher) {
        val holder = try {
            ensureSessions()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(PaddleOcrTag, "model init failed: ${error.message}")
            return@withContext OcrRecognizeOutcome.Unavailable("OCR model unavailable")
        } ?: return@withContext OcrRecognizeOutcome.Unavailable("OCR model unavailable")
        try {
            coroutineContext.ensureActive()
            val detInput = PaddleDetPreprocessor.preprocess(bitmap)
            coroutineContext.ensureActive()
            val (prob, probShape) = mutex.withLock {
                runSession(holder, det = true, detInput.tensor, longArrayOf(1, 3, detInput.height.toLong(), detInput.width.toLong()))
            }
            val mapH = probShape.getOrNull(2)?.toInt() ?: 0
            val mapW = probShape.getOrNull(3)?.toInt() ?: 0
            if (mapH <= 0 || mapW <= 0 || prob.size != mapH * mapW) {
                return@withContext OcrRecognizeOutcome.Failure("Unexpected OCR output", retryable = true)
            }
            coroutineContext.ensureActive()
            val boxes = DbPostProcessor.process(
                prob = prob,
                mapWidth = mapW,
                mapHeight = mapH,
                originalWidth = detInput.originalWidth,
                originalHeight = detInput.originalHeight,
            )
            if (boxes.isEmpty()) {
                return@withContext OcrRecognizeOutcome.Success(
                    OcrRawResult(emptyList(), bitmap.width, bitmap.height),
                )
            }
            val chars = characters
                ?: return@withContext OcrRecognizeOutcome.Unavailable("OCR model unavailable")
            val ordered = sortBoxesReadingOrder(boxes)
            val sourceBgr = bitmapToBgr(bitmap)
            val lines = mutableListOf<OcrRawLine>()
            for ((index, box) in ordered.withIndex()) {
                coroutineContext.ensureActive()
                val crop = cropLine(sourceBgr, bitmap.width, bitmap.height, box)
                    ?: continue
                val batch = PaddleRecPreprocessor.preprocessBatch(listOf(crop.first))
                val (logits, logitShape) = mutex.withLock {
                    runSession(holder, det = false, batch.tensor, longArrayOf(1, 3, 48, batch.width.toLong()))
                }
                val steps = logitShape.getOrNull(1)?.toInt() ?: 0
                val classes = logitShape.getOrNull(2)?.toInt() ?: 0
                if (steps <= 0 || classes <= 0 || logits.size != steps * classes) {
                    return@withContext OcrRecognizeOutcome.Failure("Unexpected OCR output", retryable = true)
                }
                val (text, confidence) = CtcDecoder.decode(logits, 1, steps, classes, chars).first()
                if (text.isBlank()) continue
                lines += OcrRawLine(
                    id = "paddle-l$index",
                    text = text,
                    quad = box.points.toPixelQuad(),
                    direction = if (crop.second) OcrDirection.VERTICAL else OcrDirection.HORIZONTAL,
                    confidence = confidence.takeIf { it.isFinite() && it >= 0f },
                )
            }
            OcrRecognizeOutcome.Success(OcrRawResult(lines, bitmap.width, bitmap.height))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.w(PaddleOcrTag, "recognize failed: ${error.message}")
            OcrRecognizeOutcome.Failure(error.message?.ifBlank { null } ?: "OCR failed", retryable = true)
        }
    }

    override fun close() {
        // Best-effort release; in-flight recognition retains its own safety
        // because the repository serializes engine use and never publishes
        // results after close.
        runCatching { sessions?.det?.close() }
        runCatching { sessions?.rec?.close() }
        sessions = null
        characters = null
    }

    private suspend fun ensureSessions(): OrtSessions? = mutex.withLock {
        sessions?.let { return it }
        val detBytes = readAsset(PaddleOcrAssets.DET_MODEL) ?: return null
        val recBytes = readAsset(PaddleOcrAssets.REC_MODEL) ?: return null
        val yml = readAssetText(PaddleOcrAssets.REC_CONFIG) ?: return null
        val chars = PaddleDict.parseCharacterDict(yml, PaddleOcrAssets.REC_CONFIG)
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        try {
            val options = ai.onnxruntime.OrtSession.SessionOptions().apply {
                setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
            try {
                val det = env.createSession(detBytes, options)
                val rec = try {
                    env.createSession(recBytes, options)
                } catch (t: Throwable) {
                    det.close()
                    throw t
                }
                val holder = OrtSessions(
                    env = env,
                    det = det,
                    detInputName = det.inputNames.iterator().next(),
                    rec = rec,
                    recInputName = rec.inputNames.iterator().next(),
                )
                sessions = holder
                characters = chars
                holder
            } finally {
                options.close()
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            runCatching { sessions?.det?.close() }
            runCatching { sessions?.rec?.close() }
            sessions = null
            characters = null
            throw t
        }
    }

    private fun runSession(
        holder: OrtSessions,
        det: Boolean,
        data: FloatArray,
        shape: LongArray,
    ): Pair<FloatArray, LongArray> {
        val session = if (det) holder.det else holder.rec
        val inputName = if (det) holder.detInputName else holder.recInputName
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(holder.env, FloatBuffer.wrap(data), shape)
        try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val outputName = session.outputNames.iterator().next()
                val output = result.get(outputName).orElseThrow { IllegalStateException("No OCR output tensor") }
                    as? ai.onnxruntime.OnnxTensor
                    ?: throw IllegalStateException("OCR output is not a tensor")
                val buffer = output.floatBuffer.duplicate()
                buffer.rewind()
                val array = FloatArray(buffer.remaining())
                buffer.get(array)
                return Pair(array, output.info.shape)
            }
        } finally {
            tensor.close()
        }
    }

    private fun readAsset(path: String): ByteArray? = runCatching {
        context.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    private fun readAssetText(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * Perspective-crops a detection box from the source BGR plane.
     * Returns the BGR crop plus whether it was rotated as vertical text.
     */
    private fun cropLine(
        sourceBgr: ByteArray,
        originalWidth: Int,
        originalHeight: Int,
        box: DetectedBox,
    ): Pair<RecCrop, Boolean>? {
        val ordered = orderQuadPoints(box.points)
        val widthTop = hypot(ordered[0].x - ordered[1].x, ordered[0].y - ordered[1].y)
        val widthBottom = hypot(ordered[2].x - ordered[3].x, ordered[2].y - ordered[3].y)
        val heightLeft = hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y)
        val heightRight = hypot(ordered[1].x - ordered[2].x, ordered[1].y - ordered[2].y)
        val dstW = max(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val dstH = max(heightLeft, heightRight).toInt().coerceAtLeast(1)
        if (dstW <= 0 || dstH <= 0 || dstW * dstH > 4_000_000) return null
        val warped = warpQuadToRect(sourceBgr, originalWidth, originalHeight, ordered, dstW, dstH)
        return if (dstH.toDouble() / dstW >= 1.5) {
            val (rotated, rw, rh) = rotateBgr90Ccw(warped, dstW, dstH)
            Pair(RecCrop(rotated, rw, rh), true)
        } else {
            Pair(RecCrop(warped, dstW, dstH), false)
        }
    }

    private fun bitmapToBgr(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bgr = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val argb = pixels[i]
            bgr[i * 3] = (argb and 0xFF).toByte()
            bgr[i * 3 + 1] = ((argb shr 8) and 0xFF).toByte()
            bgr[i * 3 + 2] = ((argb shr 16) and 0xFF).toByte()
        }
        return bgr
    }

    private fun List<PointD>.toPixelQuad(): OcrPixelQuad {
        val ordered = orderQuadPoints(this)
        return OcrPixelQuad(
            x0 = ordered[0].x.toFloat(), y0 = ordered[0].y.toFloat(),
            x1 = ordered[1].x.toFloat(), y1 = ordered[1].y.toFloat(),
            x2 = ordered[2].x.toFloat(), y2 = ordered[2].y.toFloat(),
            x3 = ordered[3].x.toFloat(), y3 = ordered[3].y.toFloat(),
        )
    }
}

/** Bundled PP-OCRv6_small asset locations inside the APK. */
object PaddleOcrAssets {
    const val DET_MODEL = "ocr/paddle/det/inference.onnx"
    const val REC_MODEL = "ocr/paddle/rec/inference.onnx"
    const val REC_CONFIG = "ocr/paddle/rec/inference.yml"
}
