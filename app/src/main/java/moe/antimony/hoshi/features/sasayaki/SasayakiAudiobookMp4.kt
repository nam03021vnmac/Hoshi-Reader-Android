package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

internal data class SasayakiAudiobookMp4Info(
    val metadata: SasayakiAudiobookMetadata = SasayakiAudiobookMetadata.Empty,
    val chapters: List<SasayakiAudiobookChapter> = emptyList(),
    val durationSeconds: Double? = null,
)

internal object SasayakiAudiobookMp4 {
    fun parse(file: File): SasayakiAudiobookMp4Info? =
        try {
            if (!file.isFile) return null
            Files.newByteChannel(file.toPath()).use(::parse)
        } catch (_: Exception) {
            null
        }

    fun parse(channel: SeekableByteChannel): SasayakiAudiobookMp4Info? =
        try {
            Mp4AudiobookReader(channel).read()
        } catch (_: Exception) {
            null
        }
}

private class Mp4AudiobookReader(
    private val input: SeekableByteChannel,
) {
    fun read(): SasayakiAudiobookMp4Info? {
        input.position(0)
        val moov = childBox(start = 0L, end = input.size(), type = "moov") ?: return null
        val durationSeconds = childBox(moov, "mvhd")?.let(::readMovieDurationSeconds)
        val udta = childBox(moov, "udta")
        val metadata = readMetadata(
            udta?.let { childBox(it, "meta") } ?: childBox(moov, "meta"),
        )
        val neroChapters = udta?.let { childBox(it, "chpl") }?.let(::readChpl).orEmpty()
        val trackChapters = readQuickTimeChapterTracks(moov)
        val rawChapters = mergeRawChapters(neroChapters, trackChapters)
        val chapters = rawChapters.mapIndexed { index, chapter ->
            SasayakiAudiobookChapter(
                index = index,
                title = chapter.title,
                startSeconds = chapter.startSeconds,
                endSeconds = rawChapters.getOrNull(index + 1)?.startSeconds
                    ?: durationSeconds?.takeIf { it >= chapter.startSeconds },
            )
        }
        return SasayakiAudiobookMp4Info(
            metadata = metadata,
            chapters = chapters,
            durationSeconds = durationSeconds,
        )
    }

    private fun readMetadata(meta: Mp4Box?): SasayakiAudiobookMetadata {
        meta ?: return SasayakiAudiobookMetadata.Empty
        if (meta.contentStart + FullBoxHeaderSize > meta.end) return SasayakiAudiobookMetadata.Empty
        val ilst = childBox(
            start = meta.contentStart + FullBoxHeaderSize,
            end = meta.end,
            type = "ilst",
        ) ?: return SasayakiAudiobookMetadata.Empty

        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var artworkData: ByteArray? = null
        childBoxes(start = ilst.contentStart, end = ilst.end).forEach { item ->
            val data = readMetadataData(item) ?: return@forEach
            when (item.type) {
                Mp4TitleItem -> title = title ?: data.utf8Text()
                Mp4ArtistItem -> artist = artist ?: data.utf8Text()
                Mp4AlbumArtistItem -> albumArtist = albumArtist ?: data.utf8Text()
                Mp4CoverItem -> artworkData = artworkData ?: data.bytes.takeIf { it.isNotEmpty() }
            }
        }
        return SasayakiAudiobookMetadata(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            artworkData = artworkData,
        )
    }

    private fun readMetadataData(item: Mp4Box): Mp4MetadataData? {
        val data = childBox(item, "data") ?: return null
        if (data.contentStart + MetadataDataHeaderSize > data.end) return null
        input.position(data.contentStart + MetadataDataHeaderSize)
        val byteCount = data.end - input.position()
        if (byteCount <= 0L || byteCount > MaxMetadataDataBytes) return null
        return Mp4MetadataData(ByteArray(byteCount.toInt()).also { input.readFully(it) })
    }

    private fun readMovieDurationSeconds(box: Mp4Box): Double? {
        input.position(box.contentStart)
        val version = input.readUnsignedByte()
        input.skip(FullBoxFlagsSize)
        return when (version) {
            0 -> {
                input.skip(IntSize + IntSize)
                val timescale = input.readUInt32()
                val duration = input.readUInt32()
                durationSeconds(duration = duration, timescale = timescale)
            }
            1 -> {
                input.skip(LongSize + LongSize)
                val timescale = input.readUInt32()
                val duration = input.readUInt64()
                durationSeconds(duration = duration, timescale = timescale)
            }
            else -> null
        }
    }

    private fun durationSeconds(duration: Long, timescale: Long): Double? =
        timescale.takeIf { it > 0L }
            ?.let { duration.toDouble() / it.toDouble() }
            ?.takeIf { it.isFinite() && it > 0.0 }

    private fun readChpl(box: Mp4Box): List<RawChapter> {
        if (box.contentStart + ChplHeaderSize > box.end) return emptyList()
        input.position(box.contentStart + FullBoxHeaderSize + IntSize)
        val count = input.readUnsignedByte()
        val chapters = mutableListOf<RawChapter>()
        repeat(count) {
            if (input.position() + LongSize + ByteSize > box.end) return@repeat
            val startSeconds = input.readUInt64().toDouble() / ChplTimeUnitsPerSecond
            val titleLength = input.readUnsignedByte()
            if (input.position() + titleLength > box.end) return@repeat
            val title = ByteArray(titleLength).also { input.readFully(it) }
                .toString(Charsets.UTF_8)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return@repeat
            chapters += RawChapter(startSeconds = startSeconds, title = title)
        }
        return chapters.sortedBy { it.startSeconds }
    }

    private fun mergeRawChapters(
        neroChapters: List<RawChapter>,
        trackChapters: List<RawChapter>,
    ): List<RawChapter> {
        if (neroChapters.isEmpty()) return trackChapters
        if (trackChapters.isEmpty()) return neroChapters
        // Nero first so dual-encoded files keep Nero titles on near-duplicate starts.
        val sorted = (neroChapters + trackChapters).sortedBy { it.startSeconds }
        val merged = mutableListOf<RawChapter>()
        for (chapter in sorted) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                kotlin.math.abs(chapter.startSeconds - previous.startSeconds) <
                    DuplicateChapterStartToleranceSeconds &&
                chapter.title == previous.title
            ) {
                continue
            }
            merged += chapter
        }
        return merged
    }

    private fun readQuickTimeChapterTracks(moov: Mp4Box): List<RawChapter> {
        try {
            val traks = childBoxes(start = moov.contentStart, end = moov.end)
                .filter { it.type == "trak" }
            if (traks.isEmpty() || traks.size > MaxChapterTracks) return emptyList()
            val trackInfos = traks.mapNotNull(::readTrackInfo)
            if (trackInfos.isEmpty()) return emptyList()
            val referencedChapterIds = trackInfos
                .flatMap { it.chapterRefIds }
                .toSet()
            val candidates = trackInfos.filter { info ->
                if (info.stbl == null || info.timescale <= 0L) return@filter false
                if (referencedChapterIds.isNotEmpty()) return@filter info.id in referencedChapterIds
                info.handler in ChapterTextHandlers
            }
            if (candidates.isEmpty()) return emptyList()
            return candidates
                .flatMap { readChapterTrackSamples(it.stbl!!, it.timescale) }
                .sortedBy { it.startSeconds }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun readTrackInfo(trak: Mp4Box): TrackInfo? {
        val tkhd = childBox(trak, "tkhd") ?: return null
        val trackId = readTrackId(tkhd) ?: return null
        val mdia = childBox(trak, "mdia") ?: return null
        val mdhd = childBox(mdia, "mdhd") ?: return null
        val timescale = readMediaTimescale(mdhd) ?: return null
        val handler = childBox(mdia, "hdlr")?.let(::readHandlerType).orEmpty()
        val stbl = childBox(mdia, "minf")?.let { minf -> childBox(minf, "stbl") }
        val chapterRefIds = childBox(trak, "tref")
            ?.let { tref -> childBox(tref, "chap") }
            ?.let(::readChapterRefIds)
            .orEmpty()
        return TrackInfo(
            id = trackId,
            timescale = timescale,
            handler = handler,
            stbl = stbl,
            chapterRefIds = chapterRefIds,
        )
    }

    private fun readTrackId(tkhd: Mp4Box): Long? {
        if (tkhd.contentStart + FullBoxHeaderSize + IntSize * 3 > tkhd.end) return null
        input.position(tkhd.contentStart)
        return when (input.readUnsignedByte()) {
            0 -> {
                input.skip(FullBoxFlagsSize + IntSize + IntSize)
                input.readUInt32().takeIf { it > 0L }
            }
            1 -> {
                if (tkhd.contentStart + FullBoxHeaderSize + LongSize * 2 + IntSize > tkhd.end) return null
                input.skip(FullBoxFlagsSize + LongSize + LongSize)
                input.readUInt32().takeIf { it > 0L }
            }
            else -> null
        }
    }

    private fun readMediaTimescale(mdhd: Mp4Box): Long? {
        if (mdhd.contentStart + FullBoxHeaderSize + IntSize * 3 > mdhd.end) return null
        input.position(mdhd.contentStart)
        return when (input.readUnsignedByte()) {
            0 -> {
                input.skip(FullBoxFlagsSize + IntSize + IntSize)
                input.readUInt32().takeIf { it > 0L }
            }
            1 -> {
                if (mdhd.contentStart + FullBoxHeaderSize + LongSize * 2 + IntSize > mdhd.end) return null
                input.skip(FullBoxFlagsSize + LongSize + LongSize)
                input.readUInt32().takeIf { it > 0L }
            }
            else -> null
        }
    }

    private fun readHandlerType(hdlr: Mp4Box): String {
        if (hdlr.contentStart + FullBoxHeaderSize + IntSize * 2 + IntSize > hdlr.end) return ""
        input.position(hdlr.contentStart + FullBoxHeaderSize + IntSize)
        return input.readAscii(IntSize)
    }

    private fun readChapterRefIds(chap: Mp4Box): List<Long> {
        val byteCount = chap.end - chap.contentStart
        if (byteCount <= 0L || byteCount > MaxChapterRefBytes) return emptyList()
        if (byteCount % IntSize != 0L) return emptyList()
        input.position(chap.contentStart)
        val words = List((byteCount / IntSize).toInt()) { input.readUInt32() }
        // The chap box is not a FullBox, but tolerate writers that prepend version/flags (0).
        val ids = if (words.size >= 2 && words[0] == 0L) words.drop(1) else words
        if (ids.isEmpty() || ids.size > MaxChapterRefs) return emptyList()
        if (ids.any { it < 1L || it > MaxTrackId }) return emptyList()
        return ids
    }

    private fun readChapterTrackSamples(stbl: Mp4Box, timescale: Long): List<RawChapter> {
        val sampleSizes = readSampleSizes(childBox(stbl, "stsz")) ?: return emptyList()
        if (sampleSizes.isEmpty() || sampleSizes.size > MaxChapterSamples) return emptyList()
        val sampleStarts = readSampleStartTimes(childBox(stbl, "stts"), sampleSizes.size, timescale)
            ?: return emptyList()
        val chunkOffsets = readChunkOffsets(childBox(stbl, "stco"))
            ?: readChunkOffsets64(childBox(stbl, "co64"))
            ?: return emptyList()
        if (chunkOffsets.isEmpty()) return emptyList()
        val samplesPerChunk = mapSamplesPerChunk(childBox(stbl, "stsc"), chunkOffsets.size, sampleSizes.size)
            ?: return emptyList()
        val fileSize = input.size()
        val chapters = mutableListOf<RawChapter>()
        var sampleIndex = 0
        var chunkIndex = 0
        while (sampleIndex < sampleSizes.size && chunkIndex < chunkOffsets.size) {
            var offsetInChunk = 0L
            repeat(samplesPerChunk[chunkIndex]) {
                if (sampleIndex >= sampleSizes.size) return@repeat
                val size = sampleSizes[sampleIndex]
                val startSeconds = sampleStarts[sampleIndex]
                if (size in 2..MaxChapterSampleBytes) {
                    val fileOffset = chunkOffsets[chunkIndex] + offsetInChunk
                    if (fileOffset >= 0L && fileOffset + size <= fileSize) {
                        decodeChapterTitle(fileOffset, size)?.let { title ->
                            chapters += RawChapter(startSeconds = startSeconds, title = title)
                        }
                    }
                }
                offsetInChunk += size
                sampleIndex += 1
            }
            chunkIndex += 1
        }
        return chapters.sortedBy { it.startSeconds }
    }

    private fun readSampleStartTimes(
        stts: Mp4Box?,
        sampleCount: Int,
        timescale: Long,
    ): List<Double>? {
        stts ?: return null
        if (stts.contentStart + FullBoxHeaderSize + IntSize > stts.end) return null
        input.position(stts.contentStart + FullBoxHeaderSize)
        val entryCount = input.readUInt32()
        if (entryCount <= 0L || entryCount > MaxSttsEntries) return null
        val starts = ArrayList<Double>(sampleCount)
        var decodeTime = 0L
        repeat(entryCount.toInt()) {
            if (input.position() + IntSize * 2 > stts.end) return null
            val count = input.readUInt32()
            val delta = input.readUInt32()
            if (count <= 0L || count > MaxChapterSamples) return null
            val remaining = sampleCount - starts.size
            if (remaining <= 0) return starts
            val take = count.coerceAtMost(remaining.toLong()).toInt()
            repeat(take) {
                if (decodeTime < 0L || decodeTime > MaxDecodeTimeUnits) return null
                val startSeconds = decodeTime.toDouble() / timescale.toDouble()
                if (!startSeconds.isFinite() || startSeconds > MaxChapterStartSeconds) return null
                starts += startSeconds
                decodeTime += delta
            }
            if (take < count) return starts
        }
        if (starts.size != sampleCount) return null
        return starts
    }

    private fun readSampleSizes(stsz: Mp4Box?): List<Long>? {
        stsz ?: return null
        if (stsz.contentStart + FullBoxHeaderSize + IntSize * 2 > stsz.end) return null
        input.position(stsz.contentStart + FullBoxHeaderSize)
        val uniformSize = input.readUInt32()
        val sampleCount = input.readUInt32()
        if (sampleCount <= 0L || sampleCount > MaxChapterSamples) return null
        if (uniformSize != 0L) {
            if (uniformSize > MaxChapterSampleBytes) return null
            return List(sampleCount.toInt()) { uniformSize }
        }
        if (input.position() + sampleCount * IntSize > stsz.end) return null
        return List(sampleCount.toInt()) { input.readUInt32() }
    }

    private fun readChunkOffsets(stco: Mp4Box?): List<Long>? {
        stco ?: return null
        if (stco.contentStart + FullBoxHeaderSize + IntSize > stco.end) return null
        input.position(stco.contentStart + FullBoxHeaderSize)
        val entryCount = input.readUInt32()
        if (entryCount <= 0L || entryCount > MaxChunkOffsets) return null
        if (input.position() + entryCount * IntSize > stco.end) return null
        return List(entryCount.toInt()) { input.readUInt32() }
    }

    private fun readChunkOffsets64(co64: Mp4Box?): List<Long>? {
        co64 ?: return null
        if (co64.contentStart + FullBoxHeaderSize + IntSize > co64.end) return null
        input.position(co64.contentStart + FullBoxHeaderSize)
        val entryCount = input.readUInt32()
        if (entryCount <= 0L || entryCount > MaxChunkOffsets) return null
        if (input.position() + entryCount * LongSize > co64.end) return null
        return List(entryCount.toInt()) { input.readUInt64() }
    }

    private fun mapSamplesPerChunk(
        stsc: Mp4Box?,
        chunkCount: Int,
        sampleCount: Int,
    ): List<Int>? {
        stsc ?: return null
        if (stsc.contentStart + FullBoxHeaderSize + IntSize > stsc.end) return null
        input.position(stsc.contentStart + FullBoxHeaderSize)
        val entryCount = input.readUInt32()
        if (entryCount <= 0L || entryCount > MaxStscEntries) return null
        if (input.position() + entryCount * IntSize * 3 > stsc.end) return null
        val entries = List(entryCount.toInt()) {
            val firstChunk = input.readUInt32()
            val samplesPerChunk = input.readUInt32()
            input.skip(IntSize)
            firstChunk to samplesPerChunk
        }
        if (entries.any { it.first < 1L || it.second < 1L || it.second > MaxChapterSamples }) return null
        if (entries.zipWithNext().any { (prev, next) -> next.first <= prev.first }) return null
        val perChunk = IntArray(chunkCount)
        var assigned = 0
        for (chunk in 1..chunkCount) {
            val entry = entries.last { it.first <= chunk.toLong() }
            if (assigned + entry.second > sampleCount) return null
            perChunk[chunk - 1] = entry.second.toInt()
            assigned += entry.second.toInt()
        }
        if (assigned != sampleCount) return null
        return perChunk.toList()
    }

    private fun decodeChapterTitle(fileOffset: Long, size: Long): String? {
        val bytes = ByteArray(size.toInt())
        input.position(fileOffset)
        input.readFully(bytes)
        // QuickTime/3GPP timed text: u16BE length-prefixed UTF-8, optional style boxes after text.
        val prefixLength = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        if (prefixLength in 1..bytes.size - 2) {
            decodeTitleText(bytes.copyOfRange(2, 2 + prefixLength))?.let { return it }
        }
        // Fallback: raw UTF-8 sample (some encoders omit the length prefix).
        if (prefixLength > bytes.size - 2) {
            decodeTitleText(bytes)?.let { return it }
        }
        return decodeTitleText(bytes.copyOfRange(2, bytes.size))
    }

    private fun decodeTitleText(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MaxChapterSampleBytes) return null
        // The u16 length prefix already isolates text from trailing style boxes; only trim here.
        val utf8 = bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        if (utf8 != null && utf8.any { it.isLetterOrDigit() }) return utf8
        // Fallback for legacy UTF-16 encoded chapter titles.
        val withoutNulls = bytes.filter { it != 0.toByte() }.toByteArray()
        if (withoutNulls.size in 1..MaxChapterSampleBytes) {
            val utf16 = withoutNulls.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            if (utf16 != null && utf16.any { it.isLetterOrDigit() }) return utf16
        }
        return null
    }

    private fun childBox(parent: Mp4Box, type: String): Mp4Box? =
        childBox(start = parent.contentStart, end = parent.end, type = type)

    private fun childBox(start: Long, end: Long, type: String): Mp4Box? =
        childBoxes(start = start, end = end).firstOrNull { it.type == type }

    private fun childBoxes(start: Long, end: Long): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var position = start
        while (position + BoxHeaderSize <= end) {
            val box = readBox(position, end) ?: break
            boxes += box
            position = box.end
        }
        return boxes
    }

    private fun readBox(position: Long, parentEnd: Long): Mp4Box? {
        input.position(position)
        val shortSize = input.readUInt32()
        val type = input.readAscii(IntSize)
        val headerSize: Long
        val size: Long
        if (shortSize == 1L) {
            headerSize = ExtendedBoxHeaderSize
            size = input.readUInt64()
        } else {
            headerSize = BoxHeaderSize
            size = if (shortSize == 0L) parentEnd - position else shortSize
        }
        if (size < headerSize || position + size > parentEnd) return null
        return Mp4Box(type = type, start = position, headerSize = headerSize, size = size)
    }

    private fun SeekableByteChannel.skip(bytes: Int) {
        position(position() + bytes)
    }

    private fun SeekableByteChannel.readAscii(length: Int): String =
        ByteArray(length).also { readFully(it) }.toString(Charsets.ISO_8859_1)

    private fun SeekableByteChannel.readUInt32(): Long {
        var value = 0L
        repeat(IntSize) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    private fun SeekableByteChannel.readUInt64(): Long {
        var value = 0L
        repeat(LongSize) { value = (value shl 8) or readUnsignedByte().toLong() }
        return value
    }

    private fun SeekableByteChannel.readUnsignedByte(): Int {
        val buffer = ByteBuffer.allocate(ByteSize)
        if (read(buffer) != ByteSize) throw IllegalArgumentException("Unexpected end of MP4 box")
        buffer.flip()
        return buffer.get().toInt() and 0xff
    }

    private fun SeekableByteChannel.readFully(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) throw IllegalArgumentException("Unexpected end of MP4 box")
        }
    }
}

private data class Mp4MetadataData(val bytes: ByteArray) {
    fun utf8Text(): String? = bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
}

private data class Mp4Box(
    val type: String,
    val start: Long,
    val headerSize: Long,
    val size: Long,
) {
    val contentStart: Long = start + headerSize
    val end: Long = start + size
}

private data class RawChapter(
    val startSeconds: Double,
    val title: String,
)

private data class TrackInfo(
    val id: Long,
    val timescale: Long,
    val handler: String,
    val stbl: Mp4Box?,
    val chapterRefIds: List<Long>,
)

private const val Mp4TitleItem = "\u00a9nam"
private const val Mp4ArtistItem = "\u00a9ART"
private const val Mp4AlbumArtistItem = "aART"
private const val Mp4CoverItem = "covr"
private const val BoxHeaderSize = 8L
private const val ExtendedBoxHeaderSize = 16L
private const val FullBoxHeaderSize = 4L
private const val FullBoxFlagsSize = 3
private const val MetadataDataHeaderSize = 8L
private const val ChplHeaderSize = 9L
private const val IntSize = 4
private const val LongSize = 8
private const val ByteSize = 1
private const val ChplTimeUnitsPerSecond = 10_000_000.0
private const val MaxMetadataDataBytes = 20L * 1024L * 1024L
private val ChapterTextHandlers = setOf("text", "tx3g", "sbtl", "subt")
private const val DuplicateChapterStartToleranceSeconds = 0.5
private const val MaxChapterTracks = 32
private const val MaxChapterSamples = 10_000
private const val MaxChapterSampleBytes = 8L * 1024L
private const val MaxChapterRefBytes = 4L * 1024L
private const val MaxChapterRefs = 512
private const val MaxTrackId = 16_777_215L
private const val MaxSttsEntries = 10_000L
private const val MaxStscEntries = 10_000L
private const val MaxChunkOffsets = 10_000L
private const val MaxDecodeTimeUnits = Long.MAX_VALUE / 2
private const val MaxChapterStartSeconds = 3_600_000.0
