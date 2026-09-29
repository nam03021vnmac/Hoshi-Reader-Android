package moe.antimony.hoshi.ocr.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import kotlin.math.sqrt

data class PdfOcrRasterInput(
    val bitmap: Bitmap,
    val nativeWidthPt: Int,
    val nativeHeightPt: Int,
    val dpi: Int,
    val highRes: Boolean,
)

/**
 * Renders directly from the imported PDF with a dedicated PdfRenderer owner,
 * never from the cover bitmap or a WebView screenshot. White ARGB_8888
 * destination with an explicit transform, off the main thread, serialized
 * open/render/close with resources closed in all completion paths.
 */
@Singleton
class PdfOcrRasterizer @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val rendererMutex = Mutex()

    suspend fun rasterize(
        pdfFile: File,
        pageIndex: Int,
        highRes: Boolean,
    ): PdfOcrRasterInput? = withContext(ioDispatcher) {
        if (!pdfFile.isFile || pageIndex < 0) return@withContext null
        rendererMutex.withLock {
            runCatching {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (pageIndex >= renderer.pageCount) return@withContext null
                        renderer.openPage(pageIndex).use { page ->
                            val nativeWidth = page.width
                            val nativeHeight = page.height
                            if (nativeWidth <= 0 || nativeHeight <= 0) return@withContext null
                            val baseDpi = if (highRes) 300 else 200
                            var scale = baseDpi / 72f
                            // Cap at 3 megapixels per input bitmap (~12 MiB ARGB).
                            val maxPixels = 3_000_000.0
                            val rawPixels = nativeWidth * scale * nativeHeight * scale
                            if (rawPixels > maxPixels) {
                                scale *= sqrt(maxPixels / rawPixels).toFloat()
                            }
                            val width = (nativeWidth * scale).toInt().coerceAtLeast(1)
                            val height = (nativeHeight * scale).toInt().coerceAtLeast(1)
                            if (width <= 0 || height <= 0 || width * height.toLong() > 12_000_000L) {
                                return@withContext null
                            }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            val matrix = Matrix().apply { setScale(scale, scale) }
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val dpi = (72f * scale).toInt().coerceAtLeast(72)
                            PdfOcrRasterInput(
                                bitmap = bitmap,
                                nativeWidthPt = nativeWidth,
                                nativeHeightPt = nativeHeight,
                                dpi = dpi,
                                highRes = highRes,
                            )
                        }
                    }
                }
            }.getOrNull()
        }
    }

    suspend fun nativePageSize(pdfFile: File, pageIndex: Int): Pair<Int, Int>? =
        withContext(ioDispatcher) {
            if (!pdfFile.isFile || pageIndex < 0) return@withContext null
            rendererMutex.withLock {
                runCatching {
                    ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            if (pageIndex >= renderer.pageCount) return@withContext null
                            renderer.openPage(pageIndex).use { page ->
                                Pair(page.width, page.height)
                            }
                        }
                    }
                }.getOrNull()
            }
        }
}
