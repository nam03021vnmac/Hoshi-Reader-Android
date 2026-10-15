package moe.antimony.hoshi.ocr

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.ocr.layout.JapaneseOcrPostprocessor
import moe.antimony.hoshi.ocr.pdf.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated PDFs/cache only; validates native raster lifetimes without opening installed books. */
@RunWith(AndroidJUnit4::class)
class PdfOcrRepositoryDeviceTest {
    @Test fun simultaneousRequestsShareOneRasterAndInference() = runBlocking {
        val fixture = fixture()
        try {
            val engine = FakeEngine().apply { block = true }
            val repository = PdfOcrRepository(PdfOcrRasterizer(Dispatchers.IO), PdfOcrEngines(engine, engine), { PdfOcrModel.Standard },
                JapaneseOcrPostprocessor(), PdfOcrStore(File(fixture.root, "ocr"), Dispatchers.IO),
                BookWorkRegistry(), Dispatchers.IO)
            val hash = repository.sourceHashFor(fixture.pdf)!!
            suspend fun page() = repository.ocrPage(fixture.pdf, fixture.root, hash, 0,
                595 * (96.0 / 72), 842 * (96.0 / 72), false, PdfOcrModel.Standard)
            val first = async { page() }
            engine.entered.await()
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { page() }
            engine.release.complete(Unit)
            val a = first.await() as PdfOcrResult.Ready
            val b = duplicate.await() as PdfOcrResult.Ready
            assertEquals(a.record.revisionId, b.record.revisionId)
            assertEquals(1, engine.calls)
            assertTrue(engine.lastBitmap!!.isRecycled)
        } finally { fixture.root.deleteRecursively() }
    }

    @Test fun cacheRetryAndPinsUseTheExpectedRevisionAndRasterDetail() = runBlocking {
        val fixture = fixture()
        try {
            val engine = FakeEngine()
            val registry = BookWorkRegistry()
            val store = PdfOcrStore(File(fixture.root, "ocr"), Dispatchers.IO)
            val repository = PdfOcrRepository(PdfOcrRasterizer(Dispatchers.IO), PdfOcrEngines(engine, engine), { PdfOcrModel.Standard },
                JapaneseOcrPostprocessor(), store, registry, Dispatchers.IO)
            val hash = repository.sourceHashFor(fixture.pdf)!!
            suspend fun page(high: Boolean = false, force: Boolean = false) = repository.ocrPage(
                fixture.pdf, fixture.root, hash, 0, 595 * (96.0 / 72), 842 * (96.0 / 72), high, PdfOcrModel.Standard, force,
            ) as PdfOcrResult.Ready
            val normal = page()
            assertTrue(engine.lastBitmap!!.isRecycled)
            assertEquals(normal.record.revisionId, page().record.revisionId)
            assertEquals(1, engine.calls)
            val high = page(high = true, force = true)
            assertEquals(2, engine.calls)
            assertNotEquals(normal.record.revisionId, high.record.revisionId)
            assertTrue(high.record.rasterPlan.bitmapWidth > normal.record.rasterPlan.bitmapWidth)
            assertTrue(repository.pinHighlightForRevision("highlight", hash, 0, normal.record.revisionId))
            assertEquals(normal.record.revisionId, page(high = true, force = true).record.revisionId)
            assertEquals(2, engine.calls)
            registry.delete(fixture.root) { assertNull(store.loadActive(hash, repository.pipelineKey(PdfOcrModel.Standard), 0)) }
        } finally { fixture.root.deleteRecursively() }
    }

    @Test fun deletingABookCancelsAndJoinsOcrBeforeRemovingItsCache() = runBlocking {
        val fixture = fixture()
        try {
            val engine = FakeEngine().apply { block = true }
            val registry = BookWorkRegistry()
            val store = PdfOcrStore(File(fixture.root, "ocr"), Dispatchers.IO)
            val repository = PdfOcrRepository(PdfOcrRasterizer(Dispatchers.IO), PdfOcrEngines(engine, engine), { PdfOcrModel.Standard },
                JapaneseOcrPostprocessor(), store, registry, Dispatchers.IO)
            val hash = repository.sourceHashFor(fixture.pdf)!!
            val request = async {
                repository.ocrPage(fixture.pdf, fixture.root, hash, 0, 595 * (96.0 / 72), 842 * (96.0 / 72), false, PdfOcrModel.Standard)
            }
            engine.entered.await()
            registry.delete(fixture.root) {
                assertTrue(engine.finished)
                assertTrue(engine.lastBitmap!!.isRecycled)
                assertNull(store.loadActive(hash, repository.pipelineKey(PdfOcrModel.Standard), 0))
            }
            assertTrue(request.isCancelled)
        } finally { fixture.root.deleteRecursively() }
    }

    private data class Fixture(val root: File, val pdf: File)
    private fun fixture(): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "ocr-repository-${UUID.randomUUID()}").apply { mkdirs() }
        val pdf = File(root, "generated.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 0).create())
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return Fixture(root, pdf)
    }

    private class FakeEngine : OcrEngine {
        override val engineId = "fixture"
        override val engineVersion = "1"
        var calls = 0
        var lastBitmap: Bitmap? = null
        var block = false
        var finished = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun recognize(bitmap: Bitmap): OcrRecognizeOutcome {
            calls++
            lastBitmap = bitmap
            entered.complete(Unit)
            try {
                if (block) release.await()
                return OcrRecognizeOutcome.Success(OcrRawResult(
                    listOf(OcrRawLine("line", "猫", OcrPixelQuad(10f, 10f, 100f, 10f, 100f, 50f, 10f, 50f),
                        OcrDirection.HORIZONTAL, 0.9f)), bitmap.width, bitmap.height,
                ))
            } finally { finished = true }
        }
        override fun close() = Unit
    }
}
