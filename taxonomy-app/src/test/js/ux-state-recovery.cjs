const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const resources = path.resolve(__dirname, '../../main/resources');
const source = name => readFileSync(path.join(resources, 'static/js', name), 'utf8');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => {resolve = yes; reject = no;}); return {promise, resolve, reject}; }

function dom() {
    const all = [], downloads = [], revoked = [];
    function element(tag = 'div', id) {
        const classes = new Set(), listeners = new Map();
        const node = {tagName: tag, children: [], attributes: {}, style: {}, dataset: {}, value: '',
            textContent: '', innerHTML: '', disabled: false, hidden: false, open: false,
            classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c),
                toggle(c, force) {if (force === undefined ? !classes.has(c) : force) classes.add(c); else classes.delete(c);}},
            setAttribute(k, v) {this.attributes[k] = String(v); if (k === 'id') this.id = v; if (k === 'value') this.value = v;},
            removeAttribute(k) {delete this.attributes[k]; if (k === 'src') this.src = '';},
            getAttribute(k) {return this.attributes[k] || null;},
            append(...children) {children.forEach(child => this.appendChild(child));},
            appendChild(child) {this.children.push(child); child.parentElement = this; return child;},
            addEventListener(name, fn) {if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn);},
            fire(name, event = {}) {event.preventDefault ||= () => {event.defaultPrevented = true;}; return Promise.all((listeners.get(name) || []).map(fn => fn(event)));},
            click() {if (this.tagName === 'a') downloads.push(this); return this.fire('click');},
            focus() {document.activeElement = this;}, scrollIntoView() {},
            showModal() {this.open = true;}, close() {this.open = false; this.fire('close');},
            remove() {this.removed = true;},
            querySelectorAll(selector) {const result = []; const visit = n => {for (const c of n.children) {if (!c.removed && c.tagName === selector) result.push(c); visit(c);}}; visit(this); return result;},
            querySelector(selector) {return this.querySelectorAll(selector)[0] || null;}
        };
        if (tag === 'select') Object.defineProperty(node, 'options', {get() {return this.children;}});
        if (id) node.id = id;
        all.push(node); return node;
    }
    const listeners = new Map();
    const document = {documentElement: {lang: 'de'}, title: '', activeElement: null,
        createElement: element, createTextNode: text => element('text'),
        getElementById: id => all.find(n => n.id === id && !n.removed) || null,
        addEventListener(name, fn) {listeners.set(name, fn);},
        querySelectorAll: () => [], querySelector: () => null,
        fire(name) {return listeners.get(name)?.();}};
    document.body = element('body'); document.head = element('head');
    let sequence = 0;
    const urls = {createObjectURL: () => 'blob:preview-' + (++sequence), revokeObjectURL: url => revoked.push(url)};
    return {document, element, all, downloads, revoked, urls};
}

function searchFixture() {
    const d = dom(), pending = deferred(); let options;
    for (const id of ['searchInput', 'searchBtn', 'searchClearBtn', 'searchModeSelect', 'searchMaxResults',
        'searchResultsArea', 'analysisSecondaryTools', 'searchPanel']) d.element('div', id);
    const highlights = [{classList: {remove(c) {this.removed = c;}}}];
    d.document.querySelectorAll = () => highlights;
    const window = {};
    vm.runInNewContext(source('shared/taxonomy-search.js'), {window, document: d.document, AbortController,
        TaxonomyI18n: {t: k => k}, TaxonomyUtils: {escapeHtml: String},
        fetch(url, init) {options = init; return pending.promise;}});
    return {...d, window, pending, highlights, signal: () => options?.signal};
}

test('empty search cancels pending work, clears results and cannot revive old results', async () => {
    const f = searchFixture(), area = f.document.getElementById('searchResultsArea');
    f.window.TaxonomySearch.performSearch('old request', 'fulltext', 20);
    f.window.TaxonomySearch.performSearch('', 'fulltext', 20);
    assert.equal(f.signal().aborted, true);
    assert.equal(area.style.display, 'none');
    assert.equal(area.innerHTML, '');
    assert.equal(f.highlights[0].classList.removed, 'search-highlight');
    f.pending.resolve({ok: true, json: async () => []}); await tick();
    assert.equal(area.innerHTML, '');
});

test('search errors and empty results are announced with translated messages', async () => {
    for (const failed of [true, false]) {
        const f = searchFixture(); f.window.TaxonomySearch.performSearch('query', 'fulltext', 20);
        f.pending.resolve({ok: !failed, status: 500, json: async () => []}); await tick();
        const html = f.document.getElementById('searchResultsArea').innerHTML;
        assert.match(html, failed ? /role="alert"/ : /role="status"/);
        assert.match(html, failed ? /search.failed/ : /search.no.results/);
        assert.doesNotMatch(html, /Search failed \(/);
    }
});

test('Find Similar exposes the same local clear control while pending', () => {
    const f = searchFixture(), clear = f.document.getElementById('searchClearBtn');
    clear.hidden = true;
    f.window.TaxonomySearch.findSimilar('CP');
    assert.equal(clear.hidden, false);
    f.window.TaxonomySearch.clearSearch();
    assert.equal(clear.hidden, true); assert.equal(f.signal().aborted, true);
});

async function reportsFixture() {
    const d = dom(), requests = [], prints = [];
    for (const id of ['reportsPageTitle', 'reportsHeading', 'portfolioBack', 'versioningLink',
        'reportOptionsHeading', 'reportScope', 'reportRequirement', 'matrixType', 'previewReport',
        'printReport', 'reportPreviewFrame', 'emptyPreview', 'reportsBusy', 'reportsProject',
        'previewBaseline', 'reportsLive', 'reportsError']) d.element(id.startsWith('reportS') || ['reportRequirement', 'matrixType'].includes(id) ? 'select' : 'div', id);
    const scope = d.document.getElementById('reportScope'); scope.value = 'project';
    scope.append(d.element('option'), d.element('option'));
    d.document.getElementById('matrixType').append(d.element('option'), d.element('option'), d.element('option'));
    const frame = d.document.getElementById('reportPreviewFrame'); frame.contentWindow = {focus() {}, print() {prints.push(frame.src);}};
    frame.contentWindow.location = {get href() {return frame.src;}};
    const header = d.element(); header.append(d.element('h2'));
    frame.parentElement = {previousElementSibling: header};
    const selectors = new Map();
    for (const selector of ['.skip-link', 'label[for="reportScope"]', 'label[for="reportRequirement"]', 'label[for="matrixType"]', '#reportsBusy span']) selectors.set(selector, d.element());
    selectors.set('#reportPreviewFrame', frame);
    d.document.querySelector = selector => selectors.get(selector) || null;
    const window = {addEventListener() {}, TaxonomyPortfolioApi: {
        getProject: async () => ({projectKey: 'P', title: 'Project'}),
        listRequirements: async () => [{id: 1, requirementKey: 'A', title: 'Alpha'}, {id: 2, requirementKey: 'B', title: 'Beta'}],
        fetchReport(...args) {const gate = deferred(); requests.push({args, ...gate}); return gate.promise;},
        reportUrl: () => '/api/report'
    }};
    vm.runInNewContext(source('portfolio/portfolio-reports.js'), {window, document: d.document,
        location: {pathname: '/projects/1/reports', search: '?lang=de'}, URLSearchParams, URL: d.urls, AbortController});
    await d.document.fire('DOMContentLoaded');
    return {...d, requests, prints, frame};
}
const reportResponse = () => ({blob: async () => ({size: 10})});

test('German report scope uses German matrix option labels', async () => {
    const f = await reportsFixture();
    assert.deepEqual(f.document.getElementById('matrixType').options.map(option => option.textContent),
        ['Anforderungen × Taxonomie', 'Anforderungen × Lösungen', 'Lösungen × Produkte']);
});

test('scope changes invalidate the report preview and discard stale success and failure', async () => {
    for (const failed of [true, false]) {
        const f = await reportsFixture(), button = f.document.getElementById('printReport');
        const request = f.document.getElementById('previewReport').click();
        f.document.getElementById('reportScope').value = 'requirement';
        await f.document.getElementById('reportScope').fire('change');
        assert.equal(button.disabled, true);
        assert.match(f.document.getElementById('emptyPreview').textContent, /geändert|veraltet/i);
        if (failed) f.requests[0].reject(new Error('stale failure')); else f.requests[0].resolve(reportResponse());
        await request;
        assert.ok(!f.frame.src || f.frame.src === 'about:blank');
        assert.equal(f.document.getElementById('reportsError').textContent, '');
        assert.equal(button.disabled, true);
    }
});

test('only the loaded current report can be printed, and requirement changes invalidate it', async () => {
    const f = await reportsFixture(), button = f.document.getElementById('printReport');
    const pending = f.document.getElementById('previewReport').click();
    f.requests[0].resolve(reportResponse()); await pending;
    assert.equal(button.disabled, true, 'blob assignment is not frame load completion');
    await f.frame.fire('load'); assert.equal(button.disabled, false);
    await button.click(); assert.deepEqual(f.prints, [f.frame.src]);
    f.document.getElementById('reportRequirement').value = '2';
    await f.document.getElementById('reportRequirement').fire('change');
    assert.equal(button.disabled, true); assert.deepEqual(f.revoked, ['blob:preview-1']);
    await f.frame.fire('load'); await button.click(); assert.equal(f.prints.length, 1);
});

test('newest preview owns loaded state even if an older response finishes last', async () => {
    const f = await reportsFixture();
    const first = f.document.getElementById('previewReport').click();
    const second = f.document.getElementById('previewReport').click();
    f.requests[1].resolve(reportResponse()); await second;
    const current = f.frame.src;
    f.requests[0].resolve(reportResponse()); await first;
    assert.equal(f.frame.src, current); await f.frame.fire('load');
    assert.equal(f.document.getElementById('printReport').disabled, false);
});

test('load event from an older frame document cannot enable current report printing', async () => {
    const f = await reportsFixture();
    const pending = f.document.getElementById('previewReport').click();
    f.requests[0].resolve(reportResponse()); await pending;
    f.frame.contentWindow.location = {href: 'about:blank'};
    await f.frame.fire('load'); assert.equal(f.document.getElementById('printReport').disabled, true);
    f.frame.contentWindow.location.href = f.frame.src;
    await f.frame.fire('load'); assert.equal(f.document.getElementById('printReport').disabled, false);
});

function decisionFixture() {
    const d = dom(), window = {};
    vm.runInNewContext(source('shared/decision-export-dialog.js'), {window, document: d.document,
        URLSearchParams, URL: d.urls, setTimeout() {}, AbortController, DOMException});
    return {...d, api: window.TaxonomyDecisionExport};
}

test('decision export stays cancellable and cancelled work cannot download after response arrives', async () => {
    const f = decisionFixture(), gate = deferred(); let signal;
    const dialog = f.api.openSaved({projectId: 1, snapshotId: 'snapshot', language: 'de', api: {
        decisionReportOptions: async () => ({roots: [{code: 'CP'}]}),
        downloadDecisionReport(...args) {signal = args[5]?.signal; return gate.promise;}
    }});
    await tick();
    const form = dialog.querySelector('form');
    const pending = form.fire('submit'); await tick();
    const cancel = dialog.querySelectorAll('button').find(n => n.textContent === 'Abbrechen');
    assert.equal(cancel.disabled, false);
    await cancel.click(); assert.equal(dialog.open, false); assert.equal(signal.aborted, true);
    gate.resolve({ok: true, redirected: false, headers: {get(k) {return {'Content-Type': 'text/html', 'X-Taxonomy-Analysis-SHA256': 'hash', 'X-Taxonomy-Snapshot-Id': 'snapshot'}[k];}}, blob: async () => ({size: 10})});
    await pending; assert.equal(f.downloads.length, 0);
});

test('Escape also aborts an in-progress decision export', async () => {
    const f = decisionFixture(), gate = deferred(); let signal;
    const dialog = f.api.open({roots: [{code: 'CP'}], submit({signal: value}) {signal = value; return gate.promise;}});
    await tick(); const pending = dialog.querySelector('form').fire('submit'); await tick();
    const event = {}; await dialog.fire('cancel', event);
    assert.equal(Boolean(event.defaultPrevented), false); assert.equal(signal.aborted, true);
    dialog.close(); gate.resolve(); await pending;
});

test('cancellation during report blob reading suppresses the actual download', async () => {
    const f = decisionFixture(), blob = deferred(), controller = new AbortController();
    const response = {ok: true, headers: {get(k) {return k === 'Content-Type' ? 'text/html' : 'hash';}}, blob: () => blob.promise};
    const pending = f.api.download(response, 'html', null, controller.signal);
    controller.abort(); blob.resolve({size: 10});
    await assert.rejects(pending, {name: 'AbortError'});
    assert.equal(f.downloads.length, 0);
});

test('German decision download validation explains invalid response, snapshot and empty report', async () => {
    for (const [type, snapshot, size, expected] of [
        ['text/plain', 'snap', 1, /kein Entscheidungsbericht/],
        ['text/html', 'other', 1, /ausgewählten Snapshot/],
        ['text/html', 'snap', 0, /Bericht ist leer/]
    ]) {
        const f = decisionFixture();
        const response = {ok: true, headers: {get(k) {return {'Content-Type': type, 'X-Taxonomy-Analysis-SHA256': 'hash', 'X-Taxonomy-Snapshot-Id': snapshot}[k];}}, blob: async () => ({size})};
        await assert.rejects(f.api.download(response, 'html', 'snap'), expected);
        assert.equal(f.downloads.length, 0);
    }
});

test('portfolio report adapter forwards cancellation to fetch', async () => {
    let seen;
    const window = {location: {pathname: '/'}}, controller = new AbortController();
    vm.runInNewContext(source('api/portfolio-api.js'), {window, URLSearchParams,
        document: {querySelector: () => null}, fetch: async (url, init) => {seen = init; return {ok: true};}});
    await window.TaxonomyPortfolioApi.downloadDecisionReport(1, 'snap', 'html', 'de', {}, {signal: controller.signal});
    assert.equal(seen.signal, controller.signal);
});

test('live-analysis export cancellation reaches the real API client transport', async () => {
    const f = dom();
    f.element('textarea', 'businessText').value = 'Current requirement';
    let transportSignal, rejectTransport;
    const timers = new Set();
    const window = {location: {href: 'http://localhost/', pathname: '/'},
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        TaxonomyI18n: {getLocale: () => 'de'},
        fetch(url, init) {
            assert.equal(url, '/api/decision-report/docx');
            assert.equal(init.method, 'POST');
            transportSignal = init.signal;
            return new Promise((resolve, reject) => {
                rejectTransport = reject;
                init.signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')), {once: true});
            });
        }};
    const context = vm.createContext({window, document: f.document, URL, URLSearchParams,
        Request, Headers, AbortController, DOMException,
        setTimeout(fn) {timers.add(fn); return fn;}, clearTimeout: timer => timers.delete(timer),
        S: {currentScores: {CP: 80}, taxonomyData: [{code: 'CP', name: 'Capabilities'}],
            lastAnalysisProvider: 'MOCK', lastAnalysisStatus: 'SUCCESS'}});
    vm.runInContext(source('api/taxonomy-api-client.js'), context);
    vm.runInContext(source('shared/decision-export-dialog.js'), context);
    const browse = source('core/taxonomy-browse.js');
    const start = browse.indexOf("if (btnId === 'exportDecisionReportDocx') {");
    const end = browse.indexOf("if (btnId === 'exportReportMd'", start);
    assert.ok(start >= 0 && end > start, 'actual live-analysis export handler is available');
    vm.runInContext(`(function () {const btnId='exportDecisionReportDocx';${browse.slice(start, end)}})();`, context);
    await tick();
    const dialog = f.document.body.querySelector('dialog');
    const pending = dialog.querySelector('form').fire('submit');
    await tick();
    assert.equal(transportSignal.aborted, false);
    await dialog.querySelectorAll('button').find(button => button.textContent === 'Abbrechen').click();
    const aborted = transportSignal.aborted;
    // Settle even the broken bridge so a red regression never leaves pending work.
    if (!aborted) rejectTransport(new DOMException('Test cleanup', 'AbortError'));
    await pending;
    assert.equal(aborted, true, 'caller abort must abort the signal passed to the real transport');
    assert.equal(f.downloads.length, 0);
    assert.equal(timers.size, 0, 'request timeout is cleaned up after cancellation');
});
