package moe.antimony.hoshi.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import moe.antimony.hoshi.ocr.OcrEngine
import moe.antimony.hoshi.ocr.mlkit.MlKitJapaneseOcrEngine

@Module
@InstallIn(SingletonComponent::class)
internal abstract class OcrModule {
    @Binds
    abstract fun bindOcrEngine(impl: MlKitJapaneseOcrEngine): OcrEngine
}
