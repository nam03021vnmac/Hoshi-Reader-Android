package moe.antimony.hoshi.ocr.paddle

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Double-precision image point.
 *
 * Detection geometry adapted from the official PP-OCRv6 Android SDK
 * (`com.paddle.ocr`, Apache-2.0, PaddlePaddle): quad ordering, polygon
 * unclipping and banker's rounding are ports; contour extraction,
 * minimum-area rectangles, resizing and perspective sampling are
 * dependency-free reimplementations so Hoshi does not ship OpenCV.
 */
data class PointD(val x: Double, val y: Double)

/** Minimum-area rectangle as its four corners plus edge-aligned size. */
data class MinAreaRect(
    val corners: List<PointD>,
    val width: Double,
    val height: Double,
)

/**
 * Orders four rectangle corners as top-left, top-right, bottom-right,
 * bottom-left. Port of the official SDK `QuadGeometry`.
 */
fun orderQuadPoints(points: List<PointD>): List<PointD> {
    require(points.size == 4)
    val sorted = points.sortedBy { it.x }
    val topLeft: PointD
    val bottomLeft: PointD
    if (sorted[1].y > sorted[0].y) {
        topLeft = sorted[0]
        bottomLeft = sorted[1]
    } else {
        topLeft = sorted[1]
        bottomLeft = sorted[0]
    }
    val topRight: PointD
    val bottomRight: PointD
    if (sorted[3].y > sorted[2].y) {
        topRight = sorted[2]
        bottomRight = sorted[3]
    } else {
        topRight = sorted[3]
        bottomRight = sorted[2]
    }
    return listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Andrew's monotone chain; returns counter-clockwise hull without duplication. */
fun convexHull(points: List<PointD>): List<PointD> {
    val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size < 3) return sorted
    fun cross(o: PointD, a: PointD, b: PointD): Double =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = mutableListOf<PointD>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0.0) {
            lower.removeAt(lower.size - 1)
        }
        lower += p
    }
    val upper = mutableListOf<PointD>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0.0) {
            upper.removeAt(upper.size - 1)
        }
        upper += p
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

/**
 * Minimum-area enclosing rectangle via rotating calipers over hull edges.
 * Falls back to the axis-aligned bounding box for degenerate inputs.
 */
fun minAreaRect(points: List<PointD>): MinAreaRect {
    val hull = convexHull(points)
    if (hull.size < 3) {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        val x0 = xs.min()
        val x1 = xs.max()
        val y0 = ys.min()
        val y1 = ys.max()
        return MinAreaRect(
            corners = listOf(PointD(x0, y0), PointD(x1, y0), PointD(x1, y1), PointD(x0, y1)),
            width = x1 - x0,
            height = y1 - y0,
        )
    }
    var bestArea = Double.MAX_VALUE
    var best = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
    for (i in hull.indices) {
        val p1 = hull[i]
        val p2 = hull[(i + 1) % hull.size]
        val angle = atan2(p2.y - p1.y, p2.x - p1.x)
        val cosA = cos(angle)
        val sinA = sin(angle)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in hull) {
            val xr = p.x * cosA + p.y * sinA
            val yr = -p.x * sinA + p.y * cosA
            if (xr < minX) minX = xr
            if (xr > maxX) maxX = xr
            if (yr < minY) minY = yr
            if (yr > maxY) maxY = yr
        }
        val area = (maxX - minX) * (maxY - minY)
        if (area < bestArea) {
            bestArea = area
            best = doubleArrayOf(minX, minY, maxX, maxY, angle)
        }
    }
    val (minX, minY, maxX, maxY, angle) = best.let {
        listOf(it[0], it[1], it[2], it[3], it[4])
    }
    val cosA = cos(angle)
    val sinA = sin(angle)
    fun unrotate(xr: Double, yr: Double) =
        PointD(xr * cosA - yr * sinA, xr * sinA + yr * cosA)
    return MinAreaRect(
        corners = listOf(
            unrotate(minX, minY),
            unrotate(maxX, minY),
            unrotate(maxX, maxY),
            unrotate(minX, maxY),
        ),
        width = maxX - minX,
        height = maxY - minY,
    )
}

/**
 * Expands a text polygon outward by `area * unclipRatio / perimeter` with
 * round joins. Port of the official SDK `PolygonUnclip` (pure Kotlin there).
 */
fun unclipPolygon(points: List<PointD>, unclipRatio: Float): List<PointD> {
    if (points.size < 3) return points
    val signedArea = signedPolygonArea(points)
    val area = kotlin.math.abs(signedArea)
    val perimeter = polygonPerimeter(points)
    if (!area.isFinite() || !perimeter.isFinite() || area <= 1e-6 || perimeter <= 1e-6) {
        return points
    }
    val distance = area * unclipRatio / perimeter
    if (!distance.isFinite() || distance <= 1e-6) return points
    val clockwise = signedArea > 0.0
    val normals = points.indices.map { i ->
        val start = points[i]
        val end = points[(i + 1) % points.size]
        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = hypot(dx, dy)
        if (!len.isFinite() || len <= 1e-6) return points
        if (clockwise) PointD(dy / len, -dx / len) else PointD(-dy / len, dx / len)
    }
    val expanded = mutableListOf<PointD>()
    for (i in points.indices) {
        appendRoundJoin(
            expanded,
            points[i],
            normals[(i - 1 + normals.size) % normals.size],
            normals[i],
            distance,
            clockwise,
        )
    }
    return if (expanded.size >= 3) expanded else points
}

private fun appendRoundJoin(
    output: MutableList<PointD>,
    center: PointD,
    fromNormal: PointD,
    toNormal: PointD,
    distance: Double,
    clockwise: Boolean,
) {
    val pi = kotlin.math.PI
    var startAngle = atan2(fromNormal.y, fromNormal.x)
    var endAngle = atan2(toNormal.y, toNormal.x)
    if (clockwise) {
        while (endAngle < startAngle) endAngle += 2.0 * pi
    } else {
        while (endAngle > startAngle) endAngle -= 2.0 * pi
    }
    val sweep = endAngle - startAngle
    val ratio = (1.0 - 0.25 / distance).coerceIn(-1.0, 1.0)
    var step = 2.0 * kotlin.math.acos(ratio)
    if (!step.isFinite() || step <= 1e-6) step = pi / 8.0
    val steps = kotlin.math.ceil(kotlin.math.abs(sweep) / step).toInt().coerceAtLeast(1)
    for (s in 0..steps) {
        val angle = startAngle + sweep * s.toDouble() / steps.toDouble()
        val point = PointD(center.x + cos(angle) * distance, center.y + sin(angle) * distance)
        val last = output.lastOrNull()
        if (last == null || hypot(point.x - last.x, point.y - last.y) > 1e-6) {
            output += point
        }
    }
}

private fun signedPolygonArea(points: List<PointD>): Double {
    var sum = 0.0
    for (i in points.indices) {
        val p1 = points[i]
        val p2 = points[(i + 1) % points.size]
        sum += p1.x * p2.y - p2.x * p1.y
    }
    return sum / 2.0
}

private fun polygonPerimeter(points: List<PointD>): Double {
    var length = 0.0
    for (i in points.indices) {
        val p1 = points[i]
        val p2 = points[(i + 1) % points.size]
        length += hypot(p2.x - p1.x, p2.y - p1.y)
    }
    return length
}

/** Banker's rounding. Port of the official SDK `MathUtils`. */
fun roundHalfToEven(value: Double): Int {
    val floored = kotlin.math.floor(value)
    val diff = value - floored
    return when {
        diff < 0.5 -> floored.toInt()
        diff > 0.5 -> floored.toInt() + 1
        floored.toInt() % 2 == 0 -> floored.toInt()
        else -> floored.toInt() + 1
    }
}

/** Bilinear resize of a packed BGR byte plane. */
fun resizeBgrBilinear(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
    require(src.size == srcW * srcH * 3)
    val dst = ByteArray(dstW * dstH * 3)
    val xRatio = srcW.toDouble() / dstW
    val yRatio = srcH.toDouble() / dstH
    for (y in 0 until dstH) {
        val sy = (y + 0.5) * yRatio - 0.5
        val y0 = sy.toInt().coerceIn(0, srcH - 1)
        val y1 = (y0 + 1).coerceIn(0, srcH - 1)
        val fy = (sy - y0).coerceIn(0.0, 1.0)
        for (x in 0 until dstW) {
            val sx = (x + 0.5) * xRatio - 0.5
            val x0 = sx.toInt().coerceIn(0, srcW - 1)
            val x1 = (x0 + 1).coerceIn(0, srcW - 1)
            val fx = (sx - x0).coerceIn(0.0, 1.0)
            for (c in 0..2) {
                val p00 = (src[(y0 * srcW + x0) * 3 + c].toInt() and 0xFF).toDouble()
                val p10 = (src[(y0 * srcW + x1) * 3 + c].toInt() and 0xFF).toDouble()
                val p01 = (src[(y1 * srcW + x0) * 3 + c].toInt() and 0xFF).toDouble()
                val p11 = (src[(y1 * srcW + x1) * 3 + c].toInt() and 0xFF).toDouble()
                val value = (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy
                dst[(y * dstW + x) * 3 + c] = value.toInt().coerceIn(0, 255).toByte()
            }
        }
    }
    return dst
}

/**
 * Warps an ordered source quad (top-left, top-right, bottom-right,
 * bottom-left) to a `dstW x dstH` rectangle with bilinear sampling and
 * replicate borders. Replaces `getPerspectiveTransform` + `warpPerspective`.
 */
fun warpQuadToRect(
    src: ByteArray,
    srcW: Int,
    srcH: Int,
    quad: List<PointD>,
    dstW: Int,
    dstH: Int,
): ByteArray {
    require(quad.size == 4)
    // Solve dst -> src homography directly.
    val dstPts = listOf(
        PointD(0.0, 0.0),
        PointD(dstW.toDouble(), 0.0),
        PointD(dstW.toDouble(), dstH.toDouble()),
        PointD(0.0, dstH.toDouble()),
    )
    val h = solveHomography(dstPts, quad) ?: return ByteArray(dstW * dstH * 3)
    val out = ByteArray(dstW * dstH * 3)
    for (y in 0 until dstH) {
        for (x in 0 until dstW) {
            val w = h[6] * x + h[7] * y + 1.0
            val sx = (h[0] * x + h[1] * y + h[2]) / w
            val sy = (h[3] * x + h[4] * y + h[5]) / w
            for (c in 0..2) {
                out[(y * dstW + x) * 3 + c] = sampleBgrClamped(src, srcW, srcH, sx, sy, c)
            }
        }
    }
    return out
}

private fun sampleBgrClamped(
    src: ByteArray,
    srcW: Int,
    srcH: Int,
    sx: Double,
    sy: Double,
    channel: Int,
): Byte {
    val x0 = sx.toInt().coerceIn(0, srcW - 1)
    val y0 = sy.toInt().coerceIn(0, srcH - 1)
    val x1 = (x0 + 1).coerceIn(0, srcW - 1)
    val y1 = (y0 + 1).coerceIn(0, srcH - 1)
    val fx = (sx - sx.toInt()).coerceIn(0.0, 1.0)
    val fy = (sy - sy.toInt()).coerceIn(0.0, 1.0)
    val p00 = (src[(y0 * srcW + x0) * 3 + channel].toInt() and 0xFF).toDouble()
    val p10 = (src[(y0 * srcW + x1) * 3 + channel].toInt() and 0xFF).toDouble()
    val p01 = (src[(y1 * srcW + x0) * 3 + channel].toInt() and 0xFF).toDouble()
    val p11 = (src[(y1 * srcW + x1) * 3 + channel].toInt() and 0xFF).toDouble()
    val value = (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy
    return value.toInt().coerceIn(0, 255).toByte()
}

/** 8x8 solve for the homography mapping [from] to [to]; h33 is fixed to 1. */
private fun solveHomography(from: List<PointD>, to: List<PointD>): DoubleArray? {
    val a = Array(8) { DoubleArray(9) }
    for (i in 0..3) {
        val f = from[i]
        val t = to[i]
        a[i * 2] = doubleArrayOf(f.x, f.y, 1.0, 0.0, 0.0, 0.0, -t.x * f.x, -t.x * f.y, t.x)
        a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, f.x, f.y, 1.0, -t.y * f.x, -t.y * f.y, t.y)
    }
    for (col in 0..7) {
        var pivot = col
        for (row in col + 1..7) {
            if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
        }
        if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
        val tmp = a[col]
        a[col] = a[pivot]
        a[pivot] = tmp
        for (row in 0..7) {
            if (row == col) continue
            val factor = a[row][col] / a[col][col]
            for (k in col..8) a[row][k] -= factor * a[col][k]
        }
    }
    return DoubleArray(8) { a[it][8] / a[it][it] } + doubleArrayOf(1.0)
}

/** 90-degree counter-clockwise rotation of a packed BGR plane. */
fun rotateBgr90Ccw(src: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
    val out = ByteArray(src.size)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val nx = y
            val ny = w - 1 - x
            for (c in 0..2) {
                out[(ny * h + nx) * 3 + c] = src[(y * w + x) * 3 + c]
            }
        }
    }
    return Triple(out, h, w)
}
