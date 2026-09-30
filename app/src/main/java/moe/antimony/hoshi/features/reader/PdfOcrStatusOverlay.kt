package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.asString

/**
 * Small page-local OCR progress/status indication. The PDF page stays usable
 * throughout; user-visible wording lives in localized resources.
 */
@Composable
internal fun PdfOcrStatusOverlay(
    pageState: PdfOcrPageUiState?,
    onRunOcr: () -> Unit,
    onKeepText: () -> Unit,
    onRetryHighRes: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    highDetailModifier: Modifier = Modifier,
) {
    when (pageState?.status) {
        PdfOcrPageStatus.Queued,
        PdfOcrPageStatus.Rasterizing,
        PdfOcrPageStatus.Recognizing,
        PdfOcrPageStatus.Postprocessing,
        -> {
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.pdf_ocr_scanning),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        PdfOcrPageStatus.Suspect,
        PdfOcrPageStatus.ExtractionFailed,
        -> {
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onRunOcr) {
                        Text(stringResource(R.string.pdf_ocr_run))
                    }
                    TextButton(onClick = onKeepText) {
                        Text(stringResource(R.string.pdf_ocr_keep_text))
                    }
                }
            }
        }
        PdfOcrPageStatus.NoText -> {
            // Blank/illustrated pages settle without a permanent overlay or retry loop.
        }
        PdfOcrPageStatus.OcrReady -> {
            if (pageState.error != null) {
                Text(pageState.error.asString(), modifier = modifier, style = MaterialTheme.typography.labelLarge)
            } else if (!pageState.highResUsed && !pageState.revisionLocked &&
                pageState.record?.qualityFlags?.contains("low-confidence") == true) {
                TextButton(onClick = onRetryHighRes, modifier = highDetailModifier) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
                }
            }
        }
        PdfOcrPageStatus.Failed -> {
            // Failed/Unavailable must stay visible: without this the page goes
            // back to looking unscanned with no explanation and no retry.
            Surface(
                modifier = modifier,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pageState.error?.let { error ->
                        Text(
                            text = error.asString(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (pageState.retryable) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.pdf_ocr_try_again))
                        }
                        if (!pageState.highResUsed && !pageState.revisionLocked) {
                            TextButton(onClick = onRetryHighRes) {
                                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
        else -> Unit
    }
}
