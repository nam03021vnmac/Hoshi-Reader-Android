package moe.antimony.hoshi.ocr.pdf

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
class PdfOcrStore internal constructor(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(File(context.noBackupFilesDir, "pdf-ocr"), ioDispatcher)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val memory = object : LinkedHashMap<String, OcrPageRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OcrPageRecord>?): Boolean = size > 8
    }
    private val pinsMemory = mutableMapOf<String, MutableMap<String, OcrHighlightPinRecord>>()

    private fun root(): File = directory
    private val maintenanceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val maintenanceMutex = Mutex()
    private var lastMaintenanceNanos = 0L

    fun scheduleEviction() {
        maintenanceScope.launch {
            if (!maintenanceMutex.tryLock()) return@launch
            try {
                val now = System.nanoTime()
                if (lastMaintenanceNanos != 0L && now - lastMaintenanceNanos < 60_000_000_000L) return@launch
                lastMaintenanceNanos = now
                evictUnpinnedIfOverQuota(protectWorkingSet = true)
            } finally {
                maintenanceMutex.unlock()
            }
        }
    }

    fun sourceHashForBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    suspend fun sourceHashForFile(pdfFile: File): String? = withContext(ioDispatcher) {
        if (!pdfFile.isFile) return@withContext null
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            pdfFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
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
            if (loadPinsLocked(valid.sourceSha256).values.any {
                it.pageIndex == valid.pageIndex && it.revisionId != valid.revisionId
            }) return@withContext false
            val revision = revisionFile(valid.sourceSha256, pipelineKey, valid.pageIndex, valid.revisionId)
            val active = activeFile(valid.sourceSha256, pipelineKey, valid.pageIndex)
            runCatching {
                revision.parentFile?.mkdirs()
                val temp = File(revision.parentFile, "${valid.revisionId}.tmp")
                val encoded = json.encodeToString(OcrPageRecord.serializer(), valid)
                temp.writeText(encoded)
                Files.move(temp.toPath(), revision.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                val activeTemp = File(active.parentFile, "active.tmp")
                activeTemp.writeText(encoded)
                Files.move(activeTemp.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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

    suspend fun pinHighlight(pin: OcrHighlightPinRecord): Boolean = withContext(ioDispatcher) {
        if (pin.pageIndex < 0 || pin.revisionId.isBlank() || pin.revisionId.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return@withContext false
        mutex.withLock {
            val pins = loadPinsLocked(pin.sourceSha256).toMutableMap()
            if (pins.values.any { it.pageIndex == pin.pageIndex && it.revisionId != pin.revisionId }) return@withContext false
            if (findRevisionLocked(pin.sourceSha256, pin.pageIndex, pin.revisionId) == null) return@withContext false
            pins[pin.highlightId] = pin
            if (!writePinsLocked(pin.sourceSha256, pins)) return@withContext false
            pinsMemory[pin.sourceSha256] = pins
            memory.remove(memoryKey(pin.sourceSha256, "pinned", pin.pageIndex))
            true
        }
    }

    suspend fun unpinHighlight(sourceHash: String, highlightId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val pins = loadPinsLocked(sourceHash).toMutableMap()
            if (pins.remove(highlightId) != null) {
                if (writePinsLocked(sourceHash, pins)) {
                    pinsMemory[sourceHash] = pins
                    memory.keys.removeAll { it.startsWith("$sourceHash|pinned|") }
                }
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

    /** Pins survive pipeline upgrades. Never apply old UTF-16 anchors to freshly recognized text. */
    suspend fun pinnedPage(sourceHash: String, pageIndex: Int): OcrPageRecord? = withContext(ioDispatcher) {
        mutex.withLock {
            val pin = loadPinsLocked(sourceHash).values.firstOrNull { it.pageIndex == pageIndex }
                ?: return@withContext null
            val key = memoryKey(sourceHash, "pinned", pageIndex)
            memory[key]?.takeIf { it.revisionId == pin.revisionId }?.let { return@withContext it }
            findRevisionLocked(sourceHash, pageIndex, pin.revisionId)?.also { memory[key] = it }
        }
    }

    private fun findRevisionLocked(sourceHash: String, pageIndex: Int, revisionId: String): OcrPageRecord? {
        val pipelines = File(root(), sourceHash).listFiles().orEmpty().filter { it.isDirectory }
        for (pipeline in pipelines) {
            val file = File(pipeline, "$pageIndex/$revisionId.json")
            val record = runCatching { json.decodeFromString<OcrPageRecord>(file.readText()) }
                .getOrNull()?.validateOrNull() ?: continue
            if (record.sourceSha256 == sourceHash && record.pageIndex == pageIndex && record.revisionId == revisionId) return record
        }
        return null
    }

    suspend fun hasPinnedPage(sourceHash: String, pageIndex: Int): Boolean = withContext(ioDispatcher) {
        mutex.withLock { loadPinsLocked(sourceHash).values.any { it.pageIndex == pageIndex } }
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

    suspend fun evictUnpinnedIfOverQuota(maxBytes: Long = 100L * 1024L * 1024L, protectWorkingSet: Boolean = false) =
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
                            // Pipeline is deliberately not in legacy pins; resolve in every pipeline.
                            File(rootDir, pin.sourceSha256).listFiles().orEmpty().filter { it.isDirectory }.forEach { pipeline ->
                                pinnedPaths += File(pipeline, "${pin.pageIndex}/${pin.revisionId}.json").absolutePath
                            }
                        }
                    }
                }
                val pinnedPages = pinnedPaths.map { File(it).parentFile!!.absolutePath }.toSet()
                // A displayed old revision can be pinned while a retry is waiting to install.
                // Keep every revision of cached working-set pages until they leave the LRU.
                val livePages = if (protectWorkingSet) memory.values.map { "${it.sourceSha256}/${it.pageIndex}" }.toSet() else emptySet()
                val candidates = rootDir.walkTopDown().filter { it.isFile && it.extension == "json" && it.name != "pins.json" }
                    .filter { file -> file.absolutePath !in pinnedPaths &&
                        !(file.name == "active.json" && file.parentFile!!.absolutePath in pinnedPages) &&
                        "${file.parentFile?.parentFile?.parentFile?.name}/${file.parentFile?.name}" !in livePages }
                    .sortedBy { it.lastModified() }
                for (file in candidates) {
                    if (total <= maxBytes) break
                    val size = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        total -= size
                        if (file.name == "active.json") memory.clear()
                    }
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

    private fun writePinsLocked(sourceHash: String, pins: Map<String, OcrHighlightPinRecord>): Boolean =
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
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
}
