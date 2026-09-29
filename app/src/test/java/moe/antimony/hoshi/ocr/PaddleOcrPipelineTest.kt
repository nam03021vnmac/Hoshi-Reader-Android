package moe.antimony.hoshi.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaddleOcrPipelineTest {
    @Test
    fun quadOrderingRestoresCorners() {
        val ordered = orderQuadPoints(
            listOf(PointD(9.0, 1.0), PointD(1.0, 8.0), PointD(1.0, 1.0), PointD(9.0, 8.0)),
        )
        assertEquals(listOf(PointD(1.0, 1.0), PointD(9.0, 1.0), PointD(9.0, 8.0), PointD(1.0, 8.0)), ordered)
    }

    @Test
    fun minAreaRectRecoversAxisAlignedBox() {
        val points = mutableListOf<PointD>()
        for (x in 10..30) for (y in 20..40) {
            if (x == 10 || x == 30 || y == 20 || y == 40) points += PointD(x.toDouble(), y.toDouble())
        }
        val rect = minAreaRect(points)
        assertTrue(abs(rect.width - 20.0) < 1e-6)
        assertTrue(abs(rect.height - 20.0) < 1e-6)
    }

    @Test
    fun minAreaRectRecoversDiamondSize() {
        // Diamond with vertices (0,10),(10,0),(20,10),(10,20): square side sqrt(200).
        val diamond = listOf(PointD(0.0, 10.0), PointD(10.0, 0.0), PointD(20.0, 10.0), PointD(10.0, 20.0))
        val rect = minAreaRect(diamond)
        assertTrue(abs(rect.width - rect.height) < 1e-6)
        assertTrue(abs(rect.width * rect.width - 200.0) < 1e-3)
    }

    @Test
    fun unclipExpandsConvexQuad() {
        val square = listOf(PointD(0.0, 0.0), PointD(20.0, 0.0), PointD(20.0, 12.0), PointD(0.0, 12.0))
        val expanded = unclipPolygon(square, 1.5f)
        fun area(points: List<PointD>): Double {
            var sum = 0.0
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(sum) / 2.0
        }
        assertTrue(area(expanded) > area(square))
        // Expansion distance for this rect is area*ratio/perimeter = 240*1.5/64 = 5.625.
        assertTrue(expanded.minOf { it.x } < -4.0)
        assertTrue(expanded.maxOf { it.x } > 24.0)
    }

    @Test
    fun bankersRoundingMatchesHalfToEven() {
        assertEquals(2, roundHalfToEven(2.5))
        assertEquals(4, roundHalfToEven(3.5))
        assertEquals(2, roundHalfToEven(2.4))
        assertEquals(3, roundHalfToEven(2.6))
    }

    @Test
    fun detectionFindsSingleTextBlob() {
        val w = 64
        val h = 64
        val prob = FloatArray(w * h)
        for (y in 24..36) for (x in 10..50) prob[y * w + x] = 1f
        val boxes = DbPostProcessor.process(prob, w, h, w, h)
        assertEquals(1, boxes.size)
        val box = boxes.single()
        assertTrue(box.score > 0.6f)
        val xs = box.points.map { it.x }
        val ys = box.points.map { it.y }
        // Unclipped box must cover the original blob with margin.
        assertTrue(xs.min() <= 10.0)
        assertTrue(xs.max() >= 50.0)
        assertTrue(ys.min() <= 24.0)
        assertTrue(ys.max() >= 36.0)
    }

    @Test
    fun detectionIgnoresEmptyMap() {
        assertTrue(DbPostProcessor.process(FloatArray(32 * 32), 32, 32, 32, 32).isEmpty())
    }

    @Test
    fun detectionDropsTinySpecks() {
        val w = 64
        val h = 64
        val prob = FloatArray(w * h)
        prob[10 * w + 10] = 1f
        prob[10 * w + 11] = 1f
        assertTrue(DbPostProcessor.process(prob, w, h, w, h).isEmpty())
    }

    @Test
    fun readingOrderSortsRowsThenColumns() {
        fun box(x: Double, y: Double) = DetectedBox(
            listOf(PointD(x, y), PointD(x + 8, y), PointD(x + 8, y + 4), PointD(x, y + 4)),
            0.9f,
        )
        val row2Left = box(2.0, 20.0)
        val row1Right = box(40.0, 2.0)
        val row1Left = box(2.0, 2.0)
        val sorted = sortBoxesReadingOrder(listOf(row2Left, row1Right, row1Left))
        assertEquals(listOf(row1Left, row1Right, row2Left), sorted)
    }

    @Test
    fun ctcDecodingCollapsesBlanksAndRepeats() {
        // Classes: blank, 'a', 'b'. Steps emit a a blank b; the repeated 'a'
        // collapses, so only its first probability contributes to confidence.
        val output = floatArrayOf(
            0.1f, 0.9f, 0.0f,
            0.1f, 0.8f, 0.1f,
            0.9f, 0.05f, 0.05f,
            0.1f, 0.1f, 0.8f,
        )
        val decoded = CtcDecoder.decode(output, 1, 4, 3, listOf("a", "b"))
        assertEquals(1, decoded.size)
        assertEquals("ab", decoded.single().first)
        assertTrue(abs(decoded.single().second - (0.9f + 0.8f) / 2) < 1e-4f)
    }

    @Test
    fun dictParserReadsQuotedEntriesAndAppendsSpace() {
        val yaml = """
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '!'
              - '"'
              - a
              - あ
        """.trimIndent()
        val chars = PaddleDict.parseCharacterDict(yaml, "test.yml")
        assertEquals(listOf("!", "\"", "a", "あ", " "), chars)
    }

    @Test
    fun detPreprocessSnapsToMultipleOf32() {
        // 100x60 BGR plane: 100 -> 96, 60 -> 64 after the multiple-of-32 snap.
        val bgr = ByteArray(100 * 60 * 3) { 0x7F.toByte() }
        val input = PaddleDetPreprocessor.preprocessBgr(bgr, 100, 60)
        assertEquals(96, input.width)
        assertEquals(64, input.height)
        assertEquals(100, input.originalWidth)
        assertEquals(60, input.originalHeight)
        assertEquals(3 * 96 * 64, input.tensor.size)
    }

    @Test
    fun recPreprocessNormalizesAndPadsBatch() {
        val white = RecCrop(ByteArray(96 * 48 * 3) { 0xFF.toByte() }, 96, 48)
        val narrow = RecCrop(ByteArray(24 * 48 * 3), 24, 48)
        val batch = PaddleRecPreprocessor.preprocessBatch(listOf(white, narrow))
        assertEquals(2, batch.batch)
        assertEquals(96, batch.width)
        assertEquals(2 * 3 * 48 * 96, batch.tensor.size)
        // White normalizes to +1, padding stays 0.
        assertTrue(abs(batch.tensor[0] - 1f) < 1e-5)
        val secondChannel = 3 * 48 * 96
        assertTrue(abs(batch.tensor[secondChannel + 95] - 0f) < 1e-5)
    }

    @Test
    fun warpIdentityPreservesPixels() {
        val w = 8
        val h = 8
        val src = ByteArray(w * h * 3) { i -> (i % 251).toByte() }
        val quad = listOf(PointD(0.0, 0.0), PointD(w.toDouble(), 0.0), PointD(w.toDouble(), h.toDouble()), PointD(0.0, h.toDouble()))
        val out = warpQuadToRect(src, w, h, quad, w, h)
        for (i in src.indices) {
            assertTrue(abs((src[i].toInt() and 0xFF) - (out[i].toInt() and 0xFF)) <= 2)
        }
    }

    @Test
    fun rotateSwapsDimensions() {
        val (rotated, w, h) = rotateBgr90Ccw(ByteArray(4 * 2 * 3), 4, 2)
        assertEquals(2, w)
        assertEquals(4, h)
        assertEquals(4 * 2 * 3, rotated.size)
    }
}
