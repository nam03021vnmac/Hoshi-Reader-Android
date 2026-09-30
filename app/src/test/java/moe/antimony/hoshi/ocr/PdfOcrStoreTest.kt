package moe.antimony.hoshi.ocr

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.ocr.pdf.*
import org.junit.Assert.*
import org.junit.Test

class PdfOcrStoreTest {
    private fun record(page: Int = 0, revision: String = "old") = OcrPageRecord(
        sourceSha256 = "a".repeat(64), pageIndex = page, engineId = "test", engineVersion = "1",
        configHash = "test", pageGeometry = OcrPageGeometryRecord(595, 842, 793.0, 1122.0),
        rasterPlan = OcrRasterPlanRecord(200, 100, 100, false), revisionId = revision,
        status = "OcrReady", rawObservations = emptyList(), paragraphs = emptyList(),
        canonicalText = "猫", spans = listOf(OcrSpanRecord("line", 0, 1)),
    )

    @Test fun pinsReplayAcrossPipelineUpgradesAndPreventOffsetReplacement() = runBlocking {
        val root = Files.createTempDirectory("ocr-store-pins").toFile()
        try {
            val store = PdfOcrStore(root, Dispatchers.IO)
            val old = record()
            assertTrue(store.save(old, "pipeline-v1"))
            assertTrue(store.pinHighlight(OcrHighlightPinRecord("highlight", old.sourceSha256, 0, old.revisionId)))
            assertEquals(old, store.pinnedPage(old.sourceSha256, 0))
            assertFalse(store.save(record(revision = "new"), "pipeline-v2"))
            assertFalse(store.pinHighlight(OcrHighlightPinRecord("missing", old.sourceSha256, 1, "missing")))
            val reopened = PdfOcrStore(root, Dispatchers.IO)
            assertEquals(old, reopened.pinnedPage(old.sourceSha256, 0))
            reopened.unpinHighlight(old.sourceSha256, "highlight")
            assertFalse(reopened.hasPinnedPage(old.sourceSha256, 0))
            assertTrue(reopened.save(record(revision = "new"), "pipeline-v2"))
        } finally { root.deleteRecursively() }
    }

    @Test fun quotaPreservesPinsButEvictsUnpinnedActiveRecordsAndMemoryEntries() = runBlocking {
        val root = Files.createTempDirectory("ocr-store-quota").toFile()
        try {
            val store = PdfOcrStore(root, Dispatchers.IO)
            val pinned = record()
            val unpinned = record(page = 1)
            assertTrue(store.save(pinned, "pipeline-v1"))
            assertTrue(store.save(unpinned, "pipeline-v1"))
            store.pinHighlight(OcrHighlightPinRecord("highlight", pinned.sourceSha256, 0, pinned.revisionId))
            store.evictUnpinnedIfOverQuota(maxBytes = 0)
            assertEquals(pinned, store.pinnedPage(pinned.sourceSha256, 0))
            assertEquals(pinned, store.loadActive(pinned.sourceSha256, "pipeline-v1", 0))
            assertNull(store.loadActive(unpinned.sourceSha256, "pipeline-v1", 1))
        } finally { root.deleteRecursively() }
    }

    @Test fun memoryCacheIsBoundedAndDeletionRemovesPinsAndResults() = runBlocking {
        val root = Files.createTempDirectory("ocr-store-memory").toFile()
        try {
            val store = PdfOcrStore(root, Dispatchers.IO)
            for (page in 0..8) assertTrue(store.save(record(page), "pipeline"))
            // The oldest entry must be read from disk rather than retained indefinitely.
            root.resolve("${record().sourceSha256}/pipeline/0/active.json").delete()
            assertNull(store.loadActive(record().sourceSha256, "pipeline", 0))
            store.deleteSource(record().sourceSha256)
            assertNull(store.loadActive(record().sourceSha256, "pipeline", 8))
        } finally { root.deleteRecursively() }
    }

    @Test fun maintenanceKeepsBothDisplayedAndPendingRevisionsInTheWorkingSet() = runBlocking {
        val root = Files.createTempDirectory("ocr-store-working-set").toFile()
        try {
            val store = PdfOcrStore(root, Dispatchers.IO)
            val old = record()
            assertTrue(store.save(old, "pipeline"))
            assertTrue(store.save(record(revision = "pending"), "pipeline"))
            store.evictUnpinnedIfOverQuota(maxBytes = 0, protectWorkingSet = true)
            assertTrue(store.pinHighlight(OcrHighlightPinRecord("selection", old.sourceSha256, 0, old.revisionId)))
            assertEquals(old, store.pinnedPage(old.sourceSha256, 0))
        } finally { root.deleteRecursively() }
    }
}
