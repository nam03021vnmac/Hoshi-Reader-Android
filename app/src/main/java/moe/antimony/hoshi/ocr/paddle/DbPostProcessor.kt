package moe.antimony.hoshi.ocr.paddle

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Detection box in original-image pixel coordinates with its mean-probability score. */
data class DetectedBox(
    val points: List<PointD>,
    val score: Float,
)

/**
 * Differentiable-binarization post-processing from the official PP-OCRv6
 * Android SDK without OpenCV: thresholding, connected components instead of
 * `findContours`, rotating-calipers minimum-area rectangles, mean-probability
 * box scoring over a scanline rasterization, and polygon unclipping.
 */
object DbPostProcessor {
    private const val MIN_SIZE_BEFORE_UNCLIP = 3f
    private const val MIN_SIZE_AFTER_UNCLIP = 5f

    const val THRESH = 0.3f
    const val BOX_THRESH = 0.6f
    const val UNCLIP_RATIO = 1.5f
    const val MAX_CANDIDATES = 3000

    fun process(
        prob: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        thresh: Float = THRESH,
        boxThresh: Float = BOX_THRESH,
        unclipRatio: Float = UNCLIP_RATIO,
        maxCandidates: Int = MAX_CANDIDATES,
    ): List<DetectedBox> {
        require(prob.size == mapWidth * mapHeight)
        if (originalWidth <= 0 || originalHeight <= 0) return emptyList()
        val scaleX = originalWidth.toDouble() / mapWidth
        val scaleY = originalHeight.toDouble() / mapHeight
        val components = labelComponents(prob, mapWidth, mapHeight, thresh)
            .sortedByDescending { it.pixelCount }
            .take(maxCandidates.coerceAtLeast(0))
        val boxes = mutableListOf<DetectedBox>()
        for (component in components) {
            if (component.boundary.size < 3) continue
            val rect = minAreaRect(component.boundary)
            if (minOf(rect.width, rect.height) < MIN_SIZE_BEFORE_UNCLIP) continue
            val ordered = orderQuadPoints(rect.corners)
            val score = boxScoreMean(prob, mapWidth, mapHeight, ordered)
            if (score < boxThresh) continue

            val expanded = minAreaRect(convexHull(unclipPolygon(ordered, unclipRatio)))
            if (minOf(expanded.width, expanded.height) < MIN_SIZE_AFTER_UNCLIP) continue
            val scaled = orderQuadPoints(expanded.corners).map { point ->
                PointD(
                    roundHalfToEven(point.x * scaleX).coerceIn(0, originalWidth).toDouble(),
                    roundHalfToEven(point.y * scaleY).coerceIn(0, originalHeight).toDouble(),
                )
            }
            val boxW = hypot(scaled[1].x - scaled[0].x, scaled[1].y - scaled[0].y)
            val boxH = hypot(scaled[3].x - scaled[0].x, scaled[3].y - scaled[0].y)
            if (boxW <= 3 || boxH <= 3) continue
            boxes += DetectedBox(points = scaled, score = score)
        }
        return boxes
    }

    internal data class Component(
        val pixelCount: Int,
        val boundary: List<PointD>
    )

    /** Two-pass 8-connectivity labeling; boundary uses 4-neighbourhood. */
    internal fun labelComponents(
        prob: FloatArray,
        w: Int,
        h: Int,
        thresh: Float,
    ): List<Component> {
        val labels = IntArray(w * h)
        // At most one label per pixel. No boxed neighbours/roots per foreground pixel.
        var parent = IntArray(minOf(1024, w * h + 1))
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }
        var nextLabel = 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (prob[y * w + x] <= thresh) continue
                val left = if (x > 0) labels[y * w + x - 1] else 0
                val upLeft = if (y > 0 && x > 0) labels[(y - 1) * w + x - 1] else 0
                val up = if (y > 0) labels[(y - 1) * w + x] else 0
                val upRight = if (y > 0 && x + 1 < w) labels[(y - 1) * w + x + 1] else 0
                var root = if (left == 0) 0 else find(left)
                if (upLeft != 0) root = unionLabels(parent, root, find(upLeft))
                if (up != 0) root = unionLabels(parent, root, find(up))
                if (upRight != 0) root = unionLabels(parent, root, find(upRight))
                if (root == 0) {
                    if (nextLabel == parent.size) parent = parent.copyOf(minOf(parent.size * 2, w * h + 1))
                    parent[nextLabel] = nextLabel
                    labels[y * w + x] = nextLabel
                    nextLabel += 1
                } else {
                    labels[y * w + x] = root
                }
            }
        }
        val counts = IntArray(nextLabel)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val label = labels[y * w + x]
                if (label == 0) continue
                val root = find(label)
                labels[y * w + x] = root
                counts[root]++
            }
        }
        fun isBackground(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= w || y >= h) return true
            return prob[y * w + x] <= thresh
        }
        val boundaries = mutableMapOf<Int, MutableList<PointD>>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val label = labels[y * w + x]
                if (label == 0) continue
                if (isBackground(x - 1, y) || isBackground(x + 1, y) ||
                    isBackground(x, y - 1) || isBackground(x, y + 1)
                ) {
                    boundaries.getOrPut(label) { mutableListOf() } += PointD(x.toDouble(), y.toDouble())
                }
            }
        }
        return (1 until nextLabel).filter { counts[it] > 0 }.map { label ->
            Component(counts[label], boundaries[label].orEmpty())
        }
    }

    private fun unionLabels(parent: IntArray, a: Int, b: Int): Int {
        if (a == 0) return b
        val smaller = minOf(a, b)
        parent[maxOf(a, b)] = smaller
        return smaller
    }

    /**
     * Mean probability inside a convex quad via scanline rasterization,
     * matching the official "fast" box-score mode.
     */
    internal fun boxScoreMean(
        prob: FloatArray,
        w: Int,
        h: Int,
        quad: List<PointD>,
    ): Float {
        val yMin = floor(quad.minOf { it.y }).toInt().coerceIn(0, h - 1)
        val yMax = ceil(quad.maxOf { it.y }).toInt().coerceIn(0, h - 1)
        if (yMax < yMin) return 0f
        var sum = 0.0
        var count = 0
        for (y in yMin..yMax) {
            val yc = y + 0.5
            var xMin = Double.MAX_VALUE
            var xMax = -Double.MAX_VALUE
            for (i in quad.indices) {
                val a = quad[i]
                val b = quad[(i + 1) % quad.size]
                if ((a.y <= yc && b.y > yc) || (b.y <= yc && a.y > yc)) {
                    val x = a.x + (yc - a.y) * (b.x - a.x) / (b.y - a.y)
                    if (x < xMin) xMin = x
                    if (x > xMax) xMax = x
                }
            }
            if (xMax < xMin) continue
            val c0 = floor(xMin).toInt().coerceIn(0, w - 1)
            val c1 = ceil(xMax).toInt().coerceIn(0, w - 1)
            for (x in c0..c1) {
                sum += prob[y * w + x]
                count += 1
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }
}

/**
 * Reading-order sort ported from the official SDK `BoxSorter`: top to
 * bottom with a left-to-right pass inside a 10px row band.
 */
fun sortBoxesReadingOrder(boxes: List<DetectedBox>): List<DetectedBox> {
    if (boxes.size <= 1) return boxes
    val list = boxes.sortedWith(compareBy({ it.points[0].y }, { it.points[0].x })).toMutableList()
    for (i in 0 until list.size - 1) {
        var j = i
        while (j >= 0) {
            val next = list[j + 1]
            val curr = list[j]
            if (kotlin.math.abs(next.points[0].y - curr.points[0].y) < 10f &&
                next.points[0].x < curr.points[0].x
            ) {
                list[j] = next
                list[j + 1] = curr
                j--
            } else {
                break
            }
        }
    }
    return list
}
