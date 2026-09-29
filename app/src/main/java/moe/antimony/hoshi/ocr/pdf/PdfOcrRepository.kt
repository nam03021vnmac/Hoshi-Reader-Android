package moe.antimony.hoshi.ocr.pdf

import android.util.Log
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.ocr.OcrEngine
import moe.antimony.hoshi.ocr.OcrRecognizeOutcome
import moe.antimony.hoshi.ocr.layout.JapaneseOcrPostprocessor
import moe.antimony.hoshi.ocr.layout.PostprocessorInputLine
import moe.antimony.hoshi.ocr.pdf.NormalizedQuad

sealed interface PdfOcrResult {
    data class Ready(val record: OcrPageRecord) : PdfOcrResult
    data class NoText(val record: OcrPageRecord) : PdfOcrResult
    data class Failed(val reason: String, val retryable: Boolean) : PdfOcrResult
    data class Unavailable(val reason: String) : PdfOcrResult
}

private const val PdfOcrTag = "PdfOcr"

/**
 * Resolves book sources, fingerprints them, deduplicates requests, and
 * orchestrates raster/engine/postprocessor/store. Main-safe suspend APIs.
 * Foreground visible pages outrank prefetch; obsolete prefetch is dropped at
 * page boundaries. Zoom never invalidates OCR.
 */
@Singleton
class PdfOcrRepository @Inject constructor(
    private val rasterizer: PdfOcrRasterizer,
    private val engine: OcrEngine,
    private val postprocessor: JapaneseOcrPostprocessor,
    private val store: PdfOcrStore,
    private val workRegistry: BookWorkRegistry,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val enginePermits = Semaphore(1)
    private val jobsMutex = Mutex()
    private val inFlight = mutableMapOf<String, Job>()
    private val hashCache = mutableMapOf<String, CachedHash>()
    private val coordinatorMutex = Mutex()
    private val coordinators = mutableMapOf<File, AutoCloseable>()
    private val deletedSources = mutableSetOf<String>()

    private data class CachedHash(val length: Long, val modified: Long, val hash: String)

    fun pipelineKey(): String =
        PdfOcrPipeline.pipelineKey(engine.engineId, engine.engineVersion)

    fun configHash(highRes: Boolean): String =
        PdfOcrPipeline.configHash(engine.engineId, engine.engineVersion, highRes)

    suspend fun sourceHashFor(pdfFile: File): String? = withContext(ioDispatcher) {
        if (!pdfFile.isFile) return@withContext null
        val key = pdfFile.absolutePath
        val length = pdfFile.length()
        val modified = pdfFile.lastModified()
        synchronized(hashCache) {
            val cached = hashCache[key]
            if (cached != null && cached.length == length && cached.modified == modified) {
                return@withContext cached.hash
            }
        }
        val hash = store.sourceHashForFile(pdfFile) ?: return@withContext null
        synchronized(hashCache) { hashCache[key] = CachedHash(length, modified, hash) }
        hash
    }

    suspend fun cachedPage(sourceHash: String, pageIndex: Int): OcrPageRecord? =
        store.loadActive(sourceHash, pipelineKey(), pageIndex)

    suspend fun ocrPage(
        pdfFile: File,
        bookRoot: File?,
        sourceHash: String,
        pageIndex: Int,
        cssWidth: Double,
        cssHeight: Double,
        highRes: Boolean,
    ): PdfOcrResult = withContext(ioDispatcher) {
        ensureActive()
        synchronized(deletedSources) { if (sourceHash in deletedSources) throw CancellationException() }
        // Reuse persisted results on reopen without rerunning inference.
        cachedPage(sourceHash, pageIndex)?.let { cached ->
            if (cached.status == "NoText") return@withContext PdfOcrResult.NoText(cached)
            return@withContext PdfOcrResult.Ready(cached)
        }
        ensureActive()
        // One pending request per (source, page, configuration).
        val requestKey = "$sourceHash|$pageIndex|highRes=$highRes"
        jobsMutex.withLock { if (requestKey in inFlight) return@withContext PdfOcrResult.Failed("Duplicate OCR request", retryable = true) }

        ensureCoordinator(bookRoot)

        val raster = rasterizer.rasterize(pdfFile, pageIndex, highRes)
            ?: run {
                Log.w(PdfOcrTag, "rasterize failed page=$pageIndex highRes=$highRes")
                return@withContext PdfOcrResult.Failed("Could not render PDF page", retryable = true)
            }
        try {
            ensureActive()
            // Validate the native/pdf.js plane describes the same effective page.
            // A width/height ratio alone never proves CropBox/MediaBox, origin,
            // /Rotate, or /UserUnit parity; reject unsupported mappings instead
            // of drawing an inaccurate overlay.
            if (!geometryParityOk(raster.nativeWidthPt, raster.nativeHeightPt, cssWidth, cssHeight)) {
                Log.w(
                    PdfOcrTag,
                    "geometry parity failed page=$pageIndex native=${raster.nativeWidthPt}x${raster.nativeHeightPt} css=${cssWidth}x${cssHeight}",
                )
                return@withContext PdfOcrResult.Unavailable("Unsupported PDF page geometry")
            }
            val outcome = enginePermits.withPermit {
                ensureActive()
                engine.recognize(raster.bitmap)
            }
            ensureActive()
            when (outcome) {
                is OcrRecognizeOutcome.Unavailable -> {
                    Log.w(PdfOcrTag, "engine unavailable page=$pageIndex reason=${outcome.reason}")
                    PdfOcrResult.Unavailable(outcome.reason)
                }
                is OcrRecognizeOutcome.Failure -> {
                    Log.w(PdfOcrTag, "engine failure page=$pageIndex reason=${outcome.reason} retryable=${outcome.retryable}")
                    PdfOcrResult.Failed(outcome.reason, outcome.retryable)
                }
                is OcrRecognizeOutcome.Success -> {
                    val inputs = outcome.result.lines.map { line ->
                        PostprocessorInputLine(
                            id = line.id,
                            text = line.text,
                            quad = line.quad?.toNormalized(outcome.result.imageWidth, outcome.result.imageHeight),
                            direction = when (line.direction) {
                                moe.antimony.hoshi.ocr.OcrDirection.HORIZONTAL -> "HORIZONTAL"
                                moe.antimony.hoshi.ocr.OcrDirection.VERTICAL -> "VERTICAL"
                                else -> "UNKNOWN"
                            },
                            confidence = line.confidence,
                            symbols = line.symbols.mapNotNull { symbol ->
                                if (symbol.text.isEmpty()) return@mapNotNull null
                                moe.antimony.hoshi.ocr.pdf.OcrSymbolRecord(
                                    text = symbol.text,
                                    quad = symbol.quad?.toNormalized(outcome.result.imageWidth, outcome.result.imageHeight),
                                    confidence = symbol.confidence,
                                )
                            },
                        )
                    }
                    val processed = postprocessor.process(inputs)
                    val revisionId = UUID.randomUUID().toString()
                    val record = OcrPageRecord(
                        sourceSha256 = sourceHash,
                        pageIndex = pageIndex,
                        engineId = engine.engineId,
                        engineVersion = engine.engineVersion,
                        pipelineVersion = PdfOcrPipeline.VERSION,
                        configHash = configHash(highRes),
                        pageGeometry = OcrPageGeometryRecord(
                            nativeWidthPt = raster.nativeWidthPt,
                            nativeHeightPt = raster.nativeHeightPt,
                            cssWidth = cssWidth,
                            cssHeight = cssHeight,
                        ),
                        rasterPlan = OcrRasterPlanRecord(
                            dpi = raster.dpi,
                            bitmapWidth = raster.bitmap.width,
                            bitmapHeight = raster.bitmap.height,
                            highRes = highRes,
                        ),
                        revisionId = revisionId,
                        status = if (processed.canonicalText.isBlank()) "NoText" else "OcrReady",
                        rawObservations = processed.rawObservations,
                        paragraphs = processed.paragraphs,
                        canonicalText = processed.canonicalText,
                        spans = processed.spans,
                        rubyAssociations = processed.rubyAssociations,
                        sourceBoundaryMap = processed.sourceBoundaryMap,
                        qualityFlags = processed.qualityFlags,
                    )
                    synchronized(deletedSources) {
                        if (sourceHash in deletedSources) throw CancellationException()
                    }
                    val saved = store.save(record, pipelineKey())
                    if (!saved) return@withContext PdfOcrResult.Failed("Could not save OCR result", retryable = true)
                    store.evictUnpinnedIfOverQuota()
                    if (record.status == "NoText") PdfOcrResult.NoText(record) else PdfOcrResult.Ready(record)
                }
            }
        } finally {
            runCatching { raster.bitmap.recycle() }
        }
    }

    suspend fun cancelForSource(sourceHash: String) {
        jobsMutex.withLock {
            inFlight.entries.removeAll { it.key.startsWith("$sourceHash|") }
        }
        synchronized(deletedSources) { /* jobs check deletion flag cooperatively */ }
    }

    suspend fun deleteForSource(bookRoot: File?, sourceHash: String) {
        synchronized(deletedSources) { deletedSources += sourceHash }
        try {
            cancelForSource(sourceHash)
            store.deleteSource(sourceHash)
        } finally {
            synchronized(deletedSources) { deletedSources -= sourceHash }
            if (bookRoot != null) {
                coordinatorMutex.withLock {
                    coordinators.remove(bookRoot)?.runCatching { close() }
                }
            }
            synchronized(hashCache) { hashCache.entries.removeAll { it.value.hash == sourceHash } }
        }
    }

    suspend fun pinHighlightForRevision(
        highlightId: String,
        sourceHash: String,
        pageIndex: Int,
        revisionId: String,
    ) {
        store.pinHighlight(OcrHighlightPinRecord(highlightId, sourceHash, pageIndex, revisionId))
    }

    suspend fun unpinHighlight(sourceHash: String, highlightId: String) {
        store.unpinHighlight(sourceHash, highlightId)
    }

    private suspend fun ensureCoordinator(bookRoot: File?) {
        if (bookRoot == null) return
        coordinatorMutex.withLock {
            if (bookRoot in coordinators) return
            val registration = runCatching {
                workRegistry.register(bookRoot) {
                    // Deletion cancels jobs; late writes check the deletion flag.
                }
            }.getOrNull() ?: return
            coordinators[bookRoot] = registration
        }
    }

    private fun geometryParityOk(
        nativeWidthPt: Int,
        nativeHeightPt: Int,
        cssWidth: Double,
        cssHeight: Double,
    ): Boolean {
        if (nativeWidthPt <= 0 || nativeHeightPt <= 0) return false
        if (!cssWidth.isFinite() || !cssHeight.isFinite() || cssWidth <= 0 || cssHeight <= 0) {
            // Without pdf.js viewport metadata, still allow OCR but mark estimated.
            return true
        }
        val nativeRatio = nativeWidthPt.toDouble() / nativeHeightPt.toDouble()
        val cssRatio = cssWidth / cssHeight
        if (nativeRatio <= 0 || cssRatio <= 0) return false
        val drift = kotlin.math.abs(nativeRatio - cssRatio) / kotlin.math.max(nativeRatio, cssRatio)
        return drift <= 0.02
    }

    private fun moe.antimony.hoshi.ocr.OcrPixelQuad.toNormalized(imageWidth: Int, imageHeight: Int): NormalizedQuad? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        fun nx(x: Float) = (x / imageWidth.toFloat()).coerceIn(-0.05f, 1.05f)
        fun ny(y: Float) = (y / imageHeight.toFloat()).coerceIn(-0.05f, 1.05f)
        return NormalizedQuad(
            x0 = nx(x0), y0 = ny(y0),
            x1 = nx(x1), y1 = ny(y1),
            x2 = nx(x2), y2 = ny(y2),
            x3 = nx(x3), y3 = ny(y3),
        ).takeIf { it.isFiniteAndBounded() }
    }
}
