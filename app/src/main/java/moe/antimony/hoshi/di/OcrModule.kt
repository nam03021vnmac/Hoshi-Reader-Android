package moe.antimony.hoshi.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import moe.antimony.hoshi.ocr.paddle.PaddleOcrConfiguration
import moe.antimony.hoshi.ocr.paddle.PaddleOcrEngine
import moe.antimony.hoshi.ocr.paddle.PaddleOcrModelSpec
import moe.antimony.hoshi.ocr.pdf.PdfOcrEngines
import moe.antimony.hoshi.ocr.pdf.PdfOcrModelSource
import moe.antimony.hoshi.ocr.pdf.PdfOcrReaderRepository
import moe.antimony.hoshi.ocr.pdf.PdfOcrRepository
import moe.antimony.hoshi.ocr.pdf.ShelfPdfOcrModelSource

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OcrModule {
    @Binds
    abstract fun bindPdfOcrRepository(impl: PdfOcrRepository): PdfOcrReaderRepository
    @Binds
    abstract fun bindPdfOcrModelSource(impl: ShelfPdfOcrModelSource): PdfOcrModelSource

    companion object {
        @Provides
        @Singleton
        fun providePdfOcrEngines(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
            configuration: PaddleOcrConfiguration,
        ): PdfOcrEngines = PdfOcrEngines(
            standard = PaddleOcrEngine(context, ioDispatcher, configuration, PaddleOcrModelSpec.Standard),
            manga = PaddleOcrEngine(context, ioDispatcher, configuration, PaddleOcrModelSpec.Manga),
        )
    }
}
