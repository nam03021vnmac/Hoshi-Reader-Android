package moe.antimony.hoshi.ocr

/** Code-point edit distance. No punctuation stripping, Unicode folding, or dictionary repair. */
internal object OcrAccuracy {
    fun editDistance(reference: String, actual: String): Int {
        val expected = reference.codePoints().toArray()
        val observed = actual.codePoints().toArray()
        var previous = IntArray(observed.size + 1) { it }
        var current = IntArray(observed.size + 1)
        for (row in expected.indices) {
            current[0] = row + 1
            for (column in observed.indices) {
                current[column + 1] = minOf(
                    current[column] + 1,
                    previous[column + 1] + 1,
                    previous[column] + if (expected[row] == observed[column]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[observed.size]
    }
}
