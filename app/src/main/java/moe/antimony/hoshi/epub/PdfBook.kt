package moe.antimony.hoshi.epub

data class PdfBook(
    val title: String,
    val pageCount: Int,
) {
    val bookInfo: BookInfo
        get() = BookInfo(
            characterCount = pageCount,
            chapterInfo = mapOf(
                "pdf" to BookInfo.ChapterInfo(
                    spineIndex = (pageCount - 1).coerceAtLeast(0),
                    currentTotal = 0,
                    chapterCount = pageCount,
                ),
            ),
        )

    fun characterCountAt(pageIndex: Int, progress: Double): Int =
        (pageIndex + progress.toInt()).coerceIn(0, pageCount - 1)
}
