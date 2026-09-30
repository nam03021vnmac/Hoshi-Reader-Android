//
//  pdf-ocr-layer.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//
//  PDF OCR text-layer geometry, selectable DOM, range lookup, and
//  revision-aware installation. No recognition runs here: Kotlin owns
//  raster/engine/postprocessing/persistence, this file only projects the
//  canonical normalized page plane to CSS viewport coordinates.
//
//  Coordinate contract: canonical normalized point (u,v) in [0,1] maps to
//  (u * CSS page width, v * CSS page height). CSS viewport dimensions come
//  from pdf.js page views, never device-pixel canvas sizes.
//

(function () {
  'use strict';

  const MAX_SPANS = 2000;
  const MAX_TEXT_LENGTH = 20000;
  const MAX_PAYLOAD_CHARS = 500000;

  function isFiniteNumber(value) {
    return typeof value === 'number' && Number.isFinite(value);
  }

  function quadIsUsable(quad) {
    if (!quad) return false;
    const values = [quad.x0, quad.y0, quad.x1, quad.y1, quad.x2, quad.y2, quad.x3, quad.y3];
    for (const value of values) {
      if (!isFiniteNumber(value)) return false;
      if (value < -0.05 || value > 1.05) return false;
    }
    return true;
  }

  function quadBoundingRect(quad) {
    const xs = [quad.x0, quad.x1, quad.x2, quad.x3];
    const ys = [quad.y0, quad.y1, quad.y2, quad.y3];
    return {
      left: Math.min.apply(null, xs),
      top: Math.min.apply(null, ys),
      right: Math.max.apply(null, xs),
      bottom: Math.max.apply(null, ys),
    };
  }

  /**
   * Classify embedded pdf.js text without an arbitrary minimum character
   * count. Whitespace/control-only items never count as usable. A single
   * legitimate character page stays usable.
   */
  function classifyEmbeddedText(pageText) {
    const text = typeof pageText === 'string' ? pageText : '';
    let usableLength = 0;
    for (const ch of text) {
      const code = ch.codePointAt(0);
      // Ignore whitespace and ISO control characters when deciding usability.
      if (ch.trim() === '' || (code !== undefined && code < 32)) continue;
      // Ignore zero-area/invalid geometry markers carried as object replacement.
      if (code === 0xfffc) continue;
      usableLength += 1;
    }
    const trimmed = text.trim();
    let hasSuspectChars = false;
    for (const ch of trimmed) {
      const code = ch.codePointAt(0);
      if (ch === '�') { hasSuspectChars = true; break; }
      if (code !== undefined && code >= 0xe000 && code <= 0xf8ff) { hasSuspectChars = true; break; }
    }
    const isSingleNumber = /^[0-9０-９]{1,4}$/.test(trimmed);
    return { textLength: usableLength, hasSuspectChars, isSingleNumber };
  }

  function validateOcrPayload(payload) {
    if (!payload || typeof payload !== 'object') return null;
    if (typeof payload.sessionId !== 'string' || !payload.sessionId) return null;
    if (!Number.isSafeInteger(payload.pageIndex) || payload.pageIndex < 0) return null;
    if (!Number.isSafeInteger(payload.requestId)) return null;
    if (typeof payload.revisionId !== 'string' || !payload.revisionId) return null;
    if (typeof payload.sourceHash !== 'string' || payload.sourceHash.length !== 64) return null;
    if (typeof payload.canonicalText !== 'string') return null;
    if (payload.canonicalText.length > MAX_TEXT_LENGTH) return null;
    if (!Array.isArray(payload.spans)) return null;
    if (payload.spans.length > MAX_SPANS) return null;
    if (!isFiniteNumber(payload.cssWidth) || !isFiniteNumber(payload.cssHeight)) return null;
    if (payload.cssWidth <= 0 || payload.cssHeight <= 0) return null;
    for (const span of payload.spans) {
      if (!span || typeof span !== 'object') return null;
      if (typeof span.sourceId !== 'string' || !span.sourceId) return null;
      if (!Number.isSafeInteger(span.startUtf16) || !Number.isSafeInteger(span.endUtf16)) return null;
      if (span.startUtf16 < 0 || span.endUtf16 < span.startUtf16) return null;
      if (span.endUtf16 > payload.canonicalText.length) return null;
      if (span.quad != null && !quadIsUsable(span.quad)) return null;
      // Slice must round-trip: guards against stale offsets after reimport.
      if (typeof span.text === 'string') {
        const expected = payload.canonicalText.slice(span.startUtf16, span.endUtf16);
        if (expected !== span.text) return null;
      }
    }
    return payload;
  }

  function spanDirection(span, cssWidth, cssHeight) {
    if (!span.quad) return 'horizontal';
    const rect = quadBoundingRect(span.quad);
    // Page-pixel comparison: normalized axes have different physical scales
    // on non-square pages. Fall back to normalized comparison only when the
    // caller has no usable page size.
    const width = isFiniteNumber(cssWidth) && cssWidth > 0
      ? (rect.right - rect.left) * cssWidth
      : rect.right - rect.left;
    const height = isFiniteNumber(cssHeight) && cssHeight > 0
      ? (rect.bottom - rect.top) * cssHeight
      : rect.bottom - rect.top;
    if (height > width * 1.8) return 'vertical';
    return 'horizontal';
  }

  /**
   * Build a transparent positioned OCR layer in canonical reading order.
   * DOM text concatenation agrees with the page stream, including explicit
   * paragraph separators. Ruby nodes are never duplicated into the base
   * stream here: v1 keeps ruby inside the base text.
   */
  function buildOcrDom(document, payload, cssWidth, cssHeight) {
    const valid = validateOcrPayload(payload);
    if (!valid) return null;
    const width = isFiniteNumber(cssWidth) && cssWidth > 0 ? cssWidth : valid.cssWidth;
    const height = isFiniteNumber(cssHeight) && cssHeight > 0 ? cssHeight : valid.cssHeight;
    const layer = document.createElement('p');
    layer.className = 'hoshi-pdf-text-layer textLayer hoshi-pdf-ocr-layer';
    layer.dataset.pageIndex = String(valid.pageIndex);
    layer.dataset.ocrRevision = valid.revisionId;

    const textNodes = [];

    // Group spans by paragraph breaks encoded in canonicalText '\n'.
    const canonical = valid.canonicalText;
    const byStart = new Map();
    for (const span of valid.spans) {
      if (!byStart.has(span.startUtf16)) byStart.set(span.startUtf16, []);
      byStart.get(span.startUtf16).push(span);
    }
    // Walk canonical text in order, emitting span slices and '\n' separators.
    let cursor = 0;
    const sortedSpans = [...valid.spans].sort((a, b) => a.startUtf16 - b.startUtf16 || a.endUtf16 - b.endUtf16);
    for (const span of sortedSpans) {
      if (span.startUtf16 < cursor) continue;
      if (span.startUtf16 > cursor) {
        // Paragraph separator or gap: emit the raw slice so DOM and stream agree.
        const gap = canonical.slice(cursor, span.startUtf16);
        if (gap) {
          if (gap === '\n') {
            const br = document.createElement('br');
            layer.appendChild(br);
            // Never rely on <br> for an offset: record an explicit newline node.
            const newlineHolder = document.createElement('span');
            newlineHolder.textContent = '\n';
            newlineHolder.setAttribute && newlineHolder.setAttribute('data-hoshi-ocr-newline', '1');
            layer.appendChild(newlineHolder);
            textNodes.push({ node: newlineHolder.firstChild, start: cursor, end: cursor + 1 });
          } else {
            pushTextWithGeometry(document, layer, textNodes, gap, null, width, height);
          }
        }
        cursor = span.startUtf16;
      }
      const slice = canonical.slice(span.startUtf16, span.endUtf16);
      pushTextWithGeometry(document, layer, textNodes, slice, span, width, height);
      cursor = span.endUtf16;
    }
    if (cursor < canonical.length) {
      pushTextWithGeometry(document, layer, textNodes, canonical.slice(cursor), null, width, height);
    }

    return { layer, textNodes, pageText: layer.textContent, cssWidth: width, cssHeight: height };
  }

  function pushTextWithGeometry(document, layer, textNodes, text, span, width, height) {
    if (!text.length) return;
    const element = document.createElement('span');
    // OCR strings become DOM textContent, never HTML/script fragments.
    element.textContent = text;
    const direction = span ? spanDirection(span, width, height) : 'horizontal';
    const rect = positionSpan(element, span ? span.quad : null, width, height, text, direction);
    if (direction === 'vertical') {
      element.style.writingMode = 'vertical-rl';
    }
    layer.appendChild(element);
    const start = textNodes.length ? textNodes[textNodes.length - 1].end : 0;
    // Mirror the pdf-reader walker shape: text node + half-open offsets.
    const textNode = element.firstChild;
    const end = start + text.length;
    textNodes.push({ node: textNode, start, end, rect });
  }

  function positionSpan(element, quad, width, height, text, direction) {
    if (!quad) {
      element.style.position = 'absolute';
      return;
    }
    const rect = quadBoundingRect(quad);
    let left = rect.left * width;
    let top = rect.top * height;
    const spanWidth = Math.max(1, (rect.right - rect.left) * width);
    const spanHeight = Math.max(1, (rect.bottom - rect.top) * height);
    const isVertical = direction === 'vertical';
    // DB unclipping recovers the recognition crop, not character metrics.
    // A long line's expanded thickness must not enlarge every glyph advance.
    const length = Math.max(1, [...text].length);
    const fontSize = Math.min(isVertical ? spanWidth : spanHeight,
      (isVertical ? spanHeight : spanWidth) / length);
    if (isVertical) left += (spanWidth - fontSize) / 2;
    else top += (spanHeight - fontSize) / 2;
    element.style.position = 'absolute';
    element.style.left = left + 'px';
    element.style.top = top + 'px';
    element.style.width = 'max-content';
    element.style.height = 'max-content';
    element.style.fontSize = fontSize + 'px';
    element.style.lineHeight = '1';
    element.style.whiteSpace = 'pre';
    element.style.color = 'transparent';
    return { left, top, width: isVertical ? fontSize : spanWidth, height: isVertical ? spanHeight : fontSize };
  }

  /**
   * Fit actual selectable glyph bounds only after the layer is attached.
   * Never throws: fitting only tightens selection geometry and must not
   * break page installation when measurement APIs are unavailable.
   */
  function fitOcrDom(document, built) {
    try {
      if (!document || typeof document.createRange !== 'function') return;
      if (!built || !built.layer || !Array.isArray(built.textNodes)) return;
      if (typeof built.layer.getBoundingClientRect !== 'function') return;
      const parts = built.textNodes.filter(part => part.rect && part.node.parentElement);
      // Measure unfitted text, including on a repeated fit. A transform computed
      // from already scaled bounds would discard the original fit.
      for (const part of parts) part.node.parentElement.style.transform = '';
      const range = document.createRange();
      // A line can have separate directional or fallback-font runs.
      // The first client rect is only one run, not the selectable line's bounds.
      // Batch all reads before writing transforms to avoid a layout per line.
      const measured = parts.map(part => {
        range.selectNodeContents(part.node);
        const bounds = range.getBoundingClientRect();
        if (!bounds || bounds.width <= 0 || bounds.height <= 0) return null;
        const element = part.node.parentElement;
        return { element, target: part.rect, bounds, origin: element.getBoundingClientRect() };
      }).filter(Boolean);
      if (!measured.length) return;
      const page = built.layer.getBoundingClientRect();
      const zoomX = page.width / built.cssWidth;
      const zoomY = page.height / built.cssHeight;
      if (!(zoomX > 0 && zoomY > 0)) return;
      for (const { element, target, bounds, origin } of measured) {
        const sx = target.width * zoomX / bounds.width;
        const sy = target.height * zoomY / bounds.height;
        if (!isFiniteNumber(sx) || !isFiniteNumber(sy) || sx <= 0 || sy <= 0) continue;
        const dx = -(bounds.left - origin.left) / zoomX * sx;
        const dy = -(bounds.top - origin.top) / zoomY * sy;
        if (!isFiniteNumber(dx) || !isFiniteNumber(dy)) continue;
        element.style.transform = `matrix(${sx}, 0, 0, ${sy}, ${dx}, ${dy})`;
      }
    } catch {
      // Measurement is best-effort: the unfitted overlay still installs.
    }
  }

  /**
   * Range-to-geometry projection for tap anchoring and highlight quads when
   * browser font metrics cannot align precisely with the scan. Spans are
   * whole detection lines, so a partial-line range is subdivided along the
   * reading direction instead of returning the full line box.
   */
  function projectRangeToCssRect(spans, startUtf16, endUtf16, cssWidth, cssHeight) {
    if (!Array.isArray(spans) || spans.length === 0) return null;
    if (!Number.isSafeInteger(startUtf16) || !Number.isSafeInteger(endUtf16)) return null;
    if (startUtf16 < 0 || endUtf16 <= startUtf16) return null;
    let left = Infinity;
    let top = Infinity;
    let right = -Infinity;
    let bottom = -Infinity;
    let hit = false;
    for (const span of spans) {
      if (!span || span.endUtf16 <= startUtf16 || span.startUtf16 >= endUtf16) continue;
      const sub = subdivideSpanRect(span, startUtf16, endUtf16, cssWidth, cssHeight);
      if (!sub) continue;
      left = Math.min(left, sub.left * cssWidth);
      top = Math.min(top, sub.top * cssHeight);
      right = Math.max(right, sub.right * cssWidth);
      bottom = Math.max(bottom, sub.bottom * cssHeight);
      hit = true;
    }
    if (!hit) return null;
    return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) };
  }

  /**
   * Tight normalized rect for the overlap of [rangeStart, rangeEnd) with one
   * line span. Character widths are estimated uniformly along the reading
   * direction (exact for full-width CJK, approximate otherwise); without a
   * matching span text the full line box is returned as before.
   */
  function subdivideSpanRect(span, rangeStart, rangeEnd, cssWidth, cssHeight) {
    const quad = span && span.quad;
    if (!quad || !quadIsUsable(quad)) return null;
    const rect = quadBoundingRect(quad);
    const spanLen = span.endUtf16 - span.startUtf16;
    const localStart = Math.max(rangeStart, span.startUtf16) - span.startUtf16;
    const localEnd = Math.min(rangeEnd, span.endUtf16) - span.startUtf16;
    if (localEnd <= localStart) return null;
    const text = typeof span.text === 'string' ? span.text : null;
    if (!text || text.length !== spanLen || spanLen <= 0) return rect;
    const f0 = localStart / spanLen;
    const f1 = localEnd / spanLen;
    const vertical = spanDirection(span, cssWidth, cssHeight) === 'vertical';
    if (vertical) {
      return {
        left: rect.left,
        right: rect.right,
        top: rect.top + (rect.bottom - rect.top) * f0,
        bottom: rect.top + (rect.bottom - rect.top) * f1,
      };
    }
    return {
      left: rect.left + (rect.right - rect.left) * f0,
      right: rect.left + (rect.right - rect.left) * f1,
      top: rect.top,
      bottom: rect.bottom,
    };
  }

  /**
   * Remove nested partial-range highlight marks, restoring plain text nodes.
   * Returns true when nodes were unwrapped and the caller must rebuild
   * its text-node offsets.
   */
  function clearHighlightMarks(document, layer) {
    if (!layer || typeof layer.querySelectorAll !== 'function') return false;
    const nested = [];
    const marks = layer.querySelectorAll('span[data-hoshi-highlight-id]');
    for (const mark of marks) {
      if (mark && mark.parentElement !== layer) nested.push(mark);
    }
    for (const mark of nested) {
      const parent = mark.parentNode;
      if (!parent) continue;
      while (mark.firstChild) parent.insertBefore(mark.firstChild, mark);
      parent.removeChild(mark);
    }
    if (nested.length === 0) return false;
    if (typeof layer.normalize === 'function') layer.normalize();
    return true;
  }

  /** Re-walk text nodes in document order with sequential UTF-16 offsets. */
  function rebuildTextNodes(document, layer) {
    const entries = [];
    const walker = document.createTreeWalker(layer, 4 /* NodeFilter.SHOW_TEXT */);
    let offset = 0;
    let node = null;
    while ((node = walker.nextNode())) {
      const length = node.textContent ? node.textContent.length : 0;
      entries.push({ node, start: offset, end: offset + length });
      offset += length;
    }
    return entries;
  }

  /**
   * Paint one highlight entry, splitting text nodes at partial overlaps so
   * only the selected run gets a background. Paint inline glyph boxes, never
   * the positioned line's layout box. Mutates `textNodes` to track splits;
   * concatenated text and offsets are unchanged.
   *
   * entry: { id, offset, text, background }.
   */
  function paintHighlightEntry(document, layer, textNodes, entry) {
    if (!document || !layer || !Array.isArray(textNodes) || !entry) return;
    if (typeof entry.offset !== 'number' || typeof entry.text !== 'string' || !entry.text.length) return;
    const end = entry.offset + entry.text.length;
    let index = 0;
    while (index < textNodes.length) {
      const part = textNodes[index];
      if (part.start >= end) break;
      if (part.end <= entry.offset || part.end === part.start) {
        index += 1;
        continue;
      }
      index = paintHighlightPart(document, textNodes, index, entry, end, layer);
    }
  }

  function paintHighlightPart(document, textNodes, index, entry, end, layer) {
    const part = textNodes[index];
    const node = part.node;
    const nodeLength = node && typeof node.textContent === 'string' ? node.textContent.length : 0;
    const span = node ? node.parentElement : null;
    if (!span || !span.dataset) return index + 1;
    const localStart = Math.max(entry.offset, part.start) - part.start;
    const localEnd = Math.min(end, part.end) - part.start;
    // A previous highlight may have split this node away from other line text.
    if (localStart <= 0 && localEnd >= nodeLength &&
        (!layer.dataset.ocrRevision || span.dataset.hoshiHighlightId) &&
        nodeLength === span.textContent.length) {
      span.dataset.hoshiHighlightId = entry.id;
      if (span.style) span.style.backgroundColor = entry.background;
      return index + 1;
    }
    const start = Math.max(0, localStart);
    const finish = Math.min(nodeLength, localEnd);
    if (finish <= start) return index + 1;
    const parent = node.parentNode;
    if (!parent || typeof node.splitText !== 'function') {
      return index + 1;
    }
    const tail = node.splitText(finish);
    const middle = node.splitText(start);
    const mark = document.createElement('span');
    mark.dataset.hoshiHighlightId = entry.id;
    if (mark.style) {
      mark.style.backgroundColor = entry.background;
      // Line spans are absolutely positioned; keep the nested run inline so
      // only the selected run paints instead of the full line box.
      mark.style.position = 'static';
    }
    parent.insertBefore(mark, middle);
    mark.appendChild(middle);
    const fresh = [];
    if (start > 0) fresh.push({ node, start: part.start, end: part.start + start });
    fresh.push({ node: middle, start: part.start + start, end: part.start + finish });
    if (finish < nodeLength) fresh.push({ node: tail, start: part.start + finish, end: part.end });
    textNodes.splice(index, 1, ...fresh);
    return index + fresh.length;
  }

  const api = {
    classifyEmbeddedText,
    validateOcrPayload,
    buildOcrDom,
    fitOcrDom,
    projectRangeToCssRect,
    subdivideSpanRect,
    clearHighlightMarks,
    rebuildTextNodes,
    paintHighlightEntry,
    MAX_SPANS,
    MAX_TEXT_LENGTH,
    MAX_PAYLOAD_CHARS,
  };

  if (typeof window !== 'undefined') {
    window.hoshiPdfOcrLayer = api;
  }
  if (typeof globalThis !== 'undefined') {
    globalThis.hoshiPdfOcrLayer = api;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})();
