import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sourcePath = '../../taxonomy-app/src/main/resources/static/js/';
const sources = ['shared/taxonomy-utils.js', 'core/taxonomy-browse.js', 'core/taxonomy-scoring.js']
    .map(file => readFileSync(new URL(sourcePath + file, import.meta.url), 'utf8'));
const searchSource = readFileSync(new URL(sourcePath + 'shared/taxonomy-search.js', import.meta.url), 'utf8');
const onboardingSource = readFileSync(new URL(sourcePath + 'shared/taxonomy-onboarding.js', import.meta.url), 'utf8');

// A bounded, deterministic catalogue fixture. The BP-1000 -> BP-1327 -> BP-1060
// and BR-1000 -> BR-1228 edges come from the checked-in 25AUG2025 Excel catalogue;
// labels are fixture text, and BP/BR are navigation containers. No provider runs.
function catalogue() {
    return [
        { code: 'BP', name: 'Business Processes', level: 0, taxonomyRoot: 'BP', taxonomyContainer: true,
            children: [{ code: 'BP-1000', name: 'Business Processes', level: 1, parentCode: 'BP', taxonomyRoot: 'BP',
                children: [{ code: 'BP-1327', name: 'Enable', level: 2, parentCode: 'BP-1000', taxonomyRoot: 'BP',
                    children: [{ code: 'BP-1060', name: 'Analytical Support', level: 3, parentCode: 'BP-1327', taxonomyRoot: 'BP', children: [] }] }] }] },
        { code: 'BR', name: 'Business Roles', level: 0, taxonomyRoot: 'BR', taxonomyContainer: true,
            children: [{ code: 'BR-1000', name: 'Business Roles', level: 1, parentCode: 'BR', taxonomyRoot: 'BR',
                children: [{ code: 'BR-1228', name: 'Recorded Roles', level: 2, parentCode: 'BR-1000', taxonomyRoot: 'BR', children: [] }] }] }
    ];
}

function freeze(value) {
    if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
}

// DOM boundary fixture, following the repository's node:vm UI tests. Selectors,
// descendants, bubbling, focus and dataset/attribute reads operate on the DOM
// actually constructed by the production renderer; no rendered rows are mocked.
class Element {
    constructor(tag, ownerDocument) {
        this.tagName = tag.toUpperCase(); this.ownerDocument = ownerDocument;
        this.children = []; this.parentElement = null; this.dataset = {};
        this.style = {}; this.attributes = {}; this.listeners = {}; this.className = '';
        this._text = ''; this.hidden = false;
        this.classList = {
            contains: name => this.className.split(/\s+/).includes(name),
            add: (...names) => { this.className = [...new Set([...this.className.split(/\s+/).filter(Boolean), ...names])].join(' '); },
            remove: (...names) => { this.className = this.className.split(/\s+/).filter(name => !names.includes(name)).join(' '); },
            toggle: (name, force) => {
                const add = force === undefined ? !this.classList.contains(name) : force;
                this.classList[add ? 'add' : 'remove'](name); return add;
            }
        };
    }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    set textContent(text) { this._text = String(text); this.children = []; }
    set innerHTML(html) { this._text = html; this.children = []; }
    get innerHTML() { return this._text; }
    get parentNode() { return this.parentElement; }
    get isConnected() { return this === this.ownerDocument.body || !!this.parentElement?.isConnected; }
    get firstChild() { return this.children[0] || null; }
    get nextSibling() {
        return this.parentElement?.children[this.parentElement.children.indexOf(this) + 1] || null;
    }
    setAttribute(name, value) {
        value = String(value);
        if (name === 'id') this.id = value;
        else if (name === 'class') this.className = value;
        else if (name.startsWith('data-')) this.dataset[this.dataKey(name)] = value;
        else this.attributes[name] = value;
    }
    getAttribute(name) {
        if (name === 'id') return this.id ?? null;
        if (name === 'class') return this.className;
        if (name.startsWith('data-')) return this.dataset[this.dataKey(name)] ?? null;
        return this.attributes[name] ?? null;
    }
    hasAttribute(name) { return this.getAttribute(name) !== null; }
    removeAttribute(name) {
        if (name.startsWith('data-')) delete this.dataset[this.dataKey(name)];
        else delete this.attributes[name];
    }
    dataKey(name) { return name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase()); }
    appendChild(child) {
        if (child.tagName === '#FRAGMENT') {
            [...child.children].forEach(node => this.appendChild(node)); return child;
        }
        child.remove(); child.parentElement = this; this.children.push(child); return child;
    }
    append(...children) { children.forEach(child => this.appendChild(child)); }
    insertBefore(child, before) {
        child.remove(); child.parentElement = this;
        const index = this.children.indexOf(before);
        this.children.splice(index < 0 ? this.children.length : index, 0, child); return child;
    }
    remove() {
        if (this.parentElement) this.parentElement.children.splice(this.parentElement.children.indexOf(this), 1);
        this.parentElement = null;
    }
    addEventListener(type, handler) { (this.listeners[type] ||= []).push(handler); }
    dispatchEvent(event) {
        event.target ||= this; event.currentTarget = this;
        event.preventDefault ||= () => { event.defaultPrevented = true; };
        event.stopPropagation ||= () => { event.stopped = true; };
        (this.listeners[event.type] || []).forEach(handler => handler.call(this, event));
        if (!event.stopped && this.parentElement) this.parentElement.dispatchEvent(event);
        return !event.defaultPrevented;
    }
    click() { this.dispatchEvent({ type: 'click' }); }
    focus() { this.ownerDocument.activeElement = this; this.dispatchEvent({ type: 'focusin' }); }
    scrollIntoView() {}
    matches(selector) { return selector.split(',').some(part => matchPath(this, part.trim().split(/\s+/), this)); }
    closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) || null; }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    querySelectorAll(selector) {
        const alternatives = selector.split(',').map(part => part.trim().split(/\s+/));
        return descendants(this).filter(node => alternatives.some(tokens => matchPath(node, tokens, this)));
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}
function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }
function matchPart(node, selector, scope) {
    if (selector === ':scope') return node === scope;
    const tag = selector.match(/^[\w-]+/);
    if (tag && node.tagName !== tag[0].toUpperCase()) return false;
    for (const [, name] of selector.matchAll(/\.([\w-]+)/g)) if (!node.classList.contains(name)) return false;
    for (const [, id] of selector.matchAll(/#([\w-]+)/g)) if (node.id !== id) return false;
    for (const [, name, expected] of selector.matchAll(/\[([\w-]+)(?:="([^"]*)")?\]/g)) {
        if (expected === undefined ? !node.hasAttribute(name) : node.getAttribute(name) !== expected) return false;
    }
    return true;
}
function matchPath(node, tokens, scope) {
    if (!node || !matchPart(node, tokens.at(-1), scope)) return false;
    if (tokens.length === 1) return true;
    if (tokens.at(-2) === '>') return matchPath(node.parentElement, tokens.slice(0, -2), scope);
    for (let parent = node.parentElement; parent; parent = parent.parentElement) {
        if (matchPath(parent, tokens.slice(0, -1), scope)) return true;
    }
    return false;
}

function tabStops(tree) {
    assert.ok(tree, 'catalogue entries must belong to a tree widget');
    return tree.querySelectorAll('[role="treeitem"][tabindex="0"]')
        .filter(item => item.closest('[role="tree"]') === tree)
        .map(item => item.dataset.code);
}

function harness({ view = 'list', data = catalogue(), scores = null, interactive = false, response,
    language = 'en', coverage = null, reasons = {} } = {}) {
    const document = { readyState: 'loading', activeElement: null, addEventListener() {}, dispatchEvent() {} };
    document.body = new Element('body', document); document.documentElement = new Element('html', document);
    document.documentElement.lang = language;
    document.createElement = tag => new Element(tag, document);
    document.createDocumentFragment = () => new Element('#fragment', document);
    document.createTextNode = text => { const node = new Element('#text', document); node.textContent = text; return node; };
    document.querySelectorAll = selector => document.body.querySelectorAll(selector);
    document.querySelector = selector => document.body.querySelector(selector);
    document.getElementById = id => document.body.querySelector('#' + id);
    const tree = document.createElement('div'); tree.id = 'taxonomyTree'; document.body.appendChild(tree);
    const input = document.createElement('textarea'); input.id = 'businessText'; input.value = 'A deterministic requirement';
    document.body.appendChild(input);
    const status = document.createElement('div'); status.id = 'statusArea'; document.body.appendChild(status);
    const state = { currentView: view, taxonomyData: data, currentScores: scores || {}, currentRawScores: scores || {},
        currentReasons: reasons, analysisCoverage: coverage, currentScoreDetails: {}, evaluatedNodes: new Set(), interactiveMode: interactive };
    const requests = [], errors = [];
    const messages = new Map(readFileSync(new URL('../../taxonomy-app/src/main/resources/i18n/messages'
        + (language === 'de' ? '_de' : '') + '.properties', import.meta.url), 'utf8').split(/\r?\n/)
        .filter(line => line.startsWith('browse.container.area.')).map(line => {
            const separator = line.indexOf('='); return [line.slice(0, separator), line.slice(separator + 1)];
        }));
    const i18n = { getLocale: () => language, t: (key, ...args) => messages.get(key) || (key === 'browse.container.relevance'
        ? 'Area relevance: ' + args[0] + '%' : key === 'browse.container.empty' ? 'No catalogue entries' : key + (args.length ? ': ' + args.join(', ') : '')) };
    const window = { TaxonomyState: state, TaxonomyI18n: i18n };
    const context = vm.createContext({ window, document, TaxonomyI18n: i18n, CSS: { escape: value => value },
        CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
        requestAnimationFrame: fn => fn(),
        console: { log() {}, warn(...args) { errors.push(args); }, error(...args) { errors.push(args); } },
        fetch: async url => {
            requests.push(new URL(url, 'https://fixture.invalid'));
            assert.equal(requests.at(-1).pathname, '/api/analyze-node');
            return { ok: true, json: async () => response || { scores: { 'BP-1000': 83 }, reasons: { 'BP-1000': 'Fixture evidence' } } };
        }
    });
    vm.runInContext(sources[0], context); context.TaxonomyUtils = window.TaxonomyUtils;
    // Exercise the real expand/collapse commands without adding a production API.
    vm.runInContext(sources[1].replace('window.TaxonomyBrowse = {',
        'window.TaxonomyBrowse = { expandAllForTest: expandAll, collapseAllForTest: collapseAll,'), context);
    vm.runInContext(sources[2], context);
    const browse = window.TaxonomyBrowse;
    browse.renderView(data, scores);
    return { tree, document, browse, state, window, requests, errors,
        group: code => tree.querySelector('[data-taxonomy-container="' + code + '"]'),
        row: code => tree.querySelector('.tax-node[data-code="' + code + '"]'),
        loadSearch() {
            // Expose the real private navigation function only in the test realm.
            vm.runInContext(searchSource.replace('window.TaxonomySearch = {',
                'window.TaxonomySearch = { revealNodeInTree: revealNodeInTree, '
                + 'selectResultForTest: function (node) { resultState.nodes = [node]; navigateToResult(0, false, "select"); },'), context);
            return window.TaxonomySearch;
        },
        loadOnboarding() {
            vm.runInContext(onboardingSource.replace('window.TaxonomyOnboarding = {',
                'window.TaxonomyOnboarding = { reviewResultsForTest: performNextAction,'), context);
            return window.TaxonomyOnboarding;
        },
        key(key) { document.activeElement.dispatchEvent({ type: 'keydown', key }); }
    };
}

test('list renders BP/BR as named groups and original entries as the first actual nodes', () => {
    const data = freeze(catalogue()), before = JSON.stringify(data);
    const h = harness({ data, scores: freeze({ BP: 91, 'BP-1000': 64, BR: 37 }) });
    for (const code of ['BP', 'BR']) {
        const group = h.group(code);
        assert.ok(group, code + ' needs a sub-taxonomy heading');
        assert.equal(group.classList.contains('tax-node'), false);
        assert.notEqual(group.getAttribute('role'), 'treeitem');
        assert.equal(group.getAttribute('data-code'), null);
        const heading = group.querySelector('.tax-container-heading');
        assert.equal(heading.tagName, 'H3');
        assert.equal(group.querySelector(':scope > .tax-children').getAttribute('aria-labelledby'), heading.id);
        assert.equal(group.querySelector(':scope > .tax-children > [role="treeitem"]').dataset.code, code + '-1000');
        assert.equal(h.row(code), null);
    }
    assert.equal(h.group('BP').querySelector('.tax-container-score').textContent, 'Area relevance: 91%');
    assert.equal(h.row('BP-1000').querySelector('.tax-pct').textContent, '64%');
    assert.equal(JSON.stringify(data), before, 'presentation must preserve the full source snapshot');
    assert.deepEqual(h.state.currentScores, { BP: 91, 'BP-1000': 64, BR: 37 });
});

test('each list area has a labelled tree below its heading and one original tab stop', () => {
    const h = harness();
    assert.equal(h.tree.getAttribute('role'), 'region');
    for (const code of ['BP', 'BR']) {
        const group = h.group(code), tree = group.querySelector(':scope > .tax-children');
        assert.equal(tree.getAttribute('role'), 'tree');
        assert.equal(tree.getAttribute('aria-labelledby'), group.querySelector('h3').id);
        assert.ok(tree.querySelector('h3') === null, 'headings must remain outside the ARIA tree');
        assert.deepEqual(tabStops(tree), [code + '-1000']);
        assert.equal(group.querySelector('button').getAttribute('tabindex'), null,
            'area buttons keep their native tab behavior');
    }
});

test('group collapse uses a labelled native button and keeps child construction lazy', () => {
    const h = harness(), group = h.group('BP');
    assert.ok(group, 'container group is present');
    const button = group.querySelector('.tax-container-toggle');
    assert.equal(button.tagName, 'BUTTON'); assert.equal(button.getAttribute('type'), 'button');
    assert.equal(button.getAttribute('aria-expanded'), 'true');
    const children = group.querySelector(':scope > .tax-children');
    assert.equal(button.getAttribute('aria-controls'), children.id);
    assert.ok(button.textContent.includes('Business Processes'));
    assert.equal(h.row('BP-1327'), null, 'collapsed descendants must remain deferred');
    button.click();
    assert.equal(button.getAttribute('aria-expanded'), 'false'); assert.equal(children.style.display, 'none');
    button.click();
    assert.equal(button.getAttribute('aria-expanded'), 'true'); assert.equal(children.style.display, '');
    assert.equal(h.row('BP-1327'), null);
});

test('path materialization traverses a heading and preserves original descendants', () => {
    const h = harness();
    assert.ok(h.group('BP'), 'path starts at a heading, not an extra catalogue row');
    assert.equal(h.row('BP-1060'), null);
    const child = h.browse.ensureNodeRendered('BP-1060', null);
    assert.equal(child?.dataset.code, 'BP-1060');
    assert.equal(child._taxonomyNode, h.state.taxonomyData[0].children[0].children[0].children[0]);
    assert.equal(h.browse.ensureNodeRendered('BP', null), h.group('BP'));
    assert.equal(h.row('BP'), null);
    h.browse.materializeAllTaxonomyNodes(null);
    assert.deepEqual(h.tree.querySelectorAll('[role="treeitem"]').map(node => node.dataset.code),
        ['BP-1000', 'BP-1327', 'BP-1060', 'BR-1000', 'BR-1228']);
});

test('keyboard navigation roves among actual entries within the current area tree', () => {
    const h = harness();
    assert.ok(h.row('BP-1000'), 'first original row is materialized');
    h.row('BP-1000').focus(); h.key('ArrowDown');
    assert.ok(h.document.activeElement === h.row('BP-1000'), 'ArrowDown stays in the current tree');
    h.key('Home'); assert.ok(h.document.activeElement === h.row('BP-1000'));
    h.key('ArrowRight'); assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'true');
    h.key('ArrowRight'); assert.ok(h.document.activeElement === h.row('BP-1327'));
    assert.deepEqual(tabStops(h.row('BP-1000').closest('[role="tree"]')), ['BP-1327']);
    assert.deepEqual(tabStops(h.row('BR-1000').closest('[role="tree"]')), ['BR-1000']);
    h.key('ArrowUp'); assert.ok(h.document.activeElement === h.row('BP-1000'));
    h.key('End'); assert.ok(h.document.activeElement === h.row('BP-1327'));
    h.key('ArrowLeft'); assert.ok(h.document.activeElement === h.row('BP-1000'));
    h.key('ArrowLeft'); assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'false');
    assert.deepEqual(tabStops(h.row('BP-1000').closest('[role="tree"]')), ['BP-1000']);
    const button = h.group('BP').querySelector('.tax-container-toggle');
    button.focus(); h.key('ArrowDown');
    assert.ok(h.document.activeElement === button, 'tree navigation does not intercept native heading controls');
});

test('successive lazy materialization preserves the focused row and creates no hidden tab stops', () => {
    const h = harness({ interactive: true });
    h.window.TaxonomyScoring.runInteractiveAnalysis();
    const before = JSON.stringify(h.state.taxonomyData);
    const bp = h.group('BP'), br = h.group('BR');
    for (const group of [bp, br]) {
        assert.notEqual(group.querySelector(':scope > .tax-children').getAttribute('role'), 'tree',
            'an unmaterialized area has no empty tree widget');
    }
    const bpTree = h.browse.materializeChildren(bp, null);
    assert.equal(bpTree.getAttribute('role'), 'tree');
    assert.deepEqual(tabStops(bpTree), [], 'materializing a collapsed area does not add a hidden tab stop');
    h.browse.setNodeExpanded(bp, true);
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    h.browse.setNodeExpanded(h.row('BP-1000'), true);
    h.row('BP-1327').focus();
    h.browse.setNodeExpanded(br, true);
    h.browse.setNodeExpanded(h.row('BP-1327'), true);
    h.browse.materializeChildren(bp, null);
    h.browse.materializeChildren(br, null);
    assert.ok(h.document.activeElement === h.row('BP-1327'), 'opening another branch preserves focus');
    assert.deepEqual(tabStops(bpTree), ['BP-1327']);
    assert.deepEqual(tabStops(h.row('BR-1000').closest('[role="tree"]')), ['BR-1000']);
    assert.equal(h.row('BP-1060').getAttribute('tabindex'), '-1');
    assert.equal(h.requests.length, 0, 'materialization and roving do not trigger evaluation');
    assert.equal(JSON.stringify(h.state.taxonomyData), before);
});

test('bulk materialization bounds focus scans by the final catalogue size', () => {
    const h = harness({ data: freeze([catalogue()[0]]) });
    const tree = h.row('BP-1000').closest('[role="tree"]');
    h.row('BP-1000').focus();
    const query = tree.querySelectorAll.bind(tree);
    let scannedItems = 0;
    tree.querySelectorAll = selector => {
        const items = query(selector);
        if (selector === '[role="treeitem"]') scannedItems += items.length;
        return items;
    };
    h.browse.materializeAllTaxonomyNodes(null);
    tree.querySelectorAll = query;
    const itemCount = tree.querySelectorAll('[role="treeitem"]').length;
    assert.equal(itemCount, 3, 'the real BP path is completely materialized');
    assert.ok(scannedItems <= itemCount,
        'one bulk operation must not rescan the growing tree after every deferred branch: ' + scannedItems + ' visits for ' + itemCount + ' items');
    assert.deepEqual(tabStops(tree), ['BP-1000']);
    assert.ok(h.document.activeElement === h.row('BP-1000'));
    assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'false');
    assert.equal(h.row('BP-1060').getAttribute('tabindex'), '-1');
    assert.equal(h.requests.length, 0);
});

test('collapsing a focused branch restores a visible ancestor or the native area button', () => {
    const h = harness(), bp = h.group('BP'), br = h.group('BR');
    h.browse.setNodeExpanded(h.row('BP-1000'), true);
    h.browse.setNodeExpanded(h.row('BP-1327'), true);
    h.row('BP-1060').focus();
    const bpTree = h.row('BP-1000').closest('[role="tree"]');
    h.browse.setNodeExpanded(br, false);
    assert.ok(h.document.activeElement === h.row('BP-1060'), 'unrelated collapse keeps focus');
    assert.deepEqual(tabStops(bpTree), ['BP-1060']);
    h.row('BP-1000').querySelector(':scope > .tax-node-header > .tax-toggle').click();
    assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'false');
    assert.ok(h.document.activeElement === h.row('BP-1000'));
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    h.browse.setNodeExpanded(bp, false);
    assert.ok(h.document.activeElement === bp.querySelector('.tax-container-toggle'));
    assert.deepEqual(tabStops(bpTree), [], 'collapsed area rows leave the tab order');
    h.browse.setNodeExpanded(bp, true);
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    h.browse.expandAllForTest();
    h.row('BR-1228').focus();
    h.browse.collapseAllForTest();
    assert.ok(h.document.activeElement === br.querySelector('.tax-container-toggle'));
    assert.equal(h.tree.querySelectorAll('[role="treeitem"][tabindex="0"]').length, 0);
    h.browse.expandAllForTest();
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    assert.deepEqual(tabStops(h.row('BR-1000').closest('[role="tree"]')), ['BR-1000']);
});

test('collapse all returns hidden focus to a visible original in legacy and mixed root trees', () => {
    const data = catalogue();
    for (const example of [
        { data: [data[0].children[0]], original: 'BP-1000', descendant: 'BP-1060' },
        { data: [data[0], data[1].children[0]], original: 'BR-1000', descendant: 'BR-1228' }
    ]) {
        const h = harness({ data: example.data });
        h.browse.expandAllForTest();
        h.row(example.descendant).focus();
        h.browse.collapseAllForTest();
        assert.ok(h.document.activeElement === h.row(example.original),
            'bulk collapse must move focus out of the hidden branch to its visible original root');
        assert.deepEqual(tabStops(h.row(example.original).closest('[role="tree"]')), [example.original]);
        assert.equal(h.row(example.descendant).getAttribute('tabindex'), '-1');
        assert.equal(h.requests.length, 0);
    }
});

test('tabs retain named container tabs while showing original nodes and an empty-state message', () => {
    const data = catalogue(); data[1].children = [];
    const h = harness({ view: 'tabs', data });
    assert.equal(h.tree.querySelector('#tax-tab-BP').getAttribute('role'), 'tab');
    assert.equal(h.tree.querySelector('#tax-pane-BP [role="treeitem"]').dataset.code, 'BP-1000');
    assert.ok(h.row('BR') === null, 'an empty container must never become a fallback catalogue row');
    assert.equal(h.tree.querySelector('#tax-pane-BR .tax-container-empty').textContent, 'No catalogue entries');
    assert.equal(h.tree.querySelector('#tax-pane-BR .tax-container-toggle'), null);
    h.tree.querySelector('#tax-tab-BR').click();
    assert.equal(h.tree.querySelector('#tax-tab-BR').getAttribute('aria-selected'), 'true');
    assert.equal(h.tree.querySelector('#tax-pane-BR').classList.contains('active'), true);
    assert.equal(h.tree.querySelectorAll('[role="treeitem"][tabindex="0"]').length, 0,
        'an empty active tab leaves no hidden original in the tab order');
});

test('tab changes assign the sole entry tab stop to the visible tree without moving tab focus', () => {
    const h = harness({ view: 'tabs' });
    const bpTree = h.row('BP-1000').closest('[role="tree"]');
    const brTree = h.row('BR-1000').closest('[role="tree"]');
    assert.equal(h.tree.getAttribute('role'), 'region');
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    assert.deepEqual(tabStops(brTree), []);
    const brTab = h.tree.querySelector('#tax-tab-BR');
    brTab.focus(); brTab.click();
    assert.ok(h.document.activeElement === brTab);
    assert.deepEqual(tabStops(bpTree), []);
    assert.deepEqual(tabStops(brTree), ['BR-1000']);
    h.row('BR-1000').focus(); h.key('ArrowRight'); h.key('ArrowRight');
    assert.ok(h.document.activeElement === h.row('BR-1228'));
    assert.deepEqual(tabStops(brTree), ['BR-1228']);
    h.key('Home'); assert.ok(h.document.activeElement === h.row('BR-1000'));
    const bpTab = h.tree.querySelector('#tax-tab-BP');
    bpTab.focus(); bpTab.click();
    assert.ok(h.document.activeElement === bpTab);
    assert.deepEqual(tabStops(bpTree), ['BP-1000']);
    assert.deepEqual(tabStops(brTree), []);
});

test('ordinary top-level catalogue nodes remain selectable in both list and tabs', () => {
    const ordinary = { ...catalogue()[0].children[0], level: 0, parentCode: null, taxonomyContainer: false };
    for (const view of ['list', 'tabs']) {
        const h = harness({ view, data: [ordinary] });
        const row = h.tree.querySelector('[role="treeitem"]');
        assert.equal(row.dataset.code, 'BP-1000', view);
        assert.equal(h.group('BP-1000'), null);
        assert.deepEqual(tabStops(row.closest('[role="tree"]')), ['BP-1000']);
        assert.equal(h.tree.getAttribute('role'), view === 'tabs' ? 'region' : 'tree');
        if (view === 'tabs') {
            assert.equal(row.closest('[role="tree"]').getAttribute('aria-labelledby'), 'tax-tab-BP-1000');
        }
    }
});

test('a list of original roots keeps its one tab stop on the focused visible original', () => {
    const data = catalogue().map(area => ({ ...area.children[0], level: 0, parentCode: null, taxonomyContainer: false }));
    const h = harness({ data });
    h.row('BR-1000').focus();
    h.browse.setNodeExpanded(h.row('BP-1000'), true);
    assert.deepEqual(h.tree.querySelectorAll('[role="treeitem"][tabindex="0"]').map(item => item.dataset.code),
        ['BR-1000'], 'lazy expansion preserves the focused entry as the only tab stop');
    h.key('Home');
    assert.ok(h.document.activeElement === h.row('BP-1000'));
    assert.deepEqual(tabStops(h.tree), ['BP-1000']);
});

test('mixed list roots keep original top-level entries in their own labelled tree', () => {
    const data = catalogue();
    const original = { ...data[1].children[0], level: 0, parentCode: null, taxonomyContainer: false };
    const h = harness({ data: [data[0], original] });
    const originalTree = h.row('BR-1000').closest('[role="tree"]');
    assert.equal(h.tree.getAttribute('role'), 'region');
    assert.ok(originalTree !== h.tree);
    assert.equal(originalTree.getAttribute('aria-label'), 'BR-1000 Business Roles');
    assert.deepEqual(tabStops(originalTree), ['BR-1000']);
    assert.ok(h.row('BR-1000')._taxonomyNode === original);
    assert.equal(h.group('BR-1000'), null);
});

test('rendering an explicit unscored view does not reuse scores from the previous state', () => {
    const h = harness({ scores: { BP: 91, 'BP-1000': 64 } });
    const previous = h.row('BP-1000');
    h.browse.renderView(h.state.taxonomyData, null);
    assert.ok(h.row('BP-1000') !== previous, 'rerender replaces the old DOM projection');
    assert.ok(h.row('BP-1000').querySelector('.tax-pct') === null, 'requested null scores must remain unscored');
    assert.ok(h.group('BP').querySelector('.tax-container-score') === null);
    assert.equal(h.state.currentScores['BP-1000'], 64, 'rendering must not edit analysis state');
});

test('incremental scores update the area heading and original row without changing source or raw evidence', () => {
    const data = freeze(catalogue()), before = JSON.stringify(data);
    const h = harness({ data });
    const raw = freeze({ BP: 72, 'BP-1000': 43 });
    h.state.currentRawScores = raw;
    h.state.currentScores = freeze({ ...raw });
    h.state.currentReasons = freeze({ BP: 'Area fixture', 'BP-1000': 'Original fixture' });
    const group = h.group('BP'), row = h.row('BP-1000');
    const scoring = h.window.TaxonomyScoring;
    scoring.applyScoreToNode('BP', 72, h.state.currentReasons.BP);
    scoring.applyScoreToNode('BP-1000', 43, h.state.currentReasons['BP-1000']);
    scoring.syncVisibleScoreNodes();
    const heading = group.querySelector('.tax-container-heading');
    assert.equal(heading.querySelector('.tax-container-score').textContent, 'Area relevance: 72%');
    assert.equal(heading.querySelector('.tax-container-score').title, 'Area fixture');
    assert.ok(heading.querySelector('.tax-pct') === null, 'area scores do not become catalogue-node score badges');
    assert.equal(row.querySelector('.tax-pct').textContent, '43%');
    assert.equal(row.querySelector('.tax-reason-icon').title, 'Original fixture');
    assert.ok(h.group('BR').querySelector('.tax-container-score') === null, 'missing area evidence is not a zero assessment');
    assert.ok(h.group('BP') === group && h.row('BP-1000') === row, 'incremental updates preserve DOM identity');
    assert.ok(h.state.currentRawScores === raw);
    assert.deepEqual(h.state.currentRawScores, { BP: 72, 'BP-1000': 43 });
    assert.deepEqual(h.state.currentScores, { BP: 72, 'BP-1000': 43 });
    assert.equal(JSON.stringify(data), before);
});

for (const language of ['de', 'en']) {
    test(`${language}: an unresolved root exposes its clarification without a score and retains assessed siblings`, () => {
        // CO -> CO-1000 is the original workbook edge. The bounded fixture does
        // not infer or synthesize assessments for the omitted CO descendants.
        const data = freeze([...catalogue(), { code: 'CO', name: 'Communications Services', level: 0,
            taxonomyRoot: 'CO', taxonomyContainer: true, children: [{ code: 'CO-1000', name: 'Communications Services',
                level: 1, parentCode: 'CO', taxonomyRoot: 'CO', children: [] }] }]);
        const reason = language === 'de' ? 'Die Erfassungskanäle sind offen. Soll das System vernetzt arbeiten?'
            : 'The recording channels are unspecified. Should the system use a network?';
        const scores = freeze({ BP: 72, 'BP-1000': 43 });
        const h = harness({ language, data, scores, reasons: { BP: 'Retained evidence', CO: reason },
            coverage: { failedOrBlockedNodes: 2, nodes: {
                CO: { state: 'UNKNOWN', score: null, effectiveRelevance: null, descendants: 'UNASSESSED',
                    reason: 'CLARIFICATION: ' + reason },
                'CO-1000': { state: 'UNKNOWN', score: null, effectiveRelevance: null, descendants: 'UNASSESSED',
                    reason: 'BLOCKED_BY:CO' }
            } } });
        const group = h.group('CO'), heading = group.querySelector('.tax-container-heading');
        assert.match(heading.textContent, language === 'de' ? /Klärung erforderlich/ : /Clarification needed/);
        assert.equal(group.querySelector('.tax-container-score'), null);
        assert.doesNotMatch(group.textContent, /0%|null|undefined|CLARIFICATION:/);
        const explanation = group.querySelector(':scope > .tax-container-clarification');
        assert.equal(explanation?.textContent, reason, 'the actual clarification is visible, not only in a tooltip');
        const toggle = heading.querySelector('button');
        assert.equal(toggle.getAttribute('aria-describedby'), explanation.id);
        assert.equal(group.getAttribute('aria-describedby'), explanation.id);
        toggle.focus();
        assert.equal(h.document.activeElement, toggle);
        toggle.click();
        assert.equal(toggle.getAttribute('aria-expanded'), 'false');
        assert.equal(explanation.hidden, false, 'collapsing descendants does not hide the open question');
        assert.equal(h.group('BP').querySelector('.tax-container-score').textContent, 'Area relevance: 72%');
        assert.equal(h.row('BP-1000').querySelector('.tax-pct').textContent, '43%');
        assert.deepEqual(h.state.currentRawScores, { BP: 72, 'BP-1000': 43 });
        assert.equal(h.requests.length, 0, 'reading the clarification does not invoke a model');
    });
}

test('a root clarification replaces stale numeric evidence and is removed when that root is assessed', () => {
    const h = harness({ scores: { BP: 72 } });
    const group = h.group('BP'), toggle = group.querySelector('button');
    h.state.currentScores = {};
    h.state.currentRawScores = {};
    h.state.analysisCoverage = { failedOrBlockedNodes: 4, nodes: { BP: { state: 'UNKNOWN', score: null,
        effectiveRelevance: null, descendants: 'UNASSESSED', reason: 'CLARIFICATION: Which process is required?' } } };
    h.window.TaxonomyScoring.syncVisibleScoreNodes();
    assert.equal(group.querySelector('.tax-container-score'), null);
    assert.equal(group.querySelector('.tax-container-clarification')?.textContent, 'Which process is required?');
    assert.doesNotMatch(group.textContent, /72%|0%/);
    h.state.currentScores = { BP: 0 };
    h.state.currentRawScores = { BP: 0 };
    h.state.currentReasons = { BP: 'Explicitly excluded by the clarified requirement.' };
    h.state.analysisCoverage = { failedOrBlockedNodes: 0, nodes: { BP: { state: 'NOT_RELEVANT', score: 0,
        effectiveRelevance: 0, descendants: 'COMPLETE', reason: null } } };
    h.window.TaxonomyScoring.syncVisibleScoreNodes();
    assert.equal(group.querySelector('.tax-container-score').textContent, 'Area relevance: 0%');
    assert.equal(group.querySelector('.tax-container-clarification'), null);
    assert.equal(toggle.getAttribute('aria-describedby'), null);
    assert.equal(group.getAttribute('aria-describedby'), null);
    assert.equal(h.group('BP'), group, 'assessment updates retain the current tree and focus targets');
});

test('scoring expansion opens an initially collapsed heading and original path with the native button expanded state', () => {
    for (const view of ['list', 'tabs']) for (const method of ['expandNodeByCode', 'expandMatched']) {
        const h = harness({ view }), group = h.group('BP');
        // An automatic initial collapse is not an explicit reader preference.
        h.browse.setNodeExpanded(group, false, true);
        assert.ok(h.row('BP-1060') === null, 'deep catalogue entries begin deferred');
        h.state.currentScores = freeze({ 'BP-1060': 48 });
        h.window.TaxonomyScoring[method](method === 'expandNodeByCode' ? 'BP-1060' : h.state.currentScores);
        assert.equal(group.querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'true', view + '/' + method);
        assert.equal(group.getAttribute('aria-expanded'), null, 'the group itself is not a selectable expandable treeitem');
        for (const code of ['BP-1000', 'BP-1327']) {
            const row = h.row(code);
            assert.equal(row.getAttribute('aria-expanded'), 'true');
            assert.equal(row.querySelector(':scope > .tax-children').style.display, '');
        }
        assert.equal(group.querySelector(':scope > .tax-children').style.display, '');
        assert.equal(h.row('BP-1060').dataset.code, 'BP-1060');
        assert.ok(h.row('BP') === null);
        assert.equal(h.requests.length, 0, 'making an existing score path visible does not request more analysis');
    }
});

test('automatic scoring expansion preserves an explicit closed heading through rerender', () => {
    for (const view of ['list', 'tabs']) for (const method of ['expandNodeByCode', 'expandMatched']) {
        const h = harness({ view }), group = h.group('BP');
        h.browse.setNodeExpanded(group, false);
        h.state.currentScores = freeze({ 'BP-1060': 48 });
        h.window.TaxonomyScoring[method](method === 'expandNodeByCode' ? 'BP-1060' : h.state.currentScores);
        assert.equal(group.querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'false', view + '/' + method);
        assert.equal(h.state.browseExpansion.get('BP'), false);
        assert.equal(h.row('BP-1060'), null, 'an explicitly closed path remains deferred');
        h.browse.renderView(h.state.taxonomyData, h.state.currentScores);
        assert.equal(h.group('BP').querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'false');
        assert.equal(h.row('BP-1060'), null);
        assert.equal(h.requests.length, 0);
    }
});

test('explicit scored-node navigation can open a previously closed heading and original path', () => {
    for (const view of ['list', 'tabs']) {
        const h = harness({ view }), group = h.group('BP');
        h.browse.setNodeExpanded(group, false);
        h.state.currentScores = freeze({ 'BP-1060': 48 });
        h.window.TaxonomyScoring.expandNodeByCode('BP-1060', true);
        assert.equal(group.querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'true');
        assert.equal(h.state.browseExpansion.get('BP'), true, 'explicit navigation replaces the older close choice');
        for (const code of ['BP-1000', 'BP-1327']) {
            assert.equal(h.row(code).getAttribute('aria-expanded'), 'true');
        }
        assert.equal(h.row('BP-1060').dataset.code, 'BP-1060');
        assert.equal(h.requests.length, 0, 'revealing existing evidence never starts another assessment');
    }
});

test('Review results reopens the best original match after Collapse All using real navigation modules', () => {
    for (const view of ['list', 'tabs']) {
        const data = freeze(catalogue()), before = JSON.stringify(data), h = harness({ view, data });
        const scores = freeze({ 'BP-1060': 48 });
        h.state.currentScores = scores;
        h.browse.collapseAllForTest();
        assert.equal(h.row('BP-1060'), null, 'the actual best match starts deferred');
        const action = h.document.createElement('button');
        action.id = 'taskNextAction'; action.dataset.action = 'review-results';
        h.document.body.appendChild(action);
        h.loadOnboarding().reviewResultsForTest();
        assert.equal(h.group('BP').querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'true');
        for (const code of ['BP-1000', 'BP-1327']) {
            assert.equal(h.row(code).getAttribute('aria-expanded'), 'true');
            assert.equal(h.row(code).querySelector(':scope > .tax-children').style.display, '');
        }
        assert.ok(h.document.activeElement === h.row('BP-1060'), 'the requested existing result becomes the visible focus target');
        assert.equal(h.row('BP-1060').getAttribute('tabindex'), '0');
        assert.ok(h.state.currentScores === scores);
        assert.equal(JSON.stringify(data), before);
        assert.equal(h.requests.length, 0, 'reviewing an existing assessment never requests another provider result');
    }
});

test('search reveals original catalogue descendants through a collapsed heading', () => {
    const h = harness(), group = h.group('BP');
    h.browse.setNodeExpanded(group, false);
    const revealed = h.loadSearch().revealNodeInTree('BP-1060');
    assert.equal(revealed?.dataset.code, 'BP-1060');
    assert.equal(group.querySelector('.tax-container-toggle').getAttribute('aria-expanded'), 'true');
    assert.equal(group.getAttribute('aria-expanded'), null, 'expanded state belongs to the native heading button');
    assert.equal(group.querySelector(':scope > .tax-children').style.display, '');
    assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'true');
    assert.equal(h.row('BP-1327').getAttribute('aria-expanded'), 'true');
    assert.equal(revealed.querySelector('.tax-node-header').classList.contains('search-highlight'), true);
    assert.equal(revealed.getAttribute('tabindex'), '0');
    assert.ok(h.row('BP') === null);
    assert.deepEqual(tabStops(h.row('BP-1000').closest('[role="tree"]')), ['BP-1060']);
    assert.deepEqual(tabStops(h.row('BR-1000').closest('[role="tree"]')), ['BR-1000'],
        'search updates only the selected tree widget');
});

test('searching for a container highlights its heading and returns the native focus target', () => {
    const h = harness(), group = h.group('BP');
    h.browse.setNodeExpanded(group, false);
    const revealed = h.loadSearch().revealNodeInTree('BP');
    assert.ok(revealed === group.querySelector('.tax-container-toggle'), 'container search returns the heading button');
    assert.equal(group.querySelector('.tax-container-heading').classList.contains('search-highlight'), true);
    assert.equal(group.getAttribute('aria-expanded'), null);
    assert.ok(h.row('BP') === null);
});

test('selecting a container search result confirms focus on the native heading button', () => {
    const h = harness(), group = h.group('BP');
    const area = h.document.createElement('div'); area.id = 'searchResultsArea';
    h.document.body.appendChild(area);
    h.browse.setNodeExpanded(group, false);
    h.loadSearch().selectResultForTest({code: 'BP'});
    assert.ok(h.document.activeElement === group.querySelector('.tax-container-toggle'),
        'The result-selection focus resolver must target the native control, not its section');
    assert.equal(area.dataset.navigationFocusConfirmed, 'true');
});

for (const view of ['list', 'tabs']) test(`${view}: interactive heading expansion evaluates the original first level once`, async () => {
    const h = harness({ view, interactive: true });
    h.window.TaxonomyScoring.runInteractiveAnalysis();
    const group = h.group('BP');
    assert.ok(group, 'interactive mode retains a heading control');
    const button = group.querySelector('.tax-container-toggle');
    assert.equal(button.getAttribute('aria-expanded'), 'false');
    assert.equal(h.requests.length, 0, 'rendering never spends provider quota');
    button.click();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].searchParams.get('parentCode'), 'BP');
    assert.equal(h.requests[0].searchParams.get('businessText'), 'A deterministic requirement');
    assert.equal(h.state.currentScores['BP-1000'], 83);
    assert.equal(h.row('BP-1000').querySelector('.tax-pct').textContent, '83%');
    assert.equal(h.state.currentReasons['BP-1000'], 'Fixture evidence');
    assert.equal(button.getAttribute('aria-expanded'), 'true');
    assert.equal(group.querySelector(':scope > .tax-children').style.display, '');
    button.click(); button.click();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.requests.length, 1, 'reopening evaluated content does not repeat analysis');
    assert.equal(h.errors.length, 0);
});


test('an unconfirmed session lets users browse an unevaluated area without starting node model work', () => {
    const h = harness({ interactive: true });
    h.window.TaxonomyScoring.runInteractiveAnalysis();
    h.state.analysisProviderId = 'GEMMA';
    h.window.TaxonomyAnalysisSession = { state: () => ({ ready: false }) };
    const group = h.group('BP'), button = group.querySelector('.tax-container-toggle');
    button.click();
    assert.equal(h.requests.length, 0);
    assert.equal(button.getAttribute('aria-expanded'), 'true');
    assert.equal(h.state.evaluatedNodes.has('BP'), false);
});
