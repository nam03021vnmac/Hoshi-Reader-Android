package moe.antimony.hoshi.features.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.HighlightColor

internal sealed interface ReaderSelectionCommand {
    val source: String

    data class SelectText(
        val x: Float,
        val y: Float,
        val maxLength: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.selectText($x, $y, $maxLength)"
    }

    data class HighlightSelection(
        val count: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.highlightSelection($count)"
    }

    data class HitTest(
        val x: Float,
        val y: Float,
    ) : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.hitTest($x, $y)"
    }

    data class SelectionRects(
        val count: Int,
    ) : ReaderSelectionCommand {
        override val source: String =
            "JSON.stringify(window.hoshiSelection.selectionRects($count))"
    }

    data object ClearSelection : ReaderSelectionCommand {
        override val source: String =
            "window.hoshiSelection.clearSelection()"
    }
}

internal sealed interface ReaderHighlightCommand {
    val source: String

    data object PrepareSelection : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.prepareHighlightSelection()"
    }

    data class Create(
        val color: HighlightColor,
        val id: String,
    ) : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.createHighlight('${color.rawValue}', '${id.javaScriptSingleQuotedString()}')"
    }

    data class Remove(
        val id: String,
    ) : ReaderHighlightCommand {
        override val source: String =
            "window.hoshiHighlights.removeHighlight(${id.javaScriptDoubleQuotedString()})"
    }
}

internal data class ReaderHitTestResult(
    val normalizedOffset: Int,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromWebViewResult(result: String?): Int? {
            val value = result?.trim() ?: return null
            if (value == "null" || value == "undefined") return null
            val unwrapped = runCatching { json.decodeFromString<String>(value) }.getOrNull()
                ?: return null
            val hit = runCatching { json.decodeFromString<HitTestPayload>(unwrapped) }.getOrNull()
                ?: return null
            return hit.normalizedOffset
        }
    }

    @Serializable
    private data class HitTestPayload(
        val normalizedOffset: Int,
    )
}

internal data class ReaderSelectionResult(
    val selectedNothing: Boolean,
    val isImageTap: Boolean = false,
    val isLinkTap: Boolean = false,
) {
    companion object {
        fun fromWebViewResult(result: String?): ReaderSelectionResult {
            val value = result?.trim()
            val token = value?.removeSurrounding("\"")
            return ReaderSelectionResult(
                selectedNothing = value == null || value == "null" || value == "undefined",
                isImageTap = token == "image",
                isLinkTap = token == "link",
            )
        }
    }
}

private fun String.javaScriptSingleQuotedString(): String =
    replace("\\", "\\\\").replace("'", "\\'")

private fun String.javaScriptDoubleQuotedString(): String =
    buildString(length + 2) {
        append('"')
        this@javaScriptDoubleQuotedString.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
