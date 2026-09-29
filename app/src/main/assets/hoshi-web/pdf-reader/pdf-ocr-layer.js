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

  function spanDirection(span) {
    if (!span.quad) return 'horizontal';
    const rect = quadBoundingRect(span.quad);
    const width = rect.right - rect.left;
    const height = rect.bottom - rect.top;
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
    let offset = 0;
    const pushText = (text) => {
      if (!text.length) return;
      const node = document.createTextNode ? document.createTextNode(text) : null;
      // Test harness Element supports textContent children without createTextNode.
      if (node) {
        // Wrap each span slice so offsets stay mappable after highlight moves.
        const span = document.createElement('span');
        span.textContent = text;
        positionSpan(span, null, width, height, text);
        layer.appendChild(span);
        const walkerNode = span.children && span.children[0] ? span.children[0] : { textContent: text };
        textNodes.push({ node: walkerNode, start: offset, end: offset + text.length });
      } else {
        const span = document.createElement('span');
        span.textContent = text;
        layer.appendChild(span);
      }
      offset += text.length;
    };

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
            const holderNode = newlineHolder.children && newlineHolder.children[0]
              ? newlineHolder.children[0]
              : { textContent: '\n' };
            textNodes.push({ node: holderNode, start: offset, end: offset + 1 });
            offset += 1;
          } else {
            pushTextWithGeometry(document, layer, textNodes, gap, null, width, height);
            offset = textNodes.length ? textNodes[textNodes.length - 1].end : offset;
          }
        }
        cursor = span.startUtf16;
      }
      const slice = canonical.slice(span.startUtf16, span.endUtf16);
      pushTextWithGeometry(document, layer, textNodes, slice, span, width, height);
      cursor = span.endUtf16;
    }
    if (cursor < canonical.length) {
      pushText(canonical.slice(cursor));
    }

    return { layer, textNodes, pageText: layer.textContent };
  }

  function pushTextWithGeometry(document, layer, textNodes, text, span, width, height) {
    if (!text.length) return;
    const element = document.createElement('span');
    // OCR strings become DOM textContent, never HTML/script fragments.
    element.textContent = text;
    positionSpan(element, span ? span.quad : null, width, height, text);
    const direction = span ? spanDirection(span) : 'horizontal';
    if (direction === 'vertical') {
      element.style.writingMode = 'vertical-rl';
    }
    layer.appendChild(element);
    const start = textNodes.length ? textNodes[textNodes.length - 1].end : 0;
    // Mirror the pdf-reader walker shape: text node + half-open offsets.
    const textNode = element.children && element.children[0] ? element.children[0] : { textContent: text };
    const end = start + text.length;
    textNodes.push({ node: textNode, start, end });
  }

  function positionSpan(element, quad, width, height, text) {
    element.dataset = element.dataset || {};
    if (!quad) {
      element.style.position = 'absolute';
      return;
    }
    const rect = quadBoundingRect(quad);
    const left = rect.left * width;
    const top = rect.top * height;
    const spanWidth = Math.max(1, (rect.right - rect.left) * width);
    const spanHeight = Math.max(1, (rect.bottom - rect.top) * height);
    element.style.position = 'absolute';
    element.style.left = left + 'px';
    element.style.top = top + 'px';
    element.style.width = spanWidth + 'px';
    element.style.height = spanHeight + 'px';
    // Approximate glyph size from the observed quad; never feed fabricated
    // font metrics into pdf.js TextLayer.
    const length = text ? [...text].length : 1;
    const isVertical = (rect.bottom - rect.top) > (rect.right - rect.left) * 1.8;
    const fontSize = isVertical
      ? Math.max(4, (spanWidth / Math.max(1, length)) * length > 0 ? spanWidth : spanWidth)
      : Math.max(4, spanHeight * 0.9);
    element.style.fontSize = fontSize + 'px';
    element.style.lineHeight = '1';
    element.style.whiteSpace = 'pre';
    element.style.color = 'transparent';
  }

  /**
   * Range-to-geometry projection for tap anchoring and highlight quads when
   * browser font metrics cannot align precisely with the scan.
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
      if (!span.quad || !quadIsUsable(span.quad)) continue;
      const rect = quadBoundingRect(span.quad);
      left = Math.min(left, rect.left * cssWidth);
      top = Math.min(top, rect.top * cssHeight);
      right = Math.max(right, rect.right * cssWidth);
      bottom = Math.max(bottom, rect.bottom * cssHeight);
      hit = true;
    }
    if (!hit) return null;
    return { x: left, y: top, width: Math.max(1, right - left), height: Math.max(1, bottom - top) };
  }

  const api = {
    classifyEmbeddedText,
    validateOcrPayload,
    buildOcrDom,
    projectRangeToCssRect,
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
