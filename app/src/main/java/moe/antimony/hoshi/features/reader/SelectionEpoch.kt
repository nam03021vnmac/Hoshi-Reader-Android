package moe.antimony.hoshi.features.reader

/**
 * Drops stale single-tap lookup results that lose a race against double-tap
 * Sasayaki seek: tap 1's dictionary query can finish after tap 2's seek
 * already cleared popups, which would flash (or stick) a popup over playing audio.
 */
internal class SelectionEpoch {
    private var epoch = 0L

    fun capture(): Long = epoch

    fun invalidate() {
        epoch++
    }

    fun isStale(captured: Long): Boolean = captured != epoch
}
