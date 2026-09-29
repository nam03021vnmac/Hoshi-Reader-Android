import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const asset = (path) => fs.readFileSync(new URL(`../../main/assets/hoshi-web/${path}`, import.meta.url), 'utf8');
const runtime = asset('pdf-reader/pdf-reader.js').replace(/^import .*;$/m, '');
const ocrLayerRuntime = asset('pdf-reader/pdf-ocr-layer.js');

class Element {
  constructor(tagName) {
    this.tagName = tagName.toUpperCase();
    this.nodeType = 1;
    this.children = [];
    this.dataset = {};
    this.className = '';
    this.listeners = new Map();
    this.style = {
      setProperty(name, value) { this[name] = value; },
      removeProperty(name) { delete this[name]; },
    };
    this.classList = {
      contains: (name) => this.className.split(' ').includes(name),
      add: (name) => { if (!this.classList.contains(name)) this.className += ` ${name}`; },
      remove: (name) => { this.className = this.className.split(' ').filter((item) => item !== name).join(' '); },
    };
    this.scrollTop = this.scrollLeft = 0;
    this.clientWidth = 600;
    this.clientHeight = 800;
  }
  appendChild(child) {
    this.children.push(child);
    child.parentElement = child.parentNode = this;
    return child;
  }
  insertBefore(child, before) {
    const at = before ? this.children.indexOf(before) : -1;
    if (at >= 0) this.children.splice(at, 0, child);
    else this.children.push(child);
    child.parentElement = child.parentNode = this;
    return child;
  }
  remove() {
    this.parentElement.children = this.parentElement.children.filter((child) => child !== this);
    this.parentElement = this.parentNode = null;
  }
  set textContent(text) {
    this.children = text ? [{ nodeType: 3, textContent: text, parentElement: this, parentNode: this }] : [];
  }
  get textContent() { return this.children.map((child) => child.textContent).join(''); }
  get firstChild() { return this.children[0] ?? null; }
  setAttribute() {}
  get offsetWidth() { return parseFloat(this.style.width) || 0; }
  get offsetHeight() {
    const [width, height] = (this.style.aspectRatio || '1 / 1').split('/').map(Number);
    return this.offsetWidth * height / width;
  }
  get offsetTop() {
    const siblings = this.parentElement?.children || [];
    return 8 + siblings.slice(0, siblings.indexOf(this)).reduce((sum, item) => sum + item.offsetHeight + 12, 0);
  }
  get offsetLeft() { return 0; }
  getContext() { return { canvas: this }; }
  closest(selector) {
    const matches = selector.split(',').map((part) => part.trim());
    for (let element = this; element; element = element.parentElement) {
      if (matches.some((part) => {
        if (part === '.hoshi-pdf-text-layer span') {
          return element.tagName === 'SPAN' && element.parentElement?.closest('.hoshi-pdf-text-layer');
        }
        return part.startsWith('.') ? element.classList.contains(part.slice(1)) : element.tagName.toLowerCase() === part;
      })) return element;
    }
    return null;
  }
  addEventListener(type, callback, options) { this.listeners.set(type, { callback, options }); }
  dispatch(type, event = {}) { this.listeners.get(type)?.callback(event); }
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function reader({ count = 12, pendingRender = false, pendingText = false, texts = ['alpha', 'beta.'], outline = null, dests = {} } = {}) {
  const viewport = new Element('div');
  const root = viewport.appendChild(new Element('div'));
  const frames = [];
  const messages = [];
  const renders = [];
  const textLayers = [];
  const loads = [];
  const pageRequests = [];
  const cleanedPages = [];
  const windowListeners = new Map();
  let selection = null;
  let destroyed = 0;
  let loadError = false;
  let pageGate = null;
  const makeViewport = (scale) => ({
    width: 450 * scale, height: 600 * scale, scale,
    clone: ({ scale: next }) => makeViewport(next),
  });
  const pdf = {
    numPages: count,
    async getOutline() { return outline; },
    async getDestination(id) { return dests[id]; },
    async getPageIndex(ref) {
      if (!Number.isSafeInteger(ref?.pageIndex)) throw new Error('bad ref');
      return ref.pageIndex;
    },
    async getPage(number) {
      pageRequests.push(number);
      if (pageGate) await pageGate.promise;
      return {
        cleanup: () => cleanedPages.push(number),
        getViewport: ({ scale }) => makeViewport(scale),
        streamTextContent: () => texts,
        render(options) {
          const task = { ...deferred(), options, number, cancelled: false, cancel() { this.cancelled = true; } };
          renders.push(task);
          if (!pendingRender) task.resolve();
          return task;
        },
      };
    },
  };
  const pdfjsLib = {
    PixelsPerInch: { PDF_TO_CSS_UNITS: 4 / 3 },
    GlobalWorkerOptions: {},
    getDocument(options) {
      loads.push(options);
      return {
        promise: loadError ? Promise.reject(new Error('private details')) : Promise.resolve(pdf),
        async destroy() { destroyed += 1; },
      };
    },
    TextLayer: class {
      constructor({ container, viewport }) {
        this.container = container;
        this.viewport = viewport;
        this.textDivs = texts.map((text) => {
          const span = container.appendChild(new Element('span'));
          span.textContent = text;
          return span;
        });
        this.task = deferred();
        textLayers.push(this);
      }
      render() { if (!pendingText) this.task.resolve(); return this.task.promise; }
      cancel() { this.cancelled = true; }
    },
  };
  const document = {
    body: viewport,
    documentElement: new Element('html'),
    getElementById: (id) => id === 'hoshi-pdf-viewport' ? viewport : root,
    createElement: (tag) => new Element(tag),
    addEventListener() {},
    createTreeWalker(container) {
      const nodes = [];
      const visit = (node) => node.nodeType === 3 ? nodes.push(node) : node.children.forEach(visit);
      visit(container);
      return {
        currentNode: null,
        nextNode() { return this.currentNode = nodes[nodes.indexOf(this.currentNode) + 1] || null; },
        previousNode() { return this.currentNode = nodes[nodes.indexOf(this.currentNode) - 1] || null; },
      };
    },
    createRange() {
      const state = { startNode: null, startOffset: 0, endNode: null, endOffset: 0 };
      const orderedTexts = () => {
        const out = [];
        const visit = (node) => {
          if (node.nodeType === 3) out.push(node);
          else (node.children || []).forEach(visit);
        };
        visit(document.body);
        return out;
      };
      const firstTextBelow = (node, texts) => {
        if (node.nodeType === 3) {
          const index = texts.indexOf(node);
          return index < 0 ? null : { index, char: 0 };
        }
        for (const child of node.children || []) {
          const found = firstTextBelow(child, texts);
          if (found) return found;
        }
        return null;
      };
      const lastTextBelow = (node, texts) => {
        if (node.nodeType === 3) {
          const index = texts.indexOf(node);
          return index < 0 ? null : { index, char: node.textContent.length };
        }
        const children = node.children || [];
        for (let i = children.length - 1; i >= 0; i -= 1) {
          const found = lastTextBelow(children[i], texts);
          if (found) return found;
        }
        return null;
      };
      const pointToPosition = (node, offset, isEnd, texts) => {
        if (!node) return isEnd ? { index: texts.length - 1, char: texts.at(-1)?.textContent.length || 0 } : { index: 0, char: 0 };
        if (node.nodeType === 3) {
          const index = texts.indexOf(node);
          if (index < 0) return { index: 0, char: 0 };
          const length = node.textContent.length;
          const char = Math.max(0, Math.min(offset ?? 0, length));
          return { index, char };
        }
        const children = node.children || [];
        let slot = offset ?? (isEnd ? children.length : 0);
        slot = Math.max(0, Math.min(slot, children.length));
        if (slot < children.length) {
          return firstTextBelow(children[slot], texts) || { index: 0, char: 0 };
        }
        return lastTextBelow(node, texts) || { index: 0, char: 0 };
      };
      return {
        setStart(node, offset) { state.startNode = node; state.startOffset = offset; },
        setEnd(node, offset) { state.endNode = node; state.endOffset = offset; },
        selectNodeContents(node) {
          state.startNode = node; state.startOffset = 0;
          state.endNode = node; state.endOffset = (node.children || []).length;
        },
        getClientRects() { return []; },
        toString() {
          const texts = orderedTexts();
          if (texts.length === 0) return '';
          const full = texts.map((node) => node.textContent).join('');
          const cumulative = [0];
          texts.forEach((node) => cumulative.push(cumulative.at(-1) + node.textContent.length));
          const start = pointToPosition(state.startNode, state.startOffset, false, texts);
          const end = pointToPosition(state.endNode, state.endOffset, true, texts);
          const from = cumulative[start.index] + start.char;
          const to = cumulative[end.index] + end.char;
          return full.slice(Math.min(from, to), Math.max(from, to));
        },
      };
    },
  };
  const window = {
    location: new URL('https://appassets.androidplatform.net/pdfreader/index.html'),
    devicePixelRatio: 3,
    getSelection: () => selection,
    HoshiPdfReader: { postMessage: (json) => messages.push(JSON.parse(json)) },
    addEventListener: (type, callback) => windowListeners.set(type, callback),
  };
  const context = vm.createContext({
    window, document, pdfjsLib, URL, console, Node: { TEXT_NODE: 3 },
    NodeFilter: { SHOW_TEXT: 4 }, CSS: {}, getSelection: () => selection,
    requestAnimationFrame: (callback) => frames.push(callback),
  });
  vm.runInContext(asset('shared/selection.js'), context);
  vm.runInContext(ocrLayerRuntime, context);
  vm.runInContext(runtime, context);
  return {
    api: window.hoshiPdfReader, highlights: window.hoshiHighlights, ocr: window.hoshiPdfOcr,
    window, document, viewport, root, renders, textLayers, messages, loads, pageRequests, cleanedPages,
    get destroyed() { return destroyed; },
    failLoad(value) { loadError = value; },
    gatePages(value) { pageGate = value; },
    async flush() {
      for (let i = 0; i < 20; i += 1) {
        frames.splice(0).forEach((callback) => callback());
        await Promise.resolve();
      }
    },
    select(startSpan, endSpan = startSpan, text = startSpan.textContent) {
      selection = {
        rangeCount: 1,
        getRangeAt: () => ({
          collapsed: false,
          startContainer: startSpan.children[0],
          endContainer: endSpan.children[0],
          toString: () => text,
        }),
        toString: () => text,
        removeAllRanges() { selection = null; },
      };
    },
    clearNativeSelection() { selection = null; },
    resize(width) { viewport.clientWidth = width; windowListeners.get('resize')(); },
  };
}

test('initial restore sizes every placeholder and renders only the nearby page window', async () => {
  const r = reader();
  await r.api.open({ pageIndex: 7 });
  await r.flush();
  assert.equal(r.root.children.length, 12);
  assert.ok(r.root.children.every((page) => page.offsetWidth === 600 && page.offsetHeight === 800));
  assert.equal(r.viewport.scrollTop, r.root.children[7].offsetTop - 8);
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 7);
  assert.ok(r.renders.length <= 6);
  assert.ok(r.renders.every((render) => render.number >= 5 && render.number <= 10));
  const layer = r.textLayers[0].container;
  assert.ok(layer.classList.contains('textLayer'));
  assert.equal(r.window.hoshiSelection.findParagraph(r.textLayers[0].textDivs[0].children[0]), layer);
  assert.equal(r.messages[0].type, 'hostReady');
  assert.ok(r.messages.some((message) => message.type === 'ready'));
});

test('zoom keeps old pixels until the new scale swaps in, preserves page position, and caps raster memory', async () => {
  const r = reader();
  await r.api.open({ pageIndex: 6 });
  await r.flush();
  const oldRenders = [...r.renders];
  const oldCanvases = oldRenders.map((render) => render.options.canvasContext.canvas);
  assert.ok(oldCanvases.every((canvas) => canvas.parentElement));
  r.api.setZoomFactor(8);
  await r.flush();
  await r.flush();
  assert.ok(r.renders.length > oldRenders.length);
  // Previous pages stay on screen instead of flashing empty: old canvases are
  // detached only once their replacement has rendered.
  assert.ok(oldCanvases.every((canvas) => !canvas.parentElement));
  for (const render of r.renders.slice(oldRenders.length)) {
    const canvas = render.options.canvasContext.canvas;
    assert.ok(canvas.parentElement);
    assert.equal(canvas.style.width, '4800px');
    assert.ok(canvas.width * canvas.height <= 4 * 1024 * 1024);
    assert.ok(canvas.width <= 8192 && canvas.height <= 8192);
  }
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 6);
  await r.api.fitWidth();
  await r.flush();
  assert.equal(r.root.children[6].offsetWidth, 600);
  r.resize(300);
  await r.flush();
  assert.equal(r.root.children[6].offsetWidth, 300);
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 6);
});

test('eviction cancels pending canvas work and stale completions cannot recreate text layers', async () => {
  const r = reader({ count: 30, pendingRender: true });
  await r.api.open();
  await r.flush();
  const old = [...r.renders];
  r.api.jumpToPage(20);
  await r.flush();
  assert.ok(old.every((task) => task.cancelled));
  assert.ok(old.every((task) => r.cleanedPages.includes(task.number)));
  old.forEach((task) => task.resolve());
  await r.flush();
  assert.equal(r.root.children[0].children.length, 0);
  assert.equal(r.textLayers.length, 0);
  assert.ok(!r.messages.some((message) => message.type === 'loadFailed'));
});

test('zoom during text rendering leaves the replacement task intact', async () => {
  const r = reader({ pendingText: true });
  await r.api.open();
  await r.flush();
  const old = [...r.textLayers];
  r.api.zoomIn();
  await r.flush();
  old.forEach((layer) => layer.task.reject(new Error('cancelled')));
  await r.flush();
  const current = r.textLayers.slice(old.length);
  current.forEach((layer) => layer.task.resolve());
  await r.flush();
  assert.ok(old.every((layer) => layer.cancelled && !layer.container.parentElement));
  assert.ok(current.every((layer) => layer.container.parentElement));
  assert.ok(!r.messages.some((message) => message.type === 'loadFailed'));
});

test('render failures emit only the typed failure message and release the canvas', async () => {
  const r = reader({ pendingRender: true });
  await r.api.open();
  await r.flush();
  r.renders[0].reject(new Error('private file path'));
  await r.flush();
  assert.deepEqual(r.messages.at(-1), { type: 'loadFailed' });
  assert.equal(r.root.children[0].children.length, 0);
  // A failed render never reaches the page: no black canvas is attached.
  assert.ok(!r.renders[0].options.canvasContext.canvas.parentElement);
});

test('eviction before getPage resolves never allocates an offscreen canvas', async () => {
  const r = reader({ count: 30 });
  await r.api.open();
  const gate = deferred();
  r.gatePages(gate);
  await r.flush();
  r.api.jumpToPage(20);
  await r.flush();
  gate.resolve();
  await r.flush();
  assert.ok(r.renders.every((task) => task.number >= 18));
});

test('highlights supplied before open survive and updates clear pre-rendered neighbor pages', async () => {
  const r = reader();
  r.highlights.applyHighlights([{ id: 'saved', character: 2, offset: 0, text: 'alpha', color: 'blue' }]);
  await r.api.open();
  await r.flush();
  const layer = r.root.children[2].children.find((child) => child.classList.contains('textLayer'));
  assert.equal(layer.children[0].dataset.hoshiHighlightId, 'saved');
  r.highlights.applyHighlights([]);
  assert.equal(layer.children[0].dataset.hoshiHighlightId, undefined);
});

test('native highlight picker uses its prepared selection after ActionMode clears the DOM selection', async () => {
  const r = reader();
  await r.api.open();
  await r.flush();
  r.select(r.textLayers[0].textDivs[0]);
  assert.equal(r.highlights.prepareHighlightSelection(), true);
  r.clearNativeSelection();
  const result = r.highlights.createHighlight('yellow', 'new');
  assert.equal(result.action, 'created');
  assert.equal(result.start, 0);
  assert.equal(result.offset, 0);
  assert.equal(result.text, 'alpha');
  assert.equal(r.highlights.createHighlight('blue', 'other'), null);
});

test('cross-page and whitespace-only highlight selections fail closed', async () => {
  const r = reader();
  await r.api.open();
  await r.flush();
  r.select(r.textLayers[0].textDivs[0], r.textLayers[1].textDivs[0]);
  assert.equal(r.highlights.prepareHighlightSelection(), false);
  r.select(r.textLayers[0].textDivs[0], undefined, '  \n');
  assert.equal(r.highlights.prepareHighlightSelection(), false);
});

test('malformed bridge numbers and highlight offsets are ignored without corrupting layout', async () => {
  const r = reader();
  await r.api.open({ pageIndex: NaN, zoomFactor: Infinity });
  await r.flush();
  r.api.jumpToPage(NaN);
  r.api.setZoomFactor(NaN);
  r.highlights.applyHighlights([
    null, { character: 0, offset: -2147483648, text: 'bad' },
    { character: -1, offset: 0, text: 'bad' }, { character: 0, offset: 0, text: {} },
  ]);
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 0);
  assert.equal(r.root.children[0].offsetWidth, 600);
  r.api.jumpToPage(2.9);
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 2);
});

test('document loading rejects external URLs and releases failed loading tasks before retry', async () => {
  const r = reader();
  for (const url of ['https://example.org/file.pdf', '/popup/iframe.html', '/pdfbook/data?remote=1', 'file:///data/file.pdf']) {
    await r.api.open({ url });
  }
  assert.equal(r.loads.length, 0);
  r.failLoad(true);
  await r.api.open();
  assert.equal(r.destroyed, 1);
  assert.equal(r.messages.at(-1).type, 'loadFailed');
  r.failLoad(false);
  await r.api.open();
  await r.flush();
  assert.equal(r.loads.at(-1).isEvalSupported, false);
  assert.equal(r.loads.at(-1).url, 'https://appassets.androidplatform.net/pdfbook/data');
  assert.equal(r.messages.at(-1).type, 'ready');
  const count = r.root.children.length;
  await r.api.open();
  assert.equal(r.root.children.length, count);
});

test('shared sentence extraction stays within a page instead of including adjacent pages', async () => {
  const r = reader({ texts: ['prefix ', 'selected suffix'] });
  await r.api.open();
  await r.flush();
  const selectedNode = r.textLayers[1].textDivs[1].children[0];
  const context = r.window.hoshiSelection.getSentenceContext(selectedNode, 0);
  assert.equal(context.sentence, 'prefix selected suffix');
  assert.equal(context.sentenceOffset, 7);
});

test('pinch prevents native scrolling and cancellation or third fingers leave no preview or tap', async () => {
  const r = reader();
  await r.api.open();
  await r.flush();
  const touches = [{ clientX: 100, clientY: 100 }, { clientX: 200, clientY: 100 }];
  let prevented = false;
  r.viewport.dispatch('touchstart', { touches });
  r.viewport.dispatch('touchmove', { touches, preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(r.viewport.listeners.get('touchmove').options.passive, false);
  r.viewport.dispatch('touchstart', { touches: [...touches, { clientX: 300, clientY: 100 }] });
  assert.equal(r.root.style.transform, '');
  r.viewport.dispatch('touchend', { touches: [] });
  assert.ok(!r.messages.some((message) => message.type === 'tap'));
  r.viewport.dispatch('touchstart', { touches });
  r.viewport.dispatch('touchcancel');
  assert.equal(r.root.style.transform, '');
});

test('document outline resolves named and explicit destinations to page indices', async () => {
  const r = reader({
    outline: [
      {
        title: 'Part', dest: null, items: [
          { title: 'Chapter 1', dest: 'ch1', items: [] },
          { title: 'Chapter 2', dest: [{ pageIndex: 4 }, { name: 'XYZ' }], items: [] },
          { title: 'No target', dest: 'missing', items: [] },
          { title: 42, dest: 'ch1', items: [] },
        ],
      },
    ],
    dests: { ch1: [{ pageIndex: 1 }, { name: 'Fit' }] },
  });
  await r.api.open();
  await r.flush();
  await r.flush();
  assert.deepEqual(r.messages.find((message) => message.type === 'outline'), {
    type: 'outline',
    items: [
      {
        title: 'Part', pageIndex: -1,
        children: [
          { title: 'Chapter 1', pageIndex: 1, children: [] },
          { title: 'Chapter 2', pageIndex: 4, children: [] },
          { title: 'No target', pageIndex: -1, children: [] },
        ],
      },
    ],
  });
});

test('missing outline posts an empty chapter list instead of failing open', async () => {
  const r = reader({ outline: null });
  await r.api.open();
  await r.flush();
  assert.deepEqual(r.messages.find((message) => message.type === 'outline'), { type: 'outline', items: [] });
  assert.ok(r.messages.some((message) => message.type === 'ready'));
});

test('ocr overlay tracks the displayed page scale so ruby taps hit visible glyphs', async () => {
  const r = reader();
  await r.api.open({ sessionId: 's1' });
  await r.flush();
  const payload = {
    sessionId: 's1',
    pageIndex: 0,
    requestId: 1,
    revisionId: 'r1',
    sourceHash: 'a'.repeat(64),
    canonicalText: 'しゅう',
    spans: [{
      sourceId: 'mlkit-l1',
      startUtf16: 0,
      endUtf16: 3,
      text: 'しゅう',
      quad: { x0: 0.25, y0: 0.25, x1: 0.5, y1: 0.25, x2: 0.5, y2: 0.5, x3: 0.25, y3: 0.5 },
    }],
    cssWidth: 600,
    cssHeight: 800,
  };
  assert.equal(r.ocr.applyOcr(payload), true);
  const layerAt = () => r.root.children[0].children.find((child) => child.classList.contains('hoshi-pdf-ocr-layer'));
  let layer = layerAt();
  assert.ok(layer);
  assert.equal(layer.textContent, 'しゅう');
  // Fit-width scale is 1 here, so the overlay matches the base 600x800 page.
  assert.equal(layer.children[0].style.left, '150px');
  assert.equal(layer.children[0].style.top, '275px');
  assert.equal(layer.children[0].style.fontSize, '50px');

  r.api.setZoomFactor(2);
  await r.flush();
  await r.flush();
  layer = layerAt();
  assert.ok(layer);
  assert.equal(layer.textContent, 'しゅう');
  // The cached revision reinstalls at the zoomed 1200x1600 display size.
  assert.equal(layer.children[0].style.left, '300px');
  assert.equal(layer.children[0].style.top, '550px');
  assert.equal(layer.children[0].style.fontSize, '100px');
  assert.equal(layer.children[0].style.width, 'max-content');
  assert.equal(layer.children[0].style.height, 'max-content');
});
