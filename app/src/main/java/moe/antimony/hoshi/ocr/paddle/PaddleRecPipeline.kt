package moe.antimony.hoshi.ocr.paddle

import kotlin.math.ceil

/** BGR text-line crop in source-image pixels. */
data class RecCrop(
    val bgr: ByteArray,
    val width: Int,
    val height: Int,
)

/** Recognition batch: CHW float tensor with fixed height 48. */
data class RecNetworkInput(
    val tensor: FloatArray,
    val batch: Int,
    val width: Int,
)

/**
 * Recognition preprocessing from the official PP-OCRv6 Android SDK without
 * OpenCV: BGR to RGB, aspect-preserving resize to height 48,
 * `(x / 127.5) - 1` normalization, zero padding to the batch max width,
 * and CHW batching.
 */
object PaddleRecPreprocessor {
    const val FIXED_HEIGHT = 48
    const val MAX_IMG_WIDTH = 3200

    fun preprocessBatch(crops: List<RecCrop>): RecNetworkInput {
        require(crops.isNotEmpty())
        val resized = crops.map { crop ->
            val rgb = ByteArray(crop.bgr.size)
            for (i in 0 until crop.width * crop.height) {
                rgb[i * 3] = crop.bgr[i * 3 + 2]
                rgb[i * 3 + 1] = crop.bgr[i * 3 + 1]
                rgb[i * 3 + 2] = crop.bgr[i * 3]
            }
            val aspect = if (crop.height > 0) crop.width.toDouble() / crop.height else 1.0
            val newW = ceil(FIXED_HEIGHT * aspect).toInt().coerceIn(1, MAX_IMG_WIDTH)
            val scaled = if (newW == crop.width && FIXED_HEIGHT == crop.height) {
                rgb
            } else {
                resizeBgrBilinear(rgb, crop.width, crop.height, newW, FIXED_HEIGHT)
            }
            Pair(scaled, newW)
        }
        val maxW = resized.maxOf { it.second }
        val batch = resized.size
        val channelSize = FIXED_HEIGHT * maxW
        val tensor = FloatArray(batch * 3 * channelSize)
        for ((b, entry) in resized.withIndex()) {
            val (bytes, w) = entry
            for (c in 0..2) {
                val offset = (b * 3 + c) * channelSize
                for (y in 0 until FIXED_HEIGHT) {
                    for (x in 0 until maxW) {
                        val value = if (x < w) {
                            (bytes[(y * w + x) * 3 + c].toInt() and 0xFF) / 127.5 - 1.0
                        } else {
                            0.0
                        }
                        tensor[offset + y * maxW + x] = value.toFloat()
                    }
                }
            }
        }
        return RecNetworkInput(tensor = tensor, batch = batch, width = maxW)
    }
}

/**
 * Greedy CTC decoding with blank index 0 and mean kept-probability
 * confidence. Port of the official SDK `CTCDecoder`.
 */
object CtcDecoder {
    private const val BLANK_INDEX = 0

    fun decode(
        output: FloatArray,
        batch: Int,
        timeSteps: Int,
        numClasses: Int,
        characters: List<String>,
    ): List<Pair<String, Float>> {
        require(output.size == batch * timeSteps * numClasses)
        return (0 until batch).map { b ->
            val base = b * timeSteps * numClasses
            val indices = IntArray(timeSteps)
            val probs = FloatArray(timeSteps)
            for (t in 0 until timeSteps) {
                val offset = base + t * numClasses
                var bestIndex = 0
                var bestValue = output[offset]
                for (c in 1 until numClasses) {
                    val value = output[offset + c]
                    if (value > bestValue) {
                        bestValue = value
                        bestIndex = c
                    }
                }
                indices[t] = bestIndex
                probs[t] = bestValue
            }
            val kept = mutableListOf<Float>()
            val text = StringBuilder()
            var previous = -1
            for (t in 0 until timeSteps) {
                val index = indices[t]
                if (index != BLANK_INDEX && index != previous) {
                    val charIndex = index - 1
                    if (charIndex in characters.indices) {
                        text.append(characters[charIndex])
                        kept += probs[t]
                    }
                }
                previous = index
            }
            val confidence = if (kept.isNotEmpty()) kept.average().toFloat() else 0f
            Pair(text.toString(), confidence)
        }
    }
}

/**
 * Minimal `character_dict` reader for the recognition `inference.yml`
 * (indentation-based, no YAML dependency). Port of the official SDK
 * `ModelConfig` parsing, including the appended trailing space entry.
 */
object PaddleDict {
    fun parseCharacterDict(yaml: String, assetPath: String): List<String> {
        val lines = yaml.replace("\r\n", "\n").replace('\r', '\n').lines()
        val postProcessLine = lines.indexOfFirst { it.trim() == "PostProcess:" }
        require(postProcessLine >= 0) { "Missing PostProcess in $assetPath" }
        val postProcessIndent = leadingSpaces(lines[postProcessLine])
        var dictLine = -1
        for (i in postProcessLine + 1 until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (leadingSpaces(lines[i]) <= postProcessIndent) break
            if (trimmed == "character_dict:") {
                dictLine = i
                break
            }
        }
        require(dictLine >= 0) { "Missing character_dict in $assetPath" }
        val dictIndent = leadingSpaces(lines[dictLine])
        val characters = mutableListOf<String>()
        var i = dictLine + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }
            val content = line.substring(leadingSpaces(line))
            if (!content.startsWith("-")) {
                if (leadingSpaces(line) <= dictIndent) break
                i++
                continue
            }
            characters += parseListScalar(content.substring(1))
            i++
        }
        require(characters.isNotEmpty()) { "Empty character_dict in $assetPath" }
        return characters.toMutableList().apply {
            if (lastOrNull() != " ") add(" ")
        }
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        while (count < line.length && line[count] == ' ') count++
        return count
    }

    private fun parseListScalar(raw: String): String {
        val value = raw.dropWhile { it == ' ' }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        return value
    }
}
