package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfContentsSheetTest {
    @Test
    fun outlineRowsFlattenWithDepthAndPromoteUntitledChildren() {
        val rows = pdfOutlineRows(
            listOf(
                PdfOutlineItem(title = "Part", pageIndex = -1, children = listOf(
                    PdfOutlineItem(title = "Chapter 1", pageIndex = 1),
                    PdfOutlineItem(
                        title = "",
                        pageIndex = -1,
                        children = listOf(PdfOutlineItem(title = "Section", pageIndex = 3)),
                    ),
                )),
            ),
        )

        assertEquals(
            listOf(
                PdfOutlineRow(title = "Part", pageIndex = -1, depth = 0),
                PdfOutlineRow(title = "Chapter 1", pageIndex = 1, depth = 1),
                PdfOutlineRow(title = "Section", pageIndex = 3, depth = 1),
            ),
            rows,
        )
    }

    @Test
    fun outlineMessageParsesNestedItemsAndDefaultsMissingFields() {
        val message = PdfReaderMessage.fromJson(
            """{"type":"outline","items":[{"title":"A","pageIndex":2,"children":[{"title":"B"}]}]}""",
        )

        assertEquals(
            PdfReaderMessage.Outline(
                listOf(
                    PdfOutlineItem(
                        title = "A",
                        pageIndex = 2,
                        children = listOf(PdfOutlineItem(title = "B", pageIndex = -1)),
                    ),
                ),
            ),
            message,
        )
    }

    @Test
    fun viewportChangedParsesToStaleOverlayCloseSignal() {
        assertEquals(
            PdfReaderMessage.ViewportChanged,
            PdfReaderMessage.fromJson("""{"type":"viewportChanged"}"""),
        )
    }

    @Test
    fun displayPageIsOneBasedAndClamped() {
        assertEquals(1, pdfDisplayPage(0, 10))
        assertEquals(8, pdfDisplayPage(7, 10))
        assertEquals(10, pdfDisplayPage(99, 10))
        assertEquals(1, pdfDisplayPage(-4, 10))
    }
}
