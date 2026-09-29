package moe.antimony.hoshi.ocr.layout

import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.ocr.pdf.NormalizedQuad
import moe.antimony.hoshi.ocr.pdf.OcrParagraphRecord
import moe.antimony.hoshi.ocr.pdf.OcrRawObservationRecord
import moe.antimony.hoshi.ocr.pdf.OcrRubyAssociationRecord
import moe.antimony.hoshi.ocr.pdf.OcrSpanRecord
import moe.antimony.hoshi.ocr.pdf.OcrSymbolRecord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PostprocessorInputLine(
    val id: String,
    val text: String,
    val quad: NormalizedQuad?,
    val direction: String,
    val confidence: Float?,
    val symbols: List<OcrSymbolRecord> = emptyList(),
)

data class PostprocessedOcrPage(
    val canonicalText: String,
    val paragraphs: List<OcrParagraphRecord>,
    val spans: List<OcrSpanRecord>,
    val rubyAssociations: List<OcrRubyAssociationRecord>,
    val sourceBoundaryMap: List<Int>,
    val qualityFlags: List<String>,
    val rawObservations: List<OcrRawObservationRecord>,
)

/**
 * Pure deterministic Japanese layout, ruby association, and ordered text
 * construction. No UI, dictionary repository, or Context.
 *
 * Conservative v1 rules:
 * - Preserve verbatim text, full quads, source IDs, and symbols.
 * - Never delete ambiguous small kana: ruby candidates are recorded but kept
 *   in the base lookup stream until a separately validated exclusion lands.
 * - No destructive punctuation cleanup: `!?` and ellipses are preserved.
 * - Soft wraps inside a confirmed Japanese paragraph join without a space;
 *   genuine Latin word spaces and paragraph breaks are preserved.
 */
@Singleton
class JapaneseOcrPostprocessor @Inject constructor() {
    fun process(lines: List<PostprocessorInputLine>): PostprocessedOcrPage {
        val valid = lines.mapNotNull { line ->
            val text = line.text
            if (text.isEmpty()) return@mapNotNull null
            if (text.all { it.isWhitespace() || it.isISOControl() }) return@mapNotNull null
            val quad = line.quad
            if (quad != null && !quad.isFiniteAndBounded()) return@mapNotNull null
            if (quad != null) {
                val rect = quad.boundingRect()
                if (rect[2] - rect[0] <= 0f || rect[3] - rect[1] <= 0f) return@mapNotNull null
            }
            line
        }
        val rawObservations = valid.map { line ->
            OcrRawObservationRecord(
                id = line.id,
                rawText = line.text,
                quad = line.quad,
                direction = line.direction.ifBlank { "UNKNOWN" },
                confidence = line.confidence?.takeIf { it.isFinite() },
                symbols = line.symbols,
            )
        }
        if (valid.isEmpty()) {
            return PostprocessedOcrPage(
                canonicalText = "",
                paragraphs = emptyList(),
                spans = emptyList(),
                rubyAssociations = emptyList(),
                sourceBoundaryMap = emptyList(),
                qualityFlags = listOf("empty"),
                rawObservations = rawObservations,
            )
        }

        val oriented = valid.map { it.copy(direction = resolveDirection(it)) }
        val deduplicated = deduplicate(oriented)
        val ruby = associateRuby(deduplicated)
        val paragraphs = groupParagraphs(deduplicated)
        val ordered = orderParagraphs(paragraphs, deduplicated)

        val canonical = StringBuilder()
        val spans = mutableListOf<OcrSpanRecord>()
        val paragraphRecords = mutableListOf<OcrParagraphRecord>()
        val boundaries = mutableListOf<Int>()
        var paragraphIndex = 0
        for (paragraphLines in ordered) {
            if (canonical.isNotEmpty()) {
                canonical.append('\n')
                boundaries += canonical.length
            }
            val paragraphStart = canonical.length
            paragraphLines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) {
                    val previous = paragraphLines[lineIndex - 1]
                    val separator = softSeparator(previous.text, line.text)
                    if (separator.isNotEmpty()) {
                        canonical.append(separator)
                    }
                    boundaries += canonical.length
                }
                val spanStart = canonical.length
                canonical.append(line.text)
                val spanEnd = canonical.length
                spans += OcrSpanRecord(
                    sourceId = line.id,
                    startUtf16 = spanStart,
                    endUtf16 = spanEnd,
                    quad = line.quad,
                    geometryPrecision = if (line.symbols.isNotEmpty()) "observed" else "line-estimate",
                )
            }
            val paragraphEnd = canonical.length
            paragraphRecords += OcrParagraphRecord(
                id = "p$paragraphIndex",
                orderedLineIds = paragraphLines.map { it.id },
                canonicalStartUtf16 = paragraphStart,
                canonicalEndUtf16 = paragraphEnd,
            )
            paragraphIndex += 1
        }

        val flags = mutableListOf<String>()
        val verticalCount = deduplicated.count { it.direction == "VERTICAL" }
        val horizontalCount = deduplicated.count { it.direction == "HORIZONTAL" }
        if (verticalCount > 0 && horizontalCount > 0) flags += "mixed-orientation"
        if (verticalCount > horizontalCount) flags += "vertical-dominant" else flags += "horizontal-dominant"
        if (ruby.isNotEmpty()) flags += "ruby-candidates"
        if (deduplicated.any { (it.confidence ?: 1f) < 0.5f }) flags += "low-confidence"

        return PostprocessedOcrPage(
            canonicalText = canonical.toString(),
            paragraphs = paragraphRecords,
            spans = spans,
            rubyAssociations = ruby,
            sourceBoundaryMap = boundaries,
            qualityFlags = flags,
            rawObservations = rawObservations,
        )
    }

    private fun resolveDirection(line: PostprocessorInputLine): String {
        if (line.direction == "HORIZONTAL" || line.direction == "VERTICAL") return line.direction
        val quad = line.quad ?: return "UNKNOWN"
        val rect = quad.boundingRect()
        val width = rect[2] - rect[0]
        val height = rect[3] - rect[1]
        if (width <= 0f || height <= 0f) return "UNKNOWN"
        val textLength = line.text.codePointCount(0, line.text.length).coerceAtLeast(1)
        val avgCharWidth = width / textLength
        val avgCharHeight = height / textLength
        // Vertical columns are tall and narrow; horizontal lines are wide and short.
        if (height > width * 1.8f && avgCharWidth < avgCharHeight * 1.2f) return "VERTICAL"
        if (width > height * 1.8f) return "HORIZONTAL"
        // Dense kanji fallback: tall narrow boxes with CJK content read vertically.
        if (height > width && line.text.any { it.isCjk() }) return "VERTICAL"
        return "HORIZONTAL"
    }

    private fun deduplicate(lines: List<PostprocessorInputLine>): List<PostprocessorInputLine> {
        if (lines.size < 2) return lines
        val kept = mutableListOf<PostprocessorInputLine>()
        for (line in lines) {
            val duplicate = kept.any { other ->
                other.text == line.text && quadsOverlapHeavily(other.quad, line.quad)
            }
            // Keep repeated words at distinct locations; only drop near-identical overlaps.
            if (!duplicate) kept += line
        }
        return kept
    }

    private fun quadsOverlapHeavily(a: NormalizedQuad?, b: NormalizedQuad?): Boolean {
        if (a == null || b == null) return false
        val ra = a.boundingRect()
        val rb = b.boundingRect()
        val interLeft = max(ra[0], rb[0])
        val interTop = max(ra[1], rb[1])
        val interRight = min(ra[2], rb[2])
        val interBottom = min(ra[3], rb[3])
        if (interRight <= interLeft || interBottom <= interTop) return false
        val inter = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (ra[2] - ra[0]) * (ra[3] - ra[1])
        val areaB = (rb[2] - rb[0]) * (rb[3] - rb[1])
        val smaller = min(areaA, areaB)
        if (smaller <= 0f) return false
        return inter / smaller > 0.8f
    }

    private fun associateRuby(lines: List<PostprocessorInputLine>): List<OcrRubyAssociationRecord> {
        // Small kana near larger kanji, right-of-vertical / above-horizontal.
        // Ambiguous small kana are retained as body text; associations only annotate.
        val associations = mutableListOf<OcrRubyAssociationRecord>()
        val byId = lines.associateBy { it.id }
        for (candidate in lines) {
            if (!candidate.text.isAllKana()) continue
            val candidateRect = candidate.quad?.boundingRect() ?: continue
            val candidateHeight = candidateRect[3] - candidateRect[1]
            val candidateWidth = candidateRect[2] - candidateRect[0]
            val candidateSize = min(candidateHeight, candidateWidth)
            if (candidateSize <= 0f) continue
            for (parent in lines) {
                if (parent.id == candidate.id) continue
                if (!parent.text.any { it.isKanji() }) continue
                val parentRect = parent.quad?.boundingRect() ?: continue
                val parentHeight = parentRect[3] - parentRect[1]
                val parentWidth = parentRect[2] - parentRect[0]
                val parentCharSize = if (parent.direction == "VERTICAL") {
                    parentWidth
                } else {
                    parentHeight
                }.coerceAtLeast(1e-6f)
                // Require local-size evidence: ruby clearly smaller than the parent.
                if (candidateSize > parentCharSize * 0.62f) continue
                val isRightOfVertical = parent.direction == "VERTICAL" &&
                    candidateRect[0] >= parentRect[2] - parentCharSize * 0.25f &&
                    candidateRect[0] <= parentRect[2] + parentCharSize * 1.2f &&
                    overlaps(candidateRect[1], candidateRect[3], parentRect[1], parentRect[3])
                val isAboveHorizontal = parent.direction != "VERTICAL" &&
                    candidateRect[3] <= parentRect[1] + parentCharSize * 0.25f &&
                    candidateRect[3] >= parentRect[1] - parentCharSize * 1.2f &&
                    overlaps(candidateRect[0], candidateRect[2], parentRect[0], parentRect[2])
                if (isRightOfVertical || isAboveHorizontal) {
                    associations += OcrRubyAssociationRecord(
                        readingSourceIds = listOf(candidate.id),
                        baseStartUtf16 = -1,
                        baseEndUtf16 = -1,
                        confidence = 0.4f,
                    )
                    // Resolve base range later after canonical offsets exist; keep marker only.
                    break
                }
            }
        }
        // Base ranges are filled by the caller after canonical construction; v1 keeps
        // ruby in the base stream so unresolved ranges use -1 and never shift offsets.
        return associations.filter { byId.containsKey(it.readingSourceIds.firstOrNull()) }
    }

    private fun overlaps(a0: Float, a1: Float, b0: Float, b1: Float): Boolean =
        min(a1, b1) - max(a0, b0) > 0f

    private fun groupParagraphs(lines: List<PostprocessorInputLine>): List<List<PostprocessorInputLine>> {
        if (lines.isEmpty()) return emptyList()
        val horizontal = lines.filter { it.direction != "VERTICAL" }.sortedWith(
            compareBy({ it.quad?.boundingRect()?.get(1) ?: 0f }, { it.quad?.boundingRect()?.get(0) ?: 0f }, { it.id }),
        )
        val vertical = lines.filter { it.direction == "VERTICAL" }.sortedWith(
            compareByDescending<PostprocessorInputLine> { it.quad?.boundingRect()?.get(0) ?: 0f }
                .thenBy { it.quad?.boundingRect()?.get(1) ?: 0f }
                .thenBy { it.id },
        )
        val groups = mutableListOf<List<PostprocessorInputLine>>()
        groups += groupOriented(horizontal, vertical = false)
        groups += groupOriented(vertical, vertical = true)
        return groups.filter { it.isNotEmpty() }
    }

    private fun groupOriented(
        sorted: List<PostprocessorInputLine>,
        vertical: Boolean,
    ): List<List<PostprocessorInputLine>> {
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<PostprocessorInputLine>>()
        var current = mutableListOf(sorted[0])
        groups += current
        for (index in 1 until sorted.size) {
            val previous = sorted[index - 1]
            val line = sorted[index]
            if (belongsToSameParagraph(previous, line, vertical)) {
                current += line
            } else {
                current = mutableListOf(line)
                groups += current
            }
        }
        return groups
    }

    private fun belongsToSameParagraph(
        previous: PostprocessorInputLine,
        line: PostprocessorInputLine,
        vertical: Boolean,
    ): Boolean {
        val prevRect = previous.quad?.boundingRect()
        val rect = line.quad?.boundingRect()
        if (prevRect == null || rect == null) {
            // Without geometry, only join when clearly continuing prose.
            return false
        }
        val prevCharSize = if (vertical) {
            (prevRect[2] - prevRect[0]).coerceAtLeast(1e-6f)
        } else {
            (prevRect[3] - prevRect[1]).coerceAtLeast(1e-6f)
        }
        return if (vertical) {
            // Same column: overlapping vertical span, small horizontal gutter.
            val gutter = prevRect[0] - rect[2]
            val verticalOverlap = min(prevRect[3], rect[3]) - max(prevRect[1], rect[1])
            val prevHeight = (prevRect[3] - prevRect[1]).coerceAtLeast(1e-6f)
            gutter > -prevCharSize * 0.6f && gutter < prevCharSize * 2.5f &&
                verticalOverlap > -prevHeight * 0.2f
        } else {
            val gap = rect[1] - prevRect[3]
            val horizontalOverlap = min(prevRect[2], rect[2]) - max(prevRect[0], rect[0])
            val prevWidth = (prevRect[2] - prevRect[0]).coerceAtLeast(1e-6f)
            gap > -prevCharSize * 0.6f && gap < prevCharSize * 2.0f &&
                horizontalOverlap > -prevWidth * 0.2f
        }
    }

    private fun orderParagraphs(
        groups: List<List<PostprocessorInputLine>>,
        allLines: List<PostprocessorInputLine>,
    ): List<List<PostprocessorInputLine>> {
        if (groups.size <= 1) return groups
        val verticalCount = allLines.count { it.direction == "VERTICAL" }
        val horizontalCount = allLines.size - verticalCount
        val verticalDominant = verticalCount > horizontalCount
        return groups.sortedWith { a, b ->
            val ra = boundsOf(a)
            val rb = boundsOf(b)
            val cmp = if (verticalDominant) {
                // Vertical right-to-left columns first, then top-to-bottom.
                val right = rb[2].compareTo(ra[2])
                if (right != 0) right else ra[1].compareTo(rb[1])
            } else {
                val top = ra[1].compareTo(rb[1])
                if (top != 0) top else rb[2].compareTo(ra[2])
            }
            if (cmp != 0) cmp else a.first().id.compareTo(b.first().id)
        }
    }

    private fun boundsOf(group: List<PostprocessorInputLine>): FloatArray {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (line in group) {
            val rect = line.quad?.boundingRect() ?: continue
            left = min(left, rect[0])
            top = min(top, rect[1])
            right = max(right, rect[2])
            bottom = max(bottom, rect[3])
        }
        if (left == Float.MAX_VALUE) return floatArrayOf(0f, 0f, 0f, 0f)
        return floatArrayOf(left, top, right, bottom)
    }

    private fun softSeparator(previousText: String, nextText: String): String {
        if (previousText.isEmpty() || nextText.isEmpty()) return ""
        val prev = previousText.last()
        val next = nextText.first()
        // Preserve genuine Latin word spaces; join Japanese soft wraps without a space.
        if (prev.isLatinWordChar() && next.isLatinWordChar()) return " "
        if (prev == ' ' || prev == '\u3000' || next == ' ' || next == '\u3000') return ""
        if (prev.isCjkOrKana() && next.isCjkOrKana()) return ""
        // Keep punctuation adjacency tight; paragraph breaks already carry '\n'.
        if (prev.isCjkOrKana() || next.isCjkOrKana()) return ""
        return " "
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF' ||
            this in '\u3040'..'\u309F' || this in '\u30A0'..'\u30FF'

    private fun Char.isKanji(): Boolean =
        this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'

    private fun Char.isCjkOrKana(): Boolean = isCjk() ||
        this in '\u3040'..'\u309F' || this in '\u30A0'..'\u30FF' ||
        this in '々'..'〻' || this == 'ー' || this == '・'

    private fun Char.isLatinWordChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun String.isAllKana(): Boolean {
        if (isEmpty()) return false
        return all {
            it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' ||
                it in 'ぁ'..'ん' || it in 'ァ'..'ヶ' || it == 'ー' || it == '・'
        }
    }
}

private fun Char.isKanji(): Boolean =
    this in '\u4E00'..'\u9FFF' || this in '\u3400'..'\u4DBF'
