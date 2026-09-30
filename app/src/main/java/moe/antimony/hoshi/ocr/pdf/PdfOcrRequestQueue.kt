package moe.antimony.hoshi.ocr.pdf

/** Main-thread-owned queue. Choose at dispatch time, not when probes arrive. */
internal class PdfOcrRequestQueue<T> {
    private val pending = linkedMapOf<Int, T>()
    var firstVisible: Int = 0
        private set
    var lastVisible: Int = 0
        private set

    fun updateViewport(first: Int, last: Int, pageCount: Int): Set<Int> {
        firstVisible = first.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        lastVisible = last.coerceIn(firstVisible, (pageCount - 1).coerceAtLeast(firstVisible))
        val removed = pending.keys.filterNot(::isNearby).toSet()
        removed.forEach(pending::remove)
        return removed
    }

    fun isNearby(page: Int): Boolean = page >= firstVisible - 1 && page <= lastVisible + 1

    fun put(page: Int, request: T) { pending[page] = request }
    fun remove(page: Int) { pending.remove(page) }
    fun clear() { pending.clear() }
    fun hasVisiblePending(): Boolean = pending.keys.any { it in firstVisible..lastVisible }
    fun isEmpty(): Boolean = pending.isEmpty()

    fun poll(): Pair<Int, T>? {
        val page = pending.keys.minWithOrNull(compareBy<Int>(
            { if (it in firstVisible..lastVisible) 0 else 1 },
            { if (it < firstVisible) firstVisible - it else (it - lastVisible).coerceAtLeast(0) },
            { it },
        )) ?: return null
        return page to pending.remove(page)!!
    }
}
