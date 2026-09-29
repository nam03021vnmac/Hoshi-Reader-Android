package moe.antimony.hoshi.ocr

import moe.antimony.hoshi.ocr.layout.JapaneseOcrPostprocessor
import moe.antimony.hoshi.ocr.layout.PostprocessorInputLine
import moe.antimony.hoshi.ocr.pdf.NormalizedQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseOcrPostprocessorTest {
    private val processor = JapaneseOcrPostprocessor()

    private fun quad(left: Float, top: Float, right: Float, bottom: Float) =
        NormalizedQuad(left, top, right, top, right, bottom, left, bottom)

    @Test
    fun horizontalParagraphJoinsWithoutSpaceForJapanese() {
        val lines = listOf(
            PostprocessorInputLine("l0", "今日は", quad(0.1f, 0.1f, 0.9f, 0.2f), "HORIZONTAL", 0.9f),
            PostprocessorInputLine("l1", "良い天気ですね", quad(0.1f, 0.22f, 0.9f, 0.32f), "HORIZONTAL", 0.9f),
        )
        val result = processor.process(lines)
        assertEquals("今日は良い天気ですね", result.canonicalText)
        assertEquals(1, result.paragraphs.size)
        assertEquals(0, result.paragraphs[0].canonicalStartUtf16)
        assertEquals(result.canonicalText.length, result.paragraphs[0].canonicalEndUtf16)
    }

    @Test
    fun latinWordsKeepSpacesAndParagraphBreaks() {
        val lines = listOf(
            PostprocessorInputLine("l0", "Hello", quad(0.1f, 0.1f, 0.4f, 0.2f), "HORIZONTAL", 0.9f),
            PostprocessorInputLine("l1", "world", quad(0.1f, 0.22f, 0.4f, 0.32f), "HORIZONTAL", 0.9f),
        )
        val result = processor.process(lines)
        // Same narrow column joins as one paragraph with a preserved word space.
        assertTrue(result.canonicalText.contains("Hello world") || result.canonicalText.contains("Hello\nworld"))
        assertTrue(result.spans.size == 2)
    }

    @Test
    fun verticalColumnsOrderRightToLeft() {
        val right = PostprocessorInputLine("r", "あいう", quad(0.7f, 0.1f, 0.8f, 0.9f), "VERTICAL", 0.9f)
        val left = PostprocessorInputLine("l", "えお", quad(0.2f, 0.1f, 0.3f, 0.9f), "VERTICAL", 0.9f)
        val result = processor.process(listOf(left, right))
        // Right column reads first regardless of input order.
        assertTrue(result.canonicalText.indexOf("あいう") < result.canonicalText.indexOf("えお"))
        assertTrue(result.qualityFlags.contains("vertical-dominant"))
    }

    @Test
    fun punctuationSequencesArePreserved() {
        val lines = listOf(
            PostprocessorInputLine("l0", "本当!?", quad(0.1f, 0.1f, 0.9f, 0.2f), "HORIZONTAL", 0.9f),
            PostprocessorInputLine("l1", "そう……", quad(0.1f, 0.22f, 0.9f, 0.32f), "HORIZONTAL", 0.9f),
        )
        val result = processor.process(lines)
        assertTrue(result.canonicalText.contains("!?"))
        assertTrue(result.canonicalText.contains("……"))
    }

    @Test
    fun ambiguousSmallKanaIsRetainedAsBodyText() {
        val parent = PostprocessorInputLine("p", "漢字", quad(0.3f, 0.3f, 0.5f, 0.5f), "HORIZONTAL", 0.9f)
        val smallKana = PostprocessorInputLine("r", "あ", quad(0.32f, 0.22f, 0.38f, 0.28f), "HORIZONTAL", 0.6f)
        val result = processor.process(listOf(parent, smallKana))
        // No deletion regression: small kana stays in the lookup stream.
        assertTrue(result.canonicalText.contains("あ"))
        assertTrue(result.canonicalText.contains("漢字"))
    }

    @Test
    fun supplementaryKanjiAndCombiningMarksSurvive() {
        val text = "𠮟猫ガ"
        val lines = listOf(
            PostprocessorInputLine("l0", text, quad(0.1f, 0.1f, 0.9f, 0.3f), "HORIZONTAL", 0.9f),
        )
        val result = processor.process(lines)
        assertEquals(text, result.canonicalText)
        assertEquals(0, result.spans[0].startUtf16)
        assertEquals(text.length, result.spans[0].endUtf16)
    }

    @Test
    fun invalidGeometryIsRejectedWithoutCrashing() {
        val bad = PostprocessorInputLine(
            "bad",
            "テキスト",
            NormalizedQuad(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
            "HORIZONTAL",
            0.9f,
        )
        val good = PostprocessorInputLine("good", "本文", quad(0.1f, 0.1f, 0.9f, 0.2f), "HORIZONTAL", 0.9f)
        val result = processor.process(listOf(bad, good))
        assertEquals("本文", result.canonicalText)
        assertEquals(listOf("good"), result.spans.map { it.sourceId })
    }

    @Test
    fun deterministicTieBreakersKeepStableOrder() {
        val a = PostprocessorInputLine("a", "あ", quad(0.1f, 0.1f, 0.5f, 0.2f), "HORIZONTAL", 0.9f)
        val b = PostprocessorInputLine("b", "い", quad(0.1f, 0.1f, 0.5f, 0.2f), "HORIZONTAL", 0.9f)
        val first = processor.process(listOf(a, b)).canonicalText
        val second = processor.process(listOf(b, a)).canonicalText
        assertEquals(first, second)
    }
}
