package moe.antimony.hoshi.ocr.pdf

import java.io.File

/** Reader-facing OCR boundary; bitmap/model/store lifetimes stay in the repository. */
interface PdfOcrReaderRepository {
    suspend fun sourceHashFor(pdfFile: File): String?
    suspend fun ocrModelFor(bookRoot: File?): PdfOcrModel
    suspend fun cachedPage(sourceHash: String, pageIndex: Int, model: PdfOcrModel): OcrPageRecord?
    suspend fun hasPinnedPage(sourceHash: String, pageIndex: Int): Boolean
    suspend fun ocrPage(
        pdfFile: File,
        bookRoot: File?,
        sourceHash: String,
        pageIndex: Int,
        cssWidth: Double,
        cssHeight: Double,
        highRes: Boolean,
        model: PdfOcrModel,
        force: Boolean = false,
        onStage: (PdfOcrStage) -> Unit = {},
    ): PdfOcrResult
    suspend fun pinHighlightForRevision(highlightId: String, sourceHash: String, pageIndex: Int, revisionId: String): Boolean
    suspend fun unpinHighlight(sourceHash: String, highlightId: String)
}
