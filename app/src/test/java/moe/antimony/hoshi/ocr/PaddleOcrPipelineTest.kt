package moe.antimony.hoshi.ocr.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaddleOcrPipelineTest {
    @Test fun batchPlannerBucketsWidthsBoundsOutputsAndPreservesEveryIndex() {
        val widths = listOf(3000, 80, 96, 100, 250, 300, 3200)
        val batches = recognitionBatches(widths, 4, 15000)
        assertEquals(widths.indices.toList(), batches.flatten().sorted())
        assertTrue(batches.all { batch -> batch.size <= 4 &&
            (batch.size == 1 || batch.size.toLong() * ((widths[batch.last()] + 3L) / 4L + 1L) * 15000 <= 8_000_000L) })
        assertEquals(widths.indices.map { listOf(it) }, recognitionBatches(widths, 1, 15000))
    }

    @Test fun recognitionTensorConvertsBgrToRgbWithoutAnIntermediatePlane() {
        val bgr = ByteArray(48 * 3) { index -> if (index % 3 == 2) 0xff.toByte() else 0 }
        val input = PaddleRecPreprocessor.preprocessBatch(listOf(RecCrop(bgr, 1, 48)))
        assertEquals(1f, input.tensor[0], 0f)
        assertEquals(-1f, input.tensor[48], 0f)
        assertEquals(-1f, input.tensor[96], 0f)
    }

    @Test fun batchedCtcMatchesIndependentDecodingIncludingRepeatedJapaneseAndSupplementaryText() {
        val chars = listOf("猫", "𠮟")
        val first = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val second = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f)
        val batch = CtcDecoder.decode(first + second, 2, 3, 3, chars)
        assertEquals(CtcDecoder.decode(first, 1, 3, 3, chars).single(), batch[0])
        assertEquals(CtcDecoder.decode(second, 1, 3, 3, chars).single(), batch[1])
        assertEquals("猫猫", batch[0].first)
        assertEquals("𠮟", batch[1].first)
        val buffer = java.nio.ByteBuffer.allocateDirect((first.size + second.size) * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(first + second).rewind()
        assertEquals(batch, CtcDecoder.decode(buffer, 2, 3, 3, chars))
    }

    @Test fun connectedComponentsMergeDiagonalAndBridgedLabelsWithoutMergingSeparateText() {
        val w = 10
        val prob = FloatArray(w * 10)
        listOf(1 to 1, 3 to 1, 2 to 2, 3 to 3, 8 to 8).forEach { (x, y) -> prob[y * w + x] = 1f }
        val components = DbPostProcessor.labelComponents(prob, w, 10, 0.3f)
        assertEquals(listOf(1, 4), components.map { it.pixelCount }.sorted())
    }

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
    fun plainDictParserKeepsLinesAndAppendsSpaceClass() {
        val chars = PaddleDict.parsePlainDict("!\r\n\"\nあ\n　\n", "dict.txt")
        assertEquals(listOf("!", "\"", "あ", "　", " "), chars)
    }

    @Test
    fun longSideDetResizeScalesPagesAndBudgetsStrips() {
        val page = PaddleDetPreprocessor.preprocessBgr(ByteArray(1200 * 1700 * 3), 1200, 1700, PaddleDetResize.LongSide(960))
        assertEquals(960, page.height)
        assertEquals(672, page.width)
        val small = PaddleDetPreprocessor.preprocessBgr(ByteArray(300 * 400 * 3), 300, 400, PaddleDetResize.LongSide(960))
        assertEquals(960, small.height)
        assertEquals(704, small.width) // 720 / 32 = 22.5 rounds half to even
        // 800x4000 strip: sqrt(0.75 * 960^2 / (800 * 4000)) keeps a 3:4 page's pixel budget.
        val strip = PaddleDetPreprocessor.preprocessBgr(ByteArray(800 * 4000 * 3), 800, 4000, PaddleDetResize.LongSide(960))
        assertEquals(1856, strip.height)
        assertEquals(384, strip.width)
    }

    @Test
    fun recPreprocessCanKeepBgrChannelOrder() {
        val pixel = byteArrayOf(0xFF.toByte(), 0, 0) // pure blue in BGR
        val crop = RecCrop(ByteArray(48 * 48 * 3) { pixel[it % 3] }, 48, 48)
        val channel = 48 * 48
        val rgb = PaddleRecPreprocessor.preprocessBatch(listOf(crop))
        val bgr = PaddleRecPreprocessor.preprocessBatch(listOf(crop), bgrInput = true)
        assertEquals(-1f, rgb.tensor[0], 1e-6f)
        assertEquals(1f, rgb.tensor[2 * channel], 1e-6f)
        assertEquals(1f, bgr.tensor[0], 1e-6f)
        assertEquals(-1f, bgr.tensor[2 * channel], 1e-6f)
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
