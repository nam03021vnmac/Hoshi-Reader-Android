package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import kotlin.math.roundToInt

internal data class PdfOutlineRow(
    val title: String,
    val pageIndex: Int,
    val depth: Int,
)

internal fun pdfOutlineRows(items: List<PdfOutlineItem>): List<PdfOutlineRow> {
    val rows = mutableListOf<PdfOutlineRow>()
    fun visit(entries: List<PdfOutlineItem>, depth: Int) {
        for (entry in entries) {
            if (entry.title.isNotBlank()) {
                rows.add(PdfOutlineRow(title = entry.title, pageIndex = entry.pageIndex, depth = depth))
                visit(entry.children, depth + 1)
            } else {
                visit(entry.children, depth)
            }
        }
    }
    visit(items, 0)
    return rows
}

@Composable
internal fun PdfContentsSheet(
    pageIndex: Int,
    pageCount: Int,
    outline: List<PdfOutlineItem>,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    onPageJump: (Int) -> Unit,
    onChapterJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetStyle = readerSheetStyle()
    val rows = remember(outline) { pdfOutlineRows(outline) }
    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (rows.isNotEmpty()) Modifier.weight(1f) else Modifier)
                .padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_go_to),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = progressDisplay.rangeText(
                    pdfDisplayPage(pageIndex, pageCount),
                    pageCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = pageIndex.toFloat().coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat()),
                onValueChange = { value -> onPageJump(value.roundToInt()) },
                valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (rows.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.reader_chapters),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    items(rows) { row ->
                        PdfOutlineListRow(
                            row = row,
                            pageCount = pageCount,
                            currentPageIndex = pageIndex,
                            onClick = if (row.pageIndex >= 0) {
                                { onChapterJump(row.pageIndex) }
                            } else {
                                null
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfOutlineListRow(
    row: PdfOutlineRow,
    pageCount: Int,
    currentPageIndex: Int,
    onClick: (() -> Unit)?,
) {
    val isCurrent = row.pageIndex == currentPageIndex && row.pageIndex >= 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = (4 + row.depth.coerceIn(0, 6) * 16).dp,
                top = 8.dp,
                end = 4.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.pageIndex >= 0) {
            Text(
                text = pdfDisplayPage(row.pageIndex, pageCount).toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun PdfAppearanceSheet(
    zoomFactor: Double,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitWidth: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetStyle = readerSheetStyle()
    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${(zoomFactor * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onZoomOut) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = stringResource(R.string.pdf_zoom_out),
                    )
                }
                IconButton(onClick = onFitWidth) {
                    Icon(
                        imageVector = Icons.Rounded.FitScreen,
                        contentDescription = stringResource(R.string.pdf_fit_width),
                    )
                }
                IconButton(onClick = onZoomIn) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.pdf_zoom_in),
                    )
                }
            }
        }
    }
}

internal fun pdfDisplayPage(pageIndex: Int, pageCount: Int): Int =
    (pageIndex + 1).coerceIn(1, pageCount.coerceAtLeast(1))
