package moe.antimony.hoshi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import moe.antimony.hoshi.ocr.OcrEngine
import moe.antimony.hoshi.ocr.paddle.PaddleOcrEngine
import moe.antimony.hoshi.ocr.pdf.PdfOcrReaderRepository
import moe.antimony.hoshi.ocr.pdf.PdfOcrRepository

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OcrModule {
    @Binds
    abstract fun bindOcrEngine(impl: PaddleOcrEngine): OcrEngine
    @Binds
    abstract fun bindPdfOcrRepository(impl: PdfOcrRepository): PdfOcrReaderRepository
}
