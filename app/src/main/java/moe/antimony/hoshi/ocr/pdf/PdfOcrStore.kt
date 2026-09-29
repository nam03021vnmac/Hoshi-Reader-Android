package moe.antimony.hoshi.ocr.pdf

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.IoDispatcher

/**
 * Android-only derived OCR store outside iOS-compatible sidecars:
 * `noBackupFilesDir/pdf-ocr/<source-sha256>/<pipeline-key>/<page>/<revision>.json`.
 * Atomic writes, schema/range validation, revision pins for highlights, quota
 * eviction of unpinned results, and source invalidation on reimport.
 */
@Singleton
class PdfOcrStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val memory = mutableMapOf<String, OcrPageRecord>()
    private val pinsMemory = mutableMapOf<String, MutableMap<String, OcrHighlightPinRecord>>()

    private fun root(): File = File(context.noBackupFilesDir, "pdf-ocr")

    fun sourceHashForBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun sourceHashForFile(pdfFile: File): String? = withContext(ioDispatcher) {
        if (!pdfFile.isFile) return@withContext null
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            pdfFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun activeFile(sourceHash: String, pipelineKey: String, pageIndex: Int): File =
        File(root(), "$sourceHash/$pipelineKey/$pageIndex/active.json")

    private fun revisionFile(sourceHash: String, pipelineKey: String, pageIndex: Int, revisionId: String): File =
        File(root(), "$sourceHash/$pipelineKey/$pageIndex/$revisionId.json")

    private fun pinsFile(sourceHash: String): File =
        File(root(), "$sourceHash/pins.json")

    private fun memoryKey(sourceHash: String, pipelineKey: String, pageIndex: Int): String =
        "$sourceHash|$pipelineKey|$pageIndex"

    suspend fun loadActive(
        sourceHash: String,
        pipelineKey: String,
        pageIndex: Int,
    ): OcrPageRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            memory[memoryKey(sourceHash, pipelineKey, pageIndex)]?.let { return@withContext it }
            val file = activeFile(sourceHash, pipelineKey, pageIndex)
            if (!file.isFile) return@withContext null
            val record = runCatching {
                json.decodeFromString(OcrPageRecord.serializer(), file.readText())
            }.getOrNull()?.validateOrNull() ?: run {
                runCatching { file.delete() }
                return@withContext null
            }
            if (record.sourceSha256 != sourceHash || record.pageIndex != pageIndex) {
                runCatching { file.delete() }
                return@withContext null
            }
            memory[memoryKey(sourceHash, pipelineKey, pageIndex)] = record
            record
        }
    }

    suspend fun save(record: OcrPageRecord, pipelineKey: String): Boolean = withContext(ioDispatcher) {
        val valid = record.validateOrNull() ?: return@withContext false
        mutex.withLock {
            val revision = revisionFile(valid.sourceSha256, pipelineKey, valid.pageIndex, valid.revisionId)
            val active = activeFile(valid.sourceSha256, pipelineKey, valid.pageIndex)
            runCatching {
                revision.parentFile?.mkdirs()
                val temp = File(revision.parentFile, "${valid.revisionId}.tmp")
                temp.writeText(json.encodeToString(OcrPageRecord.serializer(), valid))
                if (!temp.renameTo(revision)) {
                    temp.delete()
                    return@withContext false
                }
                val activeTemp = File(active.parentFile, "active.tmp")
                activeTemp.writeText(json.encodeToString(OcrPageRecord.serializer(), valid))
                if (!activeTemp.renameTo(active)) {
                    activeTemp.delete()
                    return@withContext false
                }
                memory[memoryKey(valid.sourceSha256, pipelineKey, valid.pageIndex)] = valid
                true
            }.getOrDefault(false)
        }
    }

    suspend fun loadRevision(
        sourceHash: String,
        pipelineKey: String,
        pageIndex: Int,
        revisionId: String,
    ): OcrPageRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            val file = revisionFile(sourceHash, pipelineKey, pageIndex, revisionId)
            if (!file.isFile) return@withContext null
            runCatching {
                json.decodeFromString(OcrPageRecord.serializer(), file.readText())
            }.getOrNull()?.validateOrNull()
        }
    }

    suspend fun pinHighlight(pin: OcrHighlightPinRecord) = withContext(ioDispatcher) {
        mutex.withLock {
            val pins = loadPinsLocked(pin.sourceSha256).toMutableMap()
            pins[pin.highlightId] = pin
            writePinsLocked(pin.sourceSha256, pins)
            pinsMemory[pin.sourceSha256] = pins
        }
    }

    suspend fun unpinHighlight(sourceHash: String, highlightId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val pins = loadPinsLocked(sourceHash).toMutableMap()
            if (pins.remove(highlightId) != null) {
                writePinsLocked(sourceHash, pins)
                pinsMemory[sourceHash] = pins
            }
        }
    }

    suspend fun pinnedRevision(
        sourceHash: String,
        highlightId: String,
        pipelineKey: String,
    ): OcrPageRecord? = withContext(ioDispatcher) {
        val pin = mutex.withLock { loadPinsLocked(sourceHash)[highlightId] } ?: return@withContext null
        loadRevision(pin.sourceSha256, pipelineKey, pin.pageIndex, pin.revisionId)
    }

    suspend fun deleteSource(sourceHash: String) = withContext(ioDispatcher) {
        mutex.withLock {
            memory.keys.removeAll { it.startsWith("$sourceHash|") }
            pinsMemory.remove(sourceHash)
            runCatching {
                File(root(), sourceHash).takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
    }

    suspend fun evictUnpinnedIfOverQuota(maxBytes: Long = 100L * 1024L * 1024L) =
        withContext(ioDispatcher) {
            mutex.withLock {
                val rootDir = root()
                if (!rootDir.isDirectory) return@withContext
                var total = rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (total <= maxBytes) return@withContext
                val pinnedPaths = mutableSetOf<String>()
                rootDir.walkTopDown().filter { it.name == "pins.json" }.forEach { pinsFile ->
                    runCatching {
                        val pins: Map<String, OcrHighlightPinRecord> = json.decodeFromString(
                            MapSerializer(String.serializer(), OcrHighlightPinRecord.serializer()),
                            pinsFile.readText(),
                        )
                        for (pin in pins.values) {
                            pinnedPaths += "${pin.sourceSha256}/${pin.pageIndex}/${pin.revisionId}.json"
                        }
                    }
                }
                val candidates = rootDir.walkTopDown().filter { it.isFile && it.extension == "json" && it.name != "active.json" && it.name != "pins.json" }
                    .filter { file -> pinnedPaths.none { suffix -> file.absolutePath.replace('\\', '/').endsWith(suffix) } }
                    .sortedBy { it.lastModified() }
                for (file in candidates) {
                    if (total <= maxBytes) break
                    val size = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) total -= size
                }
            }
        }

    private fun loadPinsLocked(sourceHash: String): Map<String, OcrHighlightPinRecord> {
        pinsMemory[sourceHash]?.let { return it }
        val file = pinsFile(sourceHash)
        if (!file.isFile) return emptyMap()
        val pins = runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), OcrHighlightPinRecord.serializer()),
                file.readText(),
            )
        }.getOrDefault(emptyMap())
        pinsMemory[sourceHash] = pins.toMutableMap()
        return pins
    }

    private fun writePinsLocked(sourceHash: String, pins: Map<String, OcrHighlightPinRecord>) {
        runCatching {
            val file = pinsFile(sourceHash)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "pins.tmp")
            temp.writeText(
                json.encodeToString(
                    MapSerializer(String.serializer(), OcrHighlightPinRecord.serializer()),
                    pins,
                ),
            )
            temp.renameTo(file)
        }
    }
}
