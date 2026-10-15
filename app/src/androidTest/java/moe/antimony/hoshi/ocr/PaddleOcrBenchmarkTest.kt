package moe.antimony.hoshi.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.ocr.paddle.PaddleOcrConfiguration
import moe.antimony.hoshi.ocr.paddle.PaddleOcrEngine
import moe.antimony.hoshi.ocr.paddle.PaddleOcrModelSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic benchmark. It never reads books/preferences or clears installed data. */
@RunWith(AndroidJUnit4::class)
class PaddleOcrBenchmarkTest {
    @Test fun compareRuntimeConfigurationsOnGeneratedJapanesePages() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit benchmark opt-in required", args.getString("ocrBenchmark") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val iterations = args.getString("ocrRuns")?.toIntOrNull()?.coerceIn(1, 30) ?: 5
        val threads = args.getString("ocrThreads")?.split(',')?.map { it.toInt() } ?: listOf(1, 2, 4)
        val batches = args.getString("ocrBatches")?.split(',')?.map { it.toInt() } ?: listOf(1, 2, 4)
        val providers = if (args.getString("ocrXnnpack") == "true") listOf(false, true) else listOf(false)
        val spec = if (args.getString("ocrModel") == "manga") PaddleOcrModelSpec.Manga else PaddleOcrModelSpec.Standard
        val rows = listOf("今日は良い天気です", "日本語の本を読みます", "猫と犬が庭で遊んでいます", "明日は図書館へ行きます")
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 36f }
        rows.forEachIndexed { i, row -> canvas.drawText(row, 60f, 160f + i * 130f, paint) }
        val report = JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("syntheticOnly", true).put("engine", spec.engineId)
        val results = JSONArray()
        var baselineText: List<String>? = null
        try {
            for (xnnpack in providers) for (threadCount in threads) for (batchSize in batches) {
                val engine = PaddleOcrEngine(context, Dispatchers.IO, PaddleOcrConfiguration(threadCount, batchSize, xnnpack), spec)
                try {
                    val samples = mutableListOf<Double>()
                    var coldMs = 0.0
                    var last: OcrRawResult? = null
                    repeat(iterations + 2) { run ->
                        val start = System.nanoTime()
                        val outcome = engine.recognize(bitmap)
                        val elapsed = (System.nanoTime() - start) / 1_000_000.0
                        assertTrue("OCR backend must run: $outcome", outcome is OcrRecognizeOutcome.Success)
                        last = (outcome as OcrRecognizeOutcome.Success).result
                        if (run == 0) coldMs = elapsed
                        if (run >= 2) samples += elapsed
                    }
                    val texts = last!!.lines.map { it.text }
                    assertTrue("Generated Japanese must be detected", texts.isNotEmpty())
                    if (!xnnpack && threadCount == threads.first() && batchSize == batches.first()) baselineText = texts
                    // Batching must not scramble line IDs or change recognized text.
                    if (!xnnpack && baselineText != null) assertEquals(baselineText, texts)
                    val actual = texts.joinToString("")
                    val expected = rows.joinToString("")
                    val sorted = samples.sorted()
                    results.put(JSONObject().put("threads", threadCount).put("batch", batchSize).put("xnnpack", xnnpack)
                        .put("coldMs", coldMs).put("p50Ms", sorted[(sorted.size - 1) / 2])
                        .put("p95Ms", sorted[(kotlin.math.ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)])
                        .put("lines", texts.size).put("codePointEdits", OcrAccuracy.editDistance(expected, actual))
                        .put("referenceCodePoints", expected.codePointCount(0, expected.length))
                        .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()))
                } finally { engine.close() }
            }
        } finally { bitmap.recycle() }
        report.put("results", results)
        val artifact = File(context.cacheDir, "ocr-benchmark-${UUID.randomUUID()}.json")
        artifact.writeText(report.toString(2))
        Log.i("PdfOcrBenchmark", "artifact=${artifact.absolutePath} results=$results")
    }
}
