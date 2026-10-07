import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../../taxonomy-app/src/main/resources/static/js/architecture-workbench-print.js', import.meta.url), 'utf8');

class Element {
    constructor(attributes = {}, children = []) {
        this.attributes = { ...attributes };
        this.children = children;
        this.style = {};
        this.events = {};
        this.textContent = '';
    }
    getAttribute(key) { return this.attributes[key] ?? null; }
    setAttribute(key, value) { this.attributes[key] = String(value); }
    removeAttribute(key) { delete this.attributes[key]; }
    getAttributeNames() { return Object.keys(this.attributes); }
    addEventListener(type, handler) { this.events[type] = handler; }
    replaceChildren(...children) { this.children = children; }
    cloneNode() {
        const clone = new Element(this.attributes, this.children.map(child => child.cloneNode(true)));
        clone.textContent = this.textContent;
        clone.tagName = this.tagName;
        return clone;
    }
    querySelectorAll(selector) {
        const all = this.children.flatMap(child => [child, ...child.querySelectorAll('*')]);
        if (selector === '*') return all;
        if (selector === '[id]') return all.filter(child => child.getAttribute('id'));
        return all.filter(child => (child.getAttribute('class') || '').split(' ').includes(selector.slice(1)));
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

function boot({ ready = true, bounds = { x: -20, y: 10, width: 2400, height: 1600 }, geometryError = false } = {}) {
    const edge = new Element({ 'marker-end': 'url(#architecture-arrow-SUPPORTS)',
        style: 'marker-start: url("#architecture-arrow-SUPPORTS")',
        href: '#architecture-arrow-SUPPORTS' });
    const node = new Element({ class: 'architecture-node', tabindex: '0', role: 'button' });
    node.textContent = 'Unmodified original title';
    const viewport = new Element({ class: 'architecture-viewport', transform: 'translate(-900,-500) scale(4)' }, [edge, node]);
    viewport.getBBox = () => {if (geometryError) throw new Error('Geometry unavailable'); return bounds;};
    const marker = new Element({ id: 'architecture-arrow-SUPPORTS' });
    const embeddedStyle = new Element();
    embeddedStyle.tagName = 'style';
    embeddedStyle.textContent = '.architecture-edge-path { marker-end: url(#architecture-arrow-SUPPORTS); }';
    const svg = new Element({ id: 'architectureCanvas', viewBox: '0 0 1000 600', tabindex: '0', role: 'group' }, [marker, embeddedStyle, viewport]);
    const button = new Element();
    button.disabled = true;
    const fit = new Element();
    fit.disabled = !ready;
    const target = new Element();
    const shell = new Element();
    const properties = new Map([['--architecture-print-source-width', '880px']]);
    shell.style = {
        getPropertyValue(name) {return properties.get(name) || '';},
        setProperty(name, value) {properties.set(name, value);},
        removeProperty(name) {properties.delete(name);}
    };
    const ids = { architectureCanvas: svg, printArchitectureView: button,
        fitArchitecture: fit, architecturePrintDiagram: target, architectureCanvasShell: shell };
    const events = {};
    let observer;
    let prints = 0;
    const context = vm.createContext({
        document: { getElementById(id) { return ids[id] || null; } },
        MutationObserver: class {
            constructor(callback) { observer = callback; }
            observe() {}
        },
        window: { addEventListener(type, handler) { events[type] = handler; },
            print() { prints++; events.beforeprint?.(); } },
        console
    });
    vm.runInContext(source, context);
    return { svg, viewport, marker, node, target, button, fit, events, embeddedStyle, properties,
        mutation() { observer?.(); }, prints() { return prints; } };
}

test('print follows the loaded view and refuses clicks while loading or after failure', () => {
    const app = boot({ ready: false });
    assert.equal(typeof app.button.events.click, 'function');
    app.button.events.click();
    assert.equal(app.prints(), 0);
    assert.equal(app.button.disabled, true);
    app.fit.disabled = false;
    app.mutation();
    assert.equal(app.button.disabled, false);
    app.button.events.click();
    assert.equal(app.prints(), 1);
    app.fit.disabled = true;
    app.mutation();
    assert.equal(app.button.disabled, true);
    app.button.events.click();
    assert.equal(app.prints(), 1);
});

test('beforeprint includes the complete graph independently of the current pan and zoom', () => {
    const app = boot();
    assert.equal(typeof app.events.beforeprint, 'function');
    app.events.beforeprint();
    const clone = app.target.children[0];
    assert.ok(clone, 'a printable graph must exist');
    const [x, y, width, height] = clone.getAttribute('viewBox').split(' ').map(Number);
    assert.ok(x < -20 && y < 10 && x + width > 2380 && y + height > 1610);
    assert.equal(clone.querySelector('.architecture-viewport').getAttribute('transform'), null);
    assert.equal(app.viewport.getAttribute('transform'), 'translate(-900,-500) scale(4)');
    assert.equal(app.svg.getAttribute('viewBox'), '0 0 1000 600');
    assert.equal(clone.getAttribute('role'), 'img');
    assert.equal(clone.querySelector('.architecture-node').textContent, 'Unmodified original title');
});

test('print clone owns its marker IDs and has no interactive descendants', () => {
    const app = boot();
    assert.equal(typeof app.events.beforeprint, 'function');
    app.events.beforeprint();
    const clone = app.target.children[0];
    const marker = clone.querySelectorAll('[id]')[0];
    assert.notEqual(marker.getAttribute('id'), app.marker.getAttribute('id'));
    assert.equal(clone.querySelector('.architecture-viewport').children[0].getAttribute('marker-end'),
        'url(#' + marker.getAttribute('id') + ')');
    assert.equal(clone.querySelector('.architecture-node').getAttribute('tabindex'), null);
    assert.equal(clone.querySelector('.architecture-node').getAttribute('role'), null);
    assert.equal(app.node.getAttribute('tabindex'), '0');
    assert.equal(app.node.getAttribute('role'), 'button');
});

test('repeated printing refreshes the snapshot and afterprint removes only its temporary clone', () => {
    const app = boot();
    assert.equal(typeof app.events.beforeprint, 'function');
    app.events.beforeprint();
    const first = app.target.children[0];
    app.node.textContent = 'New selection context';
    app.events.beforeprint();
    assert.equal(app.target.children.length, 1);
    assert.notEqual(app.target.children[0], first);
    assert.equal(app.target.children[0].querySelector('.architecture-node').textContent, 'New selection context');
    app.events.afterprint();
    assert.equal(app.target.children.length, 0);
    assert.equal(app.svg.querySelector('.architecture-node'), app.node);
});

test('an unavailable graph never revives a previously prepared print clone', () => {
    const app = boot();
    assert.equal(typeof app.events.beforeprint, 'function');
    app.events.beforeprint();
    app.fit.disabled = true;
    app.events.beforeprint();
    assert.equal(app.target.children.length, 0);
});

test('empty or invalid geometry cannot enable an otherwise loaded view for printing', () => {
    for (const options of [
        {bounds: {x: 0, y: 0, width: 0, height: 0}},
        {bounds: {x: NaN, y: 0, width: 50, height: 50}},
        {geometryError: true}
    ]) {
        const app = boot(options);
        assert.equal(app.button.disabled, true);
        app.button.events.click();
        assert.equal(app.prints(), 0);
        app.events.beforeprint();
        assert.equal(app.target.children.length, 0);
    }
});

test('print source dimensions keep the renderer viewport stable and restore previous CSS properties', () => {
    const app = boot();
    app.events.beforeprint();
    assert.equal(app.properties.get('--architecture-print-source-width'), '1000px');
    assert.equal(app.properties.get('--architecture-print-source-height'), '600px');
    assert.equal(app.svg.getAttribute('viewBox'), '0 0 1000 600');
    assert.equal(app.viewport.getAttribute('transform'), 'translate(-900,-500) scale(4)');
    app.events.beforeprint();
    app.events.afterprint();
    assert.equal(app.properties.get('--architecture-print-source-width'), '880px');
    assert.equal(app.properties.has('--architecture-print-source-height'), false);
});

test('inline and embedded SVG styles keep their cloned marker references', () => {
    const app = boot();
    app.events.beforeprint();
    const clone = app.target.children[0];
    const markerId = clone.querySelectorAll('[id]')[0].getAttribute('id');
    const edge = clone.querySelector('.architecture-viewport').children[0];
    assert.equal(edge.getAttribute('style'), 'marker-start: url("#' + markerId + '")');
    assert.equal(edge.getAttribute('href'), '#' + markerId);
    assert.equal(clone.children[1].textContent, '.architecture-edge-path { marker-end: url(#' + markerId + '); }');
    assert.equal(app.embeddedStyle.textContent, '.architecture-edge-path { marker-end: url(#architecture-arrow-SUPPORTS); }');
    assert.equal(clone.getAttribute('preserveAspectRatio'), 'xMidYMid meet');
});
