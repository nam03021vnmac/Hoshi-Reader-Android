package moe.antimony.hoshi.features.reader

import kotlin.math.abs

internal class ReaderDoubleTapTracker(
    private val timeoutMs: Long = DOUBLE_TAP_TIMEOUT_MS,
    private val slopPx: Float = DOUBLE_TAP_SLOP_PX,
) {
    private var lastTapTimeMs = NO_TAP
    private var lastX = 0f
    private var lastY = 0f

    fun onTap(x: Float, y: Float, eventTimeMs: Long): Boolean {
        val isDoubleTap = lastTapTimeMs != NO_TAP &&
            eventTimeMs - lastTapTimeMs <= timeoutMs &&
            abs(x - lastX) <= slopPx &&
            abs(y - lastY) <= slopPx
        if (isDoubleTap) {
            lastTapTimeMs = NO_TAP
        } else {
            lastTapTimeMs = eventTimeMs
            lastX = x
            lastY = y
        }
        return isDoubleTap
    }

    fun reset() {
        lastTapTimeMs = NO_TAP
    }

    internal companion object {
        const val DOUBLE_TAP_TIMEOUT_MS = 300L
        const val DOUBLE_TAP_SLOP_PX = 72f
        const val NO_TAP = -1L
    }
}
