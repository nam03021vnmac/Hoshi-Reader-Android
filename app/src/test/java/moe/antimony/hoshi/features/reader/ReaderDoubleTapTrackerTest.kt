package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDoubleTapTrackerTest {
    @Test
    fun secondTapWithinTimeoutAndSlopIsDoubleTap() {
        val tracker = ReaderDoubleTapTracker()

        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_000L))
        assertTrue(tracker.onTap(110f, 210f, eventTimeMs = 1_200L))
    }

    @Test
    fun tapAfterTimeoutStartsNewSequence() {
        val tracker = ReaderDoubleTapTracker()

        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_000L))
        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_301L))
        assertTrue(tracker.onTap(100f, 200f, eventTimeMs = 1_500L))
    }

    @Test
    fun distantSecondTapStartsNewSequence() {
        val tracker = ReaderDoubleTapTracker()

        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_000L))
        assertFalse(tracker.onTap(500f, 200f, eventTimeMs = 1_100L))
        assertTrue(tracker.onTap(500f, 200f, eventTimeMs = 1_200L))
    }

    @Test
    fun tripleTapCountsAsDoubleTapThenSingleTap() {
        val tracker = ReaderDoubleTapTracker()

        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_000L))
        assertTrue(tracker.onTap(100f, 200f, eventTimeMs = 1_100L))
        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_200L))
    }

    @Test
    fun resetClearsPendingFirstTap() {
        val tracker = ReaderDoubleTapTracker()

        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_000L))
        tracker.reset()
        assertFalse(tracker.onTap(100f, 200f, eventTimeMs = 1_100L))
    }
}
