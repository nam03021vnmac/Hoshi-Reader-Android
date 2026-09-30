package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.yield
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ocr.pdf.OcrPageRecord
import moe.antimony.hoshi.ocr.pdf.PdfOcrReaderRepository
import moe.antimony.hoshi.ocr.pdf.PdfOcrResult
import moe.antimony.hoshi.ocr.pdf.PdfOcrRequestQueue
import moe.antimony.hoshi.ocr.pdf.PdfOcrStage
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
    val revisionLocked: Boolean = false,
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


/** Entry-scoped OCR state; never Activity-wide. Survives configuration recreation. */
@HiltViewModel
internal class PdfOcrViewModel @Inject constructor(
    private val repository: PdfOcrReaderRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PdfOcrUiState())
    val uiState = _uiState.asStateFlow()

    private var pdfFile: File? = null
    private var bookRoot: File? = null
    private var pageCount: Int = 0
    private data class Request(val probe: PdfTextProbe, val highRes: Boolean, val force: Boolean, val id: Int)
    private val queue = PdfOcrRequestQueue<Request>()
    private var worker: Job? = null
    private var activePage: Int? = null
    private var requeueActive = false
    private var sourceHashJob: Deferred<String?>? = null
    private val probes = mutableMapOf<Int, PdfTextProbe>()
    private val requestCounter = AtomicInteger(0)

    fun attachBook(pdfFile: File, bookRoot: File?, pageCount: Int) {
        val changed = this.pdfFile?.absolutePath != pdfFile.absolutePath ||
            this.bookRoot?.absolutePath != bookRoot?.absolutePath
        this.pdfFile = pdfFile
        this.bookRoot = bookRoot
        this.pageCount = pageCount
        if (changed) {
            worker?.cancel()
            worker = null
            activePage = null
            requeueActive = false
            sourceHashJob?.cancel()
            queue.clear()
            probes.clear()
            queue.updateViewport(0, 0, pageCount)
            _uiState.update { PdfOcrUiState(sessionId = UUID.randomUUID().toString()) }
            startSourceHash(pdfFile)
        }
    }

    private fun startSourceHash(file: File) {
        val session = _uiState.value.sessionId
        sourceHashJob = viewModelScope.async {
            val hash = try {
                repository.sourceHashFor(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ensureActive()
            if (_uiState.value.sessionId == session) _uiState.update { it.copy(sourceHash = hash) }
            hash
        }
    }

    fun onViewportChanged(firstVisible: Int, lastVisible: Int) {
        // Drop obsolete prefetch when direction changes; keep visible + one-page prefetch.
        queue.updateViewport(firstVisible, lastVisible, pageCount)
        if (activePage?.let { !queue.isNearby(it) ||
                (it !in queue.firstVisible..queue.lastVisible && queue.hasVisiblePending()) } == true) {
            requeueActive = activePage?.let(queue::isNearby) == true
            worker?.cancel()
        }
        _uiState.update { state -> state.copy(pages = state.pages.filterKeys(queue::isNearby)) }
        probes.keys.removeAll { !queue.isNearby(it) }
        // A previously discarded page can already have rendered pixels; reuse its last probe.
        probes.values.toList().forEach(::onTextProbe)
        startWorker()
    }

    fun onTextProbe(probe: PdfTextProbe) {
        val pageIndex = probe.pageIndex
        if (pageIndex < 0 || pageIndex >= pageCount.coerceAtLeast(1)) return
        if (!queue.isNearby(pageIndex)) return
        probes[pageIndex] = probe
        val current = _uiState.value.page(pageIndex)
        // Never change active selection/highlight offsets mid-gesture: only act when idle.
        if (current.status == PdfOcrPageStatus.Queued ||
            current.status == PdfOcrPageStatus.Rasterizing ||
            current.status == PdfOcrPageStatus.Recognizing ||
            current.status == PdfOcrPageStatus.Postprocessing ||
            current.status == PdfOcrPageStatus.OcrReady ||
            current.status == PdfOcrPageStatus.NoText ||
            current.status == PdfOcrPageStatus.Failed
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
        val probe = probes[pageIndex] ?: return
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
        queue.remove(pageIndex)
        if (activePage == pageIndex) worker?.cancel()
        _uiState.update { state ->
            val current = state.page(pageIndex)
            state.copy(pages = state.pages + (pageIndex to current.copy(status = PdfOcrPageStatus.EmbeddedReady, overriddenToOcr = false, error = null, requestId = requestCounter.incrementAndGet())))
        }
    }

    fun pinForHighlight(highlightId: String, pageIndex: Int, installedRevisionId: String? = null, onDone: () -> Unit = {}) {
        val state = _uiState.value
        val revisionId = installedRevisionId ?: state.page(pageIndex).record?.revisionId ?: run { onDone(); return }
        val sourceHash = state.sourceHash ?: return
        viewModelScope.launch {
            // Persist the revision pin before writing the highlight sidecar.
            val saved = try {
                repository.pinHighlightForRevision(highlightId, sourceHash, pageIndex, revisionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (saved) {
                val pinnedRecord = repository.cachedPage(sourceHash, pageIndex)
                _uiState.update { current ->
                    val page = current.page(pageIndex)
                    if (current.sessionId != state.sessionId) current else
                        current.copy(pages = current.pages + (pageIndex to page.copy(
                            record = pinnedRecord ?: page.record, revisionLocked = true)))
                }
                if (_uiState.value.sessionId == state.sessionId) onDone()
            } else {
                _uiState.update { current -> if (current.sessionId != state.sessionId) current else
                    current.copy(pages = current.pages +
                        (pageIndex to current.page(pageIndex).copy(error = UiText.Resource(R.string.pdf_ocr_failed)))) }
            }
        }
    }

    fun unpinForHighlight(highlightId: String) {
        val sourceHash = _uiState.value.sourceHash ?: return
        viewModelScope.launch {
            runCatching { repository.unpinHighlight(sourceHash, highlightId) }
            val pages = _uiState.value.pages.keys.toList()
            for (pageIndex in pages) {
                val locked = repository.hasPinnedPage(sourceHash, pageIndex)
                _uiState.update { state ->
                    if (state.sourceHash != sourceHash || pageIndex !in state.pages) state else
                        state.copy(pages = state.pages + (pageIndex to state.page(pageIndex).copy(revisionLocked = locked)))
                }
            }
        }
    }

    private fun queueOcr(
        pageIndex: Int,
        probe: PdfTextProbe,
        highRes: Boolean,
        explicitRetry: Boolean = false,
    ) {
        if (pdfFile == null || pageIndex !in 0 until pageCount || !queue.isNearby(pageIndex)) return
        if (_uiState.value.page(pageIndex).revisionLocked && explicitRetry) return
        if (activePage == pageIndex || _uiState.value.page(pageIndex).status == PdfOcrPageStatus.Queued) return
        if (explicitRetry && _uiState.value.sourceHash == null && sourceHashJob?.isCompleted == true) {
            startSourceHash(pdfFile ?: return)
        }
        val requestId = requestCounter.incrementAndGet()
        _uiState.update { state ->
            state.copy(pages = state.pages + (pageIndex to state.page(pageIndex).copy(status = PdfOcrPageStatus.Queued, error = null, retryable = false, requestId = requestId, highResUsed = highRes)))
        }
        queue.put(pageIndex, Request(probe, highRes, explicitRetry, requestId))
        if (pageIndex in queue.firstVisible..queue.lastVisible &&
            activePage?.let { it !in queue.firstVisible..queue.lastVisible } == true) {
            requeueActive = true
            worker?.cancel()
        }
        startWorker()
    }

    private fun startWorker() {
        if (worker != null || queue.isEmpty() || !viewModelScope.isActive) return
        val file = pdfFile ?: return
        val root = bookRoot
        val session = _uiState.value.sessionId
        val hashJob = sourceHashJob ?: return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                yield() // Collect same-frame probes before choosing visible work over prefetch.
                val sourceHash = hashJob.await()
                ensureActive()
                if (sourceHash == null) {
                    _uiState.update { state -> state.copy(pages = state.pages.mapValues { (_, page) ->
                        if (page.status == PdfOcrPageStatus.Queued) page.copy(status = PdfOcrPageStatus.Failed,
                            error = UiText.Resource(R.string.pdf_ocr_failed), retryable = true) else page
                    }) }
                    queue.clear()
                    return@launch
                }
                while (true) {
                    val (pageIndex, request) = queue.poll() ?: break
                    activePage = pageIndex
                    requeueActive = false
                    runRequest(file, root, sourceHash, session, pageIndex, request)
                    activePage = null
                }
            } finally {
                if (worker === currentCoroutineContext().job) {
                    worker = null
                    activePage = null
                    if (_uiState.value.sessionId == session) startWorkerIfPending()
                }
            }
        }
        worker = job
        job.start()
    }

    private fun startWorkerIfPending() {
        val next = queue.poll() ?: return
        queue.put(next.first, next.second)
        startWorker()
    }

    private suspend fun runRequest(file: File, root: File?, sourceHash: String, session: String, pageIndex: Int, request: Request) {
        val requestId = request.id
        fun updatePage(block: (PdfOcrPageUiState) -> PdfOcrPageUiState) {
            _uiState.update { state ->
                if (state.sessionId != session || state.page(pageIndex).requestId != requestId) state else
                    state.copy(pages = state.pages + (pageIndex to block(state.page(pageIndex))))
            }
        }
            try {
                // Staged status updates; repository owns the bounded execution.
                val result = repository.ocrPage(
                    pdfFile = file,
                    bookRoot = root,
                    sourceHash = sourceHash,
                    pageIndex = pageIndex,
                    cssWidth = request.probe.cssWidth,
                    cssHeight = request.probe.cssHeight,
                    highRes = request.highRes,
                    force = request.force,
                    onStage = { stage ->
                        updatePage { it.copy(status = when (stage) {
                            PdfOcrStage.Rasterizing -> PdfOcrPageStatus.Rasterizing
                            PdfOcrStage.Recognizing -> PdfOcrPageStatus.Recognizing
                            PdfOcrStage.Postprocessing -> PdfOcrPageStatus.Postprocessing
                        }) }
                    },
                )
                currentCoroutineContext().ensureActive()
                val locked = repository.hasPinnedPage(sourceHash, pageIndex)
                when (result) {
                    is PdfOcrResult.Ready -> {
                        updatePage { it.copy(status = PdfOcrPageStatus.OcrReady, record = result.record, error = null, retryable = false,
                            highResUsed = result.record.rasterPlan.highRes, revisionLocked = locked) }
                    }
                    is PdfOcrResult.NoText -> {
                        updatePage { it.copy(status = PdfOcrPageStatus.NoText, record = result.record, error = null, retryable = true,
                            highResUsed = result.record.rasterPlan.highRes, revisionLocked = locked) }
                    }
                    is PdfOcrResult.Failed -> {
                        updatePage { it.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_failed), retryable = result.retryable) }
                    }
                    is PdfOcrResult.Unavailable -> {
                        updatePage { it.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_unavailable), retryable = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                updatePage { it.copy(status = if (it.record != null) PdfOcrPageStatus.OcrReady else PdfOcrPageStatus.Unassessed, error = null) }
                if (requeueActive && _uiState.value.sessionId == session && queue.isNearby(pageIndex) &&
                    _uiState.value.page(pageIndex).requestId == requestId && viewModelScope.isActive) {
                    queue.put(pageIndex, request)
                    updatePage { it.copy(status = PdfOcrPageStatus.Queued) }
                }
                throw cancelled
            } catch (_: Exception) {
                updatePage { it.copy(status = PdfOcrPageStatus.Failed, error = UiText.Resource(R.string.pdf_ocr_failed), retryable = true) }
            }
    }

    override fun onCleared() {
        worker?.cancel()
        sourceHashJob?.cancel()
        queue.clear()
    }
}
