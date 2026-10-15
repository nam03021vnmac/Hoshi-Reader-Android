package moe.antimony.hoshi.ocr

import moe.antimony.hoshi.ocr.pdf.PdfOcrRequestQueue
import moe.antimony.hoshi.ocr.pdf.PdfOcrRasterPlan
import org.junit.Assert.*
import org.junit.Test

class PdfOcrSchedulingTest {
    @Test fun visiblePagesOutrankEarlierPrefetchAndRequestsAreDeduplicated() {
        val queue = PdfOcrRequestQueue<String>()
        queue.updateViewport(10, 11, 100)
        queue.put(9, "prefetch")
        queue.put(12, "other prefetch")
        queue.put(11, "stale")
        queue.put(11, "latest")
        queue.put(10, "visible")
        assertTrue(queue.hasVisiblePending())
        assertEquals(10 to "visible", queue.poll())
        assertEquals(11 to "latest", queue.poll())
        assertFalse(queue.hasVisiblePending())
        assertEquals(9 to "prefetch", queue.poll())
        assertEquals(12 to "other prefetch", queue.poll())
        assertNull(queue.poll())
    }

    @Test fun viewportChangesDropObsoleteWorkAndReprioritizeRemainingPages() {
        val queue = PdfOcrRequestQueue<Int>()
        queue.updateViewport(0, 0, 100)
        (0..4).forEach { queue.put(it, it) }
        assertEquals(setOf(0, 1), queue.updateViewport(3, 3, 100))
        assertEquals(3 to 3, queue.poll())
        assertEquals(2 to 2, queue.poll())
        assertEquals(4 to 4, queue.poll())
        assertFalse(queue.isNearby(50))
    }

    @Test fun a4RasterStaysWithinPixelBudget() {
        val plan = PdfOcrRasterPlan.create(595, 842)
        assertTrue(plan.width.toLong() * plan.height <= 3_000_000L)
        assertTrue(plan.scale < 200 / 72f)
    }

    @Test fun smallPagesKeepRequestedDpiAndExtremePagesRemainBounded() {
        val small = PdfOcrRasterPlan.create(72, 72)
        assertEquals(200, small.width)
        for (size in listOf(100_000 to 100_000, 1 to 100_000)) {
            val plan = PdfOcrRasterPlan.create(size.first, size.second)
            assertTrue(plan.width > 0 && plan.height > 0)
            assertTrue(plan.width.toLong() * plan.height <= 3_000_000L)
        }
    }
}
