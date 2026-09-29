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
  const texts = [];
  const makeElement = (tag) => ({
    tagName: tag.toUpperCase(),
    children: [],
    dataset: {},
    style: {},
    className: '',
    textContent: '',
    appendChild(child) {
      this.children.push(child);
      child.parentElement = this;
      if (child.textContent && !child.children?.length) {
        // Keep textContent in sync for assertions.
      }
      return child;
    },
    setAttribute() {},
  });
  Object.defineProperty(makeElement('p'), 'textContent', {
    get() { return ''; },
  });
  return {
    createElement: (tag) => {
      const el = makeElement(tag);
      Object.defineProperty(el, 'textContent', {
        get() { return this.children.map((c) => c.textContent ?? '').join('') + (this._text ?? ''); },
        set(v) {
          this._text = v;
          this.children = v ? [{ textContent: v }] : [];
        },
      });
      return el;
    },
    createTextNode: (text) => ({ textContent: text }),
  };
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
  assert.equal(built.pageText.includes('あいう'), true);
  assert.equal(built.pageText.includes('えお'), true);
  // Offsets cover the newline separator explicitly.
  const offsets = built.textNodes.map((n) => [n.start, n.end]);
  assert.ok(offsets.some(([s, e]) => e - s === 1));
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
