package moe.antimony.hoshi.features.sasayaki

import java.io.ByteArrayOutputStream

internal data class SasayakiChapterFixture(
    val startSeconds: Double,
    val title: String,
)

internal fun minimalMp4WithChpl(
    durationSeconds: Double,
    chapters: List<SasayakiChapterFixture>,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("udta") {
                writeChpl(chapters)
            }
        }
    }

internal fun minimalMp4WithMetadata(
    title: String? = null,
    artist: String? = null,
    albumArtist: String? = null,
    artworkData: ByteArray? = null,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeBox("udta") {
                writeMeta {
                    writeBox("ilst") {
                        title?.let { writeTextMetadataItem("\u00a9nam", it) }
                        artist?.let { writeTextMetadataItem("\u00a9ART", it) }
                        albumArtist?.let { writeTextMetadataItem("aART", it) }
                        artworkData?.let { writeBinaryMetadataItem("covr", dataType = 13, it) }
                    }
                }
            }
        }
    }

internal fun minimalMp4WithInfo(
    durationSeconds: Double,
    chapters: List<SasayakiChapterFixture>,
    title: String? = null,
    artist: String? = null,
    albumArtist: String? = null,
    artworkData: ByteArray? = null,
): ByteArray =
    buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("udta") {
                writeChpl(chapters)
                writeMeta {
                    writeBox("ilst") {
                        title?.let { writeTextMetadataItem("\u00a9nam", it) }
                        artist?.let { writeTextMetadataItem("\u00a9ART", it) }
                        albumArtist?.let { writeTextMetadataItem("aART", it) }
                        artworkData?.let { writeBinaryMetadataItem("covr", dataType = 13, it) }
                    }
                }
            }
        }
    }

private fun ByteArrayOutputStream.writeMvhd(durationSeconds: Double) {
    writeBox("mvhd") {
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(1000)
        writeUInt32((durationSeconds * 1000.0).toLong())
        write(ByteArray(80))
    }
}

private fun ByteArrayOutputStream.writeChpl(chapters: List<SasayakiChapterFixture>) {
    writeBox("chpl") {
        writeUInt32(0x01000000)
        writeUInt32(0)
        write(chapters.size)
        chapters.forEach { chapter ->
            writeUInt64((chapter.startSeconds * 10_000_000.0).toLong())
            val titleBytes = chapter.title.toByteArray(Charsets.UTF_8)
            write(titleBytes.size)
            write(titleBytes)
        }
    }
}

internal fun minimalMp4WithChapterTrack(
    durationSeconds: Double,
    chapters: List<SasayakiChapterFixture>,
    timescale: Long = 1_000L,
    includeTref: Boolean = true,
): ByteArray {
    val sampleBlobs = chapters.map { chapter ->
        val text = chapter.title.toByteArray(Charsets.UTF_8)
        buildBytes {
            writeUInt16(text.size)
            write(text)
        }
    }
    val ftyp = buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
    }
    val mdatPayload = buildBytes { sampleBlobs.forEach { write(it) } }
    val mdatDataStart = (ftyp.size + 8).toLong()
    val chunkOffsets = mutableListOf<Long>()
    // Single chunk holding all samples; exercises intra-chunk offset accumulation.
    if (sampleBlobs.isNotEmpty()) chunkOffsets += mdatDataStart
    val timescaleUnits = chapters.map { (it.startSeconds * timescale).toLong() }
    val durationUnits = (durationSeconds * timescale).toLong()
    val deltas = timescaleUnits.indices.map { index ->
        val next = timescaleUnits.getOrNull(index + 1) ?: durationUnits
        (next - timescaleUnits[index]).coerceAtLeast(1L)
    }
    return buildBytes {
        write(ftyp)
        writeBox("mdat") { write(mdatPayload) }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("trak") {
                writeTkhd(trackId = 1)
                if (includeTref) {
                    writeBox("tref") {
                        writeBox("chap") { writeUInt32(2) }
                    }
                }
                writeBox("mdia") {
                    writeMdhd(timescale)
                    writeHdlr("soun")
                }
            }
            writeBox("trak") {
                writeTkhd(trackId = 2)
                writeBox("mdia") {
                    writeMdhd(timescale)
                    writeHdlr("text")
                    writeBox("minf") {
                        writeBox("stbl") {
                            writeBox("stsd") {
                                writeUInt32(0)
                                writeUInt32(0)
                            }
                            writeBox("stts") {
                                writeUInt32(0)
                                writeUInt32(chapters.size.toLong())
                                timescaleUnits.indices.forEach { index ->
                                    writeUInt32(1)
                                    writeUInt32(deltas[index])
                                }
                            }
                            writeBox("stsc") {
                                writeUInt32(0)
                                writeUInt32(1)
                                writeUInt32(1)
                                writeUInt32(chapters.size.toLong())
                                writeUInt32(1)
                            }
                            writeBox("stsz") {
                                writeUInt32(0)
                                writeUInt32(0)
                                writeUInt32(chapters.size.toLong())
                                sampleBlobs.forEach { writeUInt32(it.size.toLong()) }
                            }
                            writeBox("stco") {
                                writeUInt32(0)
                                writeUInt32(chunkOffsets.size.toLong())
                                chunkOffsets.forEach { writeUInt32(it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun minimalMp4WithChplAndChapterTrack(
    durationSeconds: Double,
    chplChapters: List<SasayakiChapterFixture>,
    trackChapters: List<SasayakiChapterFixture>,
): ByteArray {
    // Construct fresh with both udta/chpl and the chapter text track.
    val sampleBlobs = trackChapters.map { chapter ->
        val text = chapter.title.toByteArray(Charsets.UTF_8)
        buildBytes {
            writeUInt16(text.size)
            write(text)
        }
    }
    val ftyp = buildBytes {
        writeBox("ftyp") {
            writeAscii("M4A ")
            writeUInt32(0)
            writeAscii("M4A ")
        }
    }
    val mdatPayload = buildBytes { sampleBlobs.forEach { write(it) } }
    val mdatDataStart = (ftyp.size + 8).toLong()
    val timescale = 1_000L
    val timescaleUnits = trackChapters.map { (it.startSeconds * timescale).toLong() }
    val durationUnits = (durationSeconds * timescale).toLong()
    val deltas = timescaleUnits.indices.map { index ->
        val next = timescaleUnits.getOrNull(index + 1) ?: durationUnits
        (next - timescaleUnits[index]).coerceAtLeast(1L)
    }
    return buildBytes {
        write(ftyp)
        writeBox("mdat") { write(mdatPayload) }
        writeBox("moov") {
            writeMvhd(durationSeconds)
            writeBox("udta") { writeChpl(chplChapters) }
            writeBox("trak") {
                writeTkhd(trackId = 1)
                writeBox("tref") {
                    writeBox("chap") { writeUInt32(2) }
                }
                writeBox("mdia") {
                    writeMdhd(timescale)
                    writeHdlr("soun")
                }
            }
            writeBox("trak") {
                writeTkhd(trackId = 2)
                writeBox("mdia") {
                    writeMdhd(timescale)
                    writeHdlr("text")
                    writeBox("minf") {
                        writeBox("stbl") {
                            writeBox("stsd") {
                                writeUInt32(0)
                                writeUInt32(0)
                            }
                            writeBox("stts") {
                                writeUInt32(0)
                                writeUInt32(trackChapters.size.toLong())
                                timescaleUnits.indices.forEach { index ->
                                    writeUInt32(1)
                                    writeUInt32(deltas[index])
                                }
                            }
                            writeBox("stsc") {
                                writeUInt32(0)
                                writeUInt32(1)
                                writeUInt32(1)
                                writeUInt32(trackChapters.size.toLong())
                                writeUInt32(1)
                            }
                            writeBox("stsz") {
                                writeUInt32(0)
                                writeUInt32(0)
                                writeUInt32(trackChapters.size.toLong())
                                sampleBlobs.forEach { writeUInt32(it.size.toLong()) }
                            }
                            writeBox("stco") {
                                writeUInt32(0)
                                writeUInt32(if (trackChapters.isEmpty()) 0 else 1)
                                if (trackChapters.isNotEmpty()) writeUInt32(mdatDataStart)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ByteArrayOutputStream.writeTkhd(trackId: Long) {
    writeBox("tkhd") {
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(trackId)
        writeUInt32(0)
        writeUInt32(0)
        write(ByteArray(16))
    }
}

private fun ByteArrayOutputStream.writeMdhd(timescale: Long) {
    writeBox("mdhd") {
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(0)
        writeUInt32(timescale)
        writeUInt32(timescale * 100)
        write(ByteArray(4))
    }
}

private fun ByteArrayOutputStream.writeHdlr(handler: String) {
    writeBox("hdlr") {
        writeUInt32(0)
        writeUInt32(0)
        writeAscii(handler)
        write(ByteArray(12))
        write(0)
    }
}

private fun ByteArrayOutputStream.writeMeta(writeContent: ByteArrayOutputStream.() -> Unit) {
    writeBox("meta") {
        writeUInt32(0)
        writeContent()
    }
}

private fun ByteArrayOutputStream.writeTextMetadataItem(type: String, value: String) {
    writeBinaryMetadataItem(type, dataType = 1, value.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeBinaryMetadataItem(
    type: String,
    dataType: Int,
    value: ByteArray,
) {
    writeBox(type) {
        writeBox("data") {
            writeUInt32(dataType.toLong())
            writeUInt32(0)
            write(value)
        }
    }
}

private fun buildBytes(writeContent: ByteArrayOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply(writeContent).toByteArray()

private fun ByteArrayOutputStream.writeBox(type: String, writeContent: ByteArrayOutputStream.() -> Unit) {
    val content = buildBytes(writeContent)
    writeUInt32(content.size + 8L)
    writeAscii(type)
    write(content)
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.ISO_8859_1))
}

private fun ByteArrayOutputStream.writeUInt32(value: Long) {
    write(byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    ))
}

private fun ByteArrayOutputStream.writeUInt16(value: Int) {
    write(byteArrayOf(
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    ))
}

private fun ByteArrayOutputStream.writeUInt64(value: Long) {
    write(byteArrayOf(
        ((value ushr 56) and 0xff).toByte(),
        ((value ushr 48) and 0xff).toByte(),
        ((value ushr 40) and 0xff).toByte(),
        ((value ushr 32) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    ))
}
