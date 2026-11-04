import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const resources = '../../taxonomy-app/src/main/resources/static/js/';
const [sharedSource, snapshotSource] = await Promise.all([
    readFile(new URL(resources + 'shared/architecture-diagram.js', import.meta.url), 'utf8'),
    readFile(new URL(resources + 'architecture-workbench.js', import.meta.url), 'utf8')
]);
const context = vm.createContext({
    window: { TaxonomyI18n: { t: (key, ...values) => [key, ...values].join(':') } },
    document: { getElementById: () => null }
});
vm.runInContext(sharedSource, context);
vm.runInContext(snapshotSource, context);
const shared = context.window.TaxonomyArchitectureDiagram;
const snapshot = context.window.TaxonomyArchitectureSnapshot;

function node(id, overrides = {}) {
    return { id, label: id, type: 'Capabilities', layer: 0,
        x: 100, y: 100, width: 230, height: 86, relevance: 0.85, anchor: false,
        ...overrides };
}

function projection(nodes, edges = []) {
    return { scene: { title: 'Evidence', width: 1000, height: 420, nodes, edges },
        elements: {}, relations: {} };
}

function visible(data, state) {
    const scene = snapshot.prepareScene(data);
    return { scene, model: shared.visibleModel(scene, { mode: 'overview', selectedNodeId: null,
        showContext: true, ...state }) };
}

for (const [name, bounds, viewport] of [
    ['scenario overview on a phone', { x: -14, y: 4, width: 2516, height: 1220 }, { width: 320, height: 540 }],
    ['tall graph', { x: 20, y: -70, width: 900, height: 6000 }, { width: 1100, height: 600 }],
    ['compact focused graph', { x: 100, y: 40, width: 400, height: 260 }, { width: 1200, height: 900 }]
]) {
    test('shared Fit keeps every snapshot bound inside the viewport: ' + name, () => {
        const { x, y, scale } = shared.calculateFitTransform(bounds, viewport,
            { padding: 34, minimumScale: 0.001, maximumScale: 1.35, requireVisible: true });
        assert.ok(Number.isFinite(scale) && scale > 0 && scale <= 1.35);
        assert.ok(x + bounds.x * scale >= 33.99, 'left margin');
        assert.ok(y + bounds.y * scale >= 33.99, 'top margin');
        assert.ok(x + (bounds.x + bounds.width) * scale <= viewport.width - 33.99, 'right margin');
        assert.ok(y + (bounds.y + bounds.height) * scale <= viewport.height - 33.99, 'bottom margin');
        if (name !== 'compact focused graph') assert.ok(scale < 0.2, 'large scenes fit below the old 20% zoom floor');
    });
}

for (const viewport of [{ width: 0, height: 0 }, { width: 900, height: 0 }, { width: 0, height: 540 }]) {
    test('shared Fit waits for a measured snapshot viewport ' + viewport.width + 'x' + viewport.height, () => {
        const fit = shared.calculateFitTransform({ x: 105, y: 207, width: 2000, height: 700 }, viewport,
            { padding: 34, maximumScale: 1.35, requireVisible: true });
        assert.equal(fit, null, 'a hidden source must not commit a fallback viewport');
    });
}

test('evidence-only snapshots expose no anchor filter and keep every node in overview', () => {
    const { scene, model } = visible(projection([node('source'), node('target')],
        [{ sourceId: 'source', targetId: 'target', relationType: 'USES' }]));
    assert.equal(scene.hasAnchors, false, 'the adapter disables the empty anchor control');
    assert.deepEqual(Array.from(model.nodeIds), ['source', 'target']);
    assert.equal(model.edges.length, 1);
});

test('anchor-only snapshots hide context nodes and their incident edges', () => {
    const data = projection([node('anchor', { anchor: true }), node('context')],
        [{ sourceId: 'anchor', targetId: 'context', relationType: 'USES' }]);
    const hidden = visible(data, { showContext: false });
    assert.equal(hidden.scene.hasAnchors, true);
    assert.deepEqual(Array.from(hidden.model.nodeIds), ['anchor']);
    assert.equal(hidden.model.edges.length, 0);
    const restored = visible(data, { showContext: true });
    assert.deepEqual(Array.from(restored.model.nodeIds), ['anchor', 'context']);
    assert.equal(restored.model.edges.length, 1);
});

test('focused snapshots retain only the selected node and its immediate neighbors', () => {
    const data = projection([node('selected'), node('neighbor'), node('remote')], [
        { sourceId: 'selected', targetId: 'neighbor', relationType: 'USES' },
        { sourceId: 'neighbor', targetId: 'remote', relationType: 'USES' }
    ]);
    const { model } = visible(data, { mode: 'focus', selectedNodeId: 'selected' });
    assert.deepEqual(Array.from(model.nodeIds), ['selected', 'neighbor']);
    assert.equal(model.edges.length, 1);
});

for (const [relevance, expected] of [[0.00125, '0.125%'], [1e-12, '<0.000001%'], [0.85, '85%']]) {
    test('snapshot node presentation preserves relevance ' + relevance, () => {
        const scene = snapshot.prepareScene(projection([node('score', { relevance })]));
        assert.equal(scene.nodes[0].score, expected);
        assert.equal(scene.nodes[0].relevance, relevance, 'display formatting never rounds authoritative relevance');
    });
}

test('snapshot preparation preserves immutable source geometry and domain metadata', () => {
    const originalNode = Object.freeze(node('source', { anchor: true, presenceReason: 'direct evidence' }));
    const target = Object.freeze(node('target', { x: 500, layer: 1 }));
    const originalEdge = Object.freeze({ sourceId: 'source', targetId: 'target', relationType: 'USES',
        sourceX: 330, sourceY: 143, targetX: 500, targetY: 143, derivationReason: 'persisted' });
    const data = projection(Object.freeze([originalNode, target]), Object.freeze([originalEdge]));
    Object.freeze(data.scene);
    const scene = snapshot.prepareScene(data);
    const layout = snapshot.layoutScene(shared.visibleModel(scene, { showContext: true }));
    assert.notEqual(scene.nodes[0], originalNode);
    assert.equal(scene.nodes[0].presenceReason, 'direct evidence');
    assert.equal(scene.edges[0].derivationReason, 'persisted');
    assert.equal(originalEdge.id, undefined, 'generated edge IDs belong to the adapter copy');
    assert.equal(scene.edges[0].id, 'edge-0-source-target');
    assert.deepEqual(Array.from(layout.nodes, ({ id, x, y, width, height }) => ({ id, x, y, width, height })), [
        { id: 'source', x: 100, y: 100, width: 230, height: 86 },
        { id: 'target', x: 500, y: 100, width: 230, height: 86 }
    ]);
    assert.equal(layout.layers.length, 2);
    assert.equal(layout.layers[0].headerHeight, 34);
    assert.equal(layout.layers[0].count, 1);
    assert.match(layout.edges[0].path, /^M330,143 C/);
    assert.match(layout.edges[0].path, /500,143$/);
    assert.equal(layout.edges[0].sourceX, 330);
    assert.equal(layout.edges[0].targetX, 500);
});
