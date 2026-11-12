import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const graphSourcePath = new URL(
  '../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-impact-map.js',
  import.meta.url
);
const indexPath = new URL(
  '../../taxonomy-app/src/main/resources/templates/index.html',
  import.meta.url
);
const scoringSourcePath = new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js',
  import.meta.url
);
const englishMessagesPath = new URL(
  '../../taxonomy-app/src/main/resources/i18n/messages_impact_map.properties',
  import.meta.url
);
const germanMessagesPath = new URL(
  '../../taxonomy-app/src/main/resources/i18n/messages_impact_map_de.properties',
  import.meta.url
);

const graphSource = readFileSync(graphSourcePath, 'utf8');
const scoringSource = readFileSync(scoringSourcePath, 'utf8');

function loadApi({ graphAvailable = true, document: providedDocument, configure } = {}) {
  const originalGraphSelect = function selectGraphNode() {};
  const document = providedDocument || { documentElement: {} };
  const window = {
    TaxonomyGraph: {
      selectNode: originalGraphSelect,
    },
    TaxonomyI18n: {
      t(key) {
        return key;
      },
    },
  };
  if (!graphAvailable) delete window.TaxonomyGraph;
  const context = vm.createContext({
    window,
    document,
    console,
    Map,
    Set,
    Array,
    Number,
    String,
    Math,
    Object,
    RegExp,
    Boolean,
    parseInt,
    CustomEvent: class { constructor(type, options = {}) { this.type = type; this.detail = options.detail; } },
    getComputedStyle() {
      return {
        getPropertyValue() {
          return '';
        },
      };
    },
  });
  vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/architecture-diagram.js', import.meta.url), 'utf8'), context);
  if (configure) configure(window);
  vm.runInContext(graphSource, context, { filename: 'taxonomy-impact-map.js' });
  return { api: window.TaxonomyImpactMap, graph: window.TaxonomyGraph, originalGraphSelect, common: window.TaxonomyArchitectureDiagram, window, document };
}

function propertyKeys(content) {
  return new Set(
    content
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'))
      .map((line) => line.slice(0, line.indexOf('=')))
  );
}

test('exports the analysis adapter directly without requiring or replacing Graph Explorer', () => {
  const { api } = loadApi({ graphAvailable: false });

  assert.equal(typeof api.render, 'function');
  assert.equal(typeof api.normalizeNodes, 'function');
  assert.equal(typeof api.buildLayout, 'function');
  assert.equal(typeof api.calculateFitTransform, 'function');
  const { graph, originalGraphSelect } = loadApi();
  assert.deepEqual(Object.keys(graph), ['selectNode']);
  assert.equal(graph.selectNode, originalGraphSelect);
});

test('normalizes anchors, context nodes, hotspots and layer metadata', () => {
  const { api } = loadApi();
  const nodes = api.normalizeNodes(
    [
      { nodeCode: 'CP-1', title: 'Direct capability', taxonomySheet: 'CP', relevance: 0.9, hopDistance: 0 },
      { nodeCode: 'CR-2', title: 'Supporting service', taxonomySheet: 'CR', relevance: 0.55, hopDistance: 1 },
    ],
    {
      anchorCodes: new Set(['CP-1']),
      hotspotCodes: new Set(['CR-2']),
      hotspotReasons: { 'CR-2': 'shared dependency' },
      layerConfig: {
        CP: { order: 1, label: 'Capabilities' },
        CR: { order: 3, label: 'Core Services' },
      },
    }
  );

  assert.equal(nodes.length, 2);
  assert.equal(nodes[0].anchor, true);
  assert.equal(nodes[0].context, false);
  assert.equal(nodes[0].layerOrder, 1);
  assert.equal(nodes[1].anchor, false);
  assert.equal(nodes[1].context, true);
  assert.equal(nodes[1].hotspot, true);
  assert.equal(nodes[1].hotspotReason, 'shared dependency');
  assert.equal(nodes[1].sheetLabel, 'Core Services');
});

test('deduplicates relationships and ignores missing endpoints', () => {
  const { api } = loadApi();
  const nodeById = new Map([
    ['A', { id: 'A' }],
    ['B', { id: 'B' }],
  ]);
  const edges = api.normalizeEdges(
    [
      { sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS' },
      { sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS' },
      { sourceCode: 'A', targetCode: 'C', relationType: 'SUPPORTS' },
    ],
    nodeById
  );

  assert.equal(edges.length, 1);
  assert.equal(edges[0].source, 'A');
  assert.equal(edges[0].target, 'B');
});

test('builds a deterministic layer layout instead of a force simulation', () => {
  const { api } = loadApi();
  const model = {
    nodes: [
      { id: 'CR-2', title: 'Service', sheet: 'CR', sheetLabel: 'Core Services', layerOrder: 3, relevance: 0.5, anchor: false, hotspot: false },
      { id: 'CP-1', title: 'Capability', sheet: 'CP', sheetLabel: 'Capabilities', layerOrder: 1, relevance: 0.9, anchor: true, hotspot: false },
      { id: 'CP-3', title: 'Second capability', sheet: 'CP', sheetLabel: 'Capabilities', layerOrder: 1, relevance: 0.4, anchor: false, hotspot: false },
    ],
    edges: [],
  };

  const first = api.buildLayout(model);
  const second = api.buildLayout(model);

  assert.deepEqual(
    [...first.positions].map(([id, position]) => [id, position.x, position.y]),
    [...second.positions].map(([id, position]) => [id, position.x, position.y])
  );
  assert.deepEqual(first.layers.map((layer) => layer.sheet), ['CP', 'CR']);
  assert.ok(first.positions.get('CR-2').x > first.positions.get('CP-1').x);
  assert.ok(first.positions.get('CP-3').y > first.positions.get('CP-1').y);
  assert.doesNotMatch(graphSource, /forceSimulation\s*\(/);
});

test('fits a wide seven-layer graph completely inside the viewport', () => {
  const { api } = loadApi();
  const model = {
    nodes: Array.from({ length: 7 }, (_, index) => ({
      id: `L${index}`,
      title: `Layer ${index}`,
      sheet: `L${index}`,
      sheetLabel: `Layer ${index}`,
      layerOrder: index,
      relevance: 0.9 - index / 20,
      anchor: index === 0,
      hotspot: false,
    })),
    edges: [],
  };
  const layout = api.buildLayout(model);
  const viewport = { width: 781, height: 620 };
  const fit = api.calculateFitTransform(layout, viewport, {
    padding: 34,
    minimumScale: 0.01,
    maximumScale: 1.25,
  });

  assert.ok(fit.scale < 0.62, 'fixture must exercise the former clipping branch');
  assert.ok(fit.left >= 34 - 0.001);
  assert.ok(fit.right <= viewport.width - 34 + 0.001);
  assert.ok(fit.top >= 34 - 0.001);
  assert.ok(fit.bottom <= viewport.height - 34 + 0.001);
});

test('context and focus modes reduce the visible graph predictably', () => {
  const { api } = loadApi();
  const allNodes = [
    { id: 'A', anchor: true, context: false, relevance: 0.9 },
    { id: 'B', anchor: false, context: true, relevance: 0.7 },
    { id: 'C', anchor: false, context: true, relevance: 0.6 },
    { id: 'D', anchor: true, context: false, relevance: 0.5 },
  ];
  const allEdges = [
    { id: 'AB', source: 'A', target: 'B' },
    { id: 'BC', source: 'B', target: 'C' },
    { id: 'CD', source: 'C', target: 'D' },
  ];

  const withoutContext = api.visibleModel({
    allNodes,
    allEdges,
    showContext: false,
    mode: 'overview',
    selectedNodeId: 'A',
  });
  assert.deepEqual(withoutContext.nodes.map((node) => node.id), ['A', 'D']);
  assert.equal(withoutContext.edges.length, 0);

  const focused = api.visibleModel({
    allNodes,
    allEdges,
    showContext: true,
    mode: 'focus',
    selectedNodeId: 'B',
  });
  assert.deepEqual(new Set(focused.nodes.map((node) => node.id)), new Set(['A', 'B', 'C']));
  assert.deepEqual(new Set(focused.edges.map((edge) => edge.id)), new Set(['AB', 'BC']));
});

test('keeps the selected result visible when a large graph is capped', () => {
  const { api } = loadApi();
  const allNodes = Array.from({ length: 81 }, (_, index) => ({
    id: `N${index}`,
    anchor: index < 2,
    hotspot: false,
    context: false,
    relevance: index === 80 ? 0 : 1 - index / 100,
  }));

  const visible = api.visibleModel({
    allNodes,
    allEdges: [],
    showContext: true,
    mode: 'overview',
    selectedNodeId: 'N80',
  });

  assert.equal(visible.nodes.length, 80);
  assert.ok(visible.nodes.some((node) => node.id === 'N80'));
  assert.equal(visible.omitted, 1);
});

test('combines selection and search edge muting without overriding either state', () => {
  const { api } = loadApi();

  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: false, hasSearch: false, searchRelevant: true,
  }), true);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: true, hasSearch: true, searchRelevant: false,
  }), true);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: null, selectedNodeId: 'A',
    connected: true, hasSearch: true, searchRelevant: true,
  }), false);
  assert.equal(api.shouldMuteEdge({
    edgeId: 'AB', selectedEdgeId: 'AB', selectedNodeId: 'A',
    connected: false, hasSearch: true, searchRelevant: false,
  }), false);
});

test('keeps a selected node visible while applying selection and search filters', () => {
  const { api } = loadApi();

  assert.equal(api.shouldMuteNode({
    nodeId: 'A', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: false,
  }), false);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: false,
    hasSearch: false, searchMatch: true,
  }), true);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: false,
  }), true);
  assert.equal(api.shouldMuteNode({
    nodeId: 'B', selectedNodeId: 'A', connected: true,
    hasSearch: true, searchMatch: true,
  }), false);
});

test('clears selection from non-interactive diagram background clicks', () => {
  const { api } = loadApi();
  const background = { closest() { return null; } };
  const node = { closest() { return {}; } };

  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: false, target: background }), true);
  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: false, target: node }), false);
  assert.equal(api.shouldClearSelectionFromClick({ defaultPrevented: true, target: background }), false);
});

test('delegates interaction policies to the shared engine without a second SVG controller', () => {
  const {api, common} = loadApi();
  assert.equal(api.shouldMuteEdge, common.shouldMuteEdge);
  assert.equal(api.shouldMuteNode, common.shouldMuteNode);
  assert.equal(api.shouldClearSelectionFromClick, common.shouldClearSelectionFromClick);
  assert.equal(api.calculateFitTransform, common.calculateFitTransform);
  assert.doesNotMatch(graphSource, /(?:window\.)?d3\.(?:select|zoom)\s*\(/);
  assert.doesNotMatch(graphSource, /ResizeObserver|requestAnimationFrame|cancelAnimationFrame/);
  assert.doesNotMatch(graphSource, /function (?:renderNodes|renderEdges|renderLayers|renderMarkers|updateInteractionStyles|matchingNodeIds|fitView|applyTransform|centerOnNode|updateViewBox)\s*\(/);
  assert.doesNotMatch(graphSource, /TaxonomyArchitectureDiagram\.connect|diagramApi\.connect/);
  assert.doesNotMatch(graphSource, /currentView\.state\.\w+\s*=(?!=)/);
  assert.doesNotMatch(graphSource, /renderImpactForceGraph|renderImpactForceGraphLegacy/);
});

test('passes relationship derivation reasons into the impact-map detail model', () => {
  assert.match(
    scoringSource,
    /includedBecause:\s*r\.derivationReason \|\| r\.includedBecause \|\| ''/
  );
});

test('registers the stylesheet and renderer in the main page', () => {
  const index = readFileSync(indexPath, 'utf8');
  assert.match(index, /taxonomy-impact-map\.css/);
  assert.match(index, /taxonomy-impact-map\.js/);
  assert.ok(
    index.indexOf('taxonomy-impact-map.js') > index.indexOf('architecture-diagram.js'),
    'analysis adapter must load after its shared diagram engine'
  );
  assert.ok(
    index.indexOf('taxonomy-impact-map.js') < index.indexOf('taxonomy-scoring.js'),
    'analysis adapter must load before scoring renders results'
  );
});

test('keeps English and German impact-map message keys aligned', () => {
  const english = propertyKeys(readFileSync(englishMessagesPath, 'utf8'));
  const german = propertyKeys(readFileSync(germanMessagesPath, 'utf8'));
  assert.deepEqual(english, german);
  assert.ok(english.has('impactmap.title'));
  assert.ok(english.has('impactmap.details.hint'));
  assert.ok(english.has('impactmap.aria.relationship'));
});

test('maps immutable evidence and geometry into the common presentation contract', () => {
  const {api} = loadApi();
  const nodes = api.normalizeNodes([
    {nodeCode: 'A', title: 'Capability evidence', taxonomySheet: 'CP', relevance: 0.9, hopDistance: 0, includedBecause: 'Direct source evidence'},
    {nodeCode: 'B', title: 'Context service', taxonomySheet: 'CR', relevance: 0.52, hopDistance: 1},
  ], {
    anchorCodes: ['A'], hotspotCodes: ['B'], hotspotReasons: {B: 'Shared dependency'},
    layerConfig: {CP: {order: 1, label: 'Capabilities'}, CR: {order: 3, label: 'Core Services'}},
  });
  const edges = api.normalizeEdges([
    {sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS', propagatedRelevance: 0.52, derivationReason: 'Required by evidence'},
  ], new Map(nodes.map(node => [node.id, node])));
  const original = JSON.stringify({nodes, edges});
  const scene = api.layoutScene({nodes, edges});
  assert.equal(scene.nodes[0].searchText, 'A Capability evidence Capabilities');
  assert.equal(scene.nodes[0].score, '★ 90%');
  assert.equal(scene.nodes[0].tooltip, 'A — Capability evidence\nCapabilities\n90%\nDirect source evidence');
  assert.equal(scene.nodes[1].score, '52%');
  assert.match(scene.nodes[1].tooltip, /Hotspot: Shared dependency/);
  assert.equal(scene.nodes[1].hotspot, true);
  assert.equal(scene.nodes[0].width, 230);
  assert.equal(scene.nodes[0].height, 86);
  assert.equal(scene.nodes[0].x, 38);
  assert.equal(scene.nodes[1].x, 336);
  assert.equal(scene.edges[0].path, 'M268,105 C310,105 294,105 336,105');
  assert.equal(scene.edges[0].sourceId, 'A');
  assert.equal(scene.edges[0].targetId, 'B');
  assert.equal(scene.edges[0].tooltip, 'A → B\nSUPPORTS\nRequired by evidence');
  assert.equal(scene.edges[0].className, 'impact-map-edge-group');
  assert.equal(scene.edges[0].pathClass, 'impact-map-edge');
  assert.equal(scene.layers[0].headerHeight, 36);
  assert.equal(scene.layers[0].labelOffsetY, 30);
  assert.equal(scene.layers[0].count, 1);
  assert.equal(scene.bounds.width, 604);
  assert.equal(JSON.stringify({nodes, edges}), original);
});

// The controller is an explicit command boundary here. Its actual selection,
// filtering, viewport and DOM behavior is covered by architecture-diagram tests
// and native browser acceptance; this fixture verifies the source UI uses it.
class UiElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.attributes = {};
    this.dataset = {};
    this.listeners = new Map();
    this.className = '';
    this.value = '';
    this._text = '';
  }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this._text = String(value); this.children = []; }
  get classList() {
    const element = this;
    return {
      contains(name) { return element.className.split(/\s+/).includes(name); },
      remove(name) { element.className = element.className.split(/\s+/).filter(value => value !== name).join(' '); },
    };
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name === 'class') this.className = String(value);
  }
  getAttribute(name) { return name === 'class' ? this.className : this.attributes[name] ?? null; }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren() { this.children = []; this._text = ''; }
  descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
  querySelector(selector) {
    return this.descendants().find(child => selector.startsWith('.')
      ? child.classList.contains(selector.slice(1)) : child.tagName === selector) || null;
  }
  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
  }
  removeEventListener(type, handler) { this.listeners.get(type)?.delete(handler); }
  fire(type, fields = {}) {
    for (const handler of this.listeners.get(type) || []) handler({type, target: this, ...fields});
  }
  focus() { this.focused = true; }
}

function mountUi() {
  const document = new UiElement('document');
  document.documentElement = new UiElement('html');
  document.events = [];
  document.createElement = tag => {
    const element = new UiElement(tag);
    element.requestFullscreen = () => { document.fullscreenElement = element; document.fire('fullscreenchange'); };
    return element;
  };
  document.createElementNS = (_, tag) => new UiElement(tag);
  document.exitFullscreen = () => { document.fullscreenElement = null; document.fire('fullscreenchange'); };
  document.dispatchEvent = event => { document.events.push(event); document.fire(event.type, event); };
  const container = new UiElement('div'), controllers = [], graphSelections = [], graphOpens = [];
  const loaded = loadApi({document, configure(window) {
    window.d3 = {}; // Any adapter-owned D3 call would fail this boundary test.
    window.TaxonomyGraph.selectNode = id => graphSelections.push(id);
    window.TaxonomyGraph.openGraphExplorer = id => graphOpens.push(id);
    window.TaxonomyArchitectureDiagram.create = (svg, options) => {
      const calls = [];
      let view, scene;
      const controller = {
        svg, options, calls,
        render(value) {
          scene = value;
          this.emit({selectedNodeId: null, selectedEdgeId: null, mode: 'overview', showContext: true, searchQuery: ''});
        },
        emit(state, model = scene, matches = []) {
          const layout = options.layout(model, state);
          view = {state: Object.freeze({...state}), model: {...layout,
            nodeIds: new Set(layout.nodes.map(node => node.id)), omitted: model.omitted || 0}, matches};
          options.onChange(view);
          return view;
        },
        getState: () => view.state,
        getModel: () => view.model,
        getScene: () => scene,
        matchingNodeIds: () => view.matches,
      };
      for (const command of ['select', 'focus', 'unfocus', 'fit', 'zoom', 'setSearch', 'setContext', 'destroy']) {
        controller[command] = (...args) => calls.push([command, ...args]);
      }
      controllers.push(controller);
      return controller;
    };
  }});
  const nodes = ['A', 'B', 'C'].map(id => ({nodeCode: id, title: `Evidence ${id}`, taxonomySheet: 'CP', relevance: 0.9,
    includedBecause: `Evidence for ${id}`}));
  const edges = [{sourceCode: 'A', targetCode: 'B', relationType: 'SUPPORTS', derivationReason: 'Retained reason'}];
  const result = loaded.api.render(container, nodes, edges, {anchorCodes: ['A', 'B', 'C']});
  function button(label) {
    return container.descendants().find(element => element.tagName === 'button'
      && (element.getAttribute('aria-label') === label || element.textContent === label));
  }
  return {...loaded, container, controllers, controller: result.diagram, graphSelections, graphOpens, nodes, edges, button};
}

test('mounts one engine and delegates UI navigation, fitting, search and context commands', () => {
  const h = mountUi(), diagram = h.controller;
  assert.equal(h.controllers.length, 1);
  assert.equal(h.container.__taxonomyImpactMapDiagram, diagram);
  assert.equal(diagram.svg.children.length, 0, 'the adapter must not render another SVG scene');
  assert.equal(diagram.svg.listeners.size, 0, 'diagram activation belongs to the engine');
  assert.equal(diagram.options.limits.modelNodes, 80);
  assert.equal(diagram.options.presentation.edgeLabelLimit, 14);
  assert.equal(diagram.options.behavior.focus, 'neighbors');
  assert.equal(h.button('Fullscreen').hidden, false);
  assert.equal(h.button('Focus').disabled, true);
  assert.equal(h.container.querySelector('.impact-map-details').getAttribute('aria-live'), 'polite');
  assert.equal(h.container.querySelector('.impact-map-status').getAttribute('role'), 'status');
  assert.deepEqual(diagram.calls, [['fit']]);
  diagram.calls.length = 0;

  const search = h.container.querySelector('.impact-map-search');
  search.value = 'unshown';
  search.fire('input');
  assert.deepEqual(diagram.calls.pop(), ['setSearch', 'unshown']);
  const original = diagram.getScene();
  const state = {...diagram.getState(), searchQuery: 'unshown'};
  diagram.emit(state, {nodes: original.nodes.slice(0, 2), edges: original.edges}, ['C']);
  search.fire('keydown', {key: 'Enter'});
  assert.deepEqual(JSON.parse(JSON.stringify(diagram.calls.pop())), ['select', 'C', null, {center: true, reveal: true}]);
  assert.match(h.container.querySelector('.impact-map-status').textContent, /was hidden/);
  search.fire('keydown', {key: 'Escape'});
  assert.deepEqual(diagram.calls.pop(), ['setSearch', '']);
  h.button('Clear search').fire('click');
  assert.deepEqual(diagram.calls.pop(), ['setSearch', '']);
  assert.equal(search.focused, true);

  const context = h.container.querySelector('.impact-map-toggle').querySelector('input');
  context.checked = false;
  context.fire('change');
  assert.deepEqual(diagram.calls.pop(), ['setContext', false]);
  assert.equal(state.showContext, true, 'UI commands do not mutate a second controller state');
  h.button('Zoom in').fire('click');
  h.button('Zoom out').fire('click');
  h.button('Fit').fire('click');
  h.button('Overview').fire('click');
  assert.deepEqual(diagram.calls.splice(0), [['zoom', 1.25], ['zoom', 0.8], ['fit'], ['unfocus']]);
  h.button('Fullscreen').fire('click');
  assert.equal(h.document.fullscreenElement, h.container.querySelector('.impact-map-workbench'));
  assert.deepEqual(diagram.calls.pop(), ['fit']);
  h.button('Exit fullscreen').fire('click');
  assert.equal(h.document.fullscreenElement, null);

  diagram.options.onFitState('pending');
  assert.equal(h.container.querySelector('.impact-map-workbench').dataset.fitState, 'pending');
  diagram.options.onFitState('complete', 0.375);
  assert.equal(h.container.querySelector('.impact-map-workbench').dataset.fitScale, '0.375000');
  diagram.options.onFitState('user-controlled');
  assert.equal(h.container.querySelector('.impact-map-workbench').dataset.fitState, 'user-controlled');
});

test('focused element offers Unfocus through the engine and retains evidence and selection', () => {
  const h = mountUi(), diagram = h.controller, source = diagram.getScene();
  const original = JSON.stringify(source);
  const selected = {...diagram.getState(), selectedNodeId: 'A'};
  diagram.emit(selected);
  assert.match(h.container.querySelector('.impact-map-details').textContent, /Evidence for A/);
  assert.deepEqual(h.graphSelections, ['A']);
  assert.deepEqual(JSON.parse(JSON.stringify(h.document.events.at(-1).detail)), {kind: 'analysis', elementId: 'A'});
  h.button('Open in Graph Explorer').fire('click');
  assert.deepEqual(h.graphOpens, ['A']);
  h.button('Focus this element').fire('click');
  assert.deepEqual(diagram.calls.pop(), ['focus', 'A']);
  assert.equal(diagram.getState().mode, 'overview', 'only the engine changes focus state');

  diagram.emit({...selected, mode: 'focus'}, {nodes: source.nodes.slice(0, 2), edges: source.edges});
  h.container.querySelector('.impact-map-detail-actions').descendants()
    .find(element => element.tagName === 'button' && element.textContent === 'Unfocus').fire('click');
  assert.deepEqual(diagram.calls.pop(), ['unfocus']);
  diagram.emit(selected);
  assert.equal(diagram.getState().selectedNodeId, 'A');
  assert.deepEqual(Array.from(diagram.getModel().nodes, node => node.id), ['A', 'B', 'C']);
  assert.ok(h.button('Focus this element'));
  assert.equal(h.graphSelections.length, 1, 'focus and view changes must not republish unchanged selection');
  assert.equal(JSON.stringify(source), original);

  h.container.querySelector('.impact-map-related-item').fire('click');
  assert.deepEqual(JSON.parse(JSON.stringify(diagram.calls.pop())), ['select', 'B', null, {center: true, reveal: true}]);
  diagram.emit({...selected, selectedNodeId: null, selectedEdgeId: source.edges[0].id});
  assert.match(h.container.querySelector('.impact-map-details').textContent, /Retained reason/);
  h.button('Select source').fire('click');
  h.button('Select target').fire('click');
  assert.deepEqual(JSON.parse(JSON.stringify(diagram.calls.slice(-2))), [
    ['select', 'A', null, {center: true, reveal: true}], ['select', 'B', null, {center: true, reveal: true}],
  ]);
});

test('remount disposes the prior engine and fullscreen listener exactly once', () => {
  const h = mountUi(), first = h.controller;
  assert.equal(h.document.listeners.get('fullscreenchange').size, 1);
  const next = h.api.render(h.container, h.nodes, h.edges, {anchorCodes: ['A']}).diagram;
  assert.equal(h.controllers.length, 2);
  assert.equal(first.calls.filter(([command]) => command === 'destroy').length, 1);
  assert.equal(h.document.listeners.get('fullscreenchange').size, 1);
  assert.equal(h.container.children.length, 1);
  assert.equal(h.container.__taxonomyImpactMapDiagram, next);
  h.container.__taxonomyImpactMapCleanup();
  assert.equal(next.calls.filter(([command]) => command === 'destroy').length, 1);
  assert.equal(h.document.listeners.get('fullscreenchange').size, 0);
  assert.equal(h.container.__taxonomyImpactMapDiagram, undefined);
  assert.equal(h.container.__taxonomyImpactMapCleanup, undefined);
});

test('reading an impact element and following its relation keep the full overview', () => {
  const h = mountUi(), diagram = h.controller;
  const inspect = h.container.querySelector('.impact-map-inspect-select');
  assert.ok(inspect, 'A readable native control identifies elements independently of SVG zoom');
  const choices = inspect.descendants().filter(option => option.value);
  assert.deepEqual(choices.map(choice => choice.value), ['A', 'B', 'C']);
  assert.match(choices[0].textContent, /90%.*A.*Evidence A/);
  inspect.value = 'A';
  inspect.fire('change');
  assert.deepEqual(JSON.parse(JSON.stringify(diagram.calls.pop())), ['select', 'A', null, {center: false, reveal: true}]);
  diagram.emit({...diagram.getState(), selectedNodeId: 'A'});
  h.container.querySelector('.impact-map-related-item').fire('click');
  assert.deepEqual(JSON.parse(JSON.stringify(diagram.calls.pop())), ['select', 'B', null, {center: false, reveal: true}]);
  diagram.emit({...diagram.getState(), selectedNodeId: 'B'});
  assert.equal(inspect.value, 'B');
  assert.match(h.container.querySelector('.impact-map-details').textContent, /Evidence for B/);
});

test('the historical 20/16/7 workload exposes fit-scale glyph sizes at 781 pixels', t => {
  const {api} = loadApi();
  const fixture = JSON.parse(readFileSync(new URL(
    '../../taxonomy-app/src/test/resources/ui/seven-layer-impact-map.json', import.meta.url), 'utf8'));
  const nodes = api.normalizeNodes(fixture.nodes);
  const edges = api.normalizeEdges(fixture.edges, new Map(nodes.map(node => [node.id, node])));
  const scene = api.layoutScene({nodes, edges});
  assert.deepEqual([scene.nodes.length, scene.edges.length, scene.layers.length], [20, 16, 7]);
  const fit = api.calculateFitTransform(scene.bounds, {width: 781, height: 620}, {
    padding: 34, minimumScale: 0.01, maximumScale: 1.25
  });
  const css = readFileSync(new URL(
    '../../taxonomy-app/src/main/resources/static/css/taxonomy-impact-map.css', import.meta.url), 'utf8');
  const baseTitle = Number(css.match(/\.impact-map-node-title\s*\{\s*font-size:\s*([\d.]+)px/)[1]);
  const fitted = {scale: fit.scale, titlePx: baseTitle * fit.scale,
    cardWidthPx: scene.nodes[0].width * fit.scale, cardHeightPx: scene.nodes[0].height * fit.scale};
  t.diagnostic(JSON.stringify(fitted));
  assert.ok(fitted.titlePx > 0 && Number.isFinite(fitted.cardWidthPx));
});
