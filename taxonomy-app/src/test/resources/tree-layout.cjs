'use strict';
// JUnit owns this deterministic driver; use the application's real WebJar/D3 implementation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {window: {}, document: {}, TaxonomyI18n: {t: key => key}};
vm.runInNewContext(fs.readFileSync(process.argv[3], 'utf8'), context);
vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8').replace('window.TaxonomyViews = {',
    'window.treeContract = {collapse: typeof collapseInitialTree === "function" ? collapseInitialTree : null,'
    + 'layout: typeof layoutTaxonomyTree === "function" ? layoutTaxonomyTree : null,'
    + 'endpoints: typeof treeLinkEndpoints === "function" ? treeLinkEndpoints : null}; window.TaxonomyViews = {'), context);
const api = context.window.treeContract;
assert.equal(typeof api.collapse, 'function', 'Every initial hidden descendant needs a collapsed state');
assert.equal(typeof api.layout, 'function', 'SVG, Canvas and export need shared occupied-bounds layout');
const measure = (text, font) => String(text).length * (font && font.includes('10px') ? 6 : 7);
if (process.argv[4] === 'expansion') {
    const data = {code: '__root__', children: [{code: 'one', children: [{code: 'two', children: [
        {code: 'three', children: [{code: 'four', children: [{code: 'five'}]}]}]}]}]};
    const before = JSON.stringify(data), root = context.d3.hierarchy(data);
    api.collapse(root);
    assert.deepEqual(Array.from(root.descendants(), n => n.data.code), ['__root__', 'one', 'two', 'three']);
    const branch = root.descendants().find(n => n.data.code === 'three');
    branch.children = branch._children; branch._children = null;
    assert.deepEqual(Array.from(root.descendants(), n => n.data.code), ['__root__', 'one', 'two', 'three', 'four']);
    assert.ok(branch.children[0]._children, 'Next level remains collapsed until its own activation');
    assert.equal(JSON.stringify(data), before, 'Layout state must never alter source catalogue data');
} else if (process.argv[4] === 'geometry') {
    const data = {code: '__root__', children: [{code: 'root', name: 'Long original root label '.repeat(20), children: [
        {code: 'leaf', name: 'A long leaf beside another expanded branch '.repeat(20)},
        {code: 'branch', name: 'A very long intermediate branch label '.repeat(20), children: [
            {code: 'nested', name: 'A deep branch '.repeat(20), children: [{code: 'last', name: 'Last node '.repeat(20)}]},
            {code: 'sibling', name: 'Sibling label '.repeat(20)}]}]}]};
    const before = JSON.stringify(data), root = context.d3.hierarchy(data);
    const scores = {root: 80, leaf: 40, branch: 40, nested: 30, last: 30, sibling: 10};
    const result = api.layout(root, scores, measure);
    const nodes = result.nodes.filter(n => n.data.code !== '__root__');
    assert.equal(nodes.length, 6);
    for (const node of nodes) {
        const b = node._treeBounds;
        assert.ok(b.right - b.left > 220, 'Full fixture label must exceed the old fixed column distance');
        assert.ok(b.left >= result.left && b.right <= result.left + result.width);
        assert.ok(b.top >= result.top && b.bottom <= result.top + result.height);
    }
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
        const a = nodes[i]._treeBounds, b = nodes[j]._treeBounds;
        assert.ok(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top,
            'Occupied node boxes overlap: ' + nodes[i].data.code + ' / ' + nodes[j].data.code);
    }
    for (const link of result.links) {
        const ends = api.endpoints(link);
        assert.ok(ends.source.y >= link.source._treeBounds.right, 'Link starts outside parent text/badge');
        assert.ok(ends.target.y <= link.target._treeBounds.left, 'Link stops before child text/badge');
        assert.ok(ends.source.y < ends.target.y, 'Visible connector corridor must remain between columns');
    }
    // A short parent beside a long leaf: bending immediately after the parent
    // would cut diagonally through that leaf's text on the way to lower children.
    const asymmetric = api.layout(context.d3.hierarchy({code: '__root__', children: [
        {code: 'A', children: Array.from({length: 5}, (_, i) => ({code: 'A-' + i}))},
        {code: 'B', name: 'A long adjacent leaf label '.repeat(12)}
    ]}), {}, measure);
    for (const link of asymmetric.links) {
        const ends = api.endpoints(link);
        assert.ok(ends.bend, 'Outgoing curves need a shared corridor after all labels in their column');
        for (const node of asymmetric.nodes.filter(n => n.depth === link.source.depth)) {
            assert.ok(ends.bend.y > node._treeBounds.right, 'A curve must not enter a longer sibling label');
        }
    }
    assert.equal(JSON.stringify(data), before);
} else if (process.argv[4] === 'contrast') {
    // Only the native DOM boundary is substituted; execute the actual export and D3 layout.
    const element = tag => ({tag, attributes: {}, children: [], textContent: '',
        setAttribute(name, value) { this.attributes[name] = String(value); },
        appendChild(child) { this.children.push(child); return child; }});
    context.document.createElement = element;
    context.document.createElementNS = (_, tag) => element(tag);
    const data = [{code: 'one', children: [{code: 'two', children: [
        {code: 'three', children: [{code: 'four'}]}]}]}];
    const descendants = node => [node, ...node.children.flatMap(descendants)];
    for (const score of [1, 50, 100]) {
        const svg = context.window.TaxonomyViews.buildExportSVG(data, {three: score}, {});
        const markers = descendants(svg).filter(node => node.tag === 'text' && node.textContent === '+');
        assert.equal(markers.length, 1, 'Export must identify the branch whose descendants are hidden');
        const marker = markers[0].attributes;
        assert.equal(marker.fill, '#222', 'Dark marker remains visible at 1% green on white');
        assert.equal(marker.stroke, '#fff', 'White halo remains visible on dark root colours');
        assert.equal(marker['stroke-width'], '2');
        assert.equal(marker['paint-order'], 'stroke fill', 'Halo must not obscure the small glyph');
    }
    const expanded = context.window.TaxonomyViews.buildExportSVG(data, {}, {expandAll: true});
    assert.equal(descendants(expanded).filter(node => node.textContent === '+').length, 0,
        'Expanded branches must not advertise hidden descendants');
} else {
    const result = api.layout(context.d3.hierarchy({code: '__root__', children: []}), {}, measure);
    for (const property of ['left', 'top', 'width', 'height']) assert.ok(Number.isFinite(result[property]));
    assert.equal(result.links.length, 0);
}
