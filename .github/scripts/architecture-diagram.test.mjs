import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const sourcePath = new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-diagram.js', import.meta.url);
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.style = {}; }
  setAttribute(key, value) { this.attrs[key] = String(value); }
  getAttribute(key) { return this.attrs[key]; }
  appendChild(child) { this.children.push(child); return child; }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  addEventListener(name, handler) { (this.listeners[name] ||= []).push(handler); }
  removeEventListener(name, handler) { this.listeners[name] = (this.listeners[name] || []).filter(value => value !== handler); }
  fire(name, extra = {}) { const event = { key: '', preventDefault() {}, stopPropagation() {}, ...extra }; (this.listeners[name] || []).forEach(fn => fn(event)); }
}
function load() {
  assert.ok(existsSync(sourcePath), 'shared renderer must exist');
  const window = {};
  const document = { createElementNS: (_, tag) => new Element(tag) };
  vm.runInNewContext(readFileSync(sourcePath, 'utf8'), { window, document, Map, Set });
  return window.TaxonomyArchitectureDiagram;
}
const nodes = [{ id: 'A', anchor: true, x: 105, y: 207, width: 230, height: 86 }, { id: 'B', anchor: false }, { id: 'C', anchor: true }];
const edges = [{ id: 'AB', sourceId: 'A', targetId: 'B' }];

test('focus/unfocus and context use the same immutable scene for all sources', () => {
  const api = load();
  for (const source of ['analysis', 'snapshot', 'working']) {
    const state = api.createState(); state.select('A'); state.focus();
    assert.deepEqual([...api.visibleModel({ nodes, edges }, state.snapshot()).nodeIds], ['A', 'B']);
    state.unfocus(); assert.equal(api.visibleModel({ nodes, edges }, state.snapshot()).nodes.length, 3);
    state.focus(); state.select(null); assert.equal(state.snapshot().mode, 'overview', source);
    state.setContext(false); assert.equal(api.visibleModel({ nodes, edges }, state.snapshot()).nodes.length, 2);
  }
  assert.equal(nodes[0].x, 105);
});
test('scene refresh retains selected ID and user viewport, returning defensive snapshots', () => {
  const state = load().createState(); state.select('A'); state.restoreViewport({ x: 28, y: -94, k: 0.7 });
  state.refresh({ nodes, edges });
  assert.equal(state.snapshot().selectedNodeId, 'A');
  assert.deepEqual({ ...state.captureViewport() }, { x: 28, y: -94, k: 0.7 });
  const copy = state.captureViewport(); copy.x = 99;
  assert.equal(state.captureViewport().x, 28);
});
test('shared node rendering preserves geometry, metadata, and keyboard/touch click selection', () => {
  const api = load(); const parent = new Element('g'); let selections = 0, focuses = 0;
  const group = api.renderNode(parent, { id: 'A', x: 105, y: 207, width: 230, height: 86, prefix: 'architecture', color: '#cfe2ff', textColor: '#111', titleLines: ['Source label'], score: '★ 90%', meta: 'Direct match · Capability', tooltip: 'Evidence', ariaLabel: 'A, source label' }, { select() { selections++; }, focus() { focuses++; } });
  assert.equal(group.attrs.transform, 'translate(105,207)');
  assert.equal(group.children[0].attrs.width, '230');
  assert.ok(group.children.some(child => child.textContent === '★ 90%'));
  group.fire('click', { pointerType: 'touch' }); group.fire('keydown', { key: 'Enter' }); group.fire('keydown', { key: ' ' }); group.fire('keydown', { key: 'f' });
  assert.equal(selections, 3); assert.equal(focuses, 1);
});
test('shared edges retain direction, geometry, labels and keyboard activation', () => {
  const api = load(), parent = new Element('g'); let selected;
  const group = api.renderEdge(parent, { id: 'AB', source: 'A', target: 'B', prefix: 'architecture', path: 'M335,250 C390,250 390,300 440,300', color: '#27834a', markerId: 'test-arrow', label: 'SUPPORTS', labelX: 390, labelY: 270, ariaLabel: 'A supports B', tooltip: 'source evidence' }, { select(id) { selected = id; } });
  assert.equal(group.children[0].attrs['marker-end'], 'url(#test-arrow)');
  assert.equal(group.children[0].attrs.d, 'M335,250 C390,250 390,300 440,300');
  group.children[1].fire('keydown', { key: 'Enter' }); assert.equal(selected, 'AB');
});
test('hidden panes cannot produce a fit until they become measurable', () => {
  const api = load();
  assert.equal(api.calculateFitTransform({ width: 2000, height: 700 }, { width: 0, height: 0 }, { requireVisible: true }), null);
  const fit = api.calculateFitTransform({ x: 105, y: 207, width: 2000, height: 700 }, { width: 500, height: 300 }, { requireVisible: true });
  assert.ok(fit.scale > 0 && fit.scale < 0.25);
  assert.ok(fit.x + 105 * fit.scale >= 0);
});
test('lens region intersection and suggestion routes use scene geometry without mutation', () => {
  const api = load();
  assert.deepEqual([...api.regionNodeIds(nodes, [110, 210], 20)], ['A']);
  assert.equal(api.suggestionPath(nodes[0], { x: 600, y: 207, width: 230, height: 86 }), 'M335,250 L600,250');
});

function engineHarness() {
  const document = { activeElement: null, createElementNS: (_, tag) => new Element(tag) };
  let zoomHandler, observers = [], frames = new Map(), nextFrame = 1;
  function identity(x = 0, y = 0, k = 1) { return { x, y, k, translate(a, b) { return identity(a, b, k); }, scale(value) { return identity(x, y, value); }, invert(point) { return [(point[0] - x) / k, (point[1] - y) / k]; }, toString() { return `translate(${x},${y}) scale(${k})`; } }; }
  function selection(element) { return {
    append(tag) { return selection(element.appendChild(document.createElementNS('', tag))); },
    attr(key, value) { if (value === null) element.removeAttribute(key); else element.setAttribute(key, value); return this; },
    node() { return element; }, on() { return this; }, call(fn, ...args) { fn(this, ...args); return this; }, remove() { element.remove(); }
  }; }
  const d3 = { select: selection, zoomIdentity: identity(), zoomTransform: element => element.__zoom || identity(), pointer: event => [event.x, event.y],
    zoom() { let extent = [0.01, 4]; const zoom = function () {};
      zoom.scaleExtent = value => { if (!value) return extent; extent = value; return zoom; };
      zoom.filter = () => zoom; zoom.on = (_, handler) => { zoomHandler = handler; return zoom; };
      zoom.transform = (selection, value) => { selection.node().__zoom = value; zoomHandler({ transform: value }); };
      zoom.scaleBy = (selection, factor) => { const current = d3.zoomTransform(selection.node()); zoom.transform(selection, identity(current.x, current.y, current.k * factor)); };
      zoom.translateBy = (selection, x, y) => { const current = d3.zoomTransform(selection.node()); zoom.transform(selection, identity(current.x + x * current.k, current.y + y * current.k, current.k)); };
      return zoom;
    }
  };
  const window = { d3 };
  const context = { window, document, Map, Set, requestAnimationFrame(fn) { const id = nextFrame++; frames.set(id, fn); return id; }, cancelAnimationFrame(id) { frames.delete(id); }, ResizeObserver: class { constructor(fn) { this.fn = fn; observers.push(this); } observe() {} disconnect() { this.disconnected = true; } } };
  vm.runInNewContext(readFileSync(sourcePath, 'utf8'), context);
  const svg = new Element('svg'); svg.width = 1000; svg.height = 420;
  svg.getScreenCTM = () => ({ a: 1, b: 0, c: 0, d: 1 });
  svg.getBoundingClientRect = () => ({ width: svg.width, height: svg.height });
  return { api: window.TaxonomyArchitectureDiagram, svg, document, d3, frames, observers,
    flush() { const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn()); } };
}
Element.prototype.replaceChildren = function (...children) { this.children = []; this.append(...children); };
Element.prototype.removeAttribute = function (key) { delete this.attrs[key]; };
Element.prototype.remove = function () { this.removed = true; };
Element.prototype.classList = { add() {}, remove() {}, toggle() {} };

test('working scene adapter keeps server positions and viewport through refresh, focus/unfocus and teardown', () => {
  const h = engineHarness(), selected = [];
  const renderer = h.api.create(h.svg, { onSelect(id) { selected.push(id); } });
  const scene = { nodes: [nodes[0]], edges: [], width: 500, height: 400 };
  renderer.render(scene, 'A'); renderer.restoreViewport({ x: 28, y: -94, k: 0.7 }); h.flush();
  renderer.render(scene, 'A');
  assert.deepEqual({ ...renderer.captureViewport() }, { x: 28, y: -94, k: 0.7 });
  renderer.focus(); h.flush(); renderer.unfocus(); h.flush();
  assert.deepEqual({ ...renderer.captureViewport() }, { x: 28, y: -94, k: 0.7 });
  const group = h.svg.children[3].children[0];
  assert.equal(group.attrs.transform, 'translate(105,207)');
  group.fire('keydown', { key: 'Enter' }); assert.deepEqual(selected, ['A']);
  renderer.destroy(); assert.equal(h.frames.size, 0);
  assert.ok(Object.values(h.svg.listeners).every(list => list.length === 0));
});
test('fit requested while working scene is hidden waits for measured visibility', () => {
  const h = engineHarness(); h.svg.width = 0; h.svg.height = 0;
  const renderer = h.api.create(h.svg, {});
  renderer.render({ nodes: [nodes[0]], edges: [], width: 2000, height: 700 }, 'A');
  renderer.fit();
  assert.deepEqual({ ...renderer.captureViewport() }, { x: 0, y: 0, k: 1 });
  h.svg.width = 1000; h.svg.height = 420; h.observers.forEach(observer => observer.fn()); h.flush();
  assert.ok(renderer.captureViewport().k < 1);
  renderer.destroy(); assert.ok(h.observers.every(observer => observer.disconnected));
});

test('analysis/snapshot lens callbacks use displayed nodes and teardown removes keyboard and pointer handlers', () => {
  const h = engineHarness(); const regions = [], suggestions = [], actions = [];
  h.svg.__zoom = h.d3.zoomIdentity;
  const controller = h.api.connect(h.svg, {
    getNodes: () => [nodes[0], { id: 'B', x: 600, y: 207, width: 230, height: 86 }], getNodeElements: () => new Map(),
    applyTransform(value) { h.svg.__zoom = value; }, select(id) { actions.push(id); }, focus() {}, unfocus() { actions.push('unfocus'); }, fit() {}, zoom() {},
    onRegion(ids) { regions.push([...ids]); }, onSuggestion(id) { suggestions.push(id); }
  });
  const lens = { enabled: true, states: {}, suggestions: [{ id: 'proposal', sourceId: 'A', targetId: 'B', relationType: 'SUPPORTS' }, { id: 'off-scene', sourceId: 'A', targetId: 'C' }] };
  controller.presentFocus(lens); controller.presentFocus(lens);
  h.svg.fire('pointerdown', { x: 110, y: 210 }); assert.deepEqual(regions, [['A']]);
  const overlay = h.svg.children.find(child => child.attrs.class === 'architecture-lens-suggestions');
  assert.equal(overlay.children.length, 1); overlay.children[0].fire('keydown', { key: ' ' }); assert.deepEqual(suggestions, ['proposal']);
  h.svg.fire('keydown', { key: 'ArrowRight', target: h.svg }); assert.equal(controller.captureViewport().x, -60);
  h.svg.fire('keydown', { key: 'Escape', target: h.svg }); assert.deepEqual(actions, ['unfocus']);
  controller.destroy(); assert.ok(Object.values(h.svg.listeners).every(list => list.length === 0));
});

test('common edge routing preserves reverse direction and explicit server endpoints', () => {
  const api = load();
  const left = { x: 38, y: 62, width: 230, height: 86, layerIndex: 0 };
  const right = { x: 336, y: 62, width: 230, height: 86, layerIndex: 1 };
  assert.equal(api.edgePath(right, left, 0), 'M336,105 C294,105 310,105 268,105');
  assert.equal(api.edgePath(left, right, 0, { sourceX: 251, sourceY: 99, targetX: 344, targetY: 111 }), 'M251,99 C293,99 302,111 344,111');
});
