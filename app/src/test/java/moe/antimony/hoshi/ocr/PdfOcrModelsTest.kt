package moe.antimony.hoshi.ocr

import moe.antimony.hoshi.ocr.pdf.NormalizedQuad
import moe.antimony.hoshi.ocr.pdf.OcrPageGeometryRecord
import moe.antimony.hoshi.ocr.pdf.OcrPageRecord
import moe.antimony.hoshi.ocr.pdf.OcrParagraphRecord
import moe.antimony.hoshi.ocr.pdf.OcrRasterPlanRecord
import moe.antimony.hoshi.ocr.pdf.OcrRawObservationRecord
import moe.antimony.hoshi.ocr.pdf.OcrSpanRecord
import moe.antimony.hoshi.ocr.pdf.PdfOcrPipeline
import moe.antimony.hoshi.ocr.pdf.validateOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfOcrModelsTest {
    private fun validRecord(): OcrPageRecord {
        val text = "今日は良い天気"
        return OcrPageRecord(
            sourceSha256 = "a".repeat(64),
            pageIndex = 2,
            engineId = "paddle-ppocrv6-small",
            engineVersion = "20260618",
            configHash = PdfOcrPipeline.configHash("paddle-ppocrv6-small", "20260618", false),
            pageGeometry = OcrPageGeometryRecord(595, 842, 450.0, 600.0),
            rasterPlan = OcrRasterPlanRecord(200, 1200, 1600, false),
            revisionId = "rev-1",
            status = "OcrReady",
            rawObservations = listOf(OcrRawObservationRecord("paddle-l0", text)),
            paragraphs = listOf(OcrParagraphRecord("p0", listOf("paddle-l0"), 0, text.length)),
            canonicalText = text,
            spans = listOf(OcrSpanRecord("paddle-l0", 0, text.length)),
        )
    }

    @Test
    fun validRecordPassesValidation() {
        assertNotNull(validRecord().validateOrNull())
    }

    @Test
    fun outOfRangeSpanFailsClosed() {
        val record = validRecord().copy(
            spans = listOf(OcrSpanRecord("paddle-l0", 0, 999)),
        )
        assertNull(record.validateOrNull())
    }

    @Test
    fun nonFiniteQuadFailsClosed() {
        val record = validRecord().copy(
            spans = listOf(
                OcrSpanRecord(
                    "paddle-l0",
                    0,
                    validRecord().canonicalText.length,
                    NormalizedQuad(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
                ),
            ),
        )
        assertNull(record.validateOrNull())
    }

    @Test
    fun configHashDistinguishesHighResAndEngine() {
        val base = PdfOcrPipeline.configHash("paddle-ppocrv6-small", "20260618", false)
        val highRes = PdfOcrPipeline.configHash("paddle-ppocrv6-small", "20260618", true)
        val otherEngine = PdfOcrPipeline.configHash("paddle", "v6", false)
        assertEquals(false, base == highRes)
        assertEquals(false, base == otherEngine)
    }

    @Test
    fun pipelineKeyIsStable() {
        assertEquals("pdf-ocr-v2-paddle-ppocrv6-small-20260618", PdfOcrPipeline.pipelineKey("paddle-ppocrv6-small", "20260618"))
    }
}
