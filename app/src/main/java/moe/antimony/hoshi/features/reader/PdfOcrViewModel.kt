package moe.antimony.hoshi.features.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ocr.pdf.OcrPageRecord
import moe.antimony.hoshi.ocr.pdf.PdfOcrRepository
import moe.antimony.hoshi.ocr.pdf.PdfOcrResult
import moe.antimony.hoshi.ui.UiText

enum class PdfOcrPageStatus {
    Unassessed,
    EmbeddedReady,
    Suspect,
    ExtractionFailed,
    Queued,
    Rasterizing,
    Recognizing,
    Postprocessing,
    OcrReady,
    NoText,
    Failed,
}

data class PdfOcrPageUiState(
    val pageIndex: Int,
    val status: PdfOcrPageStatus = PdfOcrPageStatus.Unassessed,
    val record: OcrPageRecord? = null,
    val error: UiText? = null,
    val retryable: Boolean = false,
    val highResUsed: Boolean = false,
    val overriddenToOcr: Boolean = false,
    val requestId: Int = 0,
)

data class PdfOcrUiState(
    val sessionId: String = UUID.randomUUID().toString(),
    val sourceHash: String? = null,
    val pages: Map<Int, PdfOcrPageUiState> = emptyMap(),
) {
    fun page(pageIndex: Int): PdfOcrPageUiState =
        pages[pageIndex] ?: PdfOcrPageUiState(pageIndex = pageIndex)
}

data class PdfTextProbe(
    val pageIndex: Int,
    val textLength: Int,
    val hasSuspectChars: Boolean,
    val isSingleNumber: Boolean,
    val extractionFailed: Boolean,
    val cssWidth: Double,
    val cssHeight: Double,
)

private const val PdfOcrTag = "PdfOcr"

/** Entry-scoped OCR state; never Activity-wide. Survives configuration recreation. */
@HiltViewModel
internal class PdfOcrViewModel @Inject constructor(
    private val repository: PdfOcrRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PdfOcrUiState())
    val uiState = _uiState.asStateFlow()

    private var pdfFile: File? = null
    private var bookRoot: File? = null
    private var pageCount: Int = 0
    private val jobs = mutableMapOf<Int, Job>()
    private val requestCounter = AtomicInteger(0)

    fun attachBook(pdfFile: File, bookRoot: File?, pageCount: Int) {
        val changed = this.pdfFile?.absolutePath != pdfFile.absolutePath ||
            this.bookRoot?.absolutePath != bookRoot?.absolutePath
        this.pdfFile = pdfFile
        this.bookRoot = bookRoot
        this.pageCount = pageCount
        if (changed) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            _uiState.update { PdfOcrUiState(sessionId = UUID.randomUUID().toString()) }
            viewModelScope.launch {
                val hash = runCatching { repository.sourceHashFor(pdfFile) }.getOrNull()
                _uiState.update { it.copy(sourceHash = hash) }
                // Replay persisted results on reopen without rerunning inference.
                if (hash != null) {
                    for (pageIndex in 0 until pageCount) {
                        val cached = runCatching { repository.cachedPage(hash, pageIndex) }.getOrNull()
                        if (cached != null) {
                            _uiState.update { state ->
                                val next = state.page(pageIndex).copy(
                                    status = if (cached.status == "NoText") PdfOcrPageStatus.NoText else PdfOcrPageStatus.OcrReady,
                                    record = cached,
                                    error = null,
                                    retryable = false,
                                )
                                state.copy(pages = state.pages + (pageIndex to next))
                            }
                        }
                    }
                }
            }
        }
    }

    fun onViewportChanged(firstVisible: Int, lastVisible: Int) {
        // Drop obsolete prefetch when direction changes; keep visible + one-page prefetch.
        val low = (firstVisible - 1).coerceAtLeast(0)
        val high = (lastVisible + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        val obsolete = jobs.keys.filter { it < low || it > high }
        for (pageIndex in obsolete) {
            jobs.remove(pageIndex)?.cancel()
            _uiState.update { state ->
                val current = state.page(pageIndex)
                // Cancellation returns to an idle/retryable state, never cached as NoText.
                if (current.status == PdfOcrPageStatus.Queued ||
                    current.status == PdfOcrPageStatus.Rasterizing ||
                    current.status == PdfOcrPageStatus.Recognizing ||
                    current.status == PdfOcrPageStatus.Postprocessing
                ) {
                    state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Unassessed, error = null)))
                } else {
                    state
                }
            }
        }
    }

    fun onTextProbe(probe: PdfTextProbe) {
        val pageIndex = probe.pageIndex
        if (pageIndex < 0 || pageIndex >= pageCount.coerceAtLeast(1)) return
        val current = _uiState.value.page(pageIndex)
        Log.d(
            PdfOcrTag,
            "probe page=$pageIndex status=${current.status} len=${probe.textLength} " +
                "suspect=${probe.hasSuspectChars} single=${probe.isSingleNumber} " +
                "extractFailed=${probe.extractionFailed}",
        )
        // Never change active selection/highlight offsets mid-gesture: only act when idle.
        if (current.status == PdfOcrPageStatus.Queued ||
            current.status == PdfOcrPageStatus.Rasterizing ||
            current.status == PdfOcrPageStatus.Recognizing ||
            current.status == PdfOcrPageStatus.Postprocessing ||
            current.status == PdfOcrPageStatus.OcrReady
        ) {
            return
        }
        // An extraction exception is retryable, not proof the PDF is scanned.
        if (probe.extractionFailed) {
            _uiState.update { state ->
                state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.ExtractionFailed, error = UiText.Resource(R.string.pdf_ocr_extraction_failed), retryable = true)))
            }
            return
        }
        // Ignore whitespace/control-only: JS already trimmed; zero means missing.
        if (probe.textLength <= 0) {
            // Missing queues OCR automatically, but prefer a persisted result first.
            val cached = current.record
            if (cached != null) {
                _uiState.update { state ->
                    state.copy(pages = state.pages + (pageIndex to current.copy(status = if (cached.status == "NoText") PdfOcrPageStatus.NoText else PdfOcrPageStatus.OcrReady, error = null)))
                }
                return
            }
            queueOcr(pageIndex, probe, highRes = false)
            return
        }
        // Suspect covers replacement/private-use-heavy text or an isolated page
        // number over a scanned body; offer a page OCR action instead of replacing.
        if (probe.hasSuspectChars || probe.isSingleNumber) {
            if (current.overriddenToOcr) {
                queueOcr(pageIndex, probe, highRes = current.highResUsed)
            } else {
                _uiState.update { state ->
                    state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Suspect, error = null, retryable = true)))
                }
            }
            return
        }
        _uiState.update { state ->
            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.EmbeddedReady, error = null, retryable = false)))
        }
    }

    fun retry(pageIndex: Int, highRes: Boolean = false) {
        val file = pdfFile ?: return
        val probe = PdfTextProbe(pageIndex, 0, false, false, false, 0.0, 0.0)
        queueOcr(pageIndex, probe, highRes = highRes, explicitRetry = true)
    }

    fun retryWithProbe(pageIndex: Int, cssWidth: Double, cssHeight: Double, highRes: Boolean) {
        queueOcr(
            pageIndex,
            PdfTextProbe(pageIndex, 0, false, false, false, cssWidth, cssHeight),
            highRes = highRes,
            explicitRetry = true,
        )
    }

    fun useOcrForSuspect(pageIndex: Int, cssWidth: Double, cssHeight: Double) {
        _uiState.update { state ->
            val current = state.page(pageIndex)
            state.copy(pages = state.pages + (pageIndex to current.copy(overriddenToOcr = true)))
        }
        queueOcr(
            pageIndex,
            PdfTextProbe(pageIndex, 0, false, false, false, cssWidth, cssHeight),
            highRes = false,
            explicitRetry = true,
        )
    }

    fun useEmbedded(pageIndex: Int) {
        jobs.remove(pageIndex)?.cancel()
        _uiState.update { state ->
            val current = state.page(pageIndex)
            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.EmbeddedReady, overriddenToOcr = false, error = null)))
        }
    }

    fun pinForHighlight(highlightId: String, pageIndex: Int, onDone: () -> Unit = {}) {
        val state = _uiState.value
        val sourceHash = state.sourceHash ?: run { onDone(); return }
        val record = state.page(pageIndex).record ?: run { onDone(); return }
        viewModelScope.launch {
            // Persist the revision pin before writing the highlight sidecar.
            runCatching { repository.pinHighlightForRevision(highlightId, sourceHash, pageIndex, record.revisionId) }
            onDone()
        }
    }

    fun unpinForHighlight(highlightId: String) {
        val sourceHash = _uiState.value.sourceHash ?: return
        viewModelScope.launch {
            runCatching { repository.unpinHighlight(sourceHash, highlightId) }
        }
    }

    private fun queueOcr(
        pageIndex: Int,
        probe: PdfTextProbe,
        highRes: Boolean,
        explicitRetry: Boolean = false,
    ) {
        val file = pdfFile ?: return
        val sourceHash = _uiState.value.sourceHash
        if (sourceHash == null) {
            viewModelScope.launch {
                val hash = runCatching { repository.sourceHashFor(file) }.getOrNull()
                if (hash == null) {
                    _uiState.update { state ->
                        state.copy(pages = state.pages + (pageIndex to state.page(pageIndex).copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_failed), retryable = true)))
                    }
                    return@launch
                }
                _uiState.update { it.copy(sourceHash = hash) }
                queueOcr(pageIndex, probe, highRes, explicitRetry)
            }
            return
        }
        // One pending request per (source, page, configuration).
        if (jobs.containsKey(pageIndex)) return
        val requestId = requestCounter.incrementAndGet()
        Log.d(PdfOcrTag, "queue page=$pageIndex request=$requestId highRes=$highRes retry=$explicitRetry")
        _uiState.update { state ->
            state.copy(pages = state.pages + (pageIndex to state.page(pageIndex).copy(status = PdfOcrPageStatus.Queued, error = null, retryable = false, requestId = requestId, highResUsed = highRes)))
        }
        val job = viewModelScope.launch {
            try {
                _uiState.update { state ->
                    state.copy(pages = state.pages + (pageIndex to state.page(pageIndex).copy(status = PdfOcrPageStatus.Rasterizing)))
                }
                // Staged status updates; repository owns the bounded execution.
                val result = repository.ocrPage(
                    pdfFile = file,
                    bookRoot = bookRoot,
                    sourceHash = sourceHash,
                    pageIndex = pageIndex,
                    cssWidth = probe.cssWidth,
                    cssHeight = probe.cssHeight,
                    highRes = highRes,
                )
                when (result) {
                    is PdfOcrResult.Ready -> {
                        Log.d(PdfOcrTag, "ready page=$pageIndex request=$requestId revision=${result.record.revisionId}")
                        _uiState.update { state ->
                            // Install atomically when the request is still current.
                            val current = state.page(pageIndex)
                            if (current.requestId != requestId) return@update state
                            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.OcrReady, record = result.record, error = null, retryable = false)))
                        }
                    }
                    is PdfOcrResult.NoText -> {
                        Log.d(PdfOcrTag, "noText page=$pageIndex request=$requestId")
                        _uiState.update { state ->
                            val current = state.page(pageIndex)
                            if (current.requestId != requestId) return@update state
                            // Cache successful empty results so blank pages do not loop.
                            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.NoText, record = result.record, error = null, retryable = true)))
                        }
                    }
                    is PdfOcrResult.Failed -> {
                        Log.w(PdfOcrTag, "failed page=$pageIndex request=$requestId reason=${result.reason} retryable=${result.retryable}")
                        _uiState.update { state ->
                            val current = state.page(pageIndex)
                            if (current.requestId != requestId) return@update state
                            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_failed), retryable = result.retryable)))
                        }
                    }
                    is PdfOcrResult.Unavailable -> {
                        Log.w(PdfOcrTag, "unavailable page=$pageIndex request=$requestId reason=${result.reason}")
                        _uiState.update { state ->
                            val current = state.page(pageIndex)
                            if (current.requestId != requestId) return@update state
                            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_unavailable), retryable = false)))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                _uiState.update { state ->
                    val current = state.page(pageIndex)
                    if (current.requestId != requestId) return@update state
                    state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Unassessed, error = null)))
                }
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { state ->
                    val current = state.page(pageIndex)
                    if (current.requestId != requestId) return@update state
                    state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_failed), retryable = true)))
                }
            } finally {
                jobs.remove(pageIndex)
            }
        }
        jobs[pageIndex] = job
    }

    override fun onCleared() {
        for (job in jobs.values) job.cancel()
        jobs.clear()
    }
}
