package moe.antimony.hoshi.features.reader

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.antimony.hoshi.ocr.pdf.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfOcrViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun probe(page: Int) = PdfTextProbe(page, 0, false, false, false, 600.0, 800.0)

    @Test fun visibleFirstQueueDeduplicatesProbesAndLimitsRetainedPages() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 1000)
        vm.onViewportChanged(20, 20)
        vm.onTextProbe(probe(19))
        vm.onTextProbe(probe(20))
        vm.onTextProbe(probe(20))
        vm.onTextProbe(probe(21))
        advanceUntilIdle()
        assertEquals(listOf(20, 19, 21), repository.calls.map { it.page })
        assertEquals(1, repository.hashCalls)
        assertEquals(3, vm.uiState.value.pages.size)
        vm.onViewportChanged(100, 100)
        vm.onTextProbe(probe(100))
        advanceUntilIdle()
        assertEquals(setOf(100), vm.uiState.value.pages.keys)
    }

    @Test fun retryForcesNewInferenceAndKeepsOriginalProbeGeometry() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 10)
        vm.onTextProbe(probe(0))
        advanceUntilIdle()
        vm.retry(0, highRes = true)
        advanceUntilIdle()
        val retry = repository.calls.last()
        assertTrue(retry.force && retry.highRes)
        assertEquals(600.0, retry.width, 0.0)
        assertTrue(vm.uiState.value.page(0).highResUsed)
    }

    @Test fun explicitRetryRecoversFromTransientSourceHashFailure() = runTest(dispatcher) {
        val repository = FakeRepository().apply { failHash = true }
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 10)
        vm.onTextProbe(probe(0))
        advanceUntilIdle()
        assertEquals(PdfOcrPageStatus.Failed, vm.uiState.value.page(0).status)
        repository.failHash = false
        vm.retry(0)
        advanceUntilIdle()
        assertEquals(2, repository.hashCalls)
        assertEquals(PdfOcrPageStatus.OcrReady, vm.uiState.value.page(0).status)
    }

    @Test fun blankAndFailedPagesDoNotAutomaticallyRetryOnViewportReports() = runTest(dispatcher) {
        val repository = FakeRepository().apply { result = PdfOcrResult.Failed("fixture", true) }
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 10)
        vm.onTextProbe(probe(0))
        advanceUntilIdle()
        repeat(10) { vm.onViewportChanged(0, 0); vm.onTextProbe(probe(0)) }
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
        vm.retry(0)
        advanceUntilIdle()
        assertEquals(2, repository.calls.size)
    }

    @Test fun newVisibleWorkCancelsPrefetchAndRequeuesItOnlyAfterVisiblePage() = runTest(dispatcher) {
        val repository = FakeRepository().apply { blockFirst = true }
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 10)
        vm.onViewportChanged(1, 1)
        vm.onTextProbe(probe(0))
        runCurrent()
        assertEquals(listOf(0), repository.calls.map { it.page })
        vm.onTextProbe(probe(1))
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 0), repository.calls.map { it.page })
        assertEquals(PdfOcrPageStatus.OcrReady, vm.uiState.value.page(0).status)
    }

    @Test fun lateHashFromAnOldBookCannotOverwriteCurrentSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<String?>()
        val repository = FakeRepository().apply { hashGate = gate }
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("old.pdf"), null, 10)
        runCurrent()
        repository.hashGate = null
        vm.attachBook(File("new.pdf"), null, 10)
        advanceUntilIdle()
        val session = vm.uiState.value.sessionId
        gate.complete("b".repeat(64))
        advanceUntilIdle()
        assertEquals(session, vm.uiState.value.sessionId)
        assertEquals("a".repeat(64), vm.uiState.value.sourceHash)
    }

    @Test fun highlightPinsInstalledRevisionAndFailedPinDoesNotSaveSidecar() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = PdfOcrViewModel(repository)
        vm.attachBook(File("book.pdf"), null, 10)
        vm.onTextProbe(probe(0))
        advanceUntilIdle()
        var saves = 0
        vm.pinForHighlight("highlight", 0, "displayed-old") { saves++ }
        advanceUntilIdle()
        assertEquals("displayed-old", repository.pinRevision)
        assertTrue(vm.uiState.value.page(0).revisionLocked)
        vm.retry(0, true)
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
        repository.pinSucceeds = false
        vm.pinForHighlight("other", 0, "displayed-old") { saves++ }
        advanceUntilIdle()
        assertEquals(1, saves)
        assertNotNull(vm.uiState.value.page(0).error)
    }

    private data class Call(val page: Int, val width: Double, val highRes: Boolean, val force: Boolean)

    private class FakeRepository : PdfOcrReaderRepository {
        var hashCalls = 0
        var failHash = false
        var hashGate: CompletableDeferred<String?>? = null
        val calls = mutableListOf<Call>()
        var result: PdfOcrResult? = null
        var blockFirst = false
        var pinRevision: String? = null
        var pinSucceeds = true
        override suspend fun sourceHashFor(pdfFile: File): String? {
            hashCalls++
            if (failHash) return null
            return hashGate?.await() ?: "a".repeat(64)
        }
        override suspend fun hasPinnedPage(sourceHash: String, pageIndex: Int): Boolean = pinRevision != null
        override suspend fun cachedPage(sourceHash: String, pageIndex: Int): OcrPageRecord? = null
        override suspend fun ocrPage(pdfFile: File, bookRoot: File?, sourceHash: String, pageIndex: Int,
            cssWidth: Double, cssHeight: Double, highRes: Boolean, force: Boolean, onStage: (PdfOcrStage) -> Unit): PdfOcrResult {
            calls += Call(pageIndex, cssWidth, highRes, force)
            if (blockFirst && calls.size == 1) awaitCancellation()
            onStage(PdfOcrStage.Rasterizing)
            onStage(PdfOcrStage.Recognizing)
            onStage(PdfOcrStage.Postprocessing)
            return result ?: PdfOcrResult.Ready(OcrPageRecord(
                sourceSha256 = sourceHash, pageIndex = pageIndex, engineId = "fixture", engineVersion = "1", configHash = "fixture",
                pageGeometry = OcrPageGeometryRecord(450, 600, cssWidth, cssHeight),
                rasterPlan = OcrRasterPlanRecord(200, 100, 100, highRes), revisionId = "r${calls.size}", status = "OcrReady",
                rawObservations = emptyList(), paragraphs = emptyList(), canonicalText = "猫", spans = listOf(OcrSpanRecord("l", 0, 1)),
            ))
        }
        override suspend fun pinHighlightForRevision(highlightId: String, sourceHash: String, pageIndex: Int, revisionId: String): Boolean {
            if (pinSucceeds) pinRevision = revisionId
            return pinSucceeds
        }
        override suspend fun unpinHighlight(sourceHash: String, highlightId: String) { pinRevision = null }
    }
}
