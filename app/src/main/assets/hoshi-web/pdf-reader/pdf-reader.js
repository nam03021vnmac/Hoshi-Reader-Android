//
//  pdf-reader.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//
//  PDF reader page runtime. Renders PDF pages through pdf.js with a
//  selectable text layer, lazy page windows, zoom (fit-width, buttons,
//  pinch, double-tap), page jump, progress reporting, and highlight marks.

import * as pdfjsLib from '/pdfjs/pdf.min.mjs';

const PDF_TO_CSS_UNITS = pdfjsLib.PixelsPerInch.PDF_TO_CSS_UNITS;
const PAGE_GAP_CSS_PX = 12;
const RENDER_MARGIN_PAGES = 2;
const ZOOM_STEP = 1.25;
const ZOOM_MIN = 0.25;
const ZOOM_MAX = 8.0;
// Android ViewConfiguration double-tap timeout and slop (CSS px ~ dp).
const DOUBLE_TAP_TIMEOUT_MS = 300;
const DOUBLE_TAP_SLOP_PX = 100;
// Relative to the tapped page's own fit width.
const DOUBLE_TAP_ZOOM = 2.5;
const MAX_CANVAS_PIXELS = 4 * 1024 * 1024;
const MAX_CANVAS_DIMENSION = 8192;

pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdfjs/pdf.worker.min.mjs';

const viewport = document.getElementById('hoshi-pdf-viewport');
const pagesRoot = document.getElementById('hoshi-pdf-pages');

const state = {
  pdf: null,
  pageCount: 0,
  zoomFactor: 1.0, // 1 = fit width
  continuousScroll: true,
  pageSwipeThresholdPx: 72,
  fitWidthScale: 1.0,
  pages: [], // index -> page record
  visibleFirst: -1,
  visibleLast: -1,
  currentPageIndex: -1,
  opening: false,
  ready: false,
  failed: false,
  activePages: new Set(),
  preparedSelection: null,
  highlightIndex: new Map(), // "page:UTF-16 offset:length" -> highlight
  pinch: null,
  sessionId: '',
  ocrByPage: new Map(), // pageIndex -> validated OCR payload
  ocrRequestByPage: new Map(), // pageIndex -> latest requestId
};

function postMessage(message) {
  const bridge = window.HoshiPdfReader || globalThis.HoshiPdfReader;
  bridge?.postMessage?.(JSON.stringify(message));
}

function pageRecord(index) {
  let record = state.pages[index];
  if (!record) {
    record = {
      index,
      div: null,
      canvas: null,
      textLayerDiv: null,
      textLayer: null,
      textNodes: [],
      pageText: '',
      textSource: 'embedded',
      ocrRevisionId: null,
      highlightLayer: null,
      page: null,
      renderTask: null,
      stale: false,
      baseWidth: 0,
      baseHeight: 0,
      rendered: false,
      embeddedProbe: null,
      ocrPayload: null,
    };
    state.pages[index] = record;
  }
  return record;
}

function pageDiv(record) {
  if (record.div) return record.div;
  const div = document.createElement('div');
  div.className = 'hoshi-pdf-page';
  div.dataset.pageIndex = String(record.index);
  pagesRoot.appendChild(div);
  record.div = div;
  applyPageSize(record);
  return div;
}

function currentCssScale() {
  return state.fitWidthScale * state.zoomFactor;
}

function viewportFor(page, scale) {
  return page.getViewport({ scale: scale * PDF_TO_CSS_UNITS });
}

function baseViewportFor(page) {
  return page.getViewport({ scale: PDF_TO_CSS_UNITS });
}

function applyPageSize(record) {
  if (!record.div || record.baseWidth <= 0) return;
  record.div.style.display = state.continuousScroll || record.index === state.currentPageIndex ? '' : 'none';
  const scale = currentCssScale();
  const width = record.baseWidth * scale;
  record.div.style.width = `${width}px`;
  record.div.style.aspectRatio = `${record.baseWidth} / ${record.baseHeight}`;
}

async function loadPageBase(pageNumber) {
  const record = pageRecord(pageNumber - 1);
  if (record.baseWidth > 0) return record;
  const page = await state.pdf.getPage(pageNumber);
  const viewport = baseViewportFor(page);
  record.baseWidth = viewport.width;
  record.baseHeight = viewport.height;
  applyPageSize(record);
  return record;
}

async function buildPageLayout() {
  for (let pageNumber = 1; pageNumber <= state.pageCount; pageNumber += 1) {
    const record = await loadPageBase(pageNumber);
    pageDiv(record);
  }
}

async function computeFitWidthScale() {
  if (!state.pdf) return;
  const page = await state.pdf.getPage(1);
  const base = baseViewportFor(page);
  const available = Math.max(1, viewport.clientWidth);
  state.fitWidthScale = available / Math.max(1, base.width);
}

function layoutAllPages() {
  for (let index = 0; index < state.pageCount; index += 1) {
    applyPageSize(pageRecord(index));
  }
}

function cancelRenderTask(record) {
  const task = record.renderTask;
  if (!task) return;
  task.cancelled = true;
  task.canvasTask?.cancel();
  record.renderTask = null;
  record.textLayer?.cancel?.();
  record.textLayer = null;
  record.page?.cleanup();
  record.page = null;
}

function renderScaleChanged() {
  postMessage({ type: 'viewportChanged' });
  state.preparedSelection = null;
  window.hoshiSelection?.clearSelection();
  layoutAllPages();
  // Cancel in-flight work (it has no pixels on screen yet) but keep finished
  // pages showing until their new scale renders: discarding first is what
  // flashed empty and opaque-black fresh canvases on every zoom step.
  for (const index of state.activePages) {
    const record = pageRecord(index);
    cancelRenderTask(record);
    record.stale = true;
    renderPage(index);
  }
}

function visibleRange() {
  if (state.pageCount === 0) return { first: 0, last: 0 };
  if (!state.continuousScroll) {
    const index = Math.max(0, state.currentPageIndex);
    return { first: index, last: index };
  }
  const scrollTop = viewport.scrollTop;
  const clientHeight = Math.max(1, viewport.clientHeight);
  const topBound = scrollTop;
  const bottomBound = scrollTop + clientHeight;
  let low = 0;
  let high = state.pageCount - 1;
  while (low < high) {
    const middle = (low + high) >>> 1;
    const div = state.pages[middle].div;
    if (div.offsetTop + div.offsetHeight + PAGE_GAP_CSS_PX < topBound) low = middle + 1;
    else high = middle;
  }
  const first = low;
  let last = first;
  while (last + 1 < state.pageCount && state.pages[last + 1].div.offsetTop <= bottomBound) last += 1;
  return { first, last };
}

function scheduleRender(first, last) {
  const previousFirst = state.visibleFirst;
  const previousLast = state.visibleLast;
  state.visibleFirst = first;
  state.visibleLast = last;
  postMessage({ type: 'ocrViewport', sessionId: state.sessionId, firstVisible: first, lastVisible: last });
  // Kotlin/disk owns long-lived results. Keep only the adjacent working set here.
  for (const index of state.ocrByPage.keys()) {
    if (index < first - 1 || index > last + 1) {
      state.ocrByPage.delete(index);
      state.ocrRequestByPage.delete(index);
    }
  }
  const low = Math.max(0, first - RENDER_MARGIN_PAGES);
  const high = Math.min(state.pageCount - 1, last + RENDER_MARGIN_PAGES);
  for (const index of state.activePages) {
    if (index < low || index > high) discardPage(index);
  }
  for (let index = low; index <= high; index += 1) {
    const record = state.pages[index];
    if (index >= first - 1 && index <= last + 1 &&
        (index < previousFirst - 1 || index > previousLast + 1) && record?.rendered && record.embeddedProbe) {
      postMessage({ ...record.embeddedProbe, sessionId: state.sessionId });
    }
    renderPage(index);
  }
}

function renderPage(index) {
  if (!state.pdf) return;
  const record = pageRecord(index);
  if (record.renderTask) return;
  if (record.rendered && !record.stale) return;
  const scale = currentCssScale();
  const task = { cancelled: false, canvasTask: null };
  record.renderTask = task;
  state.activePages.add(index);
  (async () => {
    try {
      await loadPageBase(index + 1);
      if (task.cancelled) return;
      const page = await state.pdf.getPage(index + 1);
      if (task.cancelled) return;
      record.page = page;
      const viewport = viewportFor(page, scale);
      const div = pageDiv(record);
      // Render into a detached canvas so the previous scale keeps showing
      // until the new pixels are ready to swap in atomically.
      const canvas = document.createElement('canvas');
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      const outputScale = Math.min(
        window.devicePixelRatio || 1,
        Math.sqrt(MAX_CANVAS_PIXELS / (viewport.width * viewport.height)),
        MAX_CANVAS_DIMENSION / viewport.width,
        MAX_CANVAS_DIMENSION / viewport.height,
      );
      const renderViewport = viewport.clone({ scale: viewport.scale * outputScale });
      const context = canvas.getContext('2d', { alpha: false });
      canvas.width = Math.max(1, Math.floor(renderViewport.width));
      canvas.height = Math.max(1, Math.floor(renderViewport.height));
      task.canvasTask = page.render({
        canvasContext: context,
        viewport: renderViewport,
      });
      await task.canvasTask.promise;
      if (task.cancelled) return;
      await renderTextLayer(record, page, viewport);
      if (task.cancelled || record.renderTask !== task) return;
      record.renderTask = null;
      if (Math.abs(scale - currentCssScale()) > 0.0001) {
        // Zoom moved on mid-render: the pixels just produced are already
        // stale, so keep showing the previous canvas and render again.
        record.stale = true;
        renderPage(index);
        return;
      }
      record.canvas?.remove();
      record.canvas = canvas;
      div.insertBefore(canvas, record.textLayerDiv);
      record.rendered = true;
      record.stale = false;
      applyHighlightsToPage(record);
      reportReady();
    } catch (error) {
      if (!task.cancelled) {
        discardPage(index);
        state.failed = true;
        postMessage({ type: 'loadFailed' });
      }
    } finally {
      if (record.renderTask === task) record.renderTask = null;
    }
  })();
}

async function renderTextLayer(record, page, viewport) {
  // A cached OCR revision survives zoom/discard: reinstall it instead of the
  // embedded layer so the selectable overlay never flashes back to empty.
  const cachedOcr = state.ocrByPage.get(record.index);
  if (cachedOcr && cachedOcr.sessionId === state.sessionId) {
    const installed = installOcrPayload(record, cachedOcr, viewport);
    if (installed) return;
  }
  // Build detached so the previous spans keep showing until the new layer
  // is ready to swap in atomically.
  // A paragraph scopes shared lookup/sentence traversal to this PDF page.
  const div = document.createElement('p');
  div.className = 'hoshi-pdf-text-layer textLayer';
  div.dataset.pageIndex = String(record.index);
  div.style.setProperty('--scale-factor', String(viewport.scale));
  let streamSource = null;
  let extractionFailed = false;
  try {
    streamSource = page.streamTextContent();
  } catch {
    extractionFailed = true;
  }
  if (extractionFailed || !streamSource) {
    record.textLayerDiv?.remove();
    record.textLayerDiv = div;
    record.div.appendChild(div);
    record.textNodes = [];
    record.pageText = '';
    record.textSource = 'embedded';
    record.ocrRevisionId = null;
    probeEmbeddedText(record, extractionFailed);
    return;
  }
  const textLayer = new pdfjsLib.TextLayer({
    textContentSource: streamSource,
    container: div,
    viewport,
  });
  record.textLayer = textLayer;
  try {
    await textLayer.render();
  } catch {
    if (record.textLayer !== textLayer) return;
    record.textLayer = null;
    record.textLayerDiv?.remove();
    record.textLayerDiv = div;
    record.div.appendChild(div);
    record.textNodes = [];
    record.pageText = '';
    record.textSource = 'embedded';
    record.ocrRevisionId = null;
    probeEmbeddedText(record, true);
    return;
  }
  if (record.textLayer !== textLayer) return;
  record.textLayerDiv?.remove();
  record.textLayerDiv = div;
  record.div.appendChild(div);
  // DOM text concatenation deliberately gives PDF line-break elements no offset.
  const walker = document.createTreeWalker(div, NodeFilter.SHOW_TEXT);
  let offset = 0;
  let node;
  record.textNodes = [];
  while ((node = walker.nextNode())) {
    record.textNodes.push({ node, start: offset, end: offset + node.textContent.length });
    offset += node.textContent.length;
  }
  record.pageText = div.textContent;
  record.textSource = 'embedded';
  record.ocrRevisionId = null;
  probeEmbeddedText(record, false);
}

function probeEmbeddedText(record, extractionFailed) {
  try {
    const classifier = globalThis.hoshiPdfOcrLayer?.classifyEmbeddedText;
    const verdict = classifier ? classifier(record.pageText) : { textLength: (record.pageText || '').trim().length, hasSuspectChars: false, isSingleNumber: false };
    record.embeddedProbe = {
      type: 'textProbe',
      sessionId: state.sessionId,
      pageIndex: record.index,
      textLength: verdict.textLength,
      hasSuspectChars: !!verdict.hasSuspectChars,
      isSingleNumber: !!verdict.isSingleNumber,
      extractionFailed: !!extractionFailed,
      cssWidth: record.baseWidth,
      cssHeight: record.baseHeight,
    };
    postMessage(record.embeddedProbe);
  } catch {
    // Probing never breaks rendering.
  }
}

function installOcrPayload(record, payload, viewport) {
  if (!state.continuousScroll && record.index !== state.currentPageIndex) return false;
  const installStarted = globalThis.performance?.now?.();
  const layerApi = globalThis.hoshiPdfOcrLayer;
  if (!layerApi || !record.div) return false;
  const baseWidth = record.baseWidth > 0 ? record.baseWidth : payload.cssWidth;
  const baseHeight = record.baseHeight > 0 ? record.baseHeight : payload.cssHeight;
  if (!Number.isFinite(baseWidth) || !Number.isFinite(baseHeight) || baseWidth <= 0 || baseHeight <= 0) return false;
  // The page div displays at base dimensions scaled to fit width/zoom, while
  // the stored payload keeps unscaled base dimensions for geometry parity.
  // Build the selectable overlay at the currently displayed CSS size so taps
  // hit the visible glyphs; canvas pixels already use this scaled viewport.
  const scale = currentCssScale();
  if (!Number.isFinite(scale) || scale <= 0) return false;
  const cssWidth = baseWidth * scale;
  const cssHeight = baseHeight * scale;
  const built = layerApi.buildOcrDom(document, payload, cssWidth, cssHeight);
  if (!built) return false;
  built.layer.style.setProperty('--scale-factor', String(scale));
  record.textLayer?.cancel?.();
  record.textLayer = null;
  record.textLayerDiv?.remove();
  record.textLayerDiv = built.layer;
  record.div.appendChild(built.layer);
  layerApi.fitOcrDom(document, built);
  record.textNodes = built.textNodes;
  record.pageText = built.pageText;
  record.textSource = 'ocr';
  record.ocrRevisionId = payload.revisionId;
  record.ocrPayload = payload;
  applyHighlightsToPage(record);
  postMessage({ type: 'ocrInstalled', sessionId: state.sessionId, pageIndex: record.index, revisionId: payload.revisionId });
  if (Number.isFinite(installStarted)) {
    console.debug(`PdfOcr timing page=${record.index} domMs=${(performance.now() - installStarted).toFixed(1)}`);
  }
  return true;
}

function discardPage(index) {
  const record = state.pages[index];
  if (!record) return;
  if (record.renderTask) {
    record.renderTask.cancelled = true;
    record.renderTask.canvasTask?.cancel();
    record.renderTask = null;
  }
  record.textLayer?.cancel?.();
  record.textLayer = null;
  record.textNodes = [];
  record.pageText = '';
  record.textSource = 'embedded';
  record.ocrRevisionId = null;
  record.ocrPayload = null;
  if (record.textLayerDiv) {
    record.textLayerDiv.remove();
    record.textLayerDiv = null;
  }
  if (record.canvas) {
    record.canvas.width = 0;
    record.canvas.height = 0;
    record.canvas.remove();
    record.canvas = null;
  }
  record.rendered = false;
  record.stale = false;
  record.page?.cleanup();
  record.page = null;
  state.activePages.delete(index);
  // Keep state.ocrByPage cached so a discarded page reloads OCR from the
  // store without rerunning inference.
}

function reportCurrentPage() {
  if (state.pageCount === 0) return;
  const center = viewport.scrollTop + viewport.clientHeight / 2;
  let best = -1;
  let bestDistance = Number.MAX_VALUE;
  for (let index = state.visibleFirst; index <= state.visibleLast; index += 1) {
    const div = state.pages[index]?.div;
    if (!div) continue;
    const distance = Math.max(div.offsetTop - center, center - div.offsetTop - div.offsetHeight, 0);
    if (distance < bestDistance) {
      bestDistance = distance;
      best = index;
    }
  }
  if (best >= 0 && best !== state.currentPageIndex) {
    state.currentPageIndex = best;
    postMessage({
      type: 'pageChanged',
      pageIndex: best,
      pageCount: state.pageCount,
    });
  }
}

function reportReady() {
  if (state.ready || state.failed || state.opening || !state.pages[state.currentPageIndex]?.rendered) return;
  state.ready = true;
  postMessage({ type: 'ready', pageCount: state.pageCount });
}

let scrollReportScheduled = false;
function onScroll() {
  if (!state.pdf || state.opening) return;
  if (!scrollReportScheduled) {
    scrollReportScheduled = true;
    postMessage({ type: 'viewportChanged' });
    requestAnimationFrame(() => {
      scrollReportScheduled = false;
      if (!state.pdf || state.opening) return;
      const { first, last } = visibleRange();
      scheduleRender(first, last);
      flushPendingOcr();
      reportCurrentPage();
      reportReady();
    });
  }
}

function scrollToPage(index) {
  const record = pageRecord(index);
  const div = record.div;
  if (!div) return;
  state.currentPageIndex = index;
  layoutAllPages();
  viewport.scrollLeft = 0;
  viewport.scrollTop = Math.max(0, div.offsetTop - 8);
  postMessage({ type: 'pageChanged', pageIndex: index, pageCount: state.pageCount });
  onScroll();
}

function setViewMode(continuousScroll, pageSwipeThresholdPx = state.pageSwipeThresholdPx) {
  if (Number.isFinite(pageSwipeThresholdPx)) state.pageSwipeThresholdPx = Math.max(0, pageSwipeThresholdPx);
  const continuous = continuousScroll === true;
  if (state.continuousScroll === continuous) return;
  state.continuousScroll = continuous;
  if (!state.pdf || state.opening) return;
  state.preparedSelection = null;
  window.getSelection()?.removeAllRanges();
  window.hoshiSelection?.clearSelection();
  postMessage({ type: 'viewportChanged' });
  scrollToPage(Math.max(0, state.currentPageIndex));
}

function setZoomFactor(factor) {
  if (!Number.isFinite(factor) || !state.pdf || state.opening) return;
  const clamped = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, factor));
  if (Math.abs(clamped - state.zoomFactor) < 0.001) return;
  const anchorIndex = state.currentPageIndex >= 0 ? state.currentPageIndex : 0;
  const anchorRecord = pageRecord(anchorIndex);
  const anchorDiv = anchorRecord.div;
  const anchorFraction = anchorDiv
    ? (viewport.scrollTop - anchorDiv.offsetTop) / Math.max(1, anchorDiv.offsetHeight)
    : 0;
  state.zoomFactor = clamped;
  renderScaleChanged();
  if (anchorDiv) {
    viewport.scrollTop = anchorDiv.offsetTop + anchorFraction * anchorDiv.offsetHeight;
  }
  onScroll();
  postMessage({ type: 'zoomChanged', zoomFactor: state.zoomFactor });
}

function zoomIn() {
  setZoomFactor(state.zoomFactor * ZOOM_STEP);
}

function zoomOut() {
  setZoomFactor(state.zoomFactor / ZOOM_STEP);
}

async function fitWidth() {
  if (!state.pdf || state.opening) return;
  const anchor = state.currentPageIndex;
  await computeFitWidthScale();
  state.zoomFactor = 1.0;
  renderScaleChanged();
  viewport.scrollLeft = 0;
  scrollToPage(anchor);
  postMessage({ type: 'zoomChanged', zoomFactor: state.zoomFactor });
}

function jumpToPage(pageIndex) {
  if (!state.pdf || state.opening || !Number.isFinite(pageIndex)) return;
  state.preparedSelection = null;
  window.getSelection()?.removeAllRanges();
  window.hoshiSelection?.clearSelection();
  const index = Math.min(state.pageCount - 1, Math.max(0, Math.trunc(pageIndex)));
  const record = pageRecord(index);
  pageDiv(record);
  if (record.baseWidth === 0) {
    loadPageBase(index + 1).then(() => scrollToPage(index));
  } else {
    scrollToPage(index);
  }
}

// --- Pinch zoom -------------------------------------------------------------

function pinchFocal() {
  return {
    x: (state.pinch.x1 + state.pinch.x2) / 2,
    y: (state.pinch.y1 + state.pinch.y2) / 2,
  };
}

function pinchDistance() {
  return Math.hypot(state.pinch.x1 - state.pinch.x2, state.pinch.y1 - state.pinch.y2);
}

function pinchScale() {
  const low = ZOOM_MIN / Math.max(ZOOM_MIN, state.zoomFactor);
  const high = ZOOM_MAX / Math.max(ZOOM_MIN, state.zoomFactor);
  return Math.min(high, Math.max(low, pinchDistance() / Math.max(1, state.pinch.startDistance)));
}

function applyPinchPreview() {
  const scale = pinchScale();
  const focal = pinchFocal();
  const contentFocalX = viewport.scrollLeft + focal.x;
  const contentFocalY = viewport.scrollTop + focal.y;
  pagesRoot.style.transformOrigin = `${contentFocalX}px ${contentFocalY}px`;
  pagesRoot.style.transform = `scale(${scale})`;
}

// Page under a viewport-relative point, or the current page when the point
// is outside every visible page row.
function pageIndexAt(point) {
  if (!state.continuousScroll) return state.currentPageIndex;
  const contentY = viewport.scrollTop + point.y;
  const { first, last } = visibleRange();
  for (let index = first; index <= last; index += 1) {
    const div = state.pages[index]?.div;
    if (div && contentY < div.offsetTop + div.offsetHeight + PAGE_GAP_CSS_PX) return index;
  }
  return state.currentPageIndex;
}

// Keeps the content point under the viewport-relative `focal` fixed while the
// zoom factor changes. Shared by pinch release and double-tap zoom. Anchors on
// the page under the focal point: pages are centered, so a page of another
// width moves horizontally by a different amount than the current page.
// `alignPage` snaps the anchor page's left edge to the viewport instead of
// keeping the focal x, so a page zoomed to its fit width fills the screen.
function zoomAtPoint(factor, focal, { alignPage = false } = {}) {
  if (!Number.isFinite(factor) || !state.pdf || state.opening) return;
  const contentFocalX = viewport.scrollLeft + focal.x;
  const contentFocalY = viewport.scrollTop + focal.y;
  const anchorDiv = state.pages[pageIndexAt(focal)]?.div;
  const fractionX = anchorDiv ? (contentFocalX - anchorDiv.offsetLeft) / anchorDiv.offsetWidth : 0;
  const fractionY = anchorDiv ? (contentFocalY - anchorDiv.offsetTop) / anchorDiv.offsetHeight : 0;
  state.zoomFactor = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, factor));
  renderScaleChanged();
  if (anchorDiv) {
    viewport.scrollLeft = alignPage
      ? Math.max(0, anchorDiv.offsetLeft)
      : Math.max(0, anchorDiv.offsetLeft + fractionX * anchorDiv.offsetWidth - focal.x);
    viewport.scrollTop = Math.max(0, anchorDiv.offsetTop + fractionY * anchorDiv.offsetHeight - focal.y);
  }
  onScroll();
  postMessage({ type: 'zoomChanged', zoomFactor: state.zoomFactor });
}

function endPinch() {
  const scale = pinchScale();
  const focal = pinchFocal();
  pagesRoot.style.transform = '';
  state.pinch = null;
  zoomAtPoint(state.zoomFactor * scale, focal);
}

// --- Double-tap zoom ----------------------------------------------------------

// zoomFactor is relative to page 1's fit width. Convert a page's own fit
// width into that factor so pages of any size toggle the same way.
function pageFitFactor(index) {
  const record = state.pages[index];
  if (!(record?.baseWidth > 0) || !(state.fitWidthScale > 0)) return 1.0;
  const factor = Math.max(1, viewport.clientWidth) / record.baseWidth / state.fitWidthScale;
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, factor));
}

// Toggles the tapped page between fitting the screen width and 2.5x of it.
function toggleDoubleTapZoom(point) {
  const fit = pageFitFactor(pageIndexAt(point));
  if (state.zoomFactor > fit * 1.001) {
    zoomAtPoint(fit, point, { alignPage: true });
  } else {
    zoomAtPoint(DOUBLE_TAP_ZOOM * fit, point);
  }
}

// --- Highlights --------------------------------------------------------------

function highlightKey(pageIndex, offset, length) {
  return `${pageIndex}:${offset}:${length}`;
}

function applyHighlightsToPage(record) {
  if (!record?.rendered || !record.textLayerDiv) return;
  // Marks live on the page's own text spans so lookup selection, pinch
  // previews, and re-renders share one highlight source of truth.
  const layerApi = globalThis.hoshiPdfOcrLayer;
  if (layerApi && layerApi.clearHighlightMarks(document, record.textLayerDiv)) {
    record.textNodes = layerApi.rebuildTextNodes(document, record.textLayerDiv);
  }
  const spans = [];
  const seen = new Set();
  for (const part of record.textNodes) {
    const span = part.node.parentElement;
    if (span && !seen.has(span)) {
      seen.add(span);
      spans.push(span);
    }
  }
  for (const span of spans) {
    if (span.dataset) delete span.dataset.hoshiHighlightId;
    if (span.style) span.style.backgroundColor = '';
  }
  for (const entry of state.highlightIndex.values()) {
    if (entry.pageIndex !== record.index) continue;
    const end = entry.offset + entry.text.length;
    if (record.pageText.slice(entry.offset, end) !== entry.text) continue;
    if (layerApi && layerApi.paintHighlightEntry) {
      // Paint only the selected run, not an expanded OCR line's layout box.
      layerApi.paintHighlightEntry(document, record.textLayerDiv, record.textNodes, {
        id: entry.id,
        offset: entry.offset,
        text: entry.text,
        background: entry.background,
      });
      continue;
    }
    for (const part of record.textNodes) {
      if (part.start >= end) break;
      if (part.end <= entry.offset || part.end === part.start) continue;
      const span = part.node.parentElement;
      if (!span?.dataset) continue;
      span.dataset.hoshiHighlightId = entry.id;
      if (span.style) span.style.backgroundColor = entry.background;
    }
  }
}

function applyHighlightIndex(highlights) {
  state.highlightIndex.clear();
  for (const highlight of Array.isArray(highlights) ? highlights : []) {
    if (!highlight || !Number.isSafeInteger(highlight.character) || highlight.character < 0 ||
        !Number.isSafeInteger(highlight.offset) || highlight.offset < 0 ||
        typeof highlight.text !== 'string' || !highlight.text.length ||
        !Number.isSafeInteger(highlight.offset + highlight.text.length)) continue;
    state.highlightIndex.set(highlightKey(highlight.character, highlight.offset, highlight.text.length), {
      id: highlight.id,
      pageIndex: highlight.character,
      offset: highlight.offset,
      text: highlight.text,
      background: highlightBackground(highlight.color),
    });
  }
  for (const index of state.activePages) applyHighlightsToPage(pageRecord(index));
}

function pageRangeForSelection() {
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0) return null;
  const range = selection.getRangeAt(0);
  if (range.collapsed) return null;
  const startElement = range.startContainer.nodeType === Node.TEXT_NODE
    ? range.startContainer.parentElement
    : range.startContainer;
  const layer = startElement?.closest?.('.hoshi-pdf-text-layer');
  if (!layer) return null;
  const endElement = range.endContainer.nodeType === Node.TEXT_NODE
    ? range.endContainer.parentElement : range.endContainer;
  if (endElement?.closest?.('.hoshi-pdf-text-layer') !== layer) return null;
  const pageIndex = Number(layer.dataset.pageIndex);
  const record = state.pages[pageIndex];
  if (!record?.rendered || record.textLayerDiv !== layer) return null;
  const prefix = document.createRange();
  prefix.selectNodeContents(layer);
  prefix.setEnd(range.startContainer, range.startOffset);
  const offset = prefix.toString().length;
  const text = range.toString();
  if (!text.length || record.pageText.slice(offset, offset + text.length) !== text) return null;
  return { pageIndex, offset, text };
}

window.hoshiHighlights = {
  prepareHighlightSelection() {
    state.preparedSelection = pageRangeForSelection();
    return state.preparedSelection != null;
  },
  createHighlight(color, id) {
    const hit = state.preparedSelection || pageRangeForSelection();
    state.preparedSelection = null;
    if (!hit) {
      window.getSelection()?.removeAllRanges();
      return null;
    }
    const key = highlightKey(hit.pageIndex, hit.offset, hit.text.length);
    const existing = state.highlightIndex.get(key);
    window.getSelection()?.removeAllRanges();
    if (existing) {
      const existingId = existing.id;
      if (highlightColorOf(existing.background) === color) {
        state.highlightIndex.delete(key);
        applyHighlightsToPage(pageRecord(hit.pageIndex));
        return { action: 'removed', id: existingId };
      }
      state.highlightIndex.set(key, {
        id: existingId,
        pageIndex: hit.pageIndex,
        offset: hit.offset,
        text: existing.text,
        background: highlightBackground(color),
      });
      applyHighlightsToPage(pageRecord(hit.pageIndex));
      return { action: 'recolored', id: existingId };
    }
    const background = highlightBackground(color);
    state.highlightIndex.set(key, {
      id,
      pageIndex: hit.pageIndex,
      offset: hit.offset,
      text: hit.text,
      background,
    });
    applyHighlightsToPage(pageRecord(hit.pageIndex));
    return { action: 'created', start: hit.pageIndex, offset: hit.offset, text: hit.text };
  },
  removeHighlight(id) {
    for (const [key, entry] of state.highlightIndex.entries()) {
      if (entry.id === id) {
        state.highlightIndex.delete(key);
        applyHighlightsToPage(state.pages[entry.pageIndex]);
      }
    }
    return { action: 'removed', id };
  },
  applyHighlights: applyHighlightIndex,
};

function highlightBackground(color) {
  const palette = {
    yellow: 'rgba(239, 209, 56, 0.35)',
    green: 'rgba(152, 220, 129, 0.35)',
    blue: 'rgba(149, 185, 255, 0.35)',
    pink: 'rgba(255, 155, 180, 0.35)',
    purple: 'rgba(197, 175, 251, 0.35)',
  };
  return palette[color] || palette.yellow;
}

function highlightColorOf(background) {
  const palette = {
    'rgba(239, 209, 56, 0.35)': 'yellow',
    'rgba(152, 220, 129, 0.35)': 'green',
    'rgba(149, 185, 255, 0.35)': 'blue',
    'rgba(255, 155, 180, 0.35)': 'pink',
    'rgba(197, 175, 251, 0.35)': 'purple',
  };
  return palette[background] || 'yellow';
}

// --- Outline (Contents) -------------------------------------------------------

const OUTLINE_MAX_DEPTH = 32;

async function outlinePageIndex(dest) {
  try {
    // Named destinations resolve through getDestination; explicit destination
    // arrays are used directly. Both end in a page reference for getPageIndex.
    const explicit = typeof dest === 'string' ? await state.pdf.getDestination(dest) : dest;
    if (!Array.isArray(explicit) || explicit.length === 0) return -1;
    const pageIndex = await state.pdf.getPageIndex(explicit[0]);
    return Number.isSafeInteger(pageIndex) ? pageIndex : -1;
  } catch {
    return -1;
  }
}

async function resolveOutlineEntry(entry, depth) {
  if (!entry || typeof entry.title !== 'string' || depth > OUTLINE_MAX_DEPTH) return null;
  const children = [];
  for (const child of entry.items || []) {
    const resolved = await resolveOutlineEntry(child, depth + 1);
    if (resolved) children.push(resolved);
  }
  return {
    title: entry.title,
    pageIndex: await outlinePageIndex(entry.dest),
    children,
  };
}

async function reportOutline() {
  try {
    if (!state.pdf) return;
    const outline = await state.pdf.getOutline();
    const items = [];
    for (const entry of outline || []) {
      const resolved = await resolveOutlineEntry(entry, 0);
      if (resolved) items.push(resolved);
    }
    postMessage({ type: 'outline', items });
  } catch {
    postMessage({ type: 'outline', items: [] });
  }
}

// --- Public API ---------------------------------------------------------------

function ocrSelectionTouchesPage(record) {
  const belongs = node => node?.parentElement?.closest?.('.hoshi-pdf-page') === record.div;
  const native = window.getSelection();
  if (native && native.rangeCount > 0 && !native.isCollapsed) {
    const range = native.getRangeAt(0);
    if (belongs(range.startContainer) || belongs(range.endContainer)) return true;
  }
  return state.preparedSelection?.pageIndex === record.index ||
    (window.hoshiSelection?.selection?.ranges || []).some(part => belongs(part.node));
}

function pageHasHighlights(index) {
  for (const highlight of state.highlightIndex.values()) {
    if (highlight.pageIndex === index) return true;
  }
  return false;
}

function flushPendingOcr() {
  for (const index of state.activePages) {
    const record = state.pages[index];
    const payload = state.ocrByPage.get(index);
    if (!record?.rendered || !payload || payload.revisionId === record.ocrRevisionId || ocrSelectionTouchesPage(record)) continue;
    if (record.ocrRevisionId && pageHasHighlights(index)) {
      state.ocrByPage.set(index, record.ocrPayload);
      continue;
    }
    installOcrPayload(record, payload, { scale: currentCssScale() });
  }
}

document.addEventListener('selectionchange', () => requestAnimationFrame(flushPendingOcr));

window.hoshiPdfOcr = {
  selectionCleared() { requestAnimationFrame(flushPendingOcr); },
  applyOcr(payload) {
    const layerApi = globalThis.hoshiPdfOcrLayer;
    if (!layerApi || !payload || typeof payload !== 'object') return false;
    if (typeof payload.sessionId !== 'string' || payload.sessionId !== state.sessionId) return false;
    const valid = layerApi.validateOcrPayload(payload);
    if (!valid) return false;
    if (!Number.isSafeInteger(valid.pageIndex) || valid.pageIndex < 0 || valid.pageIndex >= state.pageCount) return false;
    if (state.visibleFirst >= 0 && (valid.pageIndex < state.visibleFirst - 1 || valid.pageIndex > state.visibleLast + 1)) return false;
    // Revision-aware installation: ignore stale deliveries for a rendered page.
    const record = state.pages[valid.pageIndex];
    const latestRequest = state.ocrRequestByPage.get(valid.pageIndex);
    if (latestRequest !== undefined && valid.requestId < latestRequest) return false;
    state.ocrRequestByPage.set(valid.pageIndex, valid.requestId);
    state.ocrByPage.set(valid.pageIndex, valid);
    if (!record || !record.div || !record.rendered || (!state.continuousScroll && record.index !== state.currentPageIndex)) {
      // Discarded pages keep the cached payload and install it on next render.
      return true;
    }
    if (record.ocrRevisionId && record.ocrRevisionId !== valid.revisionId && pageHasHighlights(record.index)) {
      state.ocrByPage.set(record.index, record.ocrPayload);
      return false;
    }
    if (record.ocrRevisionId !== valid.revisionId && ocrSelectionTouchesPage(record)) {
      // Keep source offsets stable during native selection, lookup, and the color picker.
      return true;
    }
    if (record.ocrRevisionId === valid.revisionId) return true;
    const viewportScale = currentCssScale();
    const fakeViewport = { scale: viewportScale };
    return installOcrPayload(record, valid, fakeViewport);
  },
  clearOcr(pageIndex) {
    if (!Number.isSafeInteger(pageIndex)) return false;
    state.ocrByPage.delete(pageIndex);
    state.ocrRequestByPage.delete(pageIndex);
    const record = state.pages[pageIndex];
    if (!record || !record.div) return true;
    // Re-render embedded text on next paint; keep pixels until then.
    record.stale = true;
    renderPage(pageIndex);
    return true;
  },
};

window.hoshiPdfReader = {
  async open(config = {}) {
    if (state.opening || state.pdf) return;
    state.opening = true;
    state.ready = false;
    state.failed = false;
    state.continuousScroll = config.continuousScroll !== false;
    state.pageSwipeThresholdPx = Number.isFinite(config.pageSwipeThresholdPx) ? Math.max(0, config.pageSwipeThresholdPx) : 72;
    state.sessionId = typeof config.sessionId === 'string' ? config.sessionId : '';
    state.ocrByPage = new Map();
    state.ocrRequestByPage = new Map();
    let loadingTask;
    try {
      const url = new URL(config.url || '/pdfbook/data', window.location.href);
      if (url.origin !== window.location.origin || url.pathname !== '/pdfbook/data' ||
          url.search || url.hash || url.username || url.password) throw new Error('Invalid PDF URL');
      loadingTask = pdfjsLib.getDocument({
        url: url.href,
        cMapUrl: '/pdfjs/cmaps/',
        cMapPacked: true,
        standardFontDataUrl: '/pdfjs/standard_fonts/',
        isEvalSupported: false,
        disableRange: true,
        disableStream: true,
      });
      state.pdf = await loadingTask.promise;
      state.pageCount = state.pdf.numPages;
      await computeFitWidthScale();
      state.zoomFactor = Number.isFinite(config.zoomFactor) && config.zoomFactor > 0
        ? Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, config.zoomFactor))
        : 1.0;
      await buildPageLayout();
      void reportOutline();
      if (config.highlights !== undefined) applyHighlightIndex(config.highlights);
      const initialPage = Math.min(
        state.pageCount - 1,
        Math.max(0, Number.isFinite(config.pageIndex) ? Math.trunc(config.pageIndex) : 0),
      );
      state.currentPageIndex = initialPage;
      state.opening = false;
      scrollToPage(initialPage);
    } catch (error) {
      for (const index of state.activePages) discardPage(index);
      try { await loadingTask?.destroy(); } catch {}
      state.pdf = null;
      state.pages = [];
      state.pageCount = 0;
      state.currentPageIndex = -1;
      state.failed = true;
      pagesRoot.textContent = '';
      postMessage({ type: 'loadFailed' });
    } finally {
      state.opening = false;
    }
  },
  setZoomFactor,
  zoomIn,
  zoomOut,
  fitWidth,
  jumpToPage,
  setViewMode,
  applyHighlights: applyHighlightIndex,
  setSessionId(sessionId) {
    if (typeof sessionId !== 'string' || !sessionId) return;
    if (sessionId === state.sessionId) return;
    state.sessionId = sessionId;
    state.ocrByPage = new Map();
    state.ocrRequestByPage = new Map();
    if (state.visibleFirst >= 0) {
      postMessage({ type: 'ocrViewport', sessionId, firstVisible: state.visibleFirst, lastVisible: state.visibleLast });
      for (const index of state.activePages) {
        const probe = state.pages[index]?.embeddedProbe;
        if (probe) postMessage({ ...probe, sessionId });
      }
    }
  },
  setBackground(color) {
    document.documentElement.style.setProperty('--hoshi-background-color', color || '#ffffff');
  },
  progress() {
    return JSON.stringify({
      pageIndex: state.currentPageIndex,
      pageCount: state.pageCount,
    });
  },
};

// --- Events --------------------------------------------------------------------

viewport.addEventListener('scroll', onScroll, { passive: true });
window.addEventListener('resize', () => {
  if (!state.pdf || state.opening) return;
  const anchor = state.currentPageIndex;
  computeFitWidthScale().then(() => {
    renderScaleChanged();
    scrollToPage(anchor);
  });
});

let touchStarted = null;
let tapCandidate = null;
// A blank-area tap held for the double-tap window. Text taps post at once so
// lookup latency is unchanged; only non-text taps can start a double-tap.
let pendingTap = null;

function clearPendingTap() {
  if (pendingTap) clearTimeout(pendingTap.timer);
  pendingTap = null;
}

function postTap(candidate) {
  postMessage({ type: 'tap', x: candidate.x, y: candidate.y });
}

function flushPendingTap() {
  const held = pendingTap;
  clearPendingTap();
  if (held) postTap(held);
}

function handleTapCandidate(candidate) {
  if (pendingTap?.awaitingSecond) {
    clearPendingTap();
    toggleDoubleTapZoom(candidate);
    return;
  }
  flushPendingTap();
  if (candidate.onText) {
    postTap(candidate);
    return;
  }
  const held = { x: candidate.x, y: candidate.y, awaitingSecond: false, timer: null };
  held.timer = setTimeout(() => {
    if (pendingTap !== held) return;
    pendingTap = null;
    postTap(held);
  }, DOUBLE_TAP_TIMEOUT_MS);
  pendingTap = held;
}

function isTextTarget(target) {
  return !!target?.closest?.('.hoshi-pdf-text-layer span');
}

viewport.addEventListener(
  'touchstart',
  (event) => {
    const touches = event.touches;
    if (touches.length === 2) {
      touchStarted = null;
      tapCandidate = null;
      clearPendingTap();
      if (state.opening || !state.pdf) return;
      postMessage({ type: 'viewportChanged' });
      state.pinch = {
        x1: touches[0].clientX,
        y1: touches[0].clientY,
        x2: touches[1].clientX,
        y2: touches[1].clientY,
        startDistance: Math.hypot(
          touches[0].clientX - touches[1].clientX,
          touches[0].clientY - touches[1].clientY,
        ),
      };
      return;
    }
    if (state.pinch) pagesRoot.style.transform = '';
    state.pinch = null;
    tapCandidate = null;
    touchStarted = null;
    if (touches.length === 1) {
      touchStarted = {
        x: touches[0].clientX,
        y: touches[0].clientY,
        time: Date.now(),
        onText: isTextTarget(event.target),
      };
      tapCandidate = touchStarted;
      if (pendingTap && !pendingTap.awaitingSecond) {
        if (Math.hypot(touchStarted.x - pendingTap.x, touchStarted.y - pendingTap.y) <= DOUBLE_TAP_SLOP_PX) {
          // Like Android's GestureDetector, a second down inside the window
          // retires the first single tap even if this touch becomes a drag.
          clearTimeout(pendingTap.timer);
          pendingTap.awaitingSecond = true;
        } else {
          flushPendingTap();
        }
      }
    } else {
      clearPendingTap();
    }
  },
  { passive: true },
);

viewport.addEventListener(
  'touchmove',
  (event) => {
    if (state.pinch) {
      const touches = event.touches;
      if (touches.length !== 2) return;
      event.preventDefault();
      state.pinch.x1 = touches[0].clientX;
      state.pinch.y1 = touches[0].clientY;
      state.pinch.x2 = touches[1].clientX;
      state.pinch.y2 = touches[1].clientY;
      applyPinchPreview();
      return;
    }
    if (touchStarted && event.touches.length === 1) {
      const dx = Math.abs(event.touches[0].clientX - touchStarted.x);
      const dy = Math.abs(event.touches[0].clientY - touchStarted.y);
      if (dx > 10 || dy > 10) tapCandidate = null;
    }
  },
  { passive: false },
);

viewport.addEventListener(
  'touchend',
  (event) => {
    if (state.pinch) {
      if (event.touches.length < 2) endPinch();
      return;
    }
    if (touchStarted && event.touches.length === 0 && !state.continuousScroll &&
        state.pageSwipeThresholdPx > 0 && Date.now() - touchStarted.time < 600 &&
        !window.getSelection()?.toString() && !window.hoshiSelection?.selection &&
        state.pages[state.currentPageIndex]?.div?.offsetWidth <= viewport.clientWidth + 1) {
      const end = event.changedTouches?.[0];
      const dx = end ? end.clientX - touchStarted.x : 0;
      const dy = end ? end.clientY - touchStarted.y : 0;
      if (Math.abs(dx) >= state.pageSwipeThresholdPx && Math.abs(dx) > Math.abs(dy) * 1.5) {
        jumpToPage(state.currentPageIndex + (dx < 0 ? 1 : -1));
        tapCandidate = null;
      }
    }
    if (tapCandidate && event.touches.length === 0) {
      const candidate = tapCandidate;
      tapCandidate = null;
      const elapsed = Date.now() - candidate.time;
      if (elapsed < 400) handleTapCandidate(candidate);
    }
    if (pendingTap?.awaitingSecond && event.touches.length === 0) clearPendingTap();
    touchStarted = null;
  },
  { passive: true },
);

viewport.addEventListener(
  'touchcancel',
  () => {
    state.pinch = null;
    touchStarted = null;
    tapCandidate = null;
    clearPendingTap();
    pagesRoot.style.transform = '';
  },
  { passive: true },
);

postMessage({ type: 'hostReady' });
