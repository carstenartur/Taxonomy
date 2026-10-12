import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/shared/taxonomy-export.js', import.meta.url), 'utf8');
const svgNamespace = 'http://www.w3.org/2000/svg';

// A small DOM boundary for the actual export adapter. Cloning, selectors and
// serialization use the source elements, rather than prebuilt download strings.
class Element {
    constructor(tag, namespace = 'http://www.w3.org/1999/xhtml') {
        this.localName = tag; this.namespaceURI = namespace; this.children = [];
        this.attributes = {}; this.className = ''; this._text = ''; this.parentNode = null;
    }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    set textContent(value) { this._text = String(value); this.children = []; }
    get firstChild() { return this.children[0] || null; }
    setAttribute(name, value) {
        if (name === 'class') this.className = String(value);
        else this.attributes[name] = String(value);
    }
    getAttribute(name) { return name === 'class' ? this.className : this.attributes[name] ?? null; }
    appendChild(child) { child.remove(); child.parentNode = this; this.children.push(child); return child; }
    insertBefore(child, before) {
        child.remove(); child.parentNode = this;
        const index = this.children.indexOf(before);
        this.children.splice(index < 0 ? this.children.length : index, 0, child);
        return child;
    }
    remove() {
        if (this.parentNode) this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1);
        this.parentNode = null;
    }
    cloneNode(deep) {
        const clone = new Element(this.localName, this.namespaceURI);
        clone.attributes = { ...this.attributes }; clone.className = this.className; clone._text = this._text;
        if (deep) this.children.forEach(child => clone.appendChild(child.cloneNode(true)));
        return clone;
    }
    matches(selector) {
        const tag = selector.match(/^[\w-]+/);
        if (tag && this.localName !== tag[0]) return false;
        for (const [, name] of selector.matchAll(/\.([\w-]+)/g)) {
            if (!this.className.split(/\s+/).includes(name)) return false;
        }
        for (const [, name, value] of selector.matchAll(/\[([\w-]+)(?:="([^"]*)")?\]/g)) {
            if (value === undefined ? this.getAttribute(name) === null : this.getAttribute(name) !== value) return false;
        }
        return true;
    }
    querySelectorAll(selector) {
        return this.children.flatMap(child => [child, ...child.descendants()]).filter(child => child.matches(selector));
    }
    descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    click() {}
}

function serialize(element) {
    const escape = value => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
    const attributes = { ...element.attributes };
    if (element.className) attributes.class = element.className;
    return '<' + element.localName + Object.entries(attributes).map(([name, value]) => ' ' + name + '="' + escape(value) + '"').join('')
        + '>' + escape(element._text) + element.children.map(serialize).join('') + '</' + element.localName + '>';
}

function harness({ headings = true, canvas = false } = {}) {
    const tree = new Element('div');
    const scores = Object.freeze({ BP: 95, BR: 40, 'BP-1000': 70 });
    const headingElements = ['BP', 'BR'].map(code => {
        const heading = new Element('h3'); heading.className = 'tax-container-heading';
        heading.setAttribute('data-taxonomy-container', code);
        heading.textContent = code + ' – ' + (code === 'BP' ? 'Business Processes' : 'Business Roles');
        const badge = new Element('span'); badge.className = 'tax-container-score';
        badge.textContent = 'Area relevance: ' + scores[code] + '%'; heading.appendChild(badge);
        if (headings) tree.appendChild(heading);
        return heading;
    });
    const sourceSvg = new Element('svg', svgNamespace);
    sourceSvg.setAttribute('width', '600'); sourceSvg.setAttribute('height', '600');
    sourceSvg.setAttribute('viewBox', '-300 -300 600 600');
    sourceSvg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
    const zoom = new Element('g', svgNamespace); zoom.setAttribute('transform', 'translate(12,-7) scale(1.25)');
    const node = new Element('path', svgNamespace); node.setAttribute('data-diagram-node', 'BP-1000');
    node.setAttribute('d', 'M0,0 L100,0 L100,100 Z'); zoom.appendChild(node);
    const label = new Element('text', svgNamespace); label.textContent = 'BP-1000 Business Processes · 70%';
    zoom.appendChild(label); sourceSvg.appendChild(zoom);
    const snapshot = Object.freeze([{ code: 'BP', taxonomyContainer: true }]);
    if (canvas) {
        // Canvas exports already carry SVG-native area labels from buildExportSVG.
        headingElements.forEach(heading => {
            const text = new Element('text', svgNamespace);
            text.setAttribute('data-taxonomy-container', heading.getAttribute('data-taxonomy-container'));
            text.textContent = heading.textContent; sourceSvg.appendChild(text);
        });
        tree._canvasRenderer = true; tree._canvasData = snapshot; tree._canvasScores = scores;
    } else tree.appendChild(sourceSvg);
    const downloads = [], pdfCalls = [], pdfSaves = [];
    const document = { readyState: 'loading', addEventListener() {}, body: new Element('body'),
        getElementById: id => id === 'taxonomyTree' ? tree : null,
        createElement: tag => new Element(tag), createElementNS: (namespace, tag) => new Element(tag),
        querySelector: () => null };
    const window = { setTimeout() {}, TaxonomyState: { currentScores: scores },
        TaxonomyViews: { buildExportSVG: () => sourceSvg },
        jspdf: { jsPDF: class {
            constructor(options) { this.options = options; }
            async svg(svg, options) { pdfCalls.push({ svg, options, format: this.options.format }); }
            save(filename) { pdfSaves.push(filename); }
        } }
    };
    vm.runInNewContext(source.replace('window.TaxonomyExport = Object.freeze({',
        'window.standaloneSvgForTest = standaloneSvg; window.TaxonomyExport = Object.freeze({'), {
        window, document, Element, Blob, TaxonomyI18n: { t: key => key },
        XMLSerializer: class { serializeToString(svg) { return serialize(svg); } },
        URL: { createObjectURL(blob) { downloads.push(blob); return 'blob:fixture'; }, revokeObjectURL() {} },
        fetch() { throw new Error('View capture must not call a provider or remote export.'); },
        alert(message) { throw new Error(message); }
    });
    return { tree, sourceSvg, scores, window, downloads, pdfCalls, pdfSaves, snapshot,
        standalone: () => window.standaloneSvgForTest('taxonomyTree') };
}

test('explicit SVG view export retains separate area labels and original nodes', async () => {
    const h = harness(), before = serialize(h.tree);
    assert.equal(h.window.TaxonomyExport.exportSvg('taxonomyTree'), true);
    assert.equal(h.downloads.length, 1);
    const output = await h.downloads[0].text();
    assert.ok(output.includes('BP – Business Processes'), 'BP heading must be included in the exported view');
    assert.ok(output.includes('Area relevance: 95%'), 'the area score must survive SVG capture');
    assert.ok(output.includes('BR – Business Roles'));
    assert.ok(output.includes('Area relevance: 40%'));
    assert.equal((output.match(/data-diagram-node="BP-1000"/g) || []).length, 1);
    assert.equal((output.match(/data-diagram-node="BP"/g) || []).length, 0, 'headings are not additional catalogue nodes');
    assert.equal(serialize(h.tree), before, 'capture must not rewrite the rendered source');
    assert.deepEqual(h.scores, { BP: 95, BR: 40, 'BP-1000': 70 });
});

test('area header wrapper preserves the negative sunburst viewBox, viewport size and current zoom', () => {
    const h = harness(), before = serialize(h.sourceSvg);
    const exported = h.standalone();
    const inner = exported.querySelector('svg');
    assert.ok(inner, 'the original view needs its own SVG viewport below the area labels');
    assert.equal(inner.getAttribute('viewBox'), '-300 -300 600 600');
    assert.equal(inner.getAttribute('preserveAspectRatio'), 'xMidYMid meet');
    assert.equal(inner.getAttribute('width'), '600'); assert.equal(inner.getAttribute('height'), '600');
    assert.equal(inner.querySelector('g').getAttribute('transform'), 'translate(12,-7) scale(1.25)');
    assert.ok(Number(inner.getAttribute('y')) > 0);
    assert.equal(Number(exported.getAttribute('height')), 600 + Number(inner.getAttribute('y')));
    assert.equal(exported.getAttribute('viewBox'), '0 0 600 ' + exported.getAttribute('height'));
    assert.equal(exported.querySelectorAll('[data-taxonomy-container]').length, 2);
    assert.equal(serialize(h.sourceSvg), before);
});

test('PDF taxonomy-view route receives the same complete vector capture', async () => {
    const h = harness(), before = serialize(h.sourceSvg);
    h.window.TaxonomyExport.exportPdf();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.pdfCalls.length, 1);
    const { svg, format } = h.pdfCalls[0];
    assert.ok(svg.textContent.includes('Area relevance: 95%'), 'PDF must include area evidence');
    assert.equal(svg.querySelector('svg')?.getAttribute('viewBox'), '-300 -300 600 600');
    assert.deepEqual(Array.from(format), [600, Number(svg.getAttribute('height'))]);
    assert.deepEqual(h.pdfSaves, ['taxonomy-view.pdf']);
    assert.equal(serialize(h.sourceSvg), before);
});

test('canvas SVG with embedded area labels is not wrapped or labelled a second time', () => {
    const h = harness({ canvas: true }), before = serialize(h.sourceSvg);
    const exported = h.standalone();
    assert.ok(exported.querySelector('svg') === null);
    assert.equal(exported.querySelectorAll('[data-taxonomy-container]').length, 2);
    assert.equal(exported.getAttribute('viewBox'), '-300 -300 600 600');
    assert.equal(serialize(h.sourceSvg), before);
    assert.equal(h.tree._canvasScores, h.scores);
    assert.equal(h.tree._canvasData, h.snapshot);
});

test('a view without external area headings retains its original SVG viewport', () => {
    const h = harness({ headings: false }), before = serialize(h.sourceSvg);
    const exported = h.standalone();
    assert.ok(exported.querySelector('svg') === null);
    assert.equal(exported.getAttribute('width'), '600'); assert.equal(exported.getAttribute('height'), '600');
    assert.equal(exported.getAttribute('viewBox'), '-300 -300 600 600');
    assert.equal(serialize(h.sourceSvg), before);
});
