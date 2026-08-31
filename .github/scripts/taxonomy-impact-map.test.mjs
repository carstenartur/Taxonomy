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

function loadApi() {
  const originalRenderer = function legacyRenderer() {};
  const document = {
    documentElement: {},
  };
  const window = {
    TaxonomyGraph: {
      renderImpactForceGraph: originalRenderer,
    },
    TaxonomyI18n: {
      t(key) {
        return key;
      },
    },
  };
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
    getComputedStyle() {
      return {
        getPropertyValue() {
          return '';
        },
      };
    },
  });
  vm.runInContext(graphSource, context, { filename: 'taxonomy-impact-map.js' });
  return { api: window.TaxonomyImpactMap, graph: window.TaxonomyGraph, originalRenderer };
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

test('installs a replacement renderer while preserving the legacy renderer', () => {
  const { api, graph, originalRenderer } = loadApi();

  assert.equal(typeof api.render, 'function');
  assert.equal(typeof api.normalizeNodes, 'function');
  assert.equal(typeof api.buildLayout, 'function');
  assert.equal(graph.renderImpactForceGraphLegacy, originalRenderer);
  assert.equal(graph.renderImpactForceGraph, api.render);
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

test('uses locale-independent matching and avoids per-edge graph scans', () => {
  assert.doesNotMatch(graphSource, /toLocaleLowerCase/);
  assert.doesNotMatch(graphSource, /state\.visible\.edges\.find/);
  assert.match(graphSource, /\.toLowerCase\(\)/);
  assert.match(graphSource, /mutedBySelection \|\| mutedBySearch/);
});

test('provides orientation, search, focus, detail and accessibility controls', () => {
  for (const requiredContract of [
    'impact-map-search',
    'impact-map-kpis',
    'impact-map-details',
    'contextCheckbox',
    "state.mode = 'focus'",
    "setAttribute('aria-label'",
    "setAttribute('aria-live'",
    'openGraphExplorer',
    'requestFullscreen',
    'ResizeObserver',
    'showReadableInitialView',
    'renderAndCenterNode',
    "initialNodeId: defaultSelection ? defaultSelection.id : null",
    'selectedNodeId: null',
    'state.selectedNodeId || state.initialNodeId',
    'fullFitScale >= 0.62',
    "fullscreenButton.hidden = typeof root.requestFullscreen !== 'function'",
  ]) {
    assert.ok(graphSource.includes(requiredContract), `missing contract: ${requiredContract}`);
  }
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
    index.indexOf('taxonomy-impact-map.js') > index.indexOf('taxonomy-graph.js'),
    'replacement renderer must load after the legacy graph API'
  );
  assert.ok(
    index.indexOf('taxonomy-impact-map.js') < index.indexOf('taxonomy-scoring.js'),
    'replacement renderer must load before scoring renders results'
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
