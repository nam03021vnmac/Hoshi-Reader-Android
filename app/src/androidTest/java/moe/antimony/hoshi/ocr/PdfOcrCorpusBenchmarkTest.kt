package moe.antimony.hoshi.ocr

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.ocr.layout.JapaneseOcrPostprocessor
import moe.antimony.hoshi.ocr.layout.PostprocessorInputLine
import moe.antimony.hoshi.ocr.paddle.PaddleOcrConfiguration
import moe.antimony.hoshi.ocr.paddle.PaddleOcrEngine
import moe.antimony.hoshi.ocr.paddle.PaddleOcrModelSpec
import moe.antimony.hoshi.ocr.pdf.NormalizedQuad
import moe.antimony.hoshi.ocr.pdf.PdfOcrRasterizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, private cache corpus only. Never traverses the user's imported books. */
@RunWith(AndroidJUnit4::class)
class PdfOcrCorpusBenchmarkTest {
    @Test fun measureLabeledScansAtNormalAndHighDetail() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit corpus opt-in required", args.getString("ocrCorpus") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ocr-corpus").canonicalFile
        val manifest = JSONObject(File(root, "manifest.json").readText())
        val pages = manifest.getJSONArray("pages")
        require(pages.length() > 0) { "A labeled corpus is required" }
        val runs = args.getString("ocrRuns")?.toIntOrNull()?.coerceIn(1, 30) ?: 5
        val threads = args.getString("ocrThreads")?.toIntOrNull() ?: 4
        val batch = args.getString("ocrBatch")?.toIntOrNull() ?: 1
        val spec = if (args.getString("ocrModel") == "manga") PaddleOcrModelSpec.Manga else PaddleOcrModelSpec.Standard
        val engine = PaddleOcrEngine(context, Dispatchers.IO, PaddleOcrConfiguration(threads, batch), spec)
        val rasterizer = PdfOcrRasterizer(Dispatchers.IO)
        val layout = JapaneseOcrPostprocessor()
        val results = JSONArray()
        var cold = true
        try {
            for (i in 0 until pages.length()) {
                val fixture = pages.getJSONObject(i)
                val file = File(root, fixture.getString("pdf")).canonicalFile
                require(file.toPath().startsWith(root.toPath()) && file.isFile) { "Corpus PDF must stay inside the fixture directory" }
                val reference = fixture.getString("canonicalText")
                require(reference.isNotEmpty()) { "Nonempty verbatim canonical truth is required" }
                val pageIndex = fixture.getInt("pageIndex")
                require(pageIndex >= 0)
                for (highDetail in listOf(false, true)) {
                    repeat(runs + 1) { run ->
                        val start = System.nanoTime()
                        val raster = requireNotNull(rasterizer.rasterize(file, pageIndex, highDetail))
                        try {
                            val rendered = System.nanoTime()
                            val outcome = engine.recognize(raster.bitmap)
                            assertTrue("Corpus inference failed", outcome is OcrRecognizeOutcome.Success)
                            val raw = (outcome as OcrRecognizeOutcome.Success).result
                            val inferred = System.nanoTime()
                            val lines = raw.lines.map { line ->
                                val q = line.quad
                                PostprocessorInputLine(line.id, line.text, q?.let { NormalizedQuad(
                                    q.x0 / raw.imageWidth, q.y0 / raw.imageHeight,
                                    q.x1 / raw.imageWidth, q.y1 / raw.imageHeight,
                                    q.x2 / raw.imageWidth, q.y2 / raw.imageHeight,
                                    q.x3 / raw.imageWidth, q.y3 / raw.imageHeight,
                                ) }, line.direction.name, line.confidence)
                            }
                            val processed = layout.process(lines, raw.imageWidth.toDouble(), raw.imageHeight.toDouble())
                            val completed = System.nanoTime()
                            val count = reference.codePointCount(0, reference.length)
                            val edits = OcrAccuracy.editDistance(reference, processed.canonicalText)
                            results.put(JSONObject().put("id", fixture.getString("id"))
                                .put("bookId", fixture.getString("bookId")).put("pageIndex", pageIndex)
                                .put("highDetail", highDetail).put("run", run).put("coldModel", cold)
                                .put("warmup", run == 0).put("width", raw.imageWidth).put("height", raw.imageHeight).put("dpi", raster.dpi)
                                .put("rasterMs", (rendered - start) / 1e6).put("inferenceMs", (inferred - rendered) / 1e6)
                                .put("layoutMs", (completed - inferred) / 1e6).put("totalMs", (completed - start) / 1e6)
                                .put("codePointEdits", edits).put("referenceCodePoints", count).put("cer", edits.toDouble() / count))
                            cold = false
                        } finally { raster.bitmap.recycle() }
                    }
                }
            }
        } finally { engine.close() }
        val report = JSONObject().put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("threads", threads).put("batch", batch)
            .put("engine", engine.engineId).put("version", engine.engineVersion).put("results", results)
        val artifact = File(context.cacheDir, "ocr-corpus-report-${UUID.randomUUID()}.json")
        artifact.writeText(report.toString(2))
        Log.i("PdfOcrBenchmark", "artifact=${artifact.absolutePath}")
    }
}
