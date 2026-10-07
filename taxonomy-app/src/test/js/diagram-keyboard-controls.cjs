const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/core/taxonomy-views.js'), 'utf8');

function fixture(canvas = false) {
    const document = {activeElement: null, createElement: tag => element(tag)};
    function element(tag = 'g') {
        return {tagName: tag, attributes: {}, children: [], listeners: {}, textContent: '',
            setAttribute(k, v) {this.attributes[k] = String(v);},
            getAttribute(k) {return this.attributes[k] ?? null;},
            removeAttribute(k) {delete this.attributes[k];},
            appendChild(child) {child.parentNode = this; this.children.push(child); return child;},
            insertBefore(child, sibling) {child.parentNode = this; this.children.splice(this.children.indexOf(sibling), 0, child);},
            addEventListener(k, v) {this.listeners[k] = v;},
            contains(child) {return child === this || this.children.some(e => e.contains(child));},
            focus() {document.activeElement = this;},
            classList: {add() {}}};
    }
    function transform(x = 0, y = 0, k = 1) {
        return {x, y, k, translate: (dx, dy) => transform(x + dx * k, y + dy * k, k), scale: factor => transform(x, y, k * factor)};
    }
    const context = {window: {}, document, TaxonomyI18n: {t: key => key}, d3: {zoomIdentity: transform()}};
    // Access internal production helpers in this VM only; no test API ships to the browser.
    vm.runInNewContext(source.replace('window.TaxonomyViews = {',
        'window.testHelpers = {navigation: typeof createDiagramNavigation === "function" ? createDiagramNavigation : null, controls: typeof createDiagramControls === "function" ? createDiagramControls : null, zoom: typeof installDiagramZoomControls === "function" ? installDiagramZoomControls : null}; window.TaxonomyViews = {'), context);
    const container = element('div'); const surface = element(canvas ? 'canvas' : 'svg'); container.appendChild(surface);
    const root = {data: {code: 'BP', name: 'Business Processes'}, children: []};
    const child = {data: {code: 'BP-1000', name: 'Processes', description: 'Recorded description'}, parent: root}; root.children.push(child);
    const other = {data: {code: 'BR', name: 'Business Roles'}};
    const entries = [root, child, other].map(node => ({node, element: canvas ? surface : element()}));
    if (!canvas) entries.forEach(e => surface.appendChild(e.element));
    const activations = []; const changes = [];
    assert.equal(typeof context.window.testHelpers.navigation, 'function', 'Diagram navigation must exist');
    const nav = context.window.testHelpers.navigation(container, surface, {scores: {'BP-1000': 70},
        activate: n => activations.push(n.data.code), canvas, change: n => changes.push(n.data.code)});
    nav.update(entries);
    function key(key, target = document.activeElement || surface) {
        let prevented = false; let stopped = false;
        surface.listeners.keydown({key, target, preventDefault() {prevented = true;}, stopPropagation() {stopped = true;}});
        return {prevented, stopped};
    }
    return {document, container, surface, entries, nav, key, activations, changes, helpers: context.window.testHelpers};
}

test('SVG nodes form one tab stop and keyboard navigation reaches every visible node', () => {
    const f = fixture();
    assert.deepEqual(f.entries.map(e => e.element.getAttribute('tabindex')), ['0', '-1', '-1']);
    f.entries[0].element.focus();
    assert.equal(f.key('ArrowDown').prevented, true);
    assert.equal(f.document.activeElement, f.entries[1].element);
    assert.match(f.entries[1].element.getAttribute('aria-label'), /BP-1000.*Processes.*70%.*Recorded description/);
    f.key('End'); assert.equal(f.document.activeElement, f.entries[2].element);
    f.key('Home'); assert.equal(f.document.activeElement, f.entries[0].element);
    f.key('Enter'); f.key(' ');
    assert.deepEqual(f.activations, ['BP', 'BP']);
    assert.equal(f.key('Tab').prevented, false);
    assert.equal(f.surface.getAttribute('role'), 'group');
});

test('collapsing a branch restores focus to its visible parent and removes stale tab stops', () => {
    const f = fixture(); f.entries[1].element.focus(); f.key('ArrowUp'); f.key('ArrowDown');
    f.entries[0].node._children = f.entries[0].node.children; f.entries[0].node.children = null;
    f.nav.update([f.entries[0], f.entries[2]]);
    assert.equal(f.document.activeElement, f.entries[0].element);
    assert.equal(f.entries[1].element.getAttribute('tabindex'), '-1');
    assert.equal(f.entries[1].element.getAttribute('aria-hidden'), 'true');
    assert.equal(f.entries[0].element.getAttribute('aria-expanded'), 'false');
    assert.equal(f.entries[2].element.getAttribute('aria-expanded'), null);
});

test('a renderer refresh preserves the active code without stealing focus from controls', () => {
    const f = fixture(); f.entries[0].element.focus(); f.key('End');
    f.document.activeElement = {tagName: 'button'};
    f.nav.update(f.entries);
    assert.equal(f.document.activeElement.tagName, 'button');
    assert.deepEqual(f.entries.map(e => e.element.getAttribute('tabindex')), ['-1', '-1', '0']);
});

test('a complete renderer replacement restores focus to the previously active taxonomy code', () => {
    const f = fixture(); f.entries[0].element.focus(); f.key('End');
    f.container._taxRestoreNodeFocus = true;
    f.document.activeElement = null;
    const replacement = f.helpers.navigation(f.container, f.surface, {scores: {}, activate() {}});
    replacement.update(f.entries);
    assert.equal(f.document.activeElement, f.entries[2].element);
    assert.equal(f.container._taxRestoreNodeFocus, false);
});

test('Canvas has one keyboard target with a readable current node and branch activation', () => {
    const f = fixture(true); f.surface.focus();
    assert.equal(f.surface.getAttribute('tabindex'), '0');
    f.key('ArrowDown'); assert.match(f.surface.getAttribute('aria-label'), /BP-1000.*70%/);
    f.key('Home'); f.key(' '); assert.deepEqual(f.activations, ['BP']);
    f.key('End'); assert.match(f.surface.getAttribute('aria-label'), /BR.*Business Roles/);
    assert.equal(f.surface.getAttribute('aria-expanded'), null);
});

test('returning focus to Canvas keeps the selected node', () => {
    const f = fixture(true); f.surface.focus(); f.key('End');
    f.surface.listeners.focusin({target: f.surface});
    assert.match(f.surface.getAttribute('aria-label'), /BR.*Business Roles/);
});

test('local controls expose labeled native buttons and invoke only their supplied diagram actions', () => {
    const f = fixture(); const calls = [];
    const controls = f.helpers.controls(f.container, f.surface, [
        {key: 'views.diagram.zoomIn', run: () => calls.push('in')},
        {key: 'views.diagram.fit', run: () => calls.push('fit')},
        {key: 'views.diagram.reset', run: () => calls.push('reset')}
    ], 'views.diagram.keyboard');
    const buttons = controls.children.filter(e => e.tagName === 'button');
    assert.equal(buttons.length, 3);
    for (const button of buttons) {assert.equal(button.getAttribute('type'), 'button'); button.listeners.click();}
    assert.deepEqual(calls, ['in', 'fit', 'reset']);
    assert.equal(controls.getAttribute('aria-label'), 'views.diagram.controls');
    assert.ok(f.surface.getAttribute('aria-describedby'));
});

test('zoom controls fit content to the local viewport and reset the D3 transform', () => {
    const f = fixture(); f.container.clientWidth = 432;
    assert.equal(typeof f.helpers.zoom, 'function', 'Local zoom controls must exist');
    const calls = []; const zoom = {scaleBy() {}, transform() {}};
    const selection = {call(...args) {calls.push(args);}};
    f.helpers.zoom(f.container, f.surface, selection, zoom, () => ({x: -50, y: -20, width: 800, height: 400}));
    const buttons = f.container.children[0].children.filter(e => e.tagName === 'button');
    buttons[0].listeners.click(); buttons[1].listeners.click(); buttons[2].listeners.click(); buttons[3].listeners.click();
    assert.equal(calls[0][0], zoom.scaleBy); assert.equal(calls[0][1], 1.25);
    assert.equal(calls[1][1], 0.8);
    assert.equal(calls[2][0], zoom.transform);
    assert.deepEqual({x: calls[2][1].x, y: calls[2][1].y, k: calls[2][1].k}, {x: 41, y: 26, k: 0.5});
    assert.deepEqual({x: calls[3][1].x, y: calls[3][1].y, k: calls[3][1].k}, {x: 0, y: 0, k: 1});
});

test('fit includes a tall visible taxonomy instead of clipping it at the old ten-percent zoom floor', () => {
    const f = fixture(); f.container.clientWidth = 432;
    const calls = []; const selection = {call(...args) {calls.push(args);}};
    f.helpers.zoom(f.container, f.surface, selection, {scaleBy() {}, transform() {}},
        () => ({x: 0, y: 0, width: 1200, height: 8000}));
    f.container.children[0].children[2].listeners.click();
    assert.equal(calls[0][1].k, 0.046);
    assert.equal(calls[0][1].y + 8000 * calls[0][1].k, 384);
});
