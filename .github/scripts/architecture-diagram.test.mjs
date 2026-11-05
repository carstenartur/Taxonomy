import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const sourcePath = new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-diagram.js', import.meta.url);

/** A small DOM with real parentage, selectors, class mutation, focus and bubbling. */
class Element {
  constructor(tag, document) {
    this.tagName = tag; this.ownerDocument = document; this.children = []; this.attrs = {};
    this.listeners = {}; this.style = {}; this.dataset = {}; this.parentNode = null; this.textContent = '';
    this.classList = {
      contains: value => this.classes().includes(value),
      add: (...values) => this.setAttribute('class', [...new Set([...this.classes(), ...values])].join(' ')),
      remove: (...values) => this.setAttribute('class', this.classes().filter(value => !values.includes(value)).join(' ')),
      toggle: (value, force) => {
        const enabled = force === undefined ? !this.classList.contains(value) : Boolean(force);
        if (enabled) this.classList.add(value); else this.classList.remove(value);
        return enabled;
      }
    };
  }
  classes() { return (this.attrs.class || '').split(/\s+/).filter(Boolean); }
  setAttribute(key, value) {
    this.attrs[key] = String(value);
    if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = String(value);
  }
  getAttribute(key) { return this.attrs[key] ?? null; }
  removeAttribute(key) { delete this.attrs[key]; }
  getAttributeNames() { return Object.keys(this.attrs); }
  appendChild(child) { child.remove(); child.parentNode = this; child.ownerDocument ||= this.ownerDocument; this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  replaceChildren(...children) { this.children.forEach(child => { child.parentNode = null; }); this.children = []; this.append(...children); }
  remove() { if (this.parentNode) { this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; } }
  contains(child) { return child === this || this.children.some(value => value.contains(child)); }
  matches(selector) {
    return selector.split(',').some(part => {
      const simple = part.trim();
      if (!simple || simple.includes(' ')) return false;
      const tag = simple.match(/^[a-z][\w-]*/i)?.[0];
      if (tag && tag.toLowerCase() !== this.tagName.toLowerCase()) return false;
      if ([...simple.matchAll(/\.([\w-]+)/g)].some(match => !this.classList.contains(match[1]))) return false;
      if ([...simple.matchAll(/#([\w-]+)/g)].some(match => this.getAttribute('id') !== match[1])) return false;
      return [...simple.matchAll(/\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]/g)].every(match =>
        match[2] === undefined ? this.getAttribute(match[1]) !== null : this.getAttribute(match[1]) === match[2]);
    });
  }
  closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) || null; }
  querySelectorAll(selector) {
    const all = this.children.flatMap(child => [child, ...child.querySelectorAll('*')]);
    return all.filter(child => child.matches(selector));
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  focus() { this.ownerDocument.activeElement = this; }
  addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
  removeEventListener(name, handler) { this.listeners[name] = (this.listeners[name] || []).filter(value => value !== handler); }
  fire(name, extra = {}) {
    const event = { key: '', target: this, currentTarget: this, defaultPrevented: false, stopped: false,
      preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...extra };
    let current = this;
    while (current) {
      event.currentTarget = current;
      [...(current.listeners[name] || [])].forEach(fn => fn(event));
      if (event.stopped) break;
      current = current.parentNode;
    }
    return event;
  }
}
function engineHarness() {
  assert.ok(existsSync(sourcePath), 'shared renderer must exist');
  const document = { activeElement: null, createElementNS: (_, tag) => new Element(tag, document), createElement: tag => new Element(tag, document) };
  const frames = new Map(), observers = [], zooms = []; let nextFrame = 1;
  function identity(x = 0, y = 0, k = 1) {
    return { x, y, k, translate(a, b) { return identity(x + a * k, y + b * k, k); }, scale(value) { return identity(x, y, k * value); },
      invert(point) { return [(point[0] - x) / k, (point[1] - y) / k]; }, toString() { return `translate(${x},${y}) scale(${k})`; } };
  }
  function selection(element) { return {
    append(tag) { return selection(element.appendChild(document.createElementNS('', tag))); },
    attr(key, value) { if (value === null) element.removeAttribute(key); else element.setAttribute(key, typeof value === 'function' ? value(element.__data__) : value); return this; },
    classed(key, value) { element.classList.toggle(key, value); return this; },
    style(key, value) { element.style[key] = value; return this; }, text(value) { element.textContent = value; return this; },
    node() { return element; }, on() { return this; }, call(fn, ...args) { fn(this, ...args); return this; }, remove() { element.remove(); },
    interrupt() { return this; }, transition() { return this; }, duration() { return this; }
  }; }
  const d3 = { select: selection, zoomIdentity: identity(), zoomTransform: element => element.__zoom || identity(), pointer: event => [event.x, event.y],
    zoom() {
      let extent = [0.01, 4], handler = () => {}, filter = () => true;
      const zoom = function () {};
      zoom.scaleExtent = value => { if (!value) return extent; extent = value; return zoom; };
      zoom.filter = value => { if (!value) return filter; filter = value; return zoom; };
      zoom.on = (_, value) => { handler = value; return zoom; };
      zoom.transform = (selected, value) => { selected.node().__zoom = value; handler({ transform: value }); };
      zoom.scaleBy = (selected, factor) => { const value = d3.zoomTransform(selected.node()); zoom.transform(selected, identity(value.x, value.y, Math.max(extent[0], Math.min(extent[1], value.k * factor)))); };
      zoom.translateBy = (selected, x, y) => { const value = d3.zoomTransform(selected.node()); zoom.transform(selected, identity(value.x + x * value.k, value.y + y * value.k, value.k)); };
      zooms.push({ zoom, manual(svg, value) { svg.__zoom = identity(value.x, value.y, value.k); handler({ transform: svg.__zoom, sourceEvent: { type: 'wheel' } }); } });
      return zoom;
    }
  };
  const window = { d3, matchMedia: () => ({ matches: true }) };
  const requestAnimationFrame = fn => { const id = nextFrame++; frames.set(id, fn); return id; };
  const cancelAnimationFrame = id => frames.delete(id);
  const ResizeObserver = class { constructor(fn) { this.fn = fn; observers.push(this); } observe() {} disconnect() { this.disconnected = true; } };
  Object.assign(window, { requestAnimationFrame, cancelAnimationFrame, ResizeObserver });
  vm.runInNewContext(readFileSync(sourcePath, 'utf8'), { window, document, Map, Set, console, requestAnimationFrame, cancelAnimationFrame, ResizeObserver });
  const svg = new Element('svg', document); svg.width = 1000; svg.height = 420;
  svg.getScreenCTM = () => ({ a: 1, b: 0, c: 0, d: 1 });
  svg.getBoundingClientRect = () => ({ width: svg.width, height: svg.height });
  Object.defineProperties(svg, { clientWidth: { get: () => svg.width }, clientHeight: { get: () => svg.height } });
  return { api: window.TaxonomyArchitectureDiagram, svg, document, d3, frames, observers, zooms,
    frame() { const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn()); },
    flush() { for (let turn = 0; frames.size && turn < 12; turn++) this.frame(); },
    resize() { observers.filter(observer => !observer.disconnected).forEach(observer => observer.fn()); },
    node(id) { return svg.querySelector(`[data-node-id="${id}"]`); },
    edge(id) { return svg.querySelector(`[data-edge-id="${id}"]`); }
  };
}
test('shared palette resolves layer aliases and safely falls back for unknown data', () => {
  const palette = engineHarness().api.taxonomyPalette();
  assert.equal(palette.layer('BP-123'), palette.layer('Business Processes'));
  assert.equal(palette.layer('CR'), palette.layer('Core Services'));
  assert.equal(palette.layer('SY'), palette.layer('Systems'));
  assert.equal(palette.layer('CM'), palette.layer('Components'));
  assert.equal(palette.layer('__proto__'), '#4b5563');
  assert.equal(palette.layer('unknown'), '#4b5563');
  assert.equal(palette.relation('unknown'), '#6c757d');
});
test('automatic diagram text retains readable contrast for opaque gray backgrounds', () => {
  const { api } = engineHarness();
  const luminance = color => {
    const values = [1, 3, 5].map(offset => parseInt(color.slice(offset, offset + 2), 16) / 255)
      .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
    return 0.2126 * values[0] + 0.7152 * values[1] + 0.0722 * values[2];
  };
  for (let value = 0; value <= 255; value += 5) {
    const part = value.toString(16).padStart(2, '0'), background = '#' + part.repeat(3);
    const foreground = api.readableTextColor(background), a = luminance(background), b = luminance(foreground);
    assert.ok((Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) >= 4.5, `${background}: ${foreground}`);
  }
});
const scene = {
  width: 950, height: 370,
  nodes: [
    { id: 'A', label: 'Alpha capability', title: 'Alpha capability', searchText: 'a alpha capability', anchor: true, x: 105, y: 107, width: 230, height: 86 },
    { id: 'B', label: 'Beta service', title: 'Beta service', searchText: 'b beta service', anchor: false, context: true, x: 405, y: 107, width: 230, height: 86 },
    { id: 'C', label: 'Gamma evidence', title: 'Gamma evidence', searchText: 'c gamma evidence', anchor: true, x: 705, y: 107, width: 230, height: 86 }
  ],
  edges: [{ id: 'AB', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS', sourceX: 335, sourceY: 150, targetX: 405, targetY: 150 }]
};
function configured(h, source, callbacks = {}) {
  return h.api.create(h.svg, { prefix: source === 'analysis' ? 'impact-map' : source === 'snapshot' ? 'architecture' : 'editor',
    presentation: { viewportClass: 'test-viewport', selectedClass: 'is-selected', highlightNeighbors: true, edgeLabelLimit: 14 },
    viewport: { fixedWidth: 1000, fixedHeight: 420, minimumScale: 0.01, maximumScale: 4 },
    behavior: { focus: 'neighbors', clearOnBackground: true, lensLocksPan: true }, limits: { cull: false },
    layout: model => model, ...callbacks });
}
function activate(element, key = 'Enter') {
  assert.ok(element, 'rendered target exists');
  const target = element.getAttribute('role') === 'button' ? element : element.querySelector('[role="button"]') || element;
  target.fire('keydown', { key });
}
function ids(model) { return Array.from(model.nodes, node => node.id); }
function copy(value) { return JSON.parse(JSON.stringify(value)); }

for (const source of ['analysis', 'snapshot', 'working']) {
  test(`${source}: create owns activation, selection, combined search/neighborhood styling and immutable focus`, () => {
    const h = engineHarness(), selections = [], changes = [], original = JSON.stringify(scene);
    const renderer = configured(h, source, { onSelect: id => selections.push(id), onChange: view => changes.push(view) });
    renderer.render(scene); h.flush();
    assert.deepEqual(selections, [], 'rendering is not a user selection');
    assert.equal(h.node('A').getAttribute('transform'), 'translate(105,107)');
    const clickedNode = h.node('A');
    activate(h.node('A')); h.flush();
    assert.equal(h.node('A'), clickedNode, 'selection must retain the DOM target for native double-click focus');
    assert.equal(renderer.getState().selectedNodeId, 'A');
    assert.equal(h.node('A').getAttribute('aria-pressed'), 'true');
    assert.equal(h.node('A').classList.contains('is-selected'), true);
    assert.equal(h.node('B').classList.contains('is-muted'), false);
    assert.equal(h.node('C').classList.contains('is-muted'), true);
    assert.equal(h.edge('AB').classList.contains('is-connected'), true);
    renderer.setSearch('gamma'); h.flush();
    assert.deepEqual(Array.from(renderer.matchingNodeIds()), ['C']);
    assert.equal(h.node('A').classList.contains('is-muted'), false, 'selected node stays visible through search');
    assert.equal(h.node('B').classList.contains('is-muted'), true, 'search can mute a neighbor');
    assert.equal(h.node('C').classList.contains('is-muted'), true, 'a match outside the selected neighborhood stays muted');
    assert.equal(h.node('C').classList.contains('is-search-match'), true);
    assert.equal(h.edge('AB').classList.contains('is-muted'), true);
    renderer.select('A', 'AB'); h.flush();
    assert.equal(h.edge('AB').classList.contains('is-muted'), false, 'selected relation wins over search muting');
    renderer.setSearch(''); renderer.focus('A'); h.flush();
    assert.deepEqual(ids(renderer.getModel()), ['A', 'B']);
    renderer.unfocus(); h.flush();
    assert.deepEqual(ids(renderer.getModel()), ['A', 'B', 'C']);
    assert.equal(renderer.getState().selectedNodeId, 'A');
    renderer.setContext(false); h.flush();
    assert.deepEqual(ids(renderer.getModel()), ['A', 'C']);
    renderer.setContext(true); renderer.select(null); h.flush();
    assert.equal(renderer.getState().mode, 'overview');
    h.node('B').fire('click', { pointerType: 'touch' }); h.flush();
    assert.equal(renderer.getState().selectedNodeId, 'B');
    activate(h.node('C'), ' '); h.flush();
    assert.equal(renderer.getState().selectedNodeId, 'C');
    h.svg.fire('click'); h.flush();
    assert.equal(renderer.getState().selectedNodeId, null, 'background clears selection');
    assert.ok(changes.some(view => view.state && view.model && view.matches), 'source UI receives the common view contract');
    assert.equal(JSON.stringify(scene), original);
    renderer.destroy();
  });
}

test('refresh preserves selected ID and user viewport, returning defensive snapshots', () => {
  const h = engineHarness(), renderer = configured(h, 'snapshot');
  renderer.render(scene, 'A'); renderer.restoreViewport({ x: 28, y: -94, k: 0.7 }); h.flush();
  renderer.render(scene); h.flush();
  assert.equal(renderer.getState().selectedNodeId, 'A');
  assert.deepEqual(copy(renderer.captureViewport()), { x: 28, y: -94, k: 0.7 });
  const viewport = renderer.captureViewport(); viewport.x = 999;
  const state = renderer.getState(); state.selectedNodeId = 'C';
  assert.equal(renderer.captureViewport().x, 28); assert.equal(renderer.getState().selectedNodeId, 'A');
  renderer.render({ ...scene, nodes: scene.nodes.filter(node => node.id !== 'A'), edges: [] }); h.flush();
  assert.equal(renderer.getState().selectedNodeId, null); assert.equal(renderer.getState().mode, 'overview');
  renderer.destroy();
});

test('selection veto precedes commit and prevents focus or viewport movement', () => {
  const h = engineHarness(), attempts = [], renderer = configured(h, 'working', { onSelect: id => { attempts.push(id); return id !== 'B'; } });
  renderer.render(scene, 'A'); renderer.restoreViewport({ x: 21, y: 43, k: 0.8 }); h.flush();
  activate(h.node('B')); renderer.focus('B'); h.flush();
  assert.ok(attempts.includes('B')); assert.equal(renderer.getState().selectedNodeId, 'A');
  assert.equal(renderer.getState().mode, 'overview');
  assert.deepEqual(copy(renderer.captureViewport()), { x: 21, y: 43, k: 0.8 });
  renderer.destroy();
});

test('editor callback can reenter select without recursion or duplicate selection intents', () => {
  const h = engineHarness(), selected = []; let renderer;
  renderer = configured(h, 'working', { onSelect(id) { selected.push(id); renderer.select(id); } });
  renderer.render(scene); h.flush(); activate(h.node('A')); h.flush();
  assert.deepEqual(selected, ['A']); assert.equal(renderer.getState().selectedNodeId, 'A');
  renderer.destroy();
});

for (const enabled of [false, true]) test(`focus-panel feedback preserves the actual clicked card while the lens is ${enabled ? 'enabled' : 'disabled'}`, () => {
  const h = engineHarness(); let renderer;
  const presentation = { enabled, states: { A: 'supported' }, suggestions: [
    { id: 'P', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS' }
  ], label: status => `Assessment: ${status}` };
  renderer = configured(h, 'working', {
    onSelect(id) { renderer.select(id); renderer.presentFocus(presentation); },
    onChange() { renderer.presentFocus(presentation); }
  });
  renderer.render(scene); h.flush();
  const card = h.node('A'), hitTarget = card.querySelector('rect');
  hitTarget.fire('click'); h.flush();
  assert.equal(h.node('A'), card, 'reentrant panel presentation must keep the clicked node attached');
  assert.equal(card.querySelector('rect'), hitTarget, 'native clicks land on the card child, whose identity must also survive');
  assert.equal(h.svg.contains(hitTarget), true);
  assert.equal(card.getAttribute('aria-pressed'), 'true');
  renderer.presentFocus({ ...presentation, states: { A: 'error' }, reviewed: 'P' });
  assert.equal(h.node('A'), card, 'new assessment results must update the existing card');
  assert.equal(card.classList.contains('lens-supported'), false);
  assert.equal(card.classList.contains('lens-error'), enabled);
  assert.equal(card.getAttribute('aria-label'), 'Alpha capability · A' + (enabled ? ' · Assessment: error' : ''));
  assert.equal(h.svg.querySelectorAll('.editor-ghost').length, enabled ? 1 : 0);
  if (enabled) assert.equal(h.svg.querySelector('.editor-ghost').classList.contains('selected'), true);
  renderer.presentFocus({ enabled: false });
  assert.equal(card.getAttribute('data-lens-state'), null);
  assert.equal(card.getAttribute('aria-label'), 'Alpha capability · A');
  assert.equal(h.svg.querySelectorAll('.editor-ghost').length, 0);
  hitTarget.fire('dblclick'); h.flush();
  assert.equal(renderer.getState().mode, 'focus');
  assert.equal(renderer.getState().focusedNodeId, 'A');
  renderer.destroy();
});

test('editor relation activation emits its intent without committing general relation selection', () => {
  const h = engineHarness(), relations = [], renderer = configured(h, 'working', { onRelation: edge => relations.push(edge.id) });
  renderer.render(scene, 'A'); h.flush(); activate(h.edge('AB')); h.flush();
  assert.deepEqual(relations, ['AB']); assert.equal(renderer.getState().selectedNodeId, 'A');
  assert.equal(renderer.getState().selectedEdgeId, null); renderer.destroy();
});

test('working viewport focus restores its prior camera and retains explicit server geometry', () => {
  const h = engineHarness(), renderer = h.api.create(h.svg, { behavior: { focus: 'viewport' } });
  renderer.render(scene, 'A'); renderer.restoreViewport({ x: 28, y: -94, k: 0.7 }); h.flush();
  renderer.focus(); h.flush(); renderer.unfocus(); h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), { x: 28, y: -94, k: 0.7 });
  assert.equal(h.node('A').getAttribute('transform'), 'translate(105,107)');
  renderer.destroy();
});

test('hiding a focused context node rebuilds the complete overview and clears the selection', () => {
  const h = engineHarness(), renderer = configured(h, 'analysis');
  renderer.render(scene); renderer.focus('B'); h.flush();
  assert.deepEqual(ids(renderer.getModel()), ['A', 'B']);
  renderer.setContext(false); h.flush();
  assert.equal(renderer.getState().selectedNodeId, null);
  assert.equal(renderer.getState().mode, 'overview');
  assert.deepEqual(ids(renderer.getModel()), ['A', 'C']);
  renderer.destroy();
});

test('clearing focus by background or relationship selection restores the prior overview camera', () => {
  for (const clearBy of ['background', 'relationship']) {
    const h = engineHarness(), renderer = configured(h, 'snapshot');
    renderer.render(scene); renderer.restoreViewport({ x: 32, y: 48, k: 0.65 });
    renderer.focus('A'); h.flush();
    if (clearBy === 'background') h.svg.fire('click'); else activate(h.edge('AB'));
    h.flush();
    assert.equal(renderer.getState().mode, 'overview');
    assert.deepEqual(ids(renderer.getModel()), ['A', 'B', 'C']);
    assert.deepEqual(copy(renderer.captureViewport()), { x: 32, y: 48, k: 0.65 });
    renderer.destroy();
  }
});

test('selecting a neighbor preserves the focused neighborhood until an explicit focus command', () => {
  const h = engineHarness(), renderer = configured(h, 'analysis');
  renderer.render({ ...scene, edges: [...scene.edges, { id: 'BC', sourceId: 'B', targetId: 'C' }] });
  renderer.focus('A'); h.flush();
  const neighbor = h.node('B'); activate(neighbor); h.flush();
  assert.equal(h.node('B'), neighbor);
  assert.deepEqual(ids(renderer.getModel()), ['A', 'B']);
  assert.equal(renderer.getState().selectedNodeId, 'B');
  renderer.focus('B'); h.flush();
  assert.deepEqual(ids(renderer.getModel()), ['A', 'B', 'C']);
  renderer.destroy();
});

test('captured diagram sessions restore both the focused view and its preceding overview after remount', () => {
  const h = engineHarness(); let renderer = configured(h, 'snapshot');
  renderer.render(scene); renderer.restoreViewport({ x: 28, y: -94, k: 0.7 });
  renderer.focus('A'); h.flush(); renderer.restoreViewport({ x: 23, y: 41, k: 0.8 });
  const saved = renderer.captureSession(), modifiedCopy = renderer.captureSession();
  modifiedCopy.state.selectedNodeId = 'C'; modifiedCopy.focusViewport.x = 999;
  assert.equal(renderer.getState().selectedNodeId, 'A');
  renderer.destroy(); renderer = configured(h, 'snapshot'); renderer.render(scene); renderer.restoreSession(saved); h.flush();
  assert.equal(renderer.getState().mode, 'focus');
  assert.deepEqual(ids(renderer.getModel()), ['A', 'B']);
  assert.deepEqual(copy(renderer.captureViewport()), { x: 23, y: 41, k: 0.8 });
  renderer.unfocus(); h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), { x: 28, y: -94, k: 0.7 });
  renderer.destroy();
});

for (const method of ['restore', 'zoom', 'manual']) test(`${method} cancels a pending fit while a source is hidden`, () => {
  const h = engineHarness(); h.svg.width = 0; h.svg.height = 0;
  const renderer = configured(h, 'snapshot'); renderer.render(scene, 'A'); renderer.fit();
  assert.deepEqual(copy(renderer.captureViewport()), { x: 0, y: 0, k: 1 });
  if (method === 'restore') renderer.restoreViewport({ x: 23, y: 41, k: 0.8 });
  else if (method === 'zoom') renderer.zoom(1.4);
  else h.zooms[0].manual(h.svg, { x: 17, y: 31, k: 0.9 });
  h.flush(); const intended = copy(renderer.captureViewport());
  h.svg.width = 1000; h.svg.height = 420; h.resize(); h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), intended);
  renderer.destroy();
});

for (const method of ['restore', 'zoom', 'manual']) test(`${method} during a pending fit retains deferred resize dimensions`, () => {
  const h = engineHarness(), renderer = configured(h, 'snapshot', { viewport: { fixedWidth: null, fixedHeight: null } });
  renderer.render(scene); renderer.fit();
  h.svg.width = 800; h.svg.height = 500; h.resize();
  if (method === 'restore') renderer.restoreViewport({ x: 20, y: 30, k: 0.8 });
  else if (method === 'zoom') renderer.zoom(1.4);
  else h.zooms[0].manual(h.svg, { x: 17, y: 31, k: 0.9 });
  const camera = copy(renderer.captureViewport());
  h.flush();
  assert.equal(h.svg.getAttribute('viewBox'), '0 0 800 500', 'canceling a pending fit must not lose its resize');
  assert.deepEqual(copy(renderer.captureViewport()), camera, 'deferred resizing must respect the user camera');
  renderer.destroy();
});

for (const source of ['analysis', 'snapshot', 'working']) for (const automatic of [false, true]) {
  test(`${source}: resize delivery defers DOM writes and preserves the ${automatic ? 'automatic' : 'manual'} camera lifecycle`, () => {
    const h = engineHarness(), fitStates = [], summaries = [];
    const renderer = configured(h, source, {
      viewport: { fixedWidth: null, fixedHeight: null }, limits: { cull: source === 'working' },
      onFitState: value => fitStates.push(value), onSummary: (...value) => summaries.push(value)
    });
    renderer.render(scene);
    if (automatic) renderer.fit(); else renderer.restoreViewport({ x: 20, y: 30, k: 0.8 });
    h.flush();
    const camera = copy(renderer.captureViewport()), card = h.node('A'), writes = [];
    const setAttribute = h.svg.setAttribute.bind(h.svg);
    h.svg.setAttribute = (key, value) => { writes.push(key); setAttribute(key, value); };
    fitStates.length = 0; summaries.length = 0;
    h.svg.width = 800; h.svg.height = 500;
    h.resize(); h.resize();
    assert.deepEqual(writes, [], 'observer delivery must not mutate the observed SVG');
    assert.equal(h.node('A'), card, 'observer delivery must not replace the observed subtree');
    assert.deepEqual(summaries, [], 'summary callbacks can change surrounding layout');
    assert.deepEqual(fitStates, [], 'fit status callbacks can mutate the observed container');
    assert.equal(h.frames.size, 1, 'repeated resize notifications share one pending update');
    h.flush();
    assert.equal(h.svg.getAttribute('viewBox'), '0 0 800 500');
    assert.equal(writes.filter(key => key === 'viewBox').length, 1);
    if (!automatic) assert.deepEqual(copy(renderer.captureViewport()), camera);
    else assert.equal(fitStates.at(-1), 'complete');
    writes.length = 0;
    h.resize(); h.flush();
    assert.deepEqual(writes, [], 'an unchanged viewBox must not invalidate SVG layout again');
    h.svg.width = 640; h.resize();
    renderer.setActive(false);
    assert.equal(h.frames.size, 0, 'suspension cancels pending resize and fit work');
    assert.equal(h.svg.getAttribute('viewBox'), '0 0 800 500');
    renderer.setActive(true); h.flush();
    assert.equal(h.svg.getAttribute('viewBox'), '0 0 640 500');
    if (!automatic) assert.deepEqual(copy(renderer.captureViewport()), camera);
    h.svg.width = 700; h.resize(); renderer.destroy();
    assert.equal(h.frames.size, 0, 'teardown cancels pending resize work');
    assert.equal(h.svg.children.length, 0);
  });
}

test('hidden source fits once measurable and reports readiness without duplicating handlers or observers', () => {
  const h = engineHarness(), fitStates = []; h.svg.width = 0; h.svg.height = 0;
  const renderer = configured(h, 'snapshot', { onFitState: value => fitStates.push(value) });
  const wide = { ...scene, width: 3000, nodes: [{ ...scene.nodes[0], width: 3000 }] };
  renderer.render(wide, 'A'); renderer.fit();
  h.svg.width = 1000; h.svg.height = 420; h.resize(); h.flush();
  assert.ok(renderer.captureViewport().k < 1); assert.ok(fitStates.includes('complete'));
  renderer.render(wide, 'A'); renderer.presentFocus({ enabled: false, states: {}, suggestions: [] }); h.flush();
  assert.equal(h.observers.length, 1);
  for (const name of ['keydown', 'pointermove', 'pointerdown', 'pointerleave', 'pointercancel']) assert.equal(h.svg.listeners[name]?.length, 1, name);
  renderer.zoom(1.1); renderer.destroy();
  assert.equal(h.frames.size, 0); assert.ok(h.observers.every(observer => observer.disconnected));
  assert.ok(Object.values(h.svg.listeners).every(list => list.length === 0)); assert.equal(h.svg.querySelectorAll('g').length, 0);
});

test('working initial framing stays readable after resize and inactive fit requests preserve a manual camera', () => {
  const h = engineHarness(), renderer = h.api.create(h.svg, {});
  renderer.render({ ...scene, width: 5000, nodes: [scene.nodes[0], { ...scene.nodes[2], x: 4500 }] });
  renderer.fit({ initial: true }); h.flush();
  const initial = copy(renderer.captureViewport());
  assert.ok(initial.k >= 1);
  h.resize(); h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), initial);
  renderer.restoreViewport({ x: 23, y: 41, k: 0.8 }); renderer.setActive(false);
  renderer.fit(); h.flush(); renderer.setActive(true); h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), { x: 23, y: 41, k: 0.8 });
  renderer.destroy();
});

test('the initial resize notification does not postpone a stable readable working frame', () => {
  const h = engineHarness(), fitStates = [];
  h.svg.width = 300; h.svg.getScreenCTM = () => ({ a: 0.3, b: 0, c: 0, d: 0.3 });
  const renderer = h.api.create(h.svg, { onFitState: state => fitStates.push(state) });
  renderer.render({ width: 366, height: 684, edges: [], nodes: [
    { id: 'A', label: 'First card', x: 52, y: 52, width: 238, height: 82 },
    { id: 'B', label: 'Second card', x: 52, y: 168, width: 238, height: 82 }
  ] });
  renderer.fit({ initial: true });
  h.frame(); h.resize(); h.frame();
  const scale = renderer.captureViewport().k * 0.3;
  assert.ok(238 * scale >= 180 && 82 * scale >= 50,
    'a stable visible editor must reach readable framing within the two measured frames despite its initial observer notification');
  assert.deepEqual(fitStates, ['pending', 'complete'], 'one observer must not restart an already pending fit');
  renderer.destroy();
});

test('explicit working Fit covers a large server scene below the ordinary zoom floor', () => {
  const h = engineHarness(), renderer = h.api.create(h.svg, {}), width = 262104;
  renderer.render({ width, height: 420, nodes: [scene.nodes[0], { ...scene.nodes[2], x: width - 230 }], edges: [] });
  renderer.fit(); h.flush();
  const fitted = renderer.captureViewport();
  assert.ok(fitted.k < 0.01, 'large working scenes must fit below the normal interaction floor');
  assert.ok(fitted.x + width * fitted.k <= 990 + 1e-7);
  renderer.zoom(1.1); h.flush();
  assert.ok(Math.abs(renderer.captureViewport().k - fitted.k * 1.1) < 1e-9, 'manual zoom starts from the full-model fit');
  renderer.destroy();
});

for (const restoredThrough of ['unfocus', 'remount']) test(`snapshot zoom continues smoothly from a large fitted camera after ${restoredThrough}`, () => {
  const h = engineHarness(), options = { viewport: { minimumScale: 0.2, extendMinimumForFit: true,
    fixedWidth: 1000, fixedHeight: 420 } };
  const wide = { ...scene, nodes: [scene.nodes[0], scene.nodes[1], { ...scene.nodes[2], x: 50000 }] };
  let renderer = configured(h, 'snapshot', options);
  renderer.render(wide); renderer.fit(); h.flush();
  const overview = copy(renderer.captureViewport());
  assert.ok(overview.k < 0.2, 'the full snapshot requires a fit below the ordinary zoom floor');
  if (restoredThrough === 'unfocus') {
    renderer.focus('A'); h.flush();
    assert.ok(renderer.captureViewport().k > 0.2, 'the focused neighborhood uses its normal zoom range');
    renderer.unfocus();
  } else {
    const saved = renderer.captureSession(); renderer.destroy();
    renderer = configured(h, 'snapshot', options); renderer.render(wide); renderer.restoreSession(saved);
  }
  h.flush();
  assert.deepEqual(copy(renderer.captureViewport()), overview);
  renderer.zoom(1.4); h.flush();
  assert.ok(Math.abs(renderer.captureViewport().k - overview.k * 1.4) < 1e-9,
    'the next zoom must scale the restored camera instead of jumping to the default minimum');
  renderer.zoom(1 / 1.4); h.flush();
  assert.ok(Math.abs(renderer.captureViewport().k - overview.k) < 1e-9);
  renderer.destroy();
});

test('a focused relation remains the keyboard target after lens redraw', () => {
  const h = engineHarness(), renderer = configured(h, 'snapshot'); renderer.render(scene); h.flush();
  const edge = h.edge('AB'), target = edge.getAttribute('role') === 'button' ? edge : edge.querySelector('[role="button"]');
  assert.ok(target); target.focus();
  renderer.presentFocus({ enabled: false, states: {}, suggestions: [] }); h.flush();
  assert.ok(h.svg.contains(h.document.activeElement), 'focused target stays attached');
  assert.equal(h.document.activeElement.closest('[data-edge-id]')?.getAttribute('data-edge-id'), 'AB');
  renderer.destroy();
});

test('lens pointers account for screen scaling and zoom, ignore off-scene suggestions, clear on leave/cancel and teardown', () => {
  const h = engineHarness(), regions = [], suggestions = [];
  h.svg.getScreenCTM = () => ({ a: 2, b: 0, c: 0, d: 2 });
  const renderer = configured(h, 'analysis', { onRegion: value => regions.push(Array.from(value)), onSuggestion: id => suggestions.push(id) });
  const nearby = { width: 300, height: 200, nodes: [
    { id: 'A', label: 'Alpha', x: 100, y: 100, width: 10, height: 10 },
    { id: 'B', label: 'Beta', x: 130, y: 100, width: 10, height: 10 }
  ], edges: [] };
  renderer.render(nearby); renderer.restoreViewport({ x: 20, y: 40, k: 2 }); h.flush();
  renderer.presentFocus({ enabled: true, states: { A: 'supported' }, suggestions: [
    { id: 'proposal', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS' },
    { id: 'off-scene', sourceId: 'A', targetId: 'C', relationType: 'SUPPORTS' }
  ] }); h.flush();
  h.svg.fire('pointerdown', { x: 220, y: 240 }); assert.deepEqual(regions.at(-1), ['A']);
  const proposals = h.svg.querySelectorAll('[data-suggestion-id]'); assert.equal(proposals.length, 1);
  activate(proposals[0], ' '); assert.deepEqual(suggestions, ['proposal']);
  assert.equal(h.node('A').classList.contains('lens-supported'), true);
  h.svg.fire('pointerleave'); assert.deepEqual(regions.at(-1), []);
  h.svg.fire('pointermove', { x: 220, y: 240 }); h.svg.fire('pointercancel'); assert.deepEqual(regions.at(-1), []);
  renderer.setActive(false); const count = regions.length;
  h.svg.fire('pointermove', { x: 220, y: 240 }); assert.equal(regions.length, count);
  renderer.setActive(true); renderer.destroy();
  h.svg.fire('pointermove', { x: 220, y: 240 }); assert.equal(regions.length, count);
});

test('working defaults cap visible nodes at 200 and total rendered edges at 400 including 32 proposals, retaining selection', () => {
  const h = engineHarness(), renderer = h.api.create(h.svg, {});
  const large = { width: 1000, height: 420,
    nodes: Array.from({ length: 201 }, (_, i) => ({ id: `N${i}`, x: 100, y: 100, width: 100, height: 40 })),
    edges: Array.from({ length: 450 }, (_, i) => ({ id: `E${i}`, sourceId: 'N0', targetId: 'N1', relationType: 'SUPPORTS', sourceX: 200, sourceY: 120, targetX: 100, targetY: 120 })) };
  renderer.render(large, 'N200'); renderer.presentFocus({ enabled: true, states: {}, suggestions: Array.from({ length: 40 }, (_, i) => ({ id: `P${i}`, sourceId: 'N0', targetId: 'N1', relationType: 'SUPPORTS' })) }); h.flush();
  assert.equal(h.svg.querySelectorAll('[data-node-id]').length, 200); assert.ok(h.node('N200'));
  const relationIds = new Set(h.svg.querySelectorAll('[data-edge-id]').map(element => element.getAttribute('data-edge-id')));
  const proposalIds = new Set(h.svg.querySelectorAll('[data-suggestion-id]').map(element => element.getAttribute('data-suggestion-id')));
  assert.equal(proposalIds.size, 32); assert.equal(relationIds.size, 368); assert.equal(relationIds.size + proposalIds.size, 400);
  renderer.destroy();
});

test('shared node and edge primitives retain geometry, metadata and keyboard/touch activation', () => {
  const h = engineHarness(), parent = new Element('g', h.document); let selections = 0, focuses = 0, relation;
  const node = h.api.renderNode(parent, { id: 'A', x: 105, y: 207, width: 230, height: 86, prefix: 'architecture', titleLines: ['Source label'], score: '★ 90%', meta: 'Direct match · Capability', tooltip: 'Evidence', ariaLabel: 'A, source label' }, { select() { selections++; }, focus() { focuses++; } });
  assert.equal(node.getAttribute('transform'), 'translate(105,207)');
  assert.equal(node.querySelector('.architecture-node-card').getAttribute('width'), '230');
  assert.equal(node.querySelector('.architecture-node-score').textContent, '★ 90%');
  node.fire('click', { pointerType: 'touch' }); activate(node); activate(node, ' '); activate(node, 'f');
  assert.equal(selections, 3); assert.equal(focuses, 1);
  const edge = h.api.renderEdge(parent, { id: 'AB', source: 'A', target: 'B', prefix: 'architecture', path: 'M335,250 C390,250 390,300 440,300', color: '#27834a', markerId: 'test-arrow', label: 'SUPPORTS', labelX: 390, labelY: 270, ariaLabel: 'A supports B' }, { select(id) { relation = id; } });
  assert.equal(edge.querySelector('.architecture-edge-path').getAttribute('marker-end'), 'url(#test-arrow)');
  assert.equal(edge.querySelector('.architecture-edge-path').getAttribute('d'), 'M335,250 C390,250 390,300 440,300');
  activate(edge); assert.equal(relation, 'AB');
});

test('hidden panes cannot produce a fit until measurable and wide geometry fits completely', () => {
  const { api } = engineHarness();
  assert.equal(api.calculateFitTransform({ width: 2000, height: 700 }, { width: 0, height: 0 }, { requireVisible: true }), null);
  const fit = api.calculateFitTransform({ x: 105, y: 207, width: 2000, height: 700 }, { width: 500, height: 300 }, { requireVisible: true });
  assert.ok(fit.scale > 0 && fit.scale < 0.25); assert.ok(fit.x + 105 * fit.scale >= 0);
});

test('lens region geometry and common routing preserve direction and explicit server endpoints', () => {
  const { api } = engineHarness();
  assert.deepEqual(Array.from(api.regionNodeIds(scene.nodes, [110, 110], 20)), ['A']);
  assert.equal(api.suggestionPath(scene.nodes[0], { x: 600, y: 107, width: 230, height: 86 }), 'M335,150 L600,150');
  const left = { x: 38, y: 62, width: 230, height: 86, layerIndex: 0 }, right = { x: 336, y: 62, width: 230, height: 86, layerIndex: 1 };
  assert.equal(api.edgePath(right, left, 0), 'M336,105 C294,105 310,105 268,105');
  assert.equal(api.edgePath(left, right, 0, { sourceX: 251, sourceY: 99, targetX: 344, targetY: 111 }), 'M251,99 C293,99 302,111 344,111');
});
