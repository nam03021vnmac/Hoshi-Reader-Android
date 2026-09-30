package moe.antimony.hoshi.ocr

/** Monotonic, text-free stage measurements. Profiling never logs recognized content. */
internal class OcrTimings {
    private val started = System.nanoTime()
    private val stages = linkedMapOf<String, Long>()

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            add(stage, System.nanoTime() - start)
        }
    }

    fun add(stage: String, nanos: Long) {
        stages[stage] = (stages[stage] ?: 0L) + nanos
    }

    fun summary(): String = (stages + ("total" to (System.nanoTime() - started)))
        .entries.joinToString(" ") { (name, nanos) -> "${name}Ms=${nanos / 1_000_000}" }
}
