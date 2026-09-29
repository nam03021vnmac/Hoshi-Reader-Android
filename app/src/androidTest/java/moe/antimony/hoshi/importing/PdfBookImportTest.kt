package moe.antimony.hoshi.importing

import android.content.Context
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookFileDataSource
import moe.antimony.hoshi.epub.BookImportDataSource
import moe.antimony.hoshi.epub.PdfBookParser
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBookImportTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        filesDir = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-import-test-").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun validPdfImportUsesByteSafeNameAndReimportPreservesStoredBook() = runBlocking {
        val files = BookFileDataSource(filesDir)
        val imports = BookImportDataSource(filesDir, files)
        val bytes = pdfBytes()
        val name = "\u9577".repeat(86) + ".PDF"

        val root = imports.importBook(name, bytes.inputStream())
        val packed = requireNotNull(files.pdfFile(root, null))
        root.resolve("bookmark.json").writeText("saved bookmark")
        val duplicate = imports.importBook(name, bytes.inputStream())

        assertTrue(root.name.toByteArray(Charsets.UTF_8).size <= 250)
        assertEquals(root, duplicate)
        assertArrayEquals(bytes, packed.readBytes())
        assertEquals(1, PdfBookParser().parse(packed).pageCount)
        assertEquals("saved bookmark", root.resolve("bookmark.json").readText())
        assertTrue(filesDir.resolve("ImportTemp").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun invalidPdfIsRejectedBeforePublishingBook() = runBlocking {
        val files = BookFileDataSource(filesDir)
        val imports = BookImportDataSource(filesDir, files)

        val result = runCatching { imports.importBook("invalid.pdf", "not a PDF".byteInputStream()) }

        assertTrue(result.isFailure)
        assertTrue(files.loadAllBooks().isEmpty())
        assertTrue(filesDir.resolve("ImportTemp").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pdfImportCannotReplaceSameTitleEpubOrItsSidecars() = runBlocking {
        val files = BookFileDataSource(filesDir)
        val root = files.createBookDirectory("Book")
        root.resolve("Book.epub").writeText("existing packed EPUB")
        root.resolve("metadata.json").writeText("existing metadata")
        val imports = BookImportDataSource(filesDir, files)

        val result = runCatching { imports.importBook("Book.pdf", pdfBytes().inputStream()) }

        assertTrue(result.isFailure)
        assertEquals(setOf("Book.epub", "metadata.json"), root.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals("existing packed EPUB", root.resolve("Book.epub").readText())
        assertEquals("existing metadata", root.resolve("metadata.json").readText())
        assertTrue(filesDir.resolve("ImportTemp").listFiles().orEmpty().isEmpty())
    }

    private fun pdfBytes(): ByteArray {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
            document.finishPage(page)
            return ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }
}
