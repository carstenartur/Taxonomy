import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sourcePath = '../../taxonomy-app/src/main/resources/static/js/';
const sources = ['shared/taxonomy-utils.js', 'core/taxonomy-browse.js', 'core/taxonomy-scoring.js']
    .map(file => readFileSync(new URL(sourcePath + file, import.meta.url), 'utf8'));
const searchSource = readFileSync(new URL(sourcePath + 'shared/taxonomy-search.js', import.meta.url), 'utf8');

// A bounded, deterministic catalogue fixture. The BP-1000 -> BP-1327 -> BP-1060
// and BR-1000 -> BR-1228 edges and names come from the checked-in 25AUG2025 Excel
// catalogue; BP/BR are the application's navigation containers. No provider runs.
function catalogue() {
    return [
        { code: 'BP', name: 'Business Processes', level: 0, taxonomyRoot: 'BP', taxonomyContainer: true,
            children: [{ code: 'BP-1000', name: 'Business Processes', level: 1, parentCode: 'BP', taxonomyRoot: 'BP',
                children: [{ code: 'BP-1327', name: 'Enable', level: 2, parentCode: 'BP-1000', taxonomyRoot: 'BP',
                    children: [{ code: 'BP-1060', name: 'Analytical Support', level: 3, parentCode: 'BP-1327', taxonomyRoot: 'BP', children: [] }] }] }] },
        { code: 'BR', name: 'Business Roles', level: 0, taxonomyRoot: 'BR', taxonomyContainer: true,
            children: [{ code: 'BR-1000', name: 'Business Roles', level: 1, parentCode: 'BR', taxonomyRoot: 'BR',
                children: [{ code: 'BR-1228', name: 'Civilian Roles', level: 2, parentCode: 'BR-1000', taxonomyRoot: 'BR', children: [] }] }] }
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
    focus() { this.ownerDocument.activeElement = this; }
    scrollIntoView() {}
    matches(selector) { return selector.split(',').some(part => matchPath(this, part.trim().split(/\s+/), this)); }
    closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) || null; }
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

function harness({ view = 'list', data = catalogue(), scores = null, interactive = false, response } = {}) {
    const document = { readyState: 'loading', activeElement: null, addEventListener() {}, dispatchEvent() {} };
    document.body = new Element('body', document); document.documentElement = new Element('html', document);
    document.documentElement.lang = 'en';
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
        currentReasons: {}, currentScoreDetails: {}, evaluatedNodes: new Set(), interactiveMode: interactive };
    const requests = [], errors = [];
    const i18n = { getLocale: () => 'en', t: (key, ...args) => key === 'browse.container.relevance'
        ? 'Area relevance: ' + args[0] + '%' : key === 'browse.container.empty' ? 'No catalogue entries' : key + (args.length ? ': ' + args.join(', ') : '') };
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
    vm.runInContext(sources[1], context); vm.runInContext(sources[2], context);
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

test('keyboard navigation starts at actual entries and expands their own descendants', () => {
    const h = harness();
    assert.ok(h.row('BP-1000'), 'first original row is materialized');
    h.row('BP-1000').focus(); h.key('ArrowDown');
    assert.equal(h.document.activeElement, h.row('BR-1000'));
    h.key('Home'); assert.equal(h.document.activeElement, h.row('BP-1000'));
    h.key('ArrowRight'); assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'true');
    h.key('ArrowRight'); assert.equal(h.document.activeElement, h.row('BP-1327'));
    h.key('ArrowLeft'); assert.equal(h.document.activeElement, h.row('BP-1000'));
    h.key('ArrowLeft'); assert.equal(h.row('BP-1000').getAttribute('aria-expanded'), 'false');
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
});

test('ordinary top-level catalogue nodes remain selectable in both list and tabs', () => {
    const ordinary = { ...catalogue()[0].children[0], level: 0, parentCode: null, taxonomyContainer: false };
    for (const view of ['list', 'tabs']) {
        const h = harness({ view, data: [ordinary] });
        assert.equal(h.tree.querySelector('[role="treeitem"]').dataset.code, 'BP-1000', view);
        assert.equal(h.group('BP-1000'), null);
    }
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

test('scoring expansion opens a collapsed heading and original path with the native button expanded state', () => {
    for (const view of ['list', 'tabs']) for (const method of ['expandNodeByCode', 'expandMatched']) {
        const h = harness({ view }), group = h.group('BP');
        h.browse.setNodeExpanded(group, false);
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
