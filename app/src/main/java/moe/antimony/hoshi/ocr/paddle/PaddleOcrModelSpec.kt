package moe.antimony.hoshi.ocr.paddle

/** Detector input sizing before the multiple-of-32 snap. */
sealed interface PaddleDetResize {
    /** Official SDK sizing: upscale tiny pages, cap the long side and total pixels. */
    data object Limited : PaddleDetResize

    /** Fixed long side; strips longer than 2:1 get the pixel budget of a 3:4 page instead. */
    data class LongSide(val side: Int) : PaddleDetResize
}

/** Recognition character list source bundled next to the recognition model. */
enum class PaddleDictFormat {
    /** Official `inference.yml` with `PostProcess.character_dict`. */
    InferenceYaml,

    /** One symbol per line; a trailing space class follows the listed symbols. */
    PlainText,
}

/**
 * Bundled PP-OCRv6 det/rec pair plus the pre/post-processing contract it was
 * trained and evaluated with. Engine id/version key the OCR cache, so a spec
 * change never reuses another model's unpinned results.
 */
class PaddleOcrModelSpec(
    val engineId: String,
    val engineVersion: String,
    val detModelAsset: String,
    val recModelAsset: String,
    val dictAsset: String,
    val dictFormat: PaddleDictFormat,
    val detResize: PaddleDetResize,
    val dbThresh: Float,
    val dbBoxThresh: Float,
    val dbUnclipRatio: Float,
    /** Feed recognition crops in decode (BGR) order instead of RGB. */
    val recInputBgr: Boolean,
) {
    companion object {
        /** Official PP-OCRv6_small for general Japanese scans. */
        val Standard = PaddleOcrModelSpec(
            engineId = "paddle-ppocrv6-small",
            engineVersion = "20260618",
            detModelAsset = "ocr/paddle/det/inference.onnx",
            recModelAsset = "ocr/paddle/rec/inference.onnx",
            dictAsset = "ocr/paddle/rec/inference.yml",
            dictFormat = PaddleDictFormat.InferenceYaml,
            detResize = PaddleDetResize.Limited,
            dbThresh = DbPostProcessor.THRESH,
            dbBoxThresh = DbPostProcessor.BOX_THRESH,
            dbUnclipRatio = DbPostProcessor.UNCLIP_RATIO,
            recInputBgr = false,
        )

        /** Kellenok/PP-OCRv6_manga v0.2 with its recommended detector size and DB parameters. */
        val Manga = PaddleOcrModelSpec(
            engineId = "paddle-ppocrv6-manga",
            engineVersion = "v0.2",
            detModelAsset = "ocr/paddle-manga/det/inference.onnx",
            recModelAsset = "ocr/paddle-manga/rec/inference.onnx",
            dictAsset = "ocr/paddle-manga/rec/dict.txt",
            dictFormat = PaddleDictFormat.PlainText,
            detResize = PaddleDetResize.LongSide(960),
            dbThresh = 0.15f,
            dbBoxThresh = 0.25f,
            dbUnclipRatio = 1.4f,
            recInputBgr = true,
        )
    }
}
