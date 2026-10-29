const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = readFileSync(path.resolve(__dirname, '../../main/resources/static/js/shared/taxonomy-help.js'), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));

// Only browser primitives and transport are doubled; the production reader owns navigation and rendering.
function fixture(hash = '#help/USER_GUIDE/guide-section-8') {
    const ids = new Map(), handlers = {}, windowHandlers = {}, requests = [], focus = [], scrolls = [];
    function element(tag, id) {
        const attrs = {}, classes = new Set(), events = {};
        const node = {tagName: tag.toUpperCase(), nodeType: 1, childNodes: [], dataset: {}, style: {},
            attributes: [], textContent: '', scrollTop: 99,
            classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c),
                toggle(c, yes) {yes ? classes.add(c) : classes.delete(c);}},
            setAttribute(k, v) {attrs[k] = String(v); this.attributes = Object.entries(attrs).map(([name, value]) => ({name, value})); if (k === 'id') ids.set(v, this);},
            getAttribute: k => attrs[k] || null,
            appendChild(child) {this.childNodes.push(child); child.parentElement = this; return child;},
            addEventListener(k, fn) {events[k] = fn;},
            fire(k, event) {events[k]?.(event);},
            closest(selector) {return selector === '.help-content-area' ? area : selector === 'a[href]' && tag === 'a' ? this : null;},
            querySelectorAll(selector) {return this.childNodes.flatMap(n => [n, ...n.querySelectorAll(selector)]).filter(n => selector === '[id]' ? !!n.getAttribute('id') : n.tagName === selector.toUpperCase());},
            querySelector(selector) {return this.querySelectorAll(selector)[0] || null;},
            contains(n) {return this === n || this.childNodes.some(c => c.contains(n));},
            focus() {focus.push(this.getAttribute('id'));},
            scrollIntoView() {scrolls.push(this.getAttribute('id'));}};
        Object.defineProperty(node, 'innerHTML', {get: () => '', set() {node.childNodes = [];}});
        Object.defineProperty(node, 'nextElementSibling', {get() {
            const siblings = node.parentElement?.childNodes.filter(n => n.nodeType === 1) || [];
            return siblings[siblings.indexOf(node) + 1] || null;
        }});
        if (id) node.setAttribute('id', id);
        return node;
    }
    const area = element('div');
    for (const id of ['helpTocList', 'helpSearchInput', 'helpDocBody', 'helpDocTitle', 'helpBackToTop']) element('div', id);
    for (const id of ['leftPanel', 'rightPanel', 'tab-help', 'tab-analyze']) element('div', id);
    const browserHelp = element('a', 'browserHelpLink');
    browserHelp.setAttribute('href', '/taxonomy/?lang=de#help/USER_GUIDE/taxonomy-view-list');
    const location = new URL('https://example.test/taxonomy/?lang=de' + hash);
    const document = {getElementById: id => ids.get(id), createElement: element,
        createTextNode: text => Object.assign(element('span'), {textContent: text}),
        querySelector: selector => selector === '#taxonomyBrowserHelp .context-help' ? browserHelp : null, querySelectorAll: () => [],
        dispatchEvent(event) {(handlers[event.type] || []).forEach(fn => fn(event));},
        addEventListener(k, fn) {(handlers[k] ||= []).push(fn);}};
    const fire = (k, detail) => (handlers[k] || []).forEach(fn => fn({detail}));
    const window = {location, addEventListener(k, fn) {(windowHandlers[k] ||= []).push(fn);},
        navigateToPage(page) {location.hash = '#' + page; fire('taxonomy:page-activated', {page});}};
    const history = {replaceState(_a, _b, url) {location.href = new URL(url, location).href;}};
    const fetch = url => new Promise(resolve => requests.push({url, resolve}));
    class DOMParser {
        parseFromString(html) {
            const body = element('body');
            const stack = [body];
            for (const match of html.matchAll(/<(\/?)([a-z][a-z0-9]*)([^>]*)>/g)) {
                if (match[1]) {if (stack.length > 1) stack.pop(); continue;}
                const node = element(match[2]);
                for (const attr of match[3].matchAll(/([\w-]+)="([^"]*)"/g)) node.setAttribute(attr[1], attr[2]);
                stack.at(-1).appendChild(node); stack.push(node);
            }
            return {body};
        }
    }
    const context = {window, document, history, fetch, DOMParser, URL, console,
        CustomEvent: class {constructor(type, init) {this.type = type; this.detail = init.detail;}},
        Node: {TEXT_NODE: 3, ELEMENT_NODE: 1},
        TaxonomyI18n: {t: k => k, getLocale: () => 'de', resolveUrl: url => '/taxonomy' + url},
        TaxonomyUtils: {escapeHtml: String}};
    vm.runInNewContext(source, context);
    function response(request, html, toc = []) {request.resolve({ok: true, text: async () => html, json: async () => toc});}
    return {ids, requests, location, focus, scrolls, window, fire, response, context,
        hash(value) {location.hash = value; (windowHandlers.hashchange || []).forEach(fn => fn());}};
}

test('a deep link survives page activation and loads the exact localized section', async () => {
    const f = fixture();
    f.window.navigateToPage('help');
    // TOC may arrive after navigation; it must not replace the requested document.
    for (const r of f.requests.filter(r => !r.url.includes('USER_GUIDE'))) f.response(r, '');
    await tick();
    const doc = f.requests.find(r => r.url === '/taxonomy/help/USER_GUIDE?lang=de');
    assert.ok(doc, 'Help must request the selected document in the current language and application mount');
    f.response(doc, '<h2 id="guide-section-8">Graph Explorer</h2>'); await tick();
    assert.equal(f.location.hash, '#help/USER_GUIDE/guide-section-8');
    assert.deepEqual(f.scrolls, ['guide-section-8']);
    assert.deepEqual(f.focus, ['guide-section-8']);
});

test('a slower previous document cannot overwrite the latest help selection', async () => {
    const f = fixture('#help/USER_GUIDE');
    f.window.navigateToPage('help');
    for (const r of f.requests.filter(r => !r.url.includes('USER_GUIDE'))) f.response(r, '');
    await tick();
    const first = f.requests.find(r => r.url.includes('/help/USER_GUIDE'));
    f.hash('#help/ARCHITECTURE_EDITOR/focus-lens');
    const second = f.requests.find(r => r.url.includes('/help/ARCHITECTURE_EDITOR'));
    assert.ok(second, 'Section links must select the document');
    f.response(second, '<h2 id="focus-lens">Editor</h2>'); await tick();
    f.response(first, '<h2 id="old">Old document</h2>'); await tick();
    assert.equal(f.ids.get('helpDocBody').childNodes[0].getAttribute('id'), 'focus-lens');
    assert.equal(f.location.hash, '#help/ARCHITECTURE_EDITOR/focus-lens');
});

test('malformed document paths do not issue help requests', () => {
    const f = fixture(''); const before = f.requests.length;
    f.hash('#help/../../secrets');
    assert.equal(f.requests.length, before);
});

test('taxonomy help follows all six rendered display modes and ignores unknown modes', () => {
    const f = fixture('');
    for (const mode of ['tree', 'decision', 'sunburst', 'tabs', 'summary', 'list']) {
        f.fire('taxonomy:view-rendered', {view: mode});
        assert.equal(f.ids.get('browserHelpLink').getAttribute('href'),
            '/taxonomy/?lang=de#help/USER_GUIDE/taxonomy-view-' + mode);
    }
    f.fire('taxonomy:view-rendered', {view: '../invalid'});
    assert.equal(f.ids.get('browserHelpLink').getAttribute('href'), '/taxonomy/?lang=de#help/USER_GUIDE/taxonomy-view-list');
});

test('the real page router starts in Help for a document-section deep link', () => {
    const f = fixture();
    const template = readFileSync(path.resolve(__dirname, '../../main/resources/templates/index.html'), 'utf8');
    const script = template.split('<!-- Page Navigation Script -->')[1].split('<script>')[1].split('</script>')[0];
    vm.runInNewContext(script, f.context);
    assert.equal(f.ids.get('tab-help').classList.contains('d-none'), false);
    assert.equal(f.ids.get('tab-analyze').classList.contains('d-none'), true);
    assert.equal(f.location.hash, '#help/USER_GUIDE/guide-section-8');
});

test('an explicit anchor wrapped by the Markdown renderer focuses its following heading', async () => {
    const f = fixture(); f.window.navigateToPage('help');
    f.response(f.requests.find(r => r.url.includes('/help/USER_GUIDE')),
        '<p><a id="guide-section-8"></a></p><h2 id="8-using-the-graph-explorer">Graph</h2>');
    await tick();
    assert.deepEqual(f.focus, ['8-using-the-graph-explorer']);
    assert.deepEqual(f.scrolls, ['8-using-the-graph-explorer']);
});

test('late table-of-contents metadata supplies the current document title without resetting its section', async () => {
    const f = fixture(); f.window.navigateToPage('help');
    f.response(f.requests.find(r => r.url.includes('/help/USER_GUIDE')), '<h2 id="guide-section-8">Graph</h2>');
    await tick();
    f.response(f.requests.find(r => !r.url.includes('USER_GUIDE')), '',
        [{filename: 'USER_GUIDE', icon: '📖', title: 'Benutzerhandbuch', audience: 'Alle'}]);
    await tick();
    assert.equal(f.ids.get('helpDocTitle').classList.contains('d-none'), false);
    assert.equal(f.ids.get('helpDocTitle').textContent, '📖 Benutzerhandbuch');
    assert.deepEqual(f.focus, ['guide-section-8']);
});
