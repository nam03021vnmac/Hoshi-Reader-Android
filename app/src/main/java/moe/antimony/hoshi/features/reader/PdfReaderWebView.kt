package moe.antimony.hoshi.features.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.navigation.ReaderOpenFailurePage
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.PdfBook
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.audio.withLocalizedSourceNames
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.closeChildPopupsAndClearSelection
import moe.antimony.hoshi.features.dictionary.closeChildPopupsForScrolledParent
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.dismissPopupAt
import moe.antimony.hoshi.features.dictionary.lookupOptions
import moe.antimony.hoshi.features.dictionary.openPopupExternalLink
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import kotlin.math.roundToInt

@Composable
internal fun PdfReaderWebView(
    bookId: String,
    book: PdfBook,
    pdfFile: File,
    bookEntry: BookEntry? = null,
    bookRoot: File? = null,
    bookCoverFile: File? = null,
    initialPageIndex: Int = 0,
    initialProgress: Double = 0.0,
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    onSaveBookmark: (pageIndex: Int, progress: Double, statistics: List<ReadingStatistics>?) -> Unit = { _, _, _ -> },
    onFlushAutoSyncExport: () -> Unit = {},
    onForegroundAutoSyncImport: () -> Unit = {},
    contentLanguageProfile: ContentLanguageProfile = ContentLanguageProfile.Default,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val appContainer = LocalHoshiUiDependencies.current
    val systemDarkTheme = isSystemInDarkTheme()
    val effectiveSettings = readerSettings
    val scope = rememberCoroutineScope()
    val bookRepository = appContainer.bookRepository
    val dictionaryRepository = appContainer.dictionaryRepository
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val statisticsDateProvider = appContainer.statisticsDateProvider
    val fontManager = appContainer.readerFontManager
    val ankiViewModel: AnkiViewModel = hiltViewModel()
    val ankiUiState by ankiViewModel.uiState.collectAsStateWithLifecycle()
    val ocrViewModel: PdfOcrViewModel = hiltViewModel()
    val ocrUiState by ocrViewModel.uiState.collectAsStateWithLifecycle()
    var installedOcrRevisions by remember { mutableStateOf(mapOf<Int, String>()) }
    val ocrSnackbarHostState = remember { SnackbarHostState() }
    val ocrFailedText = stringResource(R.string.pdf_ocr_failed)
    val ocrTryAgainText = stringResource(R.string.pdf_ocr_try_again)

    LaunchedEffect(pdfFile, bookRoot, book.pageCount) {
        ocrViewModel.attachBook(pdfFile, bookRoot, book.pageCount)
    }

    var webView by remember { mutableStateOf<HoshiPdfReaderWebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var pageIndex by remember(book) { mutableIntStateOf(initialPageIndex.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))) }
    var pageCount by remember(book) { mutableIntStateOf(book.pageCount) }
    var pdfOutline by remember(book) { mutableStateOf<List<PdfOutlineItem>>(emptyList()) }
    var zoomFactor by remember { mutableDoubleStateOf(1.0) }
    var isReaderReady by remember(book) { mutableStateOf(false) }
    var loadFailed by remember(book) { mutableStateOf(false) }
    var focusMode by remember { mutableStateOf(false) }
    var showReaderMenu by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    val progressDisplay = readerProgressDisplay(contentLanguageProfile)

    var dictionarySettings by remember { mutableStateOf(DictionarySettings()) }
    var audioSettings by remember { mutableStateOf(AudioSettings()) }
    var dictionaryStyles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            dictionarySettings = settings
        }
    }
    LaunchedEffect(dictionaryRepository) {
        dictionaryStyles = withContext(Dispatchers.IO) {
            runCatching {
                dictionaryRepository.ensureLookupQueryReady()
                dictionaryRepository.dictionaryStyles()
            }.getOrDefault(emptyMap())
        }
    }
    LaunchedEffect(audioSettingsRepository) {
        audioSettingsRepository.settings.collect { settings ->
            audioSettings = settings
        }
    }

    var highlights by remember(bookRoot) {
        mutableStateOf<List<ReaderHighlight>?>(if (bookRoot == null) emptyList() else null)
    }
    LaunchedEffect(bookRoot, bookRepository) {
        highlights = if (bookRoot != null) {
            bookRepository.loadHighlights(bookRoot)
        } else {
            emptyList()
        }
    }

    var persistedStatistics by remember(bookRoot) {
        mutableStateOf<List<ReadingStatistics>?>(if (bookRoot == null) emptyList() else null)
    }
    LaunchedEffect(bookRoot, bookRepository) {
        try {
            persistedStatistics = if (bookRoot != null) bookRepository.loadStatistics(bookRoot) else emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Leave the tracker unavailable so corrupt history cannot be overwritten.
        }
    }
    val statisticsTracker = remember(
        bookRoot,
        book.title,
        effectiveSettings.statisticsResetMinutes,
        persistedStatistics,
    ) {
        persistedStatistics?.let { statistics ->
            ReaderStatisticsTracker(
                title = book.title,
                initialStatistics = statistics,
                resetMinutes = effectiveSettings.statisticsResetMinutes,
                dateProvider = statisticsDateProvider,
            )
        }
    }
    var statisticsState by remember(statisticsTracker) { mutableStateOf(statisticsTracker?.state) }
    fun syncStatisticsState() {
        statisticsState = statisticsTracker?.state
    }
    fun currentCharacterCount(): Int = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    fun startStatisticsForPageChangeIfNeeded() {
        if (effectiveSettings.statisticsAutostartOnPageTurn) {
            statisticsTracker?.startForPageTurnIfNeeded(currentCharacterCount())
            syncStatisticsState()
        }
    }
    fun recordStatisticsAtPage() {
        statisticsTracker?.update(currentCharacterCount())
        syncStatisticsState()
    }
    fun statisticsForSave(): List<ReadingStatistics>? {
        recordStatisticsAtPage()
        return statisticsTracker?.statisticsForPersistenceOrNull()
    }
    fun toggleStatisticsTracking() {
        val tracker = statisticsTracker ?: return
        if (tracker.state.isTracking) {
            tracker.stop(currentCharacterCount())
            syncStatisticsState()
        } else {
            tracker.start(currentCharacterCount())
            syncStatisticsState()
        }
    }

    // ---- Focus mode and reader menu -------------------------------------------
    fun enterFocusMode() {
        focusMode = true
        showReaderMenu = false
    }
    fun handleBackNavigation(): Boolean {
        if (focusMode) {
            focusMode = false
            return false
        }
        return true
    }
    LaunchedEffect(statisticsTracker, isReaderReady, effectiveSettings.statisticsAutostartOnBookOpen) {
        if (isReaderReady && effectiveSettings.statisticsAutostartOnBookOpen && statisticsTracker?.state?.isTracking == false) {
            statisticsTracker?.start(currentCharacterCount())
            syncStatisticsState()
        }
    }
    // ---- Lookup popup state ---------------------------------------------------
    var lookupPopups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var readerPopupHistories by remember { mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap()) }
    var rootSelectionHighlight by remember { mutableStateOf<ReaderRootSelectionHighlight?>(null) }
    val popupDarkMode = effectiveSettings.usesDarkInterface(systemDarkTheme)
    val popupAssets = remember(context) { LookupPopupAssets.load(context) }
    val readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() }
    val noAudioFoundText = stringResource(R.string.audio_no_audio_found)
    val audioLoadingText = stringResource(R.string.loading)
    val popupAudioSettings = audioSettings.withLocalizedSourceNames()
    val readerPopupIframeDocument = remember(
        dictionaryStyles,
        dictionarySettings,
        effectiveSettings.popupSwipeToDismiss,
        effectiveSettings.popupSwipeThreshold,
        effectiveSettings.popupReducedMotionScrolling,
        effectiveSettings.popupReducedMotionScrollPercent,
        effectiveSettings.popupReducedMotionSwipeThreshold,
        popupDarkMode,
        effectiveSettings.eInkMode,
        popupAudioSettings,
        ankiUiState.popupSettings,
        fontManager,
        effectiveSettings.popupScale,
        contentLanguageProfile,
        noAudioFoundText,
        audioLoadingText,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = dictionaryStyles,
            settings = dictionarySettings,
            swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
            swipeThreshold = effectiveSettings.popupSwipeThreshold,
            reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = popupAudioSettings,
            noAudioFoundText = noAudioFoundText,
            audioLoadingText = audioLoadingText,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontManager.popupFontFaceCss(),
            popupScale = effectiveSettings.popupScale,
            contentLanguageProfile = contentLanguageProfile,
        )
    }
    val currentReaderPopupIframeDocument = rememberUpdatedState(readerPopupIframeDocument)
    val readerPopupIframeUrl = remember(readerPopupIframeDocument) {
        readerLookupPopupIframeUrl(readerPopupIframeDocument.hashCode())
    }
    LaunchedEffect(webView, readerPopupIframeUrl) {
        webView?.evaluateJavascript(
            """
                (function() {
                  var iframeUrl = ${readerJavaScriptStringLiteral(readerPopupIframeUrl)};
                  window.__hoshiReaderPopupIframeUrl = iframeUrl;
                  if (window.hoshiReaderPopupHost) {
                    window.hoshiReaderPopupHost.preloadIdleRootFrame(iframeUrl);
                  }
                })();
            """.trimIndent(),
            null,
        )
    }
    val readerPopupResourceHandler = remember(context, popupAssets, fontManager, dictionaryRepository) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = popupAssets,
            fontManager = fontManager,
            audioRequestHandler = appContainer.audioRequestHandler,
            imageRequestHandler = DictionaryImageRequestHandler(dictionaryRepository::dictionaryMedia),
            iframeDocument = { currentReaderPopupIframeDocument.value },
        )
    }
    val currentReaderPopupResourceHandler = rememberUpdatedState(readerPopupResourceHandler)
    val themedLookupPopups = remember(
        lookupPopups,
        popupDarkMode,
        effectiveSettings.eInkMode,
        audioSettings,
        effectiveSettings.popupScale,
    ) {
        lookupPopups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
            popupScale = effectiveSettings.popupScale,
        )
    }

    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        val activeIds = nextPopups.mapTo(mutableSetOf()) { it.id }
        readerPopupHistories = readerPopupHistories.filterKeys(activeIds::contains)
        rootSelectionHighlight = rootSelectionHighlight?.takeIf { highlight ->
            highlight.popupId == null || highlight.popupId in activeIds
        }
        lookupPopups = nextPopups
    }
    fun dismissRootLookupPopup() {
        rootSelectionHighlight = null
        setLookupPopups(clearPopupSelectionHighlightsForPdf(lookupPopups))
        webView?.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
        setLookupPopups(emptyList())
    }
    fun closeLookupPopupsAndSelection() {
        rootSelectionHighlight = null
        if (lookupPopups.isNotEmpty()) {
            setLookupPopups(clearPopupSelectionHighlightsForPdf(lookupPopups))
            webView?.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
            setLookupPopups(emptyList())
        } else {
            setLookupPopups(emptyList())
        }
    }
    fun lookupRootPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            dictionaryStyles = dictionaryStyles,
            lookup = { text, maxResults, scanLength -> dictionaryRepository.lookup(text, maxResults, scanLength, dictionarySettings.lookupOptions()) },
            options = LookupPopupOptions(
                isVertical = isVerticalPdfSelectionRect(selection.rect),
                isFullWidth = false,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
                reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
                reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
                popupScale = effectiveSettings.popupScale,
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = popupDarkMode,
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettings,
                documentTitle = book.title,
                coverPath = bookCoverFile?.takeIf { it.isFile }?.absolutePath,
                contentLanguageProfile = contentLanguageProfile,
            ),
        )
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            dictionaryStyles = dictionaryStyles,
            lookup = { text, maxResults, scanLength -> dictionaryRepository.lookup(text, maxResults, scanLength, dictionarySettings.lookupOptions()) },
            options = LookupPopupOptions(
                isVertical = false,
                isFullWidth = false,
                width = effectiveSettings.popupWidth,
                height = effectiveSettings.popupHeight,
                swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
                swipeThreshold = effectiveSettings.popupSwipeThreshold,
                reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
                reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
                reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
                popupScale = effectiveSettings.popupScale,
                popupActionBar = effectiveSettings.popupActionBar,
                dictionarySettings = dictionarySettings,
                darkMode = popupDarkMode,
                eInkMode = effectiveSettings.eInkMode,
                audioSettings = audioSettings,
                documentTitle = book.title,
                coverPath = bookCoverFile?.takeIf { it.isFile }?.absolutePath,
                contentLanguageProfile = contentLanguageProfile,
            ),
        )
    fun popupIndex(popupId: String): Int =
        lookupPopups.indexOfFirst { it.id == popupId }
    fun popupById(popupId: String): LookupPopupItem? =
        lookupPopups.firstOrNull { it.id == popupId }
    fun replyReaderPopupMessage(popupId: String, messageId: String, bodyJson: String) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.resolveMessage(${readerJavaScriptStringLiteral(popupId)}, ${readerJavaScriptStringLiteral(messageId)}, $bodyJson)
            """.trimIndent(),
            null,
        )
    }
    fun highlightReaderPopupSelection(popupId: String, highlightCount: Int) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.highlightSelection(${readerJavaScriptStringLiteral(popupId)}, $highlightCount)
            """.trimIndent(),
            null,
        )
    }
    fun handleReaderPopupBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
        when (message) {
            is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
            is ReaderLookupPopupBridgeMessage.TapOutside -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setLookupPopups(closeChildPopupsAndClearSelection(lookupPopups, index))
            }
            is ReaderLookupPopupBridgeMessage.SwipeDismiss -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                if (index == 0) {
                    dismissRootLookupPopup()
                } else {
                    setLookupPopups(dismissPopupAt(lookupPopups, index))
                }
            }
            is ReaderLookupPopupBridgeMessage.TextSelected -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                val nextPopups = lookupPopups.take(index + 1)
                val lookup = lookupChildPopup(message.selection)
                if (lookup == null) {
                    highlightReaderPopupSelection(message.popupId, 0)
                    return
                }
                val (childPopup, highlightCount) = lookup
                setLookupPopups(nextPopups + childPopup)
                highlightReaderPopupSelection(message.popupId, highlightCount)
            }
            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> {
                WordAudioPlayer.get(context).play(message.url, message.mode)
            }
            is ReaderLookupPopupBridgeMessage.MineEntry -> {
                val popup = popupById(message.popupId) ?: return
                val messageId = message.messageId ?: return
                ankiViewModel.mineEntryAsync(message.formatId, message.payloadJson, popup.state.ankiContext) { mined ->
                    replyReaderPopupMessage(message.popupId, messageId, mined.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                ankiViewModel.duplicateStatesAsync(message.valuesByHandlebar) { states ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@duplicateStatesAsync, readerPopupBooleanMapJson(states))
                }
            }
            is ReaderLookupPopupBridgeMessage.ShowNotes -> {
                ankiViewModel.showNotesAsync(message.formatId, message.valuesByHandlebar) { shown ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@showNotesAsync, shown.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val popup = popupById(message.popupId) ?: return
                val results = dictionaryRepository.lookup(
                    message.query,
                    popup.state.dictionarySettings.maxResults,
                    popup.state.dictionarySettings.scanLength,
                    popup.state.dictionarySettings.lookupOptions(),
                )
                if (results.isNotEmpty()) {
                    setLookupPopups(
                        lookupPopups.map { existing ->
                            if (existing.id == message.popupId) {
                                existing.copy(state = existing.state.copy(results = results))
                            } else {
                                existing
                            }
                        },
                    )
                    val current = readerPopupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount + 1,
                            forwardCount = 0,
                        )
                        )
                }
                replyReaderPopupMessage(message.popupId, message.messageId ?: return, results.size.toString())
            }
            is ReaderLookupPopupBridgeMessage.KanjiRedirect -> {
                val result = dictionaryRepository.lookupKanji(message.kanji)
                replyReaderPopupMessage(
                    message.popupId,
                    message.messageId ?: return,
                    if (result.entries.isEmpty()) "null" else LookupPopupHtml.kanjiJsonString(result),
                )
            }
            is ReaderLookupPopupBridgeMessage.KanjiRedirectCommitted -> {
                val current = readerPopupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                readerPopupHistories = readerPopupHistories + (
                    message.popupId to current.copy(
                        backCount = current.backCount + 1,
                        forwardCount = 0,
                    )
                    )
            }
            is ReaderLookupPopupBridgeMessage.GetEntry -> {
                val entry = popupById(message.popupId)?.state?.results?.getOrNull(message.index)
                val body = entry?.let(LookupPopupHtml::entryJsonString) ?: "null"
                replyReaderPopupMessage(message.popupId, message.messageId ?: return, body)
            }
            is ReaderLookupPopupBridgeMessage.PopupScrolled -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setLookupPopups(closeChildPopupsForScrolledParent(lookupPopups, index))
            }
            is ReaderLookupPopupBridgeMessage.ScrollState,
            is ReaderLookupPopupBridgeMessage.SourceHistoryRestored -> Unit
            is ReaderLookupPopupBridgeMessage.NavigateBack -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.backCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount - 1,
                            forwardCount = current.forwardCount + 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateForward -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.forwardCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount + 1,
                            forwardCount = current.forwardCount - 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.ContentReady -> Unit
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue,
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback,
            is ReaderLookupPopupBridgeMessage.SasayakiPlayForward -> Unit
        }
    }
    readerPopupBridgeHolder.callbacks = ReaderLookupPopupBridgeCallbacks(::handleReaderPopupBridgeMessage)

    val handleTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit = { selection, selectionRects ->
        rootSelectionHighlight = null
        setLookupPopups(emptyList())
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            rootSelectionHighlight = ReaderRootSelectionHighlight(
                popupId = popup.id,
                rects = null,
            )
            setLookupPopups(listOf(popup))
            selectionRects(highlightCount) { rects ->
                if (lookupPopups.none { it.id == popup.id }) return@selectionRects
                val displayRects = rects.ifEmpty { listOf(popup.state.selection.rect) }
                // Vertical OCR lines return one rect per character stacked in Y.
                // Anchoring to the first rect places a horizontal popup below the
                // first character, covering the rest of the selection. Anchor to
                // the union so the popup sits below/above the whole selection,
                // or to the side when the union is vertical.
                val anchor = pdfUnionSelectionRect(displayRects) ?: popup.state.selection.rect
                val anchorIsVertical = isVerticalPdfSelectionRect(anchor)
                setLookupPopups(
                    lookupPopups.map { existing ->
                        if (existing.id == popup.id) {
                            existing.copy(
                                state = existing.state.copy(
                                    selection = existing.state.selection.copy(rect = anchor),
                                    isVertical = anchorIsVertical,
                                ),
                            )
                        } else {
                            existing
                        }
                    },
                )
                rootSelectionHighlight = ReaderRootSelectionHighlight(
                    popupId = popup.id,
                    rects = displayRects,
                )
            }
        }
    }

    fun handleReaderTap(isBottomSafeArea: Boolean) {
        if (lookupPopups.isEmpty()) {
            rootSelectionHighlight = null
            webView?.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
            focusMode = readerFocusModeAfterTap(focusMode, isBottomSafeArea)
            if (focusMode) showReaderMenu = false
        } else {
            closeLookupPopupsAndSelection()
        }
    }
    fun handleReaderTapOutside() = handleReaderTap(isBottomSafeArea = false)

    // ---- Highlights ----------------------------------------------------------
    fun pushHighlightsToPage(nextHighlights: List<ReaderHighlight>) {
        val json = pdfReaderJson.encodeToString(PdfHighlightPayload.listFrom(nextHighlights))
        webView?.evaluateJavascript(
            "window.hoshiHighlights && window.hoshiHighlights.applyHighlights($json)",
            null,
        )
    }
    fun persistHighlights(nextHighlights: List<ReaderHighlight>) {
        highlights = nextHighlights
        val root = bookRoot ?: return
        scope.launch {
            bookRepository.saveHighlights(root, nextHighlights)
        }
        pushHighlightsToPage(nextHighlights)
    }
    fun changeHighlight(color: HighlightColor, id: String, result: ReaderHighlightResult) {
        when (result) {
            is ReaderHighlightCreationResult -> {
                val highlight = ReaderHighlight(
                    id = id,
                    character = result.start,
                    offset = result.offset,
                    text = result.text,
                    color = color,
                    createdAt = bookRepository.currentAppleReferenceDateSeconds(),
                )
                // Persist the OCR revision pin before the highlight sidecar so a
                // crash cannot leave a committed highlight without its source.
                val ocrRecord = ocrUiState.pages[result.start]?.record
                if (ocrRecord != null) {
                    ocrViewModel.pinForHighlight(id, result.start, installedOcrRevisions[result.start]) {
                        persistHighlights(highlights.orEmpty() + highlight)
                    }
                } else {
                    persistHighlights(highlights.orEmpty() + highlight)
                }
            }
            is ReaderHighlightResult.Recolored,
            is ReaderHighlightResult.Removed,
            -> {
                val current = highlights.orEmpty()
                val next = ReaderHighlights.applyEdit(current, result, color)
                if (next != current) persistHighlights(next)
                if (result is ReaderHighlightResult.Removed) {
                    val sourceHash = ocrUiState.sourceHash
                    if (sourceHash != null) {
                        ocrViewModel.unpinForHighlight(result.id)
                    }
                }
            }
        }
    }
    fun removeHighlight(highlight: ReaderHighlight) {
        persistHighlights(highlights.orEmpty().filterNot { it.id == highlight.id })
        val sourceHash = ocrUiState.sourceHash
        if (sourceHash != null) {
            ocrViewModel.unpinForHighlight(highlight.id)
        }
        webView?.evaluateJavascript(ReaderHighlightCommand.Remove(highlight.id).source, null)
    }

    // ---- Bookmark save -------------------------------------------------------
    var bookmarkSaveJob by remember { mutableStateOf<Job?>(null) }
    val currentOnSaveBookmark = rememberUpdatedState(onSaveBookmark)
    fun scheduleBookmarkSave() {
        bookmarkSaveJob?.cancel()
        bookmarkSaveJob = scope.launch {
            delay(400)
            val stats = statisticsForSave()
            currentOnSaveBookmark.value(pageIndex, pageIndex.toDouble() / pageCount.coerceAtLeast(1).toDouble(), stats)
        }
    }

    // ---- Bridge messages -----------------------------------------------------
    val currentContentLanguageProfile = rememberUpdatedState(contentLanguageProfile)
    val currentDictionarySettings = rememberUpdatedState(dictionarySettings)
    val currentEffectiveSettings = rememberUpdatedState(effectiveSettings)
    val currentHighlights = rememberUpdatedState(highlights)
    val currentPageIndex = rememberUpdatedState(pageIndex)
    val currentZoomFactor = rememberUpdatedState(zoomFactor)
    val currentOcrSessionId = rememberUpdatedState(ocrUiState.sessionId)
    val handlePdfMessage: (PdfReaderMessage) -> Unit = { message ->
        when (message) {
            PdfReaderMessage.HostReady -> {
                val highlightsJson = pdfReaderJson.encodeToString(PdfHighlightPayload.listFrom(currentHighlights.value.orEmpty()))
                val backgroundColor = currentEffectiveSettings.value.backgroundColorCss(systemDarkTheme)
                webView?.evaluateJavascript(
                    pdfReaderSetupScript(
                        contentLanguageProfile = currentContentLanguageProfile.value,
                        scanNonJapaneseText = currentDictionarySettings.value.scanNonJapaneseText,
                        backgroundCss = backgroundColor,
                        pageIndex = currentPageIndex.value,
                        zoomFactor = currentZoomFactor.value,
                        continuousScroll = currentEffectiveSettings.value.pdfContinuousScroll,
                        pageSwipeThresholdPx = currentEffectiveSettings.value.pageSwipeThresholdPx,
                        highlightsJson = highlightsJson,
                        sessionId = currentOcrSessionId.value,
                    ),
                    null,
                )
            }
            PdfReaderMessage.Ready -> {
                isReaderReady = true
            }
            is PdfReaderMessage.PageChanged -> {
                if (message.pageCount > 0) pageCount = message.pageCount
                val nextIndex = message.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                if (nextIndex != pageIndex) {
                    closeLookupPopupsAndSelection()
                    enterFocusMode()
                    startStatisticsForPageChangeIfNeeded()
                    pageIndex = nextIndex
                    recordStatisticsAtPage()
                    scheduleBookmarkSave()
                }
            }
            is PdfReaderMessage.TextProbe -> {
                // Kotlin validates the session against its active book; JS never
                // supplies a filesystem path.
                if (message.sessionId == currentOcrSessionId.value) {
                    ocrViewModel.onTextProbe(
                        PdfTextProbe(
                            pageIndex = message.pageIndex,
                            textLength = message.textLength,
                            hasSuspectChars = message.hasSuspectChars,
                            isSingleNumber = message.isSingleNumber,
                            extractionFailed = message.extractionFailed,
                            cssWidth = message.cssWidth,
                            cssHeight = message.cssHeight,
                        ),
                    )
                } else {
                    Log.w(
                        "PdfOcr",
                        "Dropping textProbe for stale session page=${message.pageIndex}",
                    )
                }
            }
            is PdfReaderMessage.OcrViewport -> {
                if (message.sessionId == currentOcrSessionId.value) {
                    ocrViewModel.onViewportChanged(message.firstVisible, message.lastVisible)
                    installedOcrRevisions = installedOcrRevisions.filterKeys { it >= message.firstVisible - 1 && it <= message.lastVisible + 1 }
                }
            }
            is PdfReaderMessage.OcrInstalled -> {
                if (message.sessionId == currentOcrSessionId.value) {
                    installedOcrRevisions = installedOcrRevisions + (message.pageIndex to message.revisionId)
                }
            }
            is PdfReaderMessage.ZoomChanged -> {
                zoomFactor = message.zoomFactor
            }
            is PdfReaderMessage.Outline -> {
                pdfOutline = message.items
            }
            is PdfReaderMessage.Tap -> {
                handlePdfTap(
                    x = message.x,
                    y = message.y,
                    scanLength = readerSelectionMaxLength(currentDictionarySettings.value),
                    webView = webView,
                    onBlankTap = ::handleReaderTapOutside,
                )
            }
            PdfReaderMessage.LoadFailed -> {
                loadFailed = true
            }
        }
    }
    val currentHandlePdfMessage = rememberUpdatedState(handlePdfMessage)
    LaunchedEffect(webView, isReaderReady, effectiveSettings.pdfContinuousScroll, effectiveSettings.pageSwipeThresholdPx) {
        if (!isReaderReady) return@LaunchedEffect
        closeLookupPopupsAndSelection()
        webView?.evaluateJavascript(
            "window.hoshiPdfReader.setViewMode(${effectiveSettings.pdfContinuousScroll}, ${effectiveSettings.pageSwipeThresholdPx})",
            null,
        )
    }

    var deliveredOcrRevisions by remember { mutableStateOf(mapOf<Int, String>()) }
    LaunchedEffect(ocrUiState.sessionId) {
        deliveredOcrRevisions = emptyMap()
        installedOcrRevisions = emptyMap()
    }
    // attachBook rotates the session when the book changes; if the reader page
    // already opened with the previous id, probes would be dropped as stale.
    // Push the current id so JS and Kotlin agree without reopening the book.
    LaunchedEffect(webView, ocrUiState.sessionId, isReaderReady) {
        if (!isReaderReady) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        view.evaluateJavascript(
            "window.hoshiPdfReader && window.hoshiPdfReader.setSessionId(${readerJavaScriptStringLiteral(ocrUiState.sessionId)})",
            null,
        )
    }
    LaunchedEffect(ocrUiState, webView, isReaderReady) {
        if (!isReaderReady) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        val sessionId = ocrUiState.sessionId
        val sourceHash = ocrUiState.sourceHash ?: return@LaunchedEffect
        deliveredOcrRevisions = deliveredOcrRevisions.filterKeys { it in ocrUiState.pages }
        for ((pageIndex, pageState) in ocrUiState.pages) {
            if (pageState.status != PdfOcrPageStatus.OcrReady) continue
            val record = pageState.record ?: continue
            if (deliveredOcrRevisions[pageIndex] == record.revisionId) continue
            if (record.canonicalText.length > 20000 || record.spans.size > 2000) continue
            val payload = PdfOcrPayload(
                sessionId = sessionId,
                pageIndex = pageIndex,
                requestId = pageState.requestId,
                revisionId = record.revisionId,
                sourceHash = sourceHash,
                canonicalText = record.canonicalText,
                spans = record.spans.map { span ->
                    val slice = record.canonicalText.substring(span.startUtf16.coerceIn(0, record.canonicalText.length), span.endUtf16.coerceIn(0, record.canonicalText.length))
                    PdfOcrSpanPayload(
                        sourceId = span.sourceId,
                        startUtf16 = span.startUtf16,
                        endUtf16 = span.endUtf16,
                        text = slice,
                        direction = span.direction,
                        quad = span.quad?.let { quad ->
                            PdfOcrQuadPayload(quad.x0, quad.y0, quad.x1, quad.y1, quad.x2, quad.y2, quad.x3, quad.y3)
                        },
                    )
                },
                cssWidth = record.pageGeometry.cssWidth,
                cssHeight = record.pageGeometry.cssHeight,
            )
            val payloadJson = runCatching { pdfReaderJson.encodeToString(payload) }.getOrNull() ?: continue
            // Bound payload size; OCR strings become DOM textContent, never HTML.
            if (payloadJson.length > 500000) continue
            view.evaluateJavascript(pdfOcrApplyScript(payloadJson), null)
            deliveredOcrRevisions = deliveredOcrRevisions + (pageIndex to record.revisionId)
        }
    }
    val currentPageOcrState = ocrUiState.pages[pageIndex]
    LaunchedEffect(currentPageOcrState?.status, currentPageOcrState?.error, pageIndex) {
        if (currentPageOcrState?.status == PdfOcrPageStatus.Failed && currentPageOcrState.retryable) {
            val result = ocrSnackbarHostState.showSnackbar(
                message = ocrFailedText,
                actionLabel = ocrTryAgainText,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val geometry = currentPageOcrState.record?.pageGeometry
                if (geometry != null && geometry.cssWidth > 0 && geometry.cssHeight > 0) {
                    ocrViewModel.retryWithProbe(pageIndex, geometry.cssWidth, geometry.cssHeight)
                } else {
                    ocrViewModel.retry(pageIndex)
                }
            }
        }
    }

    fun closeReader() {
        bookmarkSaveJob?.cancel()
        val stats = statisticsForSave()
        onSaveBookmark(pageIndex, pageIndex.toDouble() / pageCount.coerceAtLeast(1).toDouble(), stats)
        onFlushAutoSyncExport()
        onClose()
    }
    fun jumpToPageIndex(target: Int) {
        val clamped = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        closeLookupPopupsAndSelection()
        webView?.evaluateJavascript(pdfJumpToPageInvocation(clamped), null)
    }
    fun turnPdfPage(direction: ReaderNavigationDirection) {
        when (direction) {
            ReaderNavigationDirection.Forward -> jumpToPageIndex(pageIndex + 1)
            ReaderNavigationDirection.Backward -> jumpToPageIndex(pageIndex - 1)
        }
    }
    BackHandler {
        if (handleBackNavigation()) {
            closeReader()
        }
    }
    val currentReaderKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val keyEvent = readerHardwareKeyEventForKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            settings = effectiveSettings,
            sasayakiEnabled = false,
            hasSasayakiAudio = false,
            hasLookupPopup = lookupPopups.isNotEmpty(),
        )
        if (!keyEvent.consumed) return@rememberUpdatedState false
        when (val action = keyEvent.action) {
            is ReaderHardwareKeyAction.ReaderNavigation -> turnPdfPage(action.direction)
            is ReaderHardwareKeyAction.PopupTermNavigation -> {
                val direction = when (action.direction) {
                    PopupTermNavigationDirection.Previous -> "previous"
                    PopupTermNavigationDirection.Next -> "next"
                }
                webView?.evaluateJavascript(
                    "window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.navigateTopTerm('$direction')",
                    null,
                )
            }
            else -> Unit
        }
        true
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentReaderKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    val useDarkSystemBarIcons = effectiveSettings.usesDarkSystemBarIcons(systemDarkTheme)
    DisposableEffect(context, view, useDarkSystemBarIcons) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
        controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        onDispose {
            controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
            controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        }
    }
    DisposableEffect(context, view) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            if (previousBehavior != null) {
                controller.systemBarsBehavior = previousBehavior
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(context, view, focusMode) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        } ?: return@LaunchedEffect
        val visibility = readerSystemBarVisibility(focusMode)
        if (visibility.showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (visibility.showNavigationBar) {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
    DisposableEffect(bookId) {
        onDispose {
            bookmarkSaveJob?.cancel()
            onFlushAutoSyncExport()
        }
    }

    val keepScreenOn = ReaderScreenAwake.shouldKeepScreenOn(
        keepScreenOnWhileReading = effectiveSettings.keepScreenOnWhileReading,
        sasayakiIsPlaying = false,
        sasayakiAutoScroll = false,
    )
    DisposableEffect(context, keepScreenOn) {
        val window = (context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val backgroundColor = Color(effectiveSettings.backgroundColor(systemDarkTheme))
    val chromeColors = readerChromeColors(effectiveSettings, systemDarkTheme)
    val chromeState = remember(book, pageIndex, pageCount, statisticsState) {
        val displayPage = pdfDisplayPage(pageIndex, pageCount)
        ReaderChromeState(
            title = book.title,
            currentCharacter = displayPage,
            totalCharacters = pageCount,
            chapterCurrentCharacter = displayPage,
            chapterTotalCharacters = pageCount,
            statistics = statisticsState?.session?.let {
                ReaderStatisticsChromeState(
                    readingSpeed = it.lastReadingSpeed,
                    readingTimeSeconds = it.readingTime,
                )
            },
        )
    }
    val topChromeMetrics = readerTopChromeMetrics(effectiveSettings.topSafeAreaDp)
    val bottomChromeMetrics = readerBottomChromeMetrics(effectiveSettings.bottomSafeAreaDp)
    val currentStatusBarPadding = rememberCurrentStatusBarPadding()
    val stableStatusBarPadding = rememberStableStatusBarPadding()
    val currentStatusBarPaddingDp = currentStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val stableStatusBarPaddingDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val topInfoPadding = readerTopInfoOverlayPaddingDp(
        topSystemInsetDp = currentStatusBarPaddingDp,
        focusMode = focusMode,
        settings = effectiveSettings,
    ).dp
    val chromeLayout = readerChromeLayout(
        chromeState,
        effectiveSettings,
        progressDisplay = progressDisplay,
    )
    val chromeVisibility = readerChromeVisibility(
        focusMode = focusMode,
        hasStatisticsToggle = effectiveSettings.showStatisticsToggle,
        hasSasayakiToggle = false,
        hasBackJump = false,
        hasForwardJump = false,
    )
    val topInfoVisibility = chromeVisibility.copy(
        showTitleAndProgress = chromeVisibility.showTitleAndProgress &&
            readerShouldShowTitleAndProgress(
                focusMode = focusMode,
                currentStatusBarInsetDp = currentStatusBarPaddingDp,
                stableStatusBarInsetDp = stableStatusBarPaddingDp,
            ),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val readerLookupPopupViewport = ReaderLookupPopupViewport(
                width = maxWidth.value.toDouble(),
                height = maxHeight.value.toDouble(),
            )
            val readerRootSelectionHighlightPayload = remember(
                rootSelectionHighlight,
                popupDarkMode,
                effectiveSettings.eInkMode,
            ) {
                rootSelectionHighlight?.let { highlight ->
                    // PDF pages mix horizontal and vertical OCR lines, so infer
                    // orientation from the selected rects instead of reader settings.
                    val union = highlight.rects?.let(::pdfUnionSelectionRect)
                    ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                        popupId = highlight.popupId,
                        rects = highlight.rects,
                        darkMode = popupDarkMode,
                        eInkMode = effectiveSettings.eInkMode,
                        verticalWriting = union?.let(::isVerticalPdfSelectionRect) ?: false,
                    )
                }
            }
            val readerLookupPopupPayloads = remember(
                themedLookupPopups,
                readerPopupHistories,
                readerLookupPopupViewport,
                readerPopupIframeUrl,
                rootSelectionHighlight,
            ) {
                readerLookupPopupFramePayloads(
                    popups = themedLookupPopups,
                    histories = readerPopupHistories,
                    viewport = readerLookupPopupViewport,
                    sasayakiWasPaused = false,
                    sasayakiIsPlaying = false,
                    iframeUrl = readerPopupIframeUrl,
                    rootSelectionHighlight = rootSelectionHighlight,
                )
            }
            key(webViewGeneration) {
                PdfWebViewContent(
                    pdfFile = pdfFile,
                    popupResourceHandler = { currentReaderPopupResourceHandler.value },
                    onWebViewReady = { webView = it },
                    onRendererTerminated = { terminated ->
                        if (webView === terminated) {
                            webView = null
                            webViewGeneration += 1
                            closeLookupPopupsAndSelection()
                        }
                    },
                    onMessage = { currentHandlePdfMessage.value(it) },
                    onTextSelected = handleTextSelected,
                    onHighlightChanged = ::changeHighlight,
                    popupBridgeHolder = readerPopupBridgeHolder,
                    backgroundColor = backgroundColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ReaderLookupPopupIframeSync(
                webView = webView,
                payloads = readerLookupPopupPayloads,
                rootHighlight = readerRootSelectionHighlightPayload,
            )
        }

        ReaderTopInfo(
            state = chromeState,
            settings = effectiveSettings,
            progressDisplay = progressDisplay,
            colors = chromeColors,
            onStatisticsToggle = if (effectiveSettings.showStatisticsToggle) {
                ::toggleStatisticsTracking
            } else {
                null
            },
            statisticsTracking = statisticsState?.isTracking == true,
            onJumpBack = null,
            onJumpForward = null,
            onSasayakiToggle = null,
            sasayakiPlaying = false,
            visibility = topInfoVisibility,
            metrics = topChromeMetrics,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInfoPadding)
                .padding(horizontal = 15.dp),
        )
        ReaderBottomSafeProgress(
            state = chromeState,
            settings = effectiveSettings,
            progressDisplay = progressDisplay,
            colors = chromeColors,
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            sasayakiPlaybackControls = ReaderSasayakiBottomPlaybackControls(
                visible = false,
                rowHeightDp = 0,
                buttonWidthDp = 0,
                iconSizeDp = 0,
                horizontalPaddingDp = 0,
            ),
            sasayakiPlaying = false,
            onTapSafeArea = { handleReaderTap(isBottomSafeArea = true) },
            expandedTapArea = true,
            onSasayakiSkipBackward = {},
            onSasayakiTogglePlayback = {},
            onSasayakiSkipForward = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (chromeVisibility.showBottomChrome) {
            ReaderBottomChrome(
                state = chromeState,
                settings = effectiveSettings,
                layout = chromeLayout,
                progressDisplay = progressDisplay,
                colors = chromeColors,
                onClose = ::closeReader,
                onMenu = { showReaderMenu = !showReaderMenu },
                menuExpanded = showReaderMenu,
                onDismissMenu = { showReaderMenu = false },
                onGoTo = { showReaderMenu = false; showGoTo = true },
                onDisplaySettings = null,
                onReadingSettings = { showReaderMenu = false; showAppearance = true },
                onStatistics = null,
                onSasayaki = null,
                metrics = bottomChromeMetrics,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        PdfOcrStatusOverlay(
            pageState = currentPageOcrState,
            onRunOcr = {
                val geometry = currentPageOcrState?.record?.pageGeometry
                if (geometry != null && geometry.cssWidth > 0 && geometry.cssHeight > 0) {
                    ocrViewModel.useOcrForSuspect(pageIndex, geometry.cssWidth, geometry.cssHeight)
                } else {
                    ocrViewModel.retry(pageIndex)
                }
            },
            onKeepText = { ocrViewModel.useEmbedded(pageIndex) },
            onRetry = {
                val geometry = currentPageOcrState?.record?.pageGeometry
                if (geometry != null && geometry.cssWidth > 0 && geometry.cssHeight > 0) {
                    ocrViewModel.retryWithProbe(pageIndex, geometry.cssWidth, geometry.cssHeight)
                } else {
                    ocrViewModel.retry(pageIndex)
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = topInfoPadding + 48.dp),
        )
        SnackbarHost(
            hostState = ocrSnackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }

    if (showGoTo) {
        PdfContentsSheet(
            pageIndex = pageIndex,
            pageCount = pageCount,
            outline = pdfOutline,
            progressDisplay = progressDisplay,
            onPageJump = ::jumpToPageIndex,
            onChapterJump = { target ->
                jumpToPageIndex(target)
                showGoTo = false
            },
            onDismiss = { showGoTo = false },
        )
    }
    if (showAppearance) {
        PdfAppearanceSheet(
            zoomFactor = zoomFactor,
            continuousScroll = effectiveSettings.pdfContinuousScroll,
            onContinuousScrollChange = { continuous ->
                onReaderSettingsChange { it.copy(pdfContinuousScroll = continuous) }
            },
            onZoomIn = { webView?.evaluateJavascript("window.hoshiPdfReader.zoomIn()", null) },
            onZoomOut = { webView?.evaluateJavascript("window.hoshiPdfReader.zoomOut()", null) },
            onFitWidth = { webView?.evaluateJavascript("window.hoshiPdfReader.fitWidth()", null) },
            onDismiss = { showAppearance = false },
        )
    }

    if (loadFailed) {
        ReaderOpenFailurePage(
            onClose = ::closeReader,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun handlePdfTap(
    x: Double,
    y: Double,
    scanLength: Int,
    webView: HoshiPdfReaderWebView?,
    onBlankTap: () -> Unit,
) {
    val currentWebView = webView ?: return
    if (currentWebView.isNativeSelectionActionModeActive()) return
    // Popup taps never reach the viewport touch listener: the popup layer is a
    // sibling of #hoshi-pdf-viewport appended to documentElement, so touches on
    // visible popup shells do not post Tap messages. Every Tap here is already
    // outside the popup stack and should proceed directly to text selection.
    currentWebView.evaluateJavascript(
        ReaderSelectionCommand.SelectText(
            x = x.toFloat(),
            y = y.toFloat(),
            maxLength = scanLength,
        ).source,
    ) { result ->
        val selectionResult = ReaderSelectionResult.fromWebViewResult(result)
        if (selectionResult.selectedNothing) {
            onBlankTap()
        }
    }
}

@Composable
private fun PdfWebViewContent(
    pdfFile: File,
    popupResourceHandler: () -> ReaderLookupPopupResourceHandler?,
    onWebViewReady: (HoshiPdfReaderWebView) -> Unit,
    onRendererTerminated: (HoshiPdfReaderWebView) -> Unit,
    onMessage: (PdfReaderMessage) -> Unit,
    onTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit,
    onHighlightChanged: (HighlightColor, String, ReaderHighlightResult) -> Unit,
    popupBridgeHolder: ReaderLookupPopupBridgeCallbackHolder,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentOnMessage = rememberUpdatedState(onMessage)
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val currentOnHighlightChanged = rememberUpdatedState(onHighlightChanged)
    val currentPopupResourceHandler = rememberUpdatedState(popupResourceHandler)
    val currentOnRendererTerminated = rememberUpdatedState(onRendererTerminated)
    AndroidView(
        modifier = modifier.background(backgroundColor),
        factory = { factoryContext ->
            HoshiPdfReaderWebView(factoryContext).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.onHighlightChanged = { color, id, result ->
                    currentOnHighlightChanged.value(color, id, result)
                }
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection, selectionRects ->
                        if (!isReleased) currentOnTextSelected.value(selection, selectionRects)
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(
                    PdfReaderBridge(this) { message ->
                        if (!isReleased) currentOnMessage.value(message)
                    },
                    "HoshiPdfReader",
                )
                ReaderLookupPopupWebBridge.install(this, popupBridgeHolder, isActive = { !isReleased })
                webViewClient = PdfReaderWebViewClient(
                    context = factoryContext.applicationContext,
                    pdfFile = pdfFile,
                    popupResourceHandler = { currentPopupResourceHandler.value() },
                    onRendererTerminated = { view ->
                        if (!view.isReleased) {
                            view.releaseForDestroy()
                            currentOnRendererTerminated.value(view)
                        }
                    },
                )
                onWebViewReady(this)
                loadUrl(PdfReaderPageUrl)
            }
        },
        update = { view ->
            if (view.isReleased) return@AndroidView
            view.setBackgroundColor(backgroundColor.toArgb())
        },
    )
}

private fun pdfReaderSetupScript(
    contentLanguageProfile: ContentLanguageProfile,
    scanNonJapaneseText: Boolean,
    backgroundCss: String,
    pageIndex: Int,
    zoomFactor: Double,
    continuousScroll: Boolean,
    pageSwipeThresholdPx: Int,
    highlightsJson: String,
    sessionId: String,
): String {
    val highlightsLiteral = readerJavaScriptStringLiteral(highlightsJson)
    return """
        (function() {
          document.documentElement.style.setProperty('--hoshi-background-color', ${readerJavaScriptStringLiteral(backgroundCss)});
          document.documentElement.dataset.hoshiContentLanguage = ${readerJavaScriptStringLiteral(contentLanguageProfile.dictionaryLanguageId)};
          window.scanNonJapaneseText = $scanNonJapaneseText;
          if (window.hoshiSelection) {
            window.hoshiSelection.configure({
              bridge: 'android-reader',
              language: ${readerJavaScriptStringLiteral(contentLanguageProfile.dictionaryLanguageId)},
              linkTapResult: 'link',
              imageTapResult: 'image',
              rubyAwareRects: false,
              scaleRects: false
            });
          }
          window.hoshiHighlights && window.hoshiHighlights.applyHighlights(JSON.parse($highlightsLiteral));
          window.hoshiPdfReader.open({
            url: '/pdfbook/data',
            pageIndex: $pageIndex,
            zoomFactor: $zoomFactor,
            continuousScroll: $continuousScroll,
            pageSwipeThresholdPx: $pageSwipeThresholdPx,
            sessionId: ${readerJavaScriptStringLiteral(sessionId)}
          });
        })();
    """.trimIndent()
}

@Serializable
private data class PdfOcrSpanPayload(
    val sourceId: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val text: String,
    val quad: PdfOcrQuadPayload? = null,
    val direction: String = "UNKNOWN",
)

@Serializable
private data class PdfOcrQuadPayload(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
)

@Serializable
private data class PdfOcrPayload(
    val sessionId: String,
    val pageIndex: Int,
    val requestId: Int,
    val revisionId: String,
    val sourceHash: String,
    val canonicalText: String,
    val spans: List<PdfOcrSpanPayload>,
    val cssWidth: Double,
    val cssHeight: Double,
)

private fun pdfOcrApplyScript(payloadJson: String): String {
    val literal = readerJavaScriptStringLiteral(payloadJson)
    return """
        (function() {
          try {
            var payload = JSON.parse($literal);
            if (window.hoshiPdfOcr) window.hoshiPdfOcr.applyOcr(payload);
          } catch (e) {}
        })();
    """.trimIndent()
}

private fun pdfJumpToPageInvocation(pageIndex: Int): String =
    "window.hoshiPdfReader.jumpToPage($pageIndex)"

private fun clearPopupSelectionHighlightsForPdf(popups: List<LookupPopupItem>): List<LookupPopupItem> =
    popups.map { popup -> popup.copy(clearSelectionSignal = popup.clearSelectionSignal + 1) }

internal fun pdfUnionSelectionRect(rects: List<ReaderSelectionRect>): ReaderSelectionRect? {
    if (rects.isEmpty()) return null
    var left = Double.MAX_VALUE
    var top = Double.MAX_VALUE
    var right = -Double.MAX_VALUE
    var bottom = -Double.MAX_VALUE
    for (rect in rects) {
        if (!rect.width.isFinite() || !rect.height.isFinite()) continue
        if (rect.width <= 0.0 || rect.height <= 0.0) continue
        left = minOf(left, rect.x)
        top = minOf(top, rect.y)
        right = maxOf(right, rect.x + rect.width)
        bottom = maxOf(bottom, rect.y + rect.height)
    }
    if (left > right || top > bottom) return null
    return ReaderSelectionRect(x = left, y = top, width = right - left, height = bottom - top)
}

internal fun isVerticalPdfSelectionRect(rect: ReaderSelectionRect): Boolean {
    if (!rect.width.isFinite() || !rect.height.isFinite()) return false
    if (rect.width <= 0.0 || rect.height <= 0.0) return false
    // Match the OCR span direction threshold (height > width * 1.8) so vertical
    // columns use side placement instead of covering the selection from below.
    return rect.height > rect.width * 1.8
}

@Serializable
private data class PdfHighlightPayload(
    val id: String,
    val character: Int,
    val offset: Int,
    val text: String,
    val color: String,
) {
    companion object {
        fun listFrom(highlights: List<ReaderHighlight>): List<PdfHighlightPayload> =
            highlights.map { highlight ->
                PdfHighlightPayload(
                    id = highlight.id,
                    character = highlight.character,
                    offset = highlight.offset,
                    text = highlight.text,
                    color = highlight.color.rawValue,
                )
            }
    }
}

@Serializable
internal data class PdfOutlineItem(
    val title: String = "",
    val pageIndex: Int = -1,
    val children: List<PdfOutlineItem> = emptyList(),
)

private val pdfReaderJson = Json { encodeDefaults = true }

internal sealed interface PdfReaderMessage {
    data object HostReady : PdfReaderMessage
    data object Ready : PdfReaderMessage
    data class PageChanged(val pageIndex: Int, val pageCount: Int) : PdfReaderMessage
    data class ZoomChanged(val zoomFactor: Double) : PdfReaderMessage
    data class Outline(val items: List<PdfOutlineItem>) : PdfReaderMessage
    data class Tap(val x: Double, val y: Double) : PdfReaderMessage
    data class OcrViewport(val sessionId: String, val firstVisible: Int, val lastVisible: Int) : PdfReaderMessage
    data class OcrInstalled(val sessionId: String, val pageIndex: Int, val revisionId: String) : PdfReaderMessage
    data class TextProbe(
        val sessionId: String,
        val pageIndex: Int,
        val textLength: Int,
        val hasSuspectChars: Boolean,
        val isSingleNumber: Boolean,
        val extractionFailed: Boolean,
        val cssWidth: Double,
        val cssHeight: Double,
    ) : PdfReaderMessage
    data object LoadFailed : PdfReaderMessage

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(message: String): PdfReaderMessage? {
            val payload = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
            return when (payload["type"]?.toString()?.trim('"')) {
                "hostReady" -> HostReady
                "ready" -> Ready
                "pageChanged" -> PageChanged(
                    pageIndex = payload["pageIndex"]?.toString()?.toIntOrNull() ?: return null,
                    pageCount = payload["pageCount"]?.toString()?.toIntOrNull() ?: return null,
                )
                "zoomChanged" -> ZoomChanged(
                    zoomFactor = payload["zoomFactor"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null,
                )
                "outline" -> Outline(
                    items = payload["items"]?.let { element ->
                        runCatching { json.decodeFromJsonElement<List<PdfOutlineItem>>(element) }.getOrDefault(emptyList())
                    } ?: emptyList(),
                )
                "tap" -> Tap(
                    x = payload["x"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null,
                    y = payload["y"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null,
                )
                "textProbe" -> TextProbe(
                    sessionId = payload["sessionId"]?.toString()?.trim('"').orEmpty(),
                    pageIndex = payload["pageIndex"]?.toString()?.toIntOrNull() ?: return null,
                    textLength = payload["textLength"]?.toString()?.toIntOrNull()?.coerceIn(0, 20000) ?: return null,
                    hasSuspectChars = payload["hasSuspectChars"]?.toString() == "true",
                    isSingleNumber = payload["isSingleNumber"]?.toString() == "true",
                    extractionFailed = payload["extractionFailed"]?.toString() == "true",
                    cssWidth = payload["cssWidth"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0,
                    cssHeight = payload["cssHeight"]?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0,
                )
                "ocrViewport" -> OcrViewport(
                    sessionId = payload["sessionId"]?.toString()?.trim('"').orEmpty(),
                    firstVisible = payload["firstVisible"]?.toString()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                    lastVisible = payload["lastVisible"]?.toString()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                )
                "ocrInstalled" -> OcrInstalled(
                    sessionId = payload["sessionId"]?.toString()?.trim('"').orEmpty(),
                    pageIndex = payload["pageIndex"]?.toString()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
                    revisionId = payload["revisionId"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null,
                )
                "loadFailed" -> LoadFailed
                else -> null
            }
        }
    }
}

private class PdfReaderBridge(
    private val webView: HoshiPdfReaderWebView,
    private val onMessage: (PdfReaderMessage) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val parsed = PdfReaderMessage.fromJson(message) ?: return
        webView.post {
            onMessage(parsed)
        }
    }
}

internal const val PdfReaderPageUrl = "https://appassets.androidplatform.net/pdfreader/index.html"

private class PdfReaderWebViewClient(
    context: Context,
    private val pdfFile: File,
    private val popupResourceHandler: () -> ReaderLookupPopupResourceHandler?,
    private val onRendererTerminated: (HoshiPdfReaderWebView) -> Unit,
) : WebViewClient() {
    private val assetBridge = PdfReaderAssetBridge(context.applicationContext)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if ((view as? HoshiPdfReaderWebView)?.isReleased == true) return true
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        return popupResourceHandler()?.handle(uri)
            ?: assetBridge.responseFor(uri, pdfFile)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        val pdfView = view as? HoshiPdfReaderWebView ?: return true
        if (!pdfView.isReleased) {
            onRendererTerminated(pdfView)
        }
        return true
    }
}

private class PdfReaderAssetBridge(
    private val context: Context,
) {
    private val assetCache = mutableMapOf<String, ByteArray>()

    fun responseFor(uri: android.net.Uri, pdfFile: File): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
        val path = uri.path.orEmpty()
        return when {
            path == "/pdfbook/data" -> pdfResponse(pdfFile)
            path.startsWith("/pdfreader/") -> assetResponse("hoshi-web/pdf-reader", path.removePrefix("/pdfreader/"))
            path.startsWith("/pdfjs/") -> assetResponse("hoshi-web/pdfjs", path.removePrefix("/pdfjs/"))
            else -> null
        }
    }

    private fun pdfResponse(pdfFile: File): WebResourceResponse? {
        if (!pdfFile.isFile) return null
        return WebResourceResponse(
            "application/pdf",
            null,
            FileInputStream(pdfFile),
        )
    }

    private fun assetResponse(assetRoot: String, relativePath: String): WebResourceResponse? {
        val safePath = relativePath.trimStart('/')
        if (safePath.isBlank() || safePath.contains("..")) return null
        val bytes = readAsset("$assetRoot/$safePath") ?: return null
        val mimeType = when (safePath.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
        val encoding = if (mimeType == "text/html" || mimeType == "text/css" || mimeType == "application/javascript") {
            "UTF-8"
        } else {
            null
        }
        return WebResourceResponse(
            mimeType,
            encoding,
            bytes.inputStream(),
        )
    }

    private fun readAsset(path: String): ByteArray? {
        assetCache[path]?.let { return it }
        val bytes = runCatching {
            context.assets.open(path).use { input -> input.readBytes() }
        }.getOrNull() ?: return null
        assetCache[path] = bytes
        return bytes
    }
}

private class HoshiPdfReaderWebView(context: Context) : WebView(context) {
    var isReleased = false
        private set

    override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String?>?) {
        if (isReleased) return
        super.evaluateJavascript(script, resultCallback?.let { callback ->
            ValueCallback { result -> if (!isReleased) callback.onReceiveValue(result) }
        })
    }

    var onHighlightChanged: (HighlightColor, String, ReaderHighlightResult) -> Unit = { _, _, _ -> }
    private var nativeSelectionActionModeActive = false
    private var nativeSelectionActionMode: ActionMode? = null
    private var nativeSelectionContentRect: Rect? = null
    private var highlightColorPopup: android.widget.PopupWindow? = null
    private var selectionHighlightPending = false

    fun isNativeSelectionActionModeActive(): Boolean = nativeSelectionActionModeActive
    fun setNativeSelectionActionMode(mode: ActionMode?) {
        nativeSelectionActionMode = mode
        nativeSelectionActionModeActive = mode != null
        if (mode == null) {
            nativeSelectionContentRect = null
            dismissHighlightColorPopup()
        }
    }
    fun setNativeSelectionContentRect(rect: Rect) {
        nativeSelectionContentRect = Rect(rect)
    }
    fun prepareHighlightColorPicker(mode: ActionMode) {
        val anchor = nativeSelectionContentRect?.let { Rect(it) }
        evaluateJavascript(ReaderHighlightCommand.PrepareSelection.source) { result ->
            if (result?.trim() == "true") {
                selectionHighlightPending = true
                mode.finish()
                post { showHighlightColorPicker(anchor) }
            }
        }
    }
    fun showHighlightColorPicker(anchorRect: Rect? = nativeSelectionContentRect) {
        if (isReleased) return
        dismissHighlightColorPopup()
        val density = resources.displayMetrics.density
        val popupContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = 20f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x22000000)
            }
            elevation = 8f * density
            val paddingHorizontal = (ReaderHighlightSelectionMenu.colorPickerHorizontalPaddingDp * density).toInt()
            val paddingVertical = (ReaderHighlightSelectionMenu.colorPickerVerticalPaddingDp * density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            ReaderHighlightSelectionMenu.colorItems.forEach { item ->
                addView(highlightColorButton(item, density))
            }
        }
        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        highlightColorPopup = android.widget.PopupWindow(
            popupContent,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 8f * density
        }
        selectionHighlightPending = false

        val location = IntArray(2)
        getLocationOnScreen(location)
        val margin = (16f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val popupWidth = popupContent.measuredWidth
        val popupHeight = popupContent.measuredHeight
        val anchor = anchorRect?.let {
            ReaderHighlightSelectionAnchor(
                left = it.left,
                top = it.top,
                right = it.right,
                bottom = it.bottom,
            )
        }
        val position = ReaderHighlightSelectionMenu.colorPickerPopupPosition(
            viewLeft = location[0],
            viewTop = location[1],
            viewWidth = width,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            margin = margin,
            anchor = anchor,
        )
        highlightColorPopup?.showAtLocation(this, android.view.Gravity.NO_GRAVITY, position.x, position.y)
    }

    private fun highlightColorButton(item: ReaderHighlightSelectionMenuItem, density: Float): android.widget.TextView {
        val touchTargetSize = (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp * density).toInt()
        val swatchInset = (
            (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp - ReaderHighlightSelectionMenu.colorPickerSwatchSizeDp) *
                density / 2f
            ).toInt()
        val margin = (ReaderHighlightSelectionMenu.colorPickerButtonMarginDp * density).toInt()
        return android.widget.TextView(context).apply {
            background = android.graphics.drawable.InsetDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(item.color.swatchArgb.toInt())
                },
                swatchInset,
            )
            setOnClickListener { createHighlightFromNativeSelection(item.color) }
            layoutParams = LinearLayout.LayoutParams(touchTargetSize, touchTargetSize).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
    }

    fun createHighlightFromNativeSelection(color: HighlightColor) {
        if (isReleased) return
        val mode = nativeSelectionActionMode
        val id = UUID.randomUUID().toString()
        selectionHighlightPending = true
        dismissHighlightColorPopup()
        evaluateJavascript(ReaderHighlightCommand.Create(color, id).source) { result ->
            ReaderHighlightResult.fromWebViewResult(result)?.let { change ->
                onHighlightChanged(color, id, change)
            }
            mode?.finish()
            selectionHighlightPending = false
        }
    }

    private fun dismissHighlightColorPopup() {
        highlightColorPopup?.dismiss()
        highlightColorPopup = null
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(PdfHighlightActionModeCallback(this, callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(PdfHighlightActionModeCallback(this, callback), type)

    fun releaseForDestroy() {
        isReleased = true
        selectionHighlightPending = false
        nativeSelectionActionMode?.finish()
        dismissHighlightColorPopup()
        setNativeSelectionActionMode(null)
        onHighlightChanged = { _, _, _ -> }
    }
}

private class PdfHighlightActionModeCallback(
    private val webView: HoshiPdfReaderWebView,
    private val delegate: ActionMode.Callback,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        val created = delegate.onCreateActionMode(mode, menu)
        if (created) {
            webView.setNativeSelectionActionMode(mode)
            addHighlightMenu(menu)
        }
        return created
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        return delegate.onPrepareActionMode(mode, menu)
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == ReaderHighlightSelectionMenu.parentItemId) {
            webView.prepareHighlightColorPicker(mode)
            return true
        }
        val color = ReaderHighlightSelectionMenu.colorForItemId(item.itemId)
        if (color != null) {
            webView.createHighlightFromNativeSelection(color)
            return true
        }
        return delegate.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        webView.setNativeSelectionActionMode(null)
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
        webView.setNativeSelectionContentRect(outRect)
    }

    private fun addHighlightMenu(menu: Menu) {
        if (menu.findItem(ReaderHighlightSelectionMenu.parentItemId) != null) return
        ReaderHighlightSelectionMenu.actionModeItems.forEach { item ->
            menu.add(
                ReaderHighlightSelectionMenu.groupId,
                item.id,
                item.order,
                webView.context.getString(R.string.reader_highlight_action),
            ).setShowAsAction(item.showAsAction)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
