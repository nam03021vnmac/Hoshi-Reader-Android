package moe.antimony.hoshi.ocr.pdf

import java.io.File
import javax.inject.Inject
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.ocr.OcrEngine

/** Recognition model family for a PDF book. */
enum class PdfOcrModel {
    Standard,

    /** Books on a shelf marked as manga. */
    Manga,
}

/** One lazily initialized engine per model; the repository runs one at a time. */
class PdfOcrEngines(
    private val standard: OcrEngine,
    private val manga: OcrEngine,
) {
    operator fun get(model: PdfOcrModel): OcrEngine = when (model) {
        PdfOcrModel.Standard -> standard
        PdfOcrModel.Manga -> manga
    }
}

/** Chooses a book's OCR model when the reader opens it. */
fun interface PdfOcrModelSource {
    suspend fun modelFor(bookRoot: File?): PdfOcrModel
}

class ShelfPdfOcrModelSource @Inject constructor(
    private val bookRepository: BookRepository,
) : PdfOcrModelSource {
    override suspend fun modelFor(bookRoot: File?): PdfOcrModel =
        if (bookRoot != null && bookRepository.isInMangaShelf(bookRoot)) PdfOcrModel.Manga else PdfOcrModel.Standard
}
