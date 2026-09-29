import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const layerSource = fs.readFileSync(new URL('../../main/assets/hoshi-web/pdf-reader/pdf-ocr-layer.js', import.meta.url), 'utf8');

function loadLayer() {
  const sandbox = { window: {}, globalThis: {} };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(layerSource, sandbox);
  return sandbox.hoshiPdfOcrLayer || sandbox.window.hoshiPdfOcrLayer;
}

function fakeDocument() {
  const createTextNode = (text) => {
    const node = {
      nodeType: 3,
      textContent: text,
      parentNode: null,
      parentElement: null,
      splitText(offset) {
        const left = this.textContent.slice(0, offset);
        const right = this.textContent.slice(offset);
        this.textContent = left;
        const sibling = createTextNode(right);
        const parent = this.parentNode;
        if (parent) {
          const at = parent.childNodes.indexOf(this);
          parent.childNodes.splice(at + 1, 0, sibling);
          sibling.parentNode = parent;
          sibling.parentElement = parent;
        }
        return sibling;
      },
    };
    return node;
  };
  const makeElement = (tag) => {
    const dataset = {};
    const element = {
      nodeType: 1,
      tagName: tag.toUpperCase(),
      childNodes: [],
      parentNode: null,
      parentElement: null,
      get children() { return this.childNodes.filter((child) => child.nodeType === 1); },
      get firstChild() { return this.childNodes[0] ?? null; },
      get dataset() { return dataset; },
      style: {},
      className: '',
      get textContent() { return this.childNodes.map((child) => child.textContent).join(''); },
      set textContent(value) {
        for (const child of this.childNodes) {
          child.parentNode = null;
          child.parentElement = null;
        }
        this.childNodes = [];
        if (value) this.appendChild(createTextNode(value));
      },
      appendChild(child) {
        // Real DOM moves the node: detach from any previous parent first.
        if (child.parentNode && child.parentNode !== this) {
          child.parentNode.removeChild(child);
        } else if (child.parentNode === this) {
          const at = this.childNodes.indexOf(child);
          if (at >= 0) this.childNodes.splice(at, 1);
        }
        this.childNodes.push(child);
        child.parentNode = this;
        child.parentElement = this;
        return child;
      },
      insertBefore(child, ref) {
        if (child.parentNode && child.parentNode !== this) {
          child.parentNode.removeChild(child);
        } else if (child.parentNode === this) {
          const from = this.childNodes.indexOf(child);
          if (from >= 0) this.childNodes.splice(from, 1);
        }
        const at = ref ? this.childNodes.indexOf(ref) : -1;
        if (at < 0) return this.appendChild(child);
        this.childNodes.splice(at, 0, child);
        child.parentNode = this;
        child.parentElement = this;
        return child;
      },
      removeChild(child) {
        const at = this.childNodes.indexOf(child);
        if (at >= 0) this.childNodes.splice(at, 1);
        child.parentNode = null;
        child.parentElement = null;
        return child;
      },
      querySelectorAll(selector) {
        const found = [];
        const walk = (node) => {
          for (const child of node.childNodes ?? []) {
            if (child.nodeType === 1) {
              if (selector === 'span[data-hoshi-highlight-id]' &&
                child.tagName === 'SPAN' && child.dataset.hoshiHighlightId !== undefined) {
                found.push(child);
              }
              walk(child);
            }
          }
        };
        walk(this);
        return found;
      },
      normalize() {
        const merge = (node) => {
          for (const child of node.childNodes ?? []) {
            if (child.nodeType === 1) merge(child);
          }
          const merged = [];
          for (const child of node.childNodes ?? []) {
            const last = merged[merged.length - 1];
            if (child.nodeType === 3 && last && last.nodeType === 3) {
              last.textContent += child.textContent;
            } else {
              merged.push(child);
            }
          }
          node.childNodes = merged;
        };
        merge(this);
      },
      setAttribute() {},
    };
    return element;
  };
  const createTreeWalker = (root) => {
    const nodes = [];
    const walk = (node) => {
      for (const child of node.childNodes ?? []) {
        if (child.nodeType === 3) nodes.push(child);
        else walk(child);
      }
    };
    walk(root);
    let at = -1;
    return { nextNode() { at += 1; return nodes[at] ?? null; } };
  };
  return {
    createElement: makeElement,
    createTextNode,
    createTreeWalker,
  };
}

function assertCanonicalNodes(built, canonicalText, offsets) {
  assert.equal(built.pageText, canonicalText);
  assert.equal(built.layer.textContent, canonicalText);
  const attachedNodes = built.layer.childNodes.flatMap((child) => child.childNodes);
  assert.equal(built.textNodes.length, attachedNodes.length);
  assert.deepEqual(Array.from(built.textNodes, ({ start, end }) => [start, end]), offsets);
  let cursor = 0;
  for (const [index, { node, start, end }] of built.textNodes.entries()) {
    assert.equal(node, attachedNodes[index]);
    assert.equal(node.nodeType, 3);
    assert.equal(node.parentNode.parentNode, built.layer);
    assert.equal(node.parentElement.firstChild, node);
    assert.equal(node.parentElement.children.length, 0);
    assert.equal(start, cursor);
    assert.equal(end, start + node.textContent.length);
    assert.equal(node.textContent, canonicalText.slice(start, end));
    cursor = end;
  }
  assert.equal(cursor, canonicalText.length);
}

test('whitespace-only embedded text is missing and single characters stay usable', () => {
  const layer = loadLayer();
  assert.deepEqual(layer.classifyEmbeddedText('  \n\u0000').textLength, 0);
  assert.equal(layer.classifyEmbeddedText('あ').textLength, 1);
  assert.equal(layer.classifyEmbeddedText('a').textLength, 1);
});

test('replacement and private-use text is suspect, isolated numbers are suspect', () => {
  const layer = loadLayer();
  assert.equal(layer.classifyEmbeddedText('本文�続き').hasSuspectChars, true);
  assert.equal(layer.classifyEmbeddedText('\uE000\uE001').hasSuspectChars, true);
  assert.equal(layer.classifyEmbeddedText('12').isSingleNumber, true);
  assert.equal(layer.classifyEmbeddedText('本文です').hasSuspectChars, false);
  assert.equal(layer.classifyEmbeddedText('本文です').isSingleNumber, false);
});

test('payload validation rejects unbounded and stale offsets', () => {
  const layer = loadLayer();
  const good = {
    sessionId: 's1', pageIndex: 0, requestId: 1, revisionId: 'r1', sourceHash: 'a'.repeat(64),
    canonicalText: 'あいう', spans: [{ sourceId: 'l0', startUtf16: 0, endUtf16: 3, text: 'あいう' }],
    cssWidth: 450, cssHeight: 600,
  };
  assert.ok(layer.validateOcrPayload(good));
  assert.equal(layer.validateOcrPayload({ ...good, sessionId: '' }), null);
  assert.equal(layer.validateOcrPayload({ ...good, spans: [{ sourceId: 'l0', startUtf16: 0, endUtf16: 9, text: 'x' }] }), null);
  assert.equal(layer.validateOcrPayload({ ...good, canonicalText: 'x'.repeat(20001) }), null);
  assert.equal(layer.validateOcrPayload({ ...good, spans: new Array(2001).fill(good.spans[0]) }), null);
});

test('canonical DOM keeps paragraph separators as offsets, not bare <br>', () => {
  const layer = loadLayer();
  const document = fakeDocument();
  const payload = {
    sessionId: 's1', pageIndex: 1, requestId: 2, revisionId: 'r2', sourceHash: 'b'.repeat(64),
    canonicalText: 'あいう\nえお',
    spans: [
      { sourceId: 'l0', startUtf16: 0, endUtf16: 3, text: 'あいう', quad: { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.1, x2: 0.9, y2: 0.2, x3: 0.1, y3: 0.2 } },
      { sourceId: 'l1', startUtf16: 4, endUtf16: 6, text: 'えお', quad: { x0: 0.1, y0: 0.3, x1: 0.9, y1: 0.3, x2: 0.9, y2: 0.4, x3: 0.1, y3: 0.4 } },
    ],
    cssWidth: 450, cssHeight: 600,
  };
  const built = layer.buildOcrDom(document, payload, 450, 600);
  assert.ok(built);
  assertCanonicalNodes(built, payload.canonicalText, [[0, 3], [3, 4], [4, 6]]);
});

test('canonical DOM registers attached UTF16 text nodes for gaps and trailing slices', () => {
  const layer = loadLayer();
  for (const { canonicalText, ranges, offsets } of [
    {
      canonicalText: ' \u{1F600}A\nB--C\u{1F600}\n',
      ranges: [[3, 4], [5, 6], [8, 9]],
      offsets: [[0, 3], [3, 4], [4, 5], [5, 6], [6, 8], [8, 9], [9, 12]],
    },
    {
      canonicalText: '\u{1F600}\nA\nB\n',
      ranges: [[0, 2], [3, 4], [5, 6]],
      offsets: [[0, 2], [2, 3], [3, 4], [4, 5], [5, 6], [6, 7]],
    },
    { canonicalText: '\u{1F600}\ntext', ranges: [], offsets: [[0, 7]] },
    { canonicalText: '', ranges: [], offsets: [] },
  ]) {
    const payload = {
      sessionId: 's1', pageIndex: 0, requestId: 1, revisionId: 'r1', sourceHash: 'a'.repeat(64),
      canonicalText,
      spans: ranges.map(([startUtf16, endUtf16], index) => ({
        sourceId: `l${index}`, startUtf16, endUtf16, text: canonicalText.slice(startUtf16, endUtf16),
      })),
      cssWidth: 450, cssHeight: 600,
    };
    assertCanonicalNodes(layer.buildOcrDom(fakeDocument(), payload), canonicalText, offsets);
  }
});

test('range projection uses OCR quads, not font metrics', () => {
  const layer = loadLayer();
  const spans = [
    { sourceId: 'l0', startUtf16: 0, endUtf16: 3, quad: { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.1, x2: 0.9, y2: 0.2, x3: 0.1, y3: 0.2 } },
  ];
  const rect = layer.projectRangeToCssRect(spans, 0, 3, 450, 600);
  assert.ok(rect);
  assert.equal(Math.round(rect.x), Math.round(0.1 * 450));
  assert.equal(layer.projectRangeToCssRect(spans, 5, 9, 450, 600), null);
  assert.equal(layer.projectRangeToCssRect([], 0, 1, 450, 600), null);
});

test('range projection subdivides partial-line selections instead of the full line box', () => {
  const layer = loadLayer();
  const quad = { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.1, x2: 0.9, y2: 0.2, x3: 0.1, y3: 0.2 };
  const spans = [{ sourceId: 'l0', startUtf16: 0, endUtf16: 4, text: 'あいうえ', quad }];
  const full = layer.projectRangeToCssRect(spans, 0, 4, 450, 600);
  assert.equal(Math.round(full.x), Math.round(0.1 * 450));
  assert.equal(Math.round(full.width), Math.round(0.8 * 450));
  // Middle two of four characters: tight middle half, full height.
  const mid = layer.projectRangeToCssRect(spans, 1, 3, 450, 600);
  assert.equal(Math.round(mid.x), Math.round(0.3 * 450));
  assert.equal(Math.round(mid.width), Math.round(0.4 * 450));
  assert.equal(Math.round(mid.y), Math.round(full.y));
  assert.equal(Math.round(mid.height), Math.round(full.height));
  // Vertical lines subdivide top to bottom.
  const vertical = [{ sourceId: 'v0', startUtf16: 0, endUtf16: 4, text: 'あいうえ',
    quad: { x0: 0.7, y0: 0.1, x1: 0.8, y1: 0.1, x2: 0.8, y2: 0.9, x3: 0.7, y3: 0.9 } }];
  const slice = layer.projectRangeToCssRect(vertical, 0, 2, 450, 600);
  assert.equal(Math.round(slice.y), Math.round(0.1 * 600));
  assert.equal(Math.round(slice.height), Math.round(0.4 * 600));
  assert.equal(Math.round(slice.x), Math.round(0.7 * 450));
  // Spans without text keep the previous whole-box behavior.
  const legacy = layer.projectRangeToCssRect(
    [{ sourceId: 'l0', startUtf16: 0, endUtf16: 4, quad }], 1, 3, 450, 600);
  assert.equal(Math.round(legacy.width), Math.round(0.8 * 450));
});

function buildLineLayer() {
  const layer = loadLayer();
  const document = fakeDocument();
  const payload = {
    sessionId: 's1', pageIndex: 0, requestId: 1, revisionId: 'r1', sourceHash: 'a'.repeat(64),
    canonicalText: 'あいうえお',
    spans: [
      { sourceId: 'l0', startUtf16: 0, endUtf16: 5, text: 'あいうえお',
        quad: { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.1, x2: 0.9, y2: 0.2, x3: 0.1, y3: 0.2 } },
    ],
    cssWidth: 450, cssHeight: 600,
  };
  const built = layer.buildOcrDom(document, payload, 450, 600);
  assert.ok(built);
  return { layer, document, built };
}

test('partial highlight paints only the selected run, not the full line box', () => {
  const { layer, document, built } = buildLineLayer();
  layer.paintHighlightEntry(document, built.layer, built.textNodes,
    { id: 'h1', offset: 1, text: 'いう', background: 'yellow' });
  // Concatenated text and tiling offsets are unchanged.
  assert.equal(built.layer.textContent, 'あいうえお');
  assert.deepEqual(Array.from(built.textNodes, ({ start, end }) => [start, end]), [[0, 1], [1, 3], [3, 5]]);
  // The line span itself stays unpainted; only the nested run carries paint.
  const lineSpan = built.layer.childNodes[0];
  assert.equal(lineSpan.style.backgroundColor ?? '', '');
  assert.equal(lineSpan.dataset.hoshiHighlightId, undefined);
  const mark = lineSpan.childNodes[1];
  assert.equal(mark.tagName, 'SPAN');
  assert.equal(mark.textContent, 'いう');
  assert.equal(mark.dataset.hoshiHighlightId, 'h1');
  assert.equal(mark.style.backgroundColor, 'yellow');
  // Clearing restores the original single-node shape.
  assert.equal(layer.clearHighlightMarks(document, built.layer), true);
  const rebuilt = layer.rebuildTextNodes(document, built.layer);
  assert.deepEqual(Array.from(rebuilt, ({ start, end }) => [start, end]), [[0, 5]]);
  assert.equal(built.layer.textContent, 'あいうえお');
  assert.equal(lineSpan.childNodes.length, 1);
});

test('full-line highlight paints inline glyph bounds, not the positioned layout box', () => {
  const { layer, document, built } = buildLineLayer();
  layer.paintHighlightEntry(document, built.layer, built.textNodes,
    { id: 'h1', offset: 0, text: 'あいうえお', background: 'yellow' });
  const lineSpan = built.layer.childNodes[0];
  assert.equal(lineSpan.dataset.hoshiHighlightId, undefined);
  const mark = lineSpan.children[0];
  assert.equal(mark.dataset.hoshiHighlightId, 'h1');
  assert.equal(mark.style.backgroundColor, 'yellow');
  assert.equal(mark.style.position, 'static');
  assert.equal(mark.textContent, built.pageText);
  assert.equal(layer.clearHighlightMarks(document, built.layer), true);
});

test('adjacent highlights on remaining text fragments never paint the parent line', () => {
  for (const [offset, length] of [[0, 1], [3, 2]]) {
    const { layer, document, built } = buildLineLayer();
    const text = built.pageText;
    layer.paintHighlightEntry(document, built.layer, built.textNodes,
      { id: 'middle', offset: 1, text: text.slice(1, 3), background: 'yellow' });
    layer.paintHighlightEntry(document, built.layer, built.textNodes,
      { id: 'adjacent', offset, text: text.slice(offset, offset + length), background: 'blue' });

    const lineSpan = built.layer.firstChild;
    assert.equal(lineSpan.dataset.hoshiHighlightId, undefined);
    assert.equal(lineSpan.style.backgroundColor ?? '', '');
    const marks = built.layer.querySelectorAll('span[data-hoshi-highlight-id]');
    assert.equal(marks.length, 2);
    assert.equal(marks.find((mark) => mark.dataset.hoshiHighlightId === 'middle').textContent, text.slice(1, 3));
    const adjacent = marks.find((mark) => mark.dataset.hoshiHighlightId === 'adjacent');
    assert.equal(adjacent.textContent, text.slice(offset, offset + length));
    assert.equal(adjacent.style.backgroundColor, 'blue');
    assert.equal(adjacent.style.position, 'static');
    assert.equal(built.layer.textContent, text);
    assert.deepEqual(Array.from(built.textNodes, ({ start, end }) => [start, end]), [[0, 1], [1, 3], [3, 5]]);
  }
});

test('repeated fragment cleanup restores canonical nodes and is idempotent', () => {
  const { layer, document, built } = buildLineLayer();
  for (let cycle = 0; cycle < 3; cycle += 1) {
    layer.paintHighlightEntry(document, built.layer, built.textNodes,
      { id: 'outer', offset: 1, text: built.pageText.slice(1, 4), background: 'yellow' });
    layer.paintHighlightEntry(document, built.layer, built.textNodes,
      { id: 'inner', offset: 2, text: built.pageText.slice(2, 3), background: 'blue' });
    assert.equal(layer.clearHighlightMarks(document, built.layer), true);
    built.textNodes = layer.rebuildTextNodes(document, built.layer);
    assertCanonicalNodes(built, built.pageText, [[0, 5]]);
    assert.equal(built.layer.querySelectorAll('span[data-hoshi-highlight-id]').length, 0);
    assert.equal(layer.clearHighlightMarks(document, built.layer), false);
    assertCanonicalNodes(built, built.pageText, [[0, 5]]);
  }
});

test('reader repaint unwraps fragments before clearing IDs across repeated updates', () => {
  const { layer, document, built } = buildLineLayer();
  const record = {
    index: 0, rendered: true, textLayerDiv: built.layer,
    textNodes: built.textNodes, pageText: built.pageText,
  };
  const sandbox = {
    document: { ...document, getElementById: () => ({ addEventListener() {} }) },
    window: { addEventListener() {} },
    pdfjsLib: { PixelsPerInch: { PDF_TO_CSS_UNITS: 4 / 3 }, GlobalWorkerOptions: {} },
    hoshiPdfOcrLayer: layer,
    record,
  };
  vm.createContext(sandbox);
  const readerSource = fs.readFileSync(new URL('../../main/assets/hoshi-web/pdf-reader/pdf-reader.js', import.meta.url), 'utf8');
  vm.runInContext(readerSource.replace(/^import .*;$/m, ''), sandbox);
  vm.runInContext('state.pages.push(record); state.activePages.add(0);', sandbox);

  for (let cycle = 0; cycle < 3; cycle += 1) {
    const highlights = [
      { id: 'middle', character: 0, offset: 1, text: built.pageText.slice(1, 3), color: 'yellow' },
      { id: 'tail', character: 0, offset: 3, text: built.pageText.slice(3), color: 'blue' },
    ];
    for (let repaint = 0; repaint < 2; repaint += 1) {
      sandbox.applyHighlightIndex(highlights);
      const lineSpan = built.layer.firstChild;
      assert.equal(lineSpan.children.length, 2);
      assert.equal(lineSpan.dataset.hoshiHighlightId, undefined);
      assert.equal(lineSpan.style.backgroundColor ?? '', '');
      assert.equal(built.layer.textContent, built.pageText);
    }
    sandbox.applyHighlightIndex([]);
    built.textNodes = record.textNodes;
    assertCanonicalNodes(built, built.pageText, [[0, 5]]);
    assert.equal(built.layer.querySelectorAll('span[data-hoshi-highlight-id]').length, 0);

    sandbox.applyHighlightIndex([
      { id: 'whole', character: 0, offset: 0, text: built.pageText, color: 'yellow' },
    ]);
    assert.equal(built.layer.querySelectorAll('span[data-hoshi-highlight-id]')[0].dataset.hoshiHighlightId, 'whole');
    sandbox.applyHighlightIndex([]);
    sandbox.applyHighlightIndex([]);
    assert.equal(built.layer.firstChild.dataset.hoshiHighlightId, undefined);
    assert.equal(built.layer.firstChild.style.backgroundColor ?? '', '');
    built.textNodes = record.textNodes;
    assertCanonicalNodes(built, built.pageText, [[0, 5]]);
  }
});
