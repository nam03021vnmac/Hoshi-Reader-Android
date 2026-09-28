package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionEpochTest {
    @Test
    fun capturedEpochIsFreshUntilInvalidated() {
        val epoch = SelectionEpoch()

        val captured = epoch.capture()

        assertFalse(epoch.isStale(captured))
    }

    @Test
    fun invalidateStalesPreviouslyCapturedEpoch() {
        val epoch = SelectionEpoch()

        val captured = epoch.capture()
        epoch.invalidate()

        assertTrue(epoch.isStale(captured))
    }

    @Test
    fun epochCapturedAfterInvalidateIsFresh() {
        val epoch = SelectionEpoch()
        epoch.invalidate()

        val captured = epoch.capture()

        assertFalse(epoch.isStale(captured))
    }
}
