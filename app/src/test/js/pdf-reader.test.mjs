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
    if (this.style.display === 'none') return 0;
    const [width, height] = (this.style.aspectRatio || '1 / 1').split('/').map(Number);
    return this.offsetWidth * height / width;
  }
  get offsetTop() {
    const siblings = this.parentElement?.children || [];
    return 8 + siblings.slice(0, siblings.indexOf(this)).reduce((sum, item) => sum + (item.style.display === 'none' ? 0 : item.offsetHeight + 12), 0);
  }
  get offsetLeft() {
    // #hoshi-pdf-pages centers pages inside max(viewport, widest page).
    if (!this.classList.contains('hoshi-pdf-page') || this.style.display === 'none') return 0;
    const siblings = this.parentElement?.children || [];
    const widest = Math.max(this.parentElement?.parentElement?.clientWidth || 0,
      ...siblings.filter((item) => item.style.display !== 'none').map((item) => item.offsetWidth));
    return (widest - this.offsetWidth) / 2;
  }
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

function reader({ count = 12, pendingRender = false, pendingText = false, texts = ['alpha', 'beta.'], outline = null, dests = {}, pageWidths = {} } = {}) {
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
  const timers = new Map();
  let nextTimer = 1;
  let selection = null;
  let destroyed = 0;
  let loadError = false;
  let pageGate = null;
  const makeViewport = (scale, baseWidth = 450) => ({
    width: baseWidth * scale, height: 600 * scale, scale,
    clone: ({ scale: next }) => makeViewport(next, baseWidth),
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
        getViewport: ({ scale }) => makeViewport(scale, pageWidths[number]),
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
    setTimeout: (callback) => { timers.set(nextTimer, callback); return nextTimer++; },
    clearTimeout: (id) => timers.delete(id),
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
    runTimers() {
      const pending = [...timers.values()];
      timers.clear();
      pending.forEach((callback) => callback());
    },
    get pendingTimers() { return timers.size; },
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

function ocrPayload(pageIndex = 0, revisionId = 'old', requestId = 1, text = '猫') {
  return {
    sessionId: 'ocr-test', pageIndex, revisionId, requestId, sourceHash: 'a'.repeat(64),
    canonicalText: text, cssWidth: 600, cssHeight: 800,
    spans: [{ sourceId: 'line', startUtf16: 0, endUtf16: text.length, text, direction: 'HORIZONTAL',
      quad: { x0: 0.1, y0: 0.1, x1: 0.8, y1: 0.1, x2: 0.8, y2: 0.2, x3: 0.1, y3: 0.2 } }],
  };
}

test('explicit single-page mode and switching modes preserve page and zoom', async () => {
  const r = reader();
  await r.api.open({ pageIndex: 7, continuousScroll: false });
  await r.flush();
  const shown = () => r.root.children.filter(page => page.style.display !== 'none');
  assert.deepEqual(shown(), [r.root.children[7]]);
  assert.equal(r.viewport.scrollTop, 0);
  const visible = r.messages.filter(m => m.type === 'ocrViewport').at(-1);
  assert.equal(visible.firstVisible, 7);
  assert.equal(visible.lastVisible, 7);
  r.api.setZoomFactor(2);
  r.api.setViewMode(true);
  await r.flush();
  assert.equal(shown().length, 12);
  assert.equal(JSON.parse(r.api.progress()).pageIndex, 7);
  assert.equal(r.root.children[7].offsetWidth, 1200);
  r.api.setViewMode(false);
  await r.flush();
  assert.deepEqual(shown(), [r.root.children[7]]);
  r.api.jumpToPage(100);
  await r.flush();
  assert.deepEqual(shown(), [r.root.children[11]]);
});

test('single-page swipes turn pages, respect boundaries, and do not hijack zoom or continuous scroll', async () => {
  const r = reader({ count: 3 });
  await r.api.open({ continuousScroll: false });
  await r.flush();
  const swipe = (dx, dy = 0) => {
    r.viewport.dispatch('touchstart', { touches: [{ clientX: 300, clientY: 300 }] });
    r.viewport.dispatch('touchmove', { touches: [{ clientX: 300 + dx, clientY: 300 + dy }] });
    r.viewport.dispatch('touchend', { touches: [], changedTouches: [{ clientX: 300 + dx, clientY: 300 + dy }] });
  };
  const page = () => JSON.parse(r.api.progress()).pageIndex;
  swipe(100);
  assert.equal(page(), 0);
  swipe(-71);
  assert.equal(page(), 0);
  swipe(-72);
  await r.flush();
  assert.equal(page(), 1);
  swipe(-100, 200);
  assert.equal(page(), 1);
  r.api.setZoomFactor(2);
  swipe(-100);
  assert.equal(page(), 1);
  await r.api.fitWidth();
  r.api.setViewMode(false, 0);
  swipe(-100);
  assert.equal(page(), 1);
  r.api.setViewMode(true, 72);
  await r.flush();
  swipe(-100);
  assert.equal(page(), 1);
  r.api.setViewMode(false);
  await r.flush();
  swipe(-100);
  await r.flush();
  swipe(-100);
  assert.equal(page(), 2);
  swipe(100);
  assert.equal(page(), 1);
});

test('OCR replacement waits for native selection and acknowledges the installed revision', async () => {
  const r = reader({ texts: [] });
  await r.api.open({ sessionId: 'ocr-test' });
  await r.flush();
  assert.equal(r.ocr.applyOcr(ocrPayload()), true);
  const old = r.root.children[0].children.find(child => child.classList.contains('hoshi-pdf-ocr-layer'));
  r.select(old.children[0]);
  assert.equal(r.ocr.applyOcr(ocrPayload(0, 'new', 2, '犬')), true);
  assert.equal(old.parentElement, r.root.children[0]);
  assert.equal(r.messages.filter(m => m.type === 'ocrInstalled').at(-1).revisionId, 'old');
  r.clearNativeSelection();
  r.ocr.selectionCleared();
  await r.flush();
  assert.equal(old.parentElement, null);
  assert.equal(r.messages.filter(m => m.type === 'ocrInstalled').at(-1).revisionId, 'new');
});

test('prefetched OCR waits until its single page is visible before fitting the overlay', async () => {
  const r = reader({ texts: [] });
  await r.api.open({ sessionId: 'ocr-test', continuousScroll: false });
  await r.flush();
  assert.equal(r.ocr.applyOcr(ocrPayload(1)), true);
  assert.equal(r.messages.some(m => m.type === 'ocrInstalled' && m.pageIndex === 1), false);
  r.api.jumpToPage(1);
  await r.flush();
  assert.ok(r.messages.some(m => m.type === 'ocrInstalled' && m.pageIndex === 1));
  const overlay = r.root.children[1].children.find(child => child.classList.contains('hoshi-pdf-ocr-layer'));
  assert.equal(overlay.textContent, '猫');
});

test('OCR lookup selection defers replacement and a saved highlight retains its original source', async () => {
  const r = reader({ texts: [] });
  await r.api.open({ sessionId: 'ocr-test' });
  await r.flush();
  r.ocr.applyOcr(ocrPayload());
  const old = r.root.children[0].children.find(child => child.classList.contains('hoshi-pdf-ocr-layer'));
  r.window.hoshiSelection.selection = { ranges: [{ node: old.children[0].children[0], start: 0, end: 1 }] };
  r.ocr.applyOcr(ocrPayload(0, 'new', 2, '犬'));
  assert.equal(old.parentElement, r.root.children[0]);
  r.highlights.applyHighlights([{ id: 'saved', character: 0, offset: 0, text: '猫', color: 'yellow' }]);
  r.window.hoshiSelection.clearSelection();
  await r.flush();
  assert.equal(old.parentElement, r.root.children[0]);
  assert.equal(r.ocr.applyOcr(ocrPayload(0, 'another', 3, '犬')), false);
});

test('session updates replay nearby probes and offscreen OCR responses are rejected', async () => {
  const r = reader({ count: 50, texts: [] });
  await r.api.open({ pageIndex: 20 });
  await r.flush();
  r.api.setSessionId('ocr-test');
  const probes = r.messages.filter(m => m.type === 'textProbe' && m.sessionId === 'ocr-test');
  assert.ok(probes.some(m => m.pageIndex === 20));
  const visible = r.messages.filter(m => m.type === 'ocrViewport' && m.sessionId === 'ocr-test').at(-1);
  assert.ok(visible.firstVisible <= 20 && visible.lastVisible >= 20);
  assert.equal(r.ocr.applyOcr(ocrPayload(0)), false);
  assert.equal(r.ocr.applyOcr(ocrPayload(20)), true);
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

test('double-tap on blank areas toggles fit-width and 2.5x at the tap point while text taps stay immediate', async () => {
  const r = reader();
  await r.api.open();
  await r.flush();
  const tap = (x, y, target) => {
    const touch = { clientX: x, clientY: y };
    r.viewport.dispatch('touchstart', { touches: [touch], target });
    r.viewport.dispatch('touchend', { touches: [], changedTouches: [touch] });
  };
  const taps = () => r.messages.filter((message) => message.type === 'tap');
  const zooms = () => r.messages.filter((message) => message.type === 'zoomChanged').map((message) => message.zoomFactor);

  tap(300, 400);
  assert.equal(taps().length, 0);
  r.runTimers();
  assert.deepEqual(taps().map(({ x, y }) => [x, y]), [[300, 400]]);

  const span = r.textLayers[0].textDivs[0];
  tap(300, 400, span);
  assert.equal(taps().length, 2);
  tap(300, 400, span);
  assert.equal(taps().length, 3);
  assert.equal(r.pendingTimers, 0);

  tap(100, 100);
  tap(500, 700);
  assert.deepEqual(taps().slice(3).map(({ x }) => x), [100]);
  r.runTimers();
  assert.deepEqual(taps().slice(3).map(({ x }) => x), [100, 500]);

  tap(300, 400);
  r.viewport.dispatch('touchstart', { touches: [{ clientX: 300, clientY: 400 }] });
  r.viewport.dispatch('touchmove', { touches: [{ clientX: 300, clientY: 500 }] });
  r.viewport.dispatch('touchend', { touches: [], changedTouches: [{ clientX: 300, clientY: 500 }] });
  tap(300, 400);
  r.viewport.dispatch('touchstart', { touches: [{ clientX: 100, clientY: 100 }, { clientX: 200, clientY: 100 }] });
  r.viewport.dispatch('touchcancel');
  r.runTimers();
  assert.equal(taps().length, 5);
  assert.deepEqual(zooms(), []);

  tap(300, 400);
  tap(300, 400);
  await r.flush();
  assert.deepEqual(zooms(), [2.5]);
  assert.equal(r.root.children[0].offsetWidth, 1500);
  // The tapped content point stays under the finger.
  assert.equal(r.viewport.scrollLeft, 450);
  assert.equal(r.viewport.scrollTop, 588);

  tap(300, 400);
  tap(300, 400);
  await r.flush();
  assert.deepEqual(zooms(), [2.5, 1]);
  assert.equal(r.root.children[0].offsetWidth, 600);
  assert.equal(r.viewport.scrollLeft, 0);
  assert.equal(r.viewport.scrollTop, 0);
  assert.equal(taps().length, 5);
  r.runTimers();
  assert.equal(r.pendingTimers, 0);
  assert.equal(taps().length, 5);
});

test('double-tap zoom animates from the old page box to the committed layout and touches cancel it', async () => {
  const r = reader();
  await r.api.open();
  await r.flush();
  const transforms = [];
  let transform = '';
  Object.defineProperty(r.root.style, 'transform', {
    get: () => transform,
    set: (value) => { transforms.push(value); transform = value; },
  });
  const tap = (x, y) => {
    const touch = { clientX: x, clientY: y };
    r.viewport.dispatch('touchstart', { touches: [touch] });
    r.viewport.dispatch('touchend', { touches: [], changedTouches: [touch] });
  };
  tap(300, 400);
  tap(300, 400);
  // Committed: page 0 is 1500px wide at offset (0, 8), scrolled to (450, 588).
  // The start transform maps it back onto its old 600px box at (0, 8).
  const [start, end] = transforms.slice(-2);
  const [, tx, ty, ratio] = start.match(/^translate\(([-\d.]+)px, ([-\d.]+)px\) scale\(([\d.]+)\)$/).map(Number);
  assert.equal(ratio, 0.4);
  assert.equal(tx, 450);
  assert.ok(Math.abs(ty - (8 + 588 - 8 * 0.4)) < 1e-9);
  assert.equal(end, 'none');
  assert.equal(r.root.style.transformOrigin, '0 0');
  assert.match(r.root.style.transition, /^transform 220ms /);
  assert.equal(r.pendingTimers, 1);

  // A new touch snaps to the committed layout before hit-testing.
  r.viewport.dispatch('touchstart', { touches: [{ clientX: 10, clientY: 10 }] });
  assert.equal(r.root.style.transform, '');
  assert.equal(r.root.style.transition, '');
  assert.equal(r.pendingTimers, 0);
  assert.equal(r.viewport.scrollLeft, 450);
  assert.equal(r.viewport.scrollTop, 588);
});

test('double-tap toggles a narrower page between its own fit width and 2.5x, anchored on that page', async () => {
  const r = reader({ count: 3, pageWidths: { 2: 225 } });
  await r.api.open();
  await r.flush();
  const narrow = r.root.children[1];
  assert.equal(narrow.offsetWidth, 300);
  assert.equal(narrow.offsetLeft, 150);
  // Page 0 stays current while the tap lands on the narrower page 1.
  r.viewport.scrollTop = 300;
  const doubleTap = (x, y) => {
    for (let i = 0; i < 2; i += 1) {
      const touch = { clientX: x, clientY: y };
      r.viewport.dispatch('touchstart', { touches: [touch] });
      r.viewport.dispatch('touchend', { touches: [], changedTouches: [touch] });
    }
  };
  doubleTap(300, 700);
  await r.flush();
  assert.equal(narrow.offsetWidth, 1500);
  assert.equal(r.viewport.scrollLeft, narrow.offsetLeft + 0.5 * narrow.offsetWidth - 300);
  assert.equal(r.viewport.scrollTop, narrow.offsetTop + 0.225 * narrow.offsetHeight - 700);
  // Zooming out fits the narrower page to the screen rather than page 0.
  doubleTap(300, 700);
  await r.flush();
  assert.equal(narrow.offsetWidth, 600);
  assert.equal(r.viewport.scrollLeft, narrow.offsetLeft);
  assert.equal(r.viewport.scrollTop, narrow.offsetTop + 0.225 * narrow.offsetHeight - 700);
  doubleTap(300, 700);
  await r.flush();
  assert.equal(narrow.offsetWidth, 1500);
  assert.ok(!r.messages.some((message) => message.type === 'tap'));
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
