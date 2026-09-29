package moe.antimony.hoshi.epub

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class PdfBookParser @Inject constructor() {
    fun parse(pdfFile: File, fallbackTitle: String? = null): PdfBook {
        val pageCount = pdfPageCount(pdfFile)
        return PdfBook(
            title = fallbackTitle ?: pdfFile.nameWithoutExtension,
            pageCount = pageCount,
        )
    }

    fun renderCover(pdfFile: File, destination: File, maxDimensionPx: Int = 768): Boolean {
        if (destination.exists()) destination.delete()
        return renderFirstPage(
            pdfFile = pdfFile,
            maxDimensionPx = maxDimensionPx,
        )?.let { bitmap ->
            try {
                FileOutputStream(destination).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                }
                true
            } catch (_: Exception) {
                false
            } finally {
                bitmap.recycle()
            }
        } ?: false
    }
}

private fun pdfPageCount(pdfFile: File): Int {
    require(pdfFile.isFile) { "PDF file does not exist: ${pdfFile.absolutePath}" }
    ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            return renderer.pageCount
        }
    }
}

private fun renderFirstPage(pdfFile: File, maxDimensionPx: Int): Bitmap? {
    require(pdfFile.isFile) { "PDF file does not exist: ${pdfFile.absolutePath}" }
    return ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount <= 0) return null
            renderer.openPage(0).use { page ->
                val scale = computeCoverScale(page.width, page.height, maxDimensionPx)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                )
                bitmap
            }
        }
    }
}

private fun computeCoverScale(width: Int, height: Int, maxDimensionPx: Int): Float {
    val longest = maxOf(width, height)
    if (longest <= 0 || longest <= maxDimensionPx) return 1f
    return maxDimensionPx.toFloat() / longest.toFloat()
}
