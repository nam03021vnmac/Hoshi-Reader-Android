package moe.antimony.hoshi.ocr.pdf

import kotlinx.serialization.Serializable

/** Pipeline identity for cache invalidation. Bump on raster/model/postprocessor changes. */
object PdfOcrPipeline {
    const val VERSION = "pdf-ocr-v2"
    const val LANGUAGE = "ja"
    const val SCHEMA_VERSION = 1

    /** Exact model/runtime/postprocessor/raster identity; zoom/theme/popup never invalidate. */
    fun configHash(engineId: String, engineVersion: String, highRes: Boolean): String =
        "$VERSION|$engineId|$engineVersion|highRes=$highRes"

    fun pipelineKey(engineId: String, engineVersion: String): String =
        "$VERSION-$engineId-$engineVersion"
}

/** Normalized quad in the canonical display-oriented page plane, top-left origin, [0,1]. */
@Serializable
data class NormalizedQuad(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
) {
    fun isFiniteAndBounded(): Boolean {
        val values = floatArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)
        if (values.any { !it.isFinite() }) return false
        // Tolerate small overshoot from detection; reject wild coordinates.
        if (values.any { it < -0.05f || it > 1.05f }) return false
        return true
    }

    fun boundingRect(): FloatArray {
        val xs = floatArrayOf(x0, x1, x2, x3)
        val ys = floatArrayOf(y0, y1, y2, y3)
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

@Serializable
data class OcrSymbolRecord(
    val text: String,
    val quad: NormalizedQuad? = null,
    val confidence: Float? = null,
)

@Serializable
data class OcrRawObservationRecord(
    val id: String,
    val rawText: String,
    val quad: NormalizedQuad? = null,
    /** HORIZONTAL, VERTICAL, or UNKNOWN */
    val direction: String = "UNKNOWN",
    val confidence: Float? = null,
    val symbols: List<OcrSymbolRecord> = emptyList(),
)

@Serializable
data class OcrParagraphRecord(
    val id: String,
    val orderedLineIds: List<String>,
    val canonicalStartUtf16: Int,
    val canonicalEndUtf16: Int,
)

@Serializable
data class OcrSpanRecord(
    val sourceId: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val quad: NormalizedQuad? = null,
    /** "observed" for symbol geometry, "line-estimate" for subdivided lines. */
    val geometryPrecision: String = "line-estimate",
    val direction: String = "UNKNOWN",
)

@Serializable
data class OcrRubyAssociationRecord(
    val readingSourceIds: List<String>,
    val baseStartUtf16: Int,
    val baseEndUtf16: Int,
    val confidence: Float? = null,
)

@Serializable
data class OcrPageGeometryRecord(
    val nativeWidthPt: Int,
    val nativeHeightPt: Int,
    val cssWidth: Double,
    val cssHeight: Double,
)

@Serializable
data class OcrRasterPlanRecord(
    val dpi: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val highRes: Boolean,
)

/**
 * Versioned per-page OCR result. Intervals are half-open UTF-16 offsets into
 * [canonicalText]. Kotlin strings and JS DOM offsets share UTF-16; quality
 * metrics count code points separately at validation time.
 */
@Serializable
data class OcrPageRecord(
    val schemaVersion: Int = PdfOcrPipeline.SCHEMA_VERSION,
    val sourceSha256: String,
    val pageIndex: Int,
    val engineId: String,
    val engineVersion: String,
    val language: String = PdfOcrPipeline.LANGUAGE,
    val pipelineVersion: String = PdfOcrPipeline.VERSION,
    val configHash: String,
    val pageGeometry: OcrPageGeometryRecord,
    val rasterPlan: OcrRasterPlanRecord,
    val revisionId: String,
    /** "OcrReady" or "NoText" */
    val status: String,
    val rawObservations: List<OcrRawObservationRecord>,
    val paragraphs: List<OcrParagraphRecord>,
    val canonicalText: String,
    val spans: List<OcrSpanRecord>,
    val rubyAssociations: List<OcrRubyAssociationRecord> = emptyList(),
    /** Removed separators / ruby decisions kept in the source map, not destructive cleanup. */
    val sourceBoundaryMap: List<Int> = emptyList(),
    val qualityFlags: List<String> = emptyList(),
)

@Serializable
data class OcrHighlightPinRecord(
    val highlightId: String,
    val sourceSha256: String,
    val pageIndex: Int,
    val revisionId: String,
)

internal fun OcrPageRecord.validateOrNull(): OcrPageRecord? {
    if (schemaVersion != PdfOcrPipeline.SCHEMA_VERSION) return null
    if (sourceSha256.length != 64 || !sourceSha256.all { it in '0'..'9' || it in 'a'..'f' }) return null
    if (pageIndex < 0 || revisionId.isBlank()) return null
    if (status != "OcrReady" && status != "NoText") return null
    val textLength = canonicalText.length
    for (paragraph in paragraphs) {
        if (paragraph.canonicalStartUtf16 < 0 || paragraph.canonicalEndUtf16 < paragraph.canonicalStartUtf16 ||
            paragraph.canonicalEndUtf16 > textLength
        ) return null
    }
    for (span in spans) {
        if (span.startUtf16 < 0 || span.endUtf16 < span.startUtf16 || span.endUtf16 > textLength) return null
        if (span.quad != null && !span.quad.isFiniteAndBounded()) return null
    }
    for (observation in rawObservations) {
        if (observation.quad != null && !observation.quad.isFiniteAndBounded()) return null
        for (symbol in observation.symbols) {
            if (symbol.quad != null && !symbol.quad.isFiniteAndBounded()) return null
        }
    }
    return this
}
