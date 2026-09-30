package moe.antimony.hoshi.features.reader

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real OCR assets and generated pages only; never opens books or writes preferences. */
@RunWith(AndroidJUnit4::class)
class PdfOcrGeometryWebViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun fittedJapaneseRangesStayCenteredAndHitTestableAcrossZoomAndScroll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.targetContext.assets
        val css = assets.open("hoshi-web/pdf-reader/pdf-reader.css").bufferedReader().use { it.readText() }
        val script = assets.open("hoshi-web/pdf-reader/pdf-ocr-layer.js").bufferedReader().use { it.readText() }
        val loaded = CountDownLatch(1)
        lateinit var webView: WebView
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also {
                    webView = it
                    // WebView percentage-height layout must not use a wrap-content viewport.
                    it.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    it.settings.javaScriptEnabled = true
                }
            })
        }
        composeRule.waitForIdle()
        try {
            instrumentation.runOnMainSync {
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                webView.loadDataWithBaseURL(
                    null,
                    """
                    <!doctype html><html lang="ja"><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>$css</style><script>$script</script>
                    </head><body><div id="hoshi-pdf-viewport">
                    <div id="hoshi-pdf-pages"></div></div></body></html>
                    """.trimIndent(),
                    "text/html", "utf-8", null,
                )
            }
            assertTrue("OCR fixture loaded", loaded.await(15, TimeUnit.SECONDS))
            evaluate(webView, geometryScript())
            composeRule.waitUntil(15_000) {
                evaluate(webView, "Boolean(window.geometryResult)") == "true"
            }
            val result = JSONObject(evaluate(webView, "window.geometryResult"))
            assertEquals(result.toString(), "", result.optString("error"))
            assertEquals(result.toString(), 0, result.getJSONArray("failures").length())
            assertEquals("page scale / parent transform / scroll combinations", 8, result.getInt("cases"))
            assertEquals("every character was hit-tested", 152, result.getInt("hits"))
        } finally {
            instrumentation.runOnMainSync { webView.destroy() }
        }
    }

    private fun evaluate(webView: WebView, script: String): String {
        val complete = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { result = it; complete.countDown() }
        }
        assertTrue("JavaScript callback", complete.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun geometryScript(): String = """
        (async () => {
            try {
                const failures = [];
                let cases = 0, hits = 0;
                const check = (condition, label) => { if (!condition) failures.push(label); };
                const near = (actual, expected, label) => check(
                    Number.isFinite(actual) && Math.abs(actual - expected) <= 1,
                    label + ': expected ' + expected + ', actual ' + actual);
                const rectMatches = (actual, expected, label) => {
                    for (const edge of ['left', 'top', 'right', 'bottom', 'width', 'height']) {
                        near(actual[edge], expected[edge], label + ' ' + edge);
                    }
                };
                const text = '\u65e5\u672c\u8a9e\u8aad\u66f8\u6642\u9593\u7a7a';
                // Fixed full-width CJK advances make per-character expectations independent of font metrics.
                const lines = [
                    { name: 'horizontal', text, x: 24, y: 48, w: 144, h: 48, vertical: false },
                    { name: 'vertical', text, x: 120, y: 168, w: 48, h: 192, vertical: true },
                    // The normalized quad is square, but the portrait page makes this a vertical line.
                    { name: 'short portrait', text: '\u65e5\u672c\u8a9e',
                      x: 24, y: 168, w: 36, h: 72, vertical: true }
                ];
                const viewport = document.getElementById('hoshi-pdf-viewport');
                const pages = document.getElementById('hoshi-pdf-pages');
                if (viewport.clientWidth <= 0 || viewport.clientHeight <= 0) {
                    throw new Error('Empty fixture viewport: ' + viewport.clientWidth + 'x' + viewport.clientHeight);
                }
                for (const scale of [1, 2]) for (const pinch of [1, 1.25]) {
                    for (const scrolled of [false, true]) {
                        const label = 'scale=' + scale + ' pinch=' + pinch + ' scrolled=' + scrolled;
                        const w = 240 * scale, h = 480 * scale;
                        pages.replaceChildren();
                        pages.style.cssText = 'display:block;min-width:0;padding:0;' +
                            'width:' + (innerWidth + w + 400) + 'px;' +
                            'height:' + (innerHeight + h + 600) + 'px;transform:scale(' + pinch + ')';
                        const page = document.createElement('div');
                        page.className = 'hoshi-pdf-page';
                        page.style.cssText = 'position:absolute;left:37px;top:83px;margin:0;' +
                            'width:' + w + 'px;height:' + h + 'px';
                        pages.appendChild(page);
                        let canonicalText = '';
                        const spans = lines.map((line, index) => {
                            const startUtf16 = canonicalText.length;
                            canonicalText += line.text;
                            const left = line.x / 240, right = (line.x + line.w) / 240;
                            const top = line.y / 480, bottom = (line.y + line.h) / 480;
                            return { sourceId: String(index), text: line.text, startUtf16,
                                endUtf16: canonicalText.length,
                                quad: { x0: left, y0: top, x1: right, y1: top,
                                    x2: right, y2: bottom, x3: left, y3: bottom } };
                        });
                        const payload = { sessionId: 'geometry', pageIndex: 0, requestId: 1,
                            revisionId: 'fixture', sourceHash: 'a'.repeat(64),
                            cssWidth: 240, cssHeight: 480, canonicalText, spans };
                        const built = window.hoshiPdfOcrLayer.buildOcrDom(document, payload, w, h);
                        page.appendChild(built.layer);
                        await document.fonts.ready;
                        viewport.scrollLeft = scrolled ? 53 : 0;
                        viewport.scrollTop = scrolled ? 127 : 0;
                        window.hoshiPdfOcrLayer.fitOcrDom(document, built);
                        near(viewport.scrollLeft, scrolled ? 53 : 0, label + ' scrollLeft');
                        near(viewport.scrollTop, scrolled ? 127 : 0, label + ' scrollTop');
                        const pageRect = page.getBoundingClientRect();
                        const viewportRect = viewport.getBoundingClientRect();
                        near(pageRect.left, viewportRect.left + 37 * pinch - viewport.scrollLeft,
                            label + ' page origin x');
                        near(pageRect.top, viewportRect.top + 83 * pinch - viewport.scrollTop,
                            label + ' page origin y');
                        near(pageRect.width, w * pinch, label + ' page width');
                        near(pageRect.height, h * pinch, label + ' page height');
                        check(built.pageText === canonicalText, label + ' canonical text');
                        check(built.textNodes.length === lines.length, label + ' line count');
                        check(page.scrollWidth <= w + 1 && page.scrollHeight <= h + 1,
                            label + ' page overflow: ' + page.scrollWidth + 'x' + page.scrollHeight);
                        const range = document.createRange();
                        // Expectations come only from fixture quads, never production rect/projection helpers.
                        const target = (line, start, count) => {
                            const n = Array.from(line.text).length;
                            const advance = (line.vertical ? line.h : line.w) / n;
                            const thickness = Math.min(line.vertical ? line.w : line.h, advance);
                            const origin = page.getBoundingClientRect();
                            const factor = scale * pinch;
                            const left = origin.left + factor * (line.x + (line.vertical
                                ? (line.w - thickness) / 2 : advance * start));
                            const top = origin.top + factor * (line.y + (line.vertical
                                ? advance * start : (line.h - thickness) / 2));
                            const width = factor * (line.vertical ? thickness : advance * count);
                            const height = factor * (line.vertical ? advance * count : thickness);
                            return { left, top, width, height, right: left + width, bottom: top + height };
                        };
                         lines.forEach((line, index) => {
                            const node = built.textNodes[index].node;
                            const chars = Array.from(line.text);
                            const name = label + ' ' + line.name;
                            range.selectNodeContents(node);
                            check(range.getClientRects().length === 1, name + ' must not wrap');
                            rectMatches(range.getBoundingClientRect(), target(line, 0, chars.length), name);
                            let offset = 0;
                            chars.forEach((ch, i) => {
                                range.setStart(node, offset);
                                range.setEnd(node, offset + ch.length);
                                rectMatches(range.getBoundingClientRect(), target(line, i, 1),
                                    name + ' character ' + i);
                                offset += ch.length;
                            });
                        });
                        // Scroll each expected glyph into view; do not let offscreen points skip hit coverage.
                        lines.forEach((line, index) => {
                            const node = built.textNodes[index].node;
                            let offset = 0;
                            Array.from(line.text).forEach((ch, i) => {
                                let expected = target(line, i, 1);
                                viewport.scrollLeft += expected.left + expected.width / 2 -
                                    viewportRect.left - viewport.clientWidth / 2;
                                viewport.scrollTop += expected.top + expected.height / 2 -
                                    viewportRect.top - viewport.clientHeight / 2;
                                expected = target(line, i, 1);
                                const x = expected.left + expected.width * (line.vertical ? 0.5 : 0.25);
                                const y = expected.top + expected.height * (line.vertical ? 0.25 : 0.5);
                                const name = label + ' ' + line.name + ' hit ' + i;
                                check(x > viewportRect.left && x < viewportRect.right &&
                                    y > viewportRect.top && y < viewportRect.bottom &&
                                    x > 0 && x < innerWidth && y > 0 && y < innerHeight,
                                    name + ' probe outside visible viewport: ' + x + ', ' + y);
                                check(document.elementFromPoint(x, y) === node.parentElement,
                                     name + ' expected text element ' + JSON.stringify({ x, y,
                                         viewportWidth: viewport.clientWidth, viewportHeight: viewport.clientHeight,
                                         innerWidth, innerHeight, scroll: viewport.scrollTop,
                                         hit: document.elementFromPoint(x, y)?.outerHTML?.slice(0, 200) }));
                                const caret = document.caretRangeFromPoint(x, y);
                                check(caret && caret.startContainer === node && caret.startOffset === offset,
                                    name + ' expected UTF-16 offset ' + offset + ', actual ' +
                                    (caret ? caret.startOffset : 'null'));
                                range.setStart(node, offset);
                                range.setEnd(node, offset + ch.length);
                                rectMatches(range.getBoundingClientRect(), expected, name + ' after scroll');
                                offset += ch.length;
                                 hits++;
                             });
                         });
                         // Mixed-direction runs can have separate client rects.
                         // Include upright/sideways vertical text and fit the entire
                         // range, then verify a repeat fit does not undo it.
                         built.layer.remove();
                         for (const vertical of [false, true]) {
                             const mixedText = '\u65e5ABC\u05d0\u05d1\u05d2\u672cDEF\u8a9e';
                             const line = { text: mixedText, x: 24, y: 48,
                                 w: vertical ? 36 : 180, h: vertical ? 180 : 36, vertical };
                             const left = line.x / 240, right = (line.x + line.w) / 240;
                             const top = line.y / 480, bottom = (line.y + line.h) / 480;
                             const mixed = window.hoshiPdfOcrLayer.buildOcrDom(document, {
                                 ...payload, canonicalText: mixedText, spans: [{
                                     sourceId: 'mixed', text: mixedText, startUtf16: 0,
                                     endUtf16: mixedText.length,
                                     quad: { x0: left, y0: top, x1: right, y1: top,
                                         x2: right, y2: bottom, x3: left, y3: bottom }
                                 }]
                             }, w, h);
                             page.appendChild(mixed.layer);
                             range.selectNodeContents(mixed.textNodes[0].node);
                             check(range.getClientRects().length > 1,
                                 label + ' mixed-direction fixture must exercise fragmented bounds');
                             for (let pass = 0; pass < 2; pass++) {
                                 window.hoshiPdfOcrLayer.fitOcrDom(document, mixed);
                                 range.selectNodeContents(mixed.textNodes[0].node);
                                 rectMatches(range.getBoundingClientRect(),
                                     target(line, 0, mixedText.length),
                                     label + ' mixed vertical=' + vertical + ' fit=' + pass);
                             }
                             mixed.layer.remove();
                         }
                         cases++;
                    }
                }
                window.geometryResult = { failures, cases, hits };
            } catch (error) {
                window.geometryResult = { error: String(error.stack || error) };
            }
        })();
    """.trimIndent()
}
