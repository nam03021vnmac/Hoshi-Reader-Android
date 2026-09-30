package moe.antimony.hoshi.ocr.paddle

import javax.inject.Inject

/** Benchmark controls. Defaults stay conservative until real-device comparisons pass. */
class PaddleOcrConfiguration(val threads: Int, val recognitionBatchSize: Int, val useXnnpack: Boolean = false) {
    @Inject constructor() : this(threads = 4, recognitionBatchSize = 1)

    init {
        require(threads in 1..8)
        require(recognitionBatchSize in 1..4)
    }
}

/** Similar-width batches avoid paying for the longest line's padding on every crop. */
internal fun recognitionBatches(widths: List<Int>, batchSize: Int, classes: Int): List<List<Int>> {
    require(batchSize in 1..4 && classes > 0)
    if (batchSize == 1) return widths.indices.map { listOf(it) }
    val ordered = widths.indices.sortedWith(compareBy({ widths[it] }, { it }))
    val batches = mutableListOf<MutableList<Int>>()
    for (index in ordered) {
        val batch = batches.lastOrNull()
        val width = widths[index]
        // Bound CTC output (~32 MiB), not just the relatively small input tensor.
        val fits = batch != null && batch.size < batchSize &&
            width <= widths[batch.first()] * 1.5 &&
            (batch.size + 1L) * ((width + 3L) / 4L + 1L) * classes <= 8_000_000L
        if (fits) batch!!.add(index) else batches.add(mutableListOf(index))
    }
    return batches
}
