package moe.antimony.hoshi.ocr.pdf

import android.util.Log
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.ocr.OcrRecognizeOutcome
import moe.antimony.hoshi.ocr.OcrTimings
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
    private val engines: PdfOcrEngines,
    private val modelSource: PdfOcrModelSource,
    private val postprocessor: JapaneseOcrPostprocessor,
    private val store: PdfOcrStore,
    private val workRegistry: BookWorkRegistry,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PdfOcrReaderRepository {
    private val enginePermits = Semaphore(1)
    private val jobsMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<PdfOcrResult>>()
    private val hashCache = mutableMapOf<String, CachedHash>()
    private val coordinatorMutex = Mutex()
    private val coordinators = mutableMapOf<File, AutoCloseable>()
    private val deletedSources = mutableSetOf<String>()
    // Guarded by enginePermits: only the last used model keeps its sessions loaded.
    private var loadedModel: PdfOcrModel? = null

    private data class CachedHash(val length: Long, val modified: Long, val hash: String)

    fun pipelineKey(model: PdfOcrModel): String =
        engines[model].let { PdfOcrPipeline.pipelineKey(it.engineId, it.engineVersion) }

    fun configHash(model: PdfOcrModel, highRes: Boolean = false): String =
        engines[model].let { PdfOcrPipeline.configHash(it.engineId, it.engineVersion, highRes) }

    override suspend fun ocrModelFor(bookRoot: File?): PdfOcrModel = modelSource.modelFor(bookRoot)

    override suspend fun sourceHashFor(pdfFile: File): String? = withContext(ioDispatcher) {
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

    override suspend fun cachedPage(sourceHash: String, pageIndex: Int, model: PdfOcrModel): OcrPageRecord? {
        val engine = engines[model]
        // Records from the removed high-detail retry remain valid results for the same engine.
        return store.pinnedPage(sourceHash, pageIndex) ?: store.loadActive(sourceHash, pipelineKey(model), pageIndex)?.takeIf {
            it.pipelineVersion == PdfOcrPipeline.VERSION && it.engineId == engine.engineId &&
                it.engineVersion == engine.engineVersion && it.configHash == configHash(model, it.rasterPlan.highRes)
        }
    }

    override suspend fun hasPinnedPage(sourceHash: String, pageIndex: Int): Boolean = store.hasPinnedPage(sourceHash, pageIndex)

    override suspend fun ocrPage(
        pdfFile: File,
        bookRoot: File?,
        sourceHash: String,
        pageIndex: Int,
        cssWidth: Double,
        cssHeight: Double,
        model: PdfOcrModel,
        force: Boolean,
        onStage: (PdfOcrStage) -> Unit,
    ): PdfOcrResult = coroutineScope {
        val requestKey = "$sourceHash|$pageIndex|$model"
        var owner = false
        val request = jobsMutex.withLock {
            inFlight[requestKey] ?: async(start = CoroutineStart.LAZY) {
                runPage(pdfFile, bookRoot, sourceHash, pageIndex, cssWidth, cssHeight, model, force, onStage)
            }.also {
                inFlight[requestKey] = it
                owner = true
            }
        }
        try {
            request.await()
        } finally {
            if (owner) withContext(NonCancellable) {
                // Native calls may finish after await observes cancellation. Keep them registered
                // until cleanup/recycling completes so deletion can still join the request.
                request.join()
                jobsMutex.withLock { if (inFlight[requestKey] === request) inFlight.remove(requestKey) }
            }
        }
    }

    private suspend fun runPage(
        pdfFile: File,
        bookRoot: File?,
        sourceHash: String,
        pageIndex: Int,
        cssWidth: Double,
        cssHeight: Double,
        model: PdfOcrModel,
        force: Boolean,
        onStage: (PdfOcrStage) -> Unit,
    ): PdfOcrResult = withContext(ioDispatcher) {
        val timings = OcrTimings()
        val engine = engines[model]
        ensureActive()
        synchronized(deletedSources) { if (sourceHash in deletedSources) throw CancellationException() }
        ensureCoordinator(bookRoot, sourceHash)
        // A page with saved anchors keeps its pinned text, even after a pipeline upgrade.
        val pinned = timings.measure("pinRead") { store.pinnedPage(sourceHash, pageIndex) }
        if (pinned != null) return@withContext resultFor(pinned)
        if (store.hasPinnedPage(sourceHash, pageIndex)) {
            return@withContext PdfOcrResult.Unavailable("Pinned OCR revision unavailable")
        }
        if (!force) timings.measure("cache") { cachedPage(sourceHash, pageIndex, model) }?.let { cached ->
            return@withContext resultFor(cached)
        }
        ensureActive()
        // Bound the entire bitmap lifetime, including raster and postprocessing.
        timings.measure("queue") { enginePermits.acquire() }
        try {
        if (loadedModel != model) {
            loadedModel?.let { engines[it].close() }
            loadedModel = model
        }
        ensureActive()
        onStage(PdfOcrStage.Rasterizing)
        val raster = timings.measure("raster") { rasterizer.rasterize(pdfFile, pageIndex) }
            ?: run {
                Log.w(PdfOcrTag, "rasterize failed page=$pageIndex")
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
            onStage(PdfOcrStage.Recognizing)
            val outcome = timings.measure("engine") {
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
                    onStage(PdfOcrStage.Postprocessing)
                    val processed = timings.measure("layout") {
                        postprocessor.process(inputs, outcome.result.imageWidth.toDouble(), outcome.result.imageHeight.toDouble())
                    }
                    val revisionId = UUID.randomUUID().toString()
                    val record = OcrPageRecord(
                        sourceSha256 = sourceHash,
                        pageIndex = pageIndex,
                        engineId = engine.engineId,
                        engineVersion = engine.engineVersion,
                        pipelineVersion = PdfOcrPipeline.VERSION,
                        configHash = configHash(model),
                        pageGeometry = OcrPageGeometryRecord(
                            nativeWidthPt = raster.nativeWidthPt,
                            nativeHeightPt = raster.nativeHeightPt,
                            cssWidth = cssWidth.takeIf { it.isFinite() && it > 0 } ?: raster.nativeWidthPt * (96.0 / 72.0),
                            cssHeight = cssHeight.takeIf { it.isFinite() && it > 0 } ?: raster.nativeHeightPt * (96.0 / 72.0),
                        ),
                        rasterPlan = OcrRasterPlanRecord(
                            dpi = raster.dpi,
                            bitmapWidth = raster.bitmap.width,
                            bitmapHeight = raster.bitmap.height,
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
                    val saved = timings.measure("save") { store.save(record, pipelineKey(model)) }
                    if (!saved) return@withContext store.pinnedPage(sourceHash, pageIndex)?.let(::resultFor)
                        ?: PdfOcrResult.Failed("Could not save OCR result", retryable = true)
                    store.scheduleEviction()
                    if (record.status == "NoText") PdfOcrResult.NoText(record) else PdfOcrResult.Ready(record)
                }
            }
        } finally {
            runCatching { raster.bitmap.recycle() }
        }
        } finally {
            enginePermits.release()
            Log.d(PdfOcrTag, "timing page=$pageIndex model=$model ${timings.summary()}")
        }
    }

    private fun resultFor(record: OcrPageRecord): PdfOcrResult =
        if (record.status == "NoText") PdfOcrResult.NoText(record) else PdfOcrResult.Ready(record)

    suspend fun cancelForSource(sourceHash: String) {
        val requests = jobsMutex.withLock {
            inFlight.filterKeys { it.startsWith("$sourceHash|") }.values.toList()
        }
        requests.forEach { it.cancel() }
        requests.forEach { it.join() }
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

    override suspend fun pinHighlightForRevision(
        highlightId: String,
        sourceHash: String,
        pageIndex: Int,
        revisionId: String,
    ): Boolean = store.pinHighlight(OcrHighlightPinRecord(highlightId, sourceHash, pageIndex, revisionId))

    override suspend fun unpinHighlight(sourceHash: String, highlightId: String) {
        store.unpinHighlight(sourceHash, highlightId)
    }

    private suspend fun ensureCoordinator(bookRoot: File?, sourceHash: String) {
        if (bookRoot == null) return
        coordinatorMutex.withLock {
            if (bookRoot in coordinators) return
            val registration = try {
                workRegistry.register(bookRoot) {
                    deleteForSource(bookRoot, sourceHash)
                }
            } catch (_: IllegalStateException) {
                throw CancellationException("Book is unavailable")
            }
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

enum class PdfOcrStage { Rasterizing, Recognizing, Postprocessing }
