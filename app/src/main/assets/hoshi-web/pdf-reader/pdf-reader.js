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
//  pinch), page jump, progress reporting, and highlight marks.

import * as pdfjsLib from '/pdfjs/pdf.min.mjs';

const PDF_TO_CSS_UNITS = pdfjsLib.PixelsPerInch.PDF_TO_CSS_UNITS;
const PAGE_GAP_CSS_PX = 12;
const RENDER_MARGIN_PAGES = 2;
const ZOOM_STEP = 1.25;
const ZOOM_MIN = 0.25;
const ZOOM_MAX = 8.0;
const MAX_CANVAS_PIXELS = 4 * 1024 * 1024;
const MAX_CANVAS_DIMENSION = 8192;

pdfjsLib.GlobalWorkerOptions.workerSrc = '/pdfjs/pdf.worker.min.mjs';

const viewport = document.getElementById('hoshi-pdf-viewport');
const pagesRoot = document.getElementById('hoshi-pdf-pages');

const state = {
  pdf: null,
  pageCount: 0,
  zoomFactor: 1.0, // 1 = fit width
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
      highlightLayer: null,
      page: null,
      renderTask: null,
      stale: false,
      baseWidth: 0,
      baseHeight: 0,
      rendered: false,
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
  state.visibleFirst = first;
  state.visibleLast = last;
  const low = Math.max(0, first - RENDER_MARGIN_PAGES);
  const high = Math.min(state.pageCount - 1, last + RENDER_MARGIN_PAGES);
  for (const index of state.activePages) {
    if (index < low || index > high) discardPage(index);
  }
  for (let index = low; index <= high; index += 1) renderPage(index);
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
  // Build detached so the previous spans keep showing until the new layer
  // is ready to swap in atomically.
  // A paragraph scopes shared lookup/sentence traversal to this PDF page.
  const div = document.createElement('p');
  div.className = 'hoshi-pdf-text-layer textLayer';
  div.dataset.pageIndex = String(record.index);
  div.style.setProperty('--scale-factor', String(viewport.scale));
  const textLayer = new pdfjsLib.TextLayer({
    textContentSource: page.streamTextContent(),
    container: div,
    viewport,
  });
  record.textLayer = textLayer;
  await textLayer.render();
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
      reportCurrentPage();
      reportReady();
    });
  }
}

function scrollToPage(index) {
  const record = pageRecord(index);
  const div = record.div;
  if (!div) return;
  viewport.scrollTop = Math.max(0, div.offsetTop - 8);
  state.currentPageIndex = index;
  postMessage({ type: 'pageChanged', pageIndex: index, pageCount: state.pageCount });
  onScroll();
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

function endPinch() {
  const scale = pinchScale();
  const focal = pinchFocal();
  const contentFocalX = viewport.scrollLeft + focal.x;
  const contentFocalY = viewport.scrollTop + focal.y;
  pagesRoot.style.transform = '';
  const anchorDiv = state.pages[state.currentPageIndex]?.div;
  const fractionX = anchorDiv ? (contentFocalX - anchorDiv.offsetLeft) / anchorDiv.offsetWidth : 0;
  const fractionY = anchorDiv ? (contentFocalY - anchorDiv.offsetTop) / anchorDiv.offsetHeight : 0;
  state.zoomFactor = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, state.zoomFactor * scale));
  renderScaleChanged();
  if (anchorDiv) {
    viewport.scrollLeft = Math.max(0, anchorDiv.offsetLeft + fractionX * anchorDiv.offsetWidth - focal.x);
    viewport.scrollTop = Math.max(0, anchorDiv.offsetTop + fractionY * anchorDiv.offsetHeight - focal.y);
  }
  state.pinch = null;
  onScroll();
  postMessage({ type: 'zoomChanged', zoomFactor: state.zoomFactor });
}

// --- Highlights --------------------------------------------------------------

function highlightKey(pageIndex, offset, length) {
  return `${pageIndex}:${offset}:${length}`;
}

function applyHighlightsToPage(record) {
  if (!record?.rendered || !record.textLayerDiv) return;
  // Marks live on the page's own text spans so lookup selection, pinch
  // previews, and re-renders share one highlight source of truth.
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

window.hoshiPdfReader = {
  async open(config = {}) {
    if (state.opening || state.pdf) return;
    state.opening = true;
    state.ready = false;
    state.failed = false;
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
  applyHighlights: applyHighlightIndex,
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

viewport.addEventListener(
  'touchstart',
  (event) => {
    const touches = event.touches;
    if (touches.length === 2) {
      touchStarted = null;
      tapCandidate = null;
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
      };
      tapCandidate = touchStarted;
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
    if (tapCandidate && event.touches.length === 0) {
      const candidate = tapCandidate;
      tapCandidate = null;
      const elapsed = Date.now() - candidate.time;
      if (elapsed < 400) {
        postMessage({ type: 'tap', x: candidate.x, y: candidate.y });
      }
    }
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
    pagesRoot.style.transform = '';
  },
  { passive: true },
);

postMessage({ type: 'hostReady' });
