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
  const createTextNode = (text) => ({ nodeType: 3, textContent: text, parentNode: null, parentElement: null });
  const makeElement = (tag) => {
    const dataset = {};
    return {
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
        this.childNodes.push(child);
        child.parentNode = this;
        child.parentElement = this;
        return child;
      },
      setAttribute() {},
    };
  };
  return {
    createElement: makeElement,
    createTextNode,
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
