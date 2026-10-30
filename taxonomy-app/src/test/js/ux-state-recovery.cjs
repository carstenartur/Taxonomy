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
    const all = [], downloads = [], revoked = [], events = [];
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
            replaceChildren(...children) {this.children = []; this.innerHTML = ''; this.textContent = ''; this.append(...children);},
            appendChild(child) {this.children.push(child); child.parentElement = this; return child;},
            addEventListener(name, fn) {if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn);},
            fire(name, event = {}) {event.preventDefault ||= () => {event.defaultPrevented = true;}; event.target ||= this; event.currentTarget = this; return Promise.all((listeners.get(name) || []).map(fn => fn.call(this, event)));},
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
        dispatchEvent(event) {events.push(event);},
        fire(name, event) {return listeners.get(name)?.(event);}};
    document.body = element('body'); document.head = element('head');
    let sequence = 0;
    const urls = {createObjectURL: () => 'blob:preview-' + (++sequence), revokeObjectURL: url => revoked.push(url)};
    return {document, element, all, downloads, revoked, events, urls};
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

// These transport/cancellation fixtures use the three installed built-in formats.
// Capability parsing and real HTTP discovery have their own contract/browser tests.
function installReportCapabilities(window) {
    const reports = [
        {id:'docx', displayName:'Word', fileExtension:'docx', contentType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'},
        {id:'html', displayName:'HTML', fileExtension:'html', contentType:'text/html'},
        {id:'json', displayName:'JSON', fileExtension:'json', contentType:'application/json'}
    ];
    window.TaxonomyCapabilities = {load:async () => ({reports}), formats:items => items};
}

function decisionFixture() {
    const d = dom(), window = {};
    installReportCapabilities(window);
    const context = vm.createContext({window, document: d.document,
        URLSearchParams, URL: d.urls, setTimeout() {}, AbortController, DOMException,
        CustomEvent: class {constructor(type, init) {this.type = type; this.detail = init.detail;}}});
    vm.runInContext(source('shared/content-language.js'), context);
    vm.runInContext(source('shared/decision-export-dialog.js'), context);
    return {...d, api: window.TaxonomyDecisionExport};
}

test('decision document language follows recorded analysis or an independent explicit selection', async () => {
    for (const [recorded, choice, expected] of [
        ['en', 'analysis', 'en'], ['en', 'de', 'de'], ['en', 'ui', 'de'],
        ['de', 'en', 'en'], [null, 'analysis', null]
    ]) {
        const f = decisionFixture(); let submitted;
        const dialog = f.api.openSaved({projectId: 1, snapshotId: 'snapshot', language: 'de', api: {
            decisionReportOptions: async (project, snapshot, language) => {
                assert.equal(language, null, 'UI locale must not override recorded document language');
                return {roots: [{code: 'CP'}], analysisLanguage: recorded};
            }
        }, submit: async selection => {submitted = selection;}});
        await tick();
        const language = dialog.querySelectorAll('select').find(n => n.attributes.name === 'language');
        assert.ok(language, 'report dialog exposes the independent document language');
        language.value = choice;
        await dialog.querySelector('form').fire('submit');
        assert.equal(submitted.language, expected);
        assert.equal(submitted.options.language, undefined, 'language is a transport option, not report sections');
    }
});

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

test('new requirement writes capture AI language for automatic analysis without leaking it across origins', async () => {
    const f = dom(), requests = [];
    const window = {location: {href: 'http://localhost/app/', origin: 'http://localhost', pathname: '/app/'},
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        TaxonomyContentLanguage: {analysis: () => 'de'},
        fetch: async (url, init) => {requests.push({url, headers: new Headers(init?.headers)}); return new Response('{}');}};
    vm.runInNewContext(source('api/taxonomy-api-client.js'), {window, document: f.document, URL, Request, Response,
        Headers, AbortController, setTimeout, clearTimeout});
    await window.TaxonomyApiClient.sendJson('/api/projects/1/requirements', {text: 'Source'});
    await window.fetch('/app/api/projects/1/requirements/2/versions', {method: 'POST'});
    await window.fetch('/api/projects/1/requirements', {method: 'GET'});
    await window.fetch('https://external.example/api/projects/1/requirements', {method: 'POST'});
    await window.fetch('/api/projects/1/requirements', {method: 'POST', headers: {'X-Taxonomy-Analysis-Language': 'en'}});
    assert.deepEqual(requests.map(request => request.headers.get('X-Taxonomy-Analysis-Language')), ['de', 'de', null, null, 'en']);
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
    installReportCapabilities(window);
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

function pendingReportBody({ignoreAbort = false, snapshotId = null} = {}) {
    const body = deferred();
    let signal, reading = false, transportAborted = false;
    const headers = new Headers({
        'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'X-Taxonomy-Analysis-SHA256': 'analysis-hash',
        'Content-Disposition': 'attachment; filename="decision-report.docx"',
        ...(snapshotId ? {'X-Taxonomy-Snapshot-Id': snapshotId} : {})
    });
    return {body, reading: () => reading, aborted: () => transportAborted,
        signal: () => signal,
        fetch(url, init) {
            signal = init.signal;
            signal?.addEventListener('abort', () => {
                transportAborted = true;
                if (!ignoreAbort) body.reject(new DOMException('Body transfer cancelled', 'AbortError'));
            }, {once: true});
            return Promise.resolve({ok: true, status: 200, redirected: false, url, headers,
                blob() {reading = true; return body.promise;}});
        }
    };
}

async function liveReportBodyFixture() {
    const f = dom(), transfer = pendingReportBody(), timers = new Set();
    f.element('textarea', 'businessText').value = 'Current requirement';
    const window = {location: {href: 'http://localhost/', origin: 'http://localhost', pathname: '/'},
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        TaxonomyI18n: {getLocale: () => 'de'}, fetch: transfer.fetch};
    class FixtureURL extends URL {}
    Object.assign(FixtureURL, f.urls);
    const context = vm.createContext({window, document: f.document, URL: FixtureURL, URLSearchParams,
        Request, Headers, AbortController, DOMException,
        setTimeout(fn) {timers.add(fn); return fn;}, clearTimeout: timer => timers.delete(timer),
        S: {currentScores: {CP: 80}, taxonomyData: [{code: 'CP', name: 'Capabilities'}],
            lastAnalysisProvider: 'MOCK', lastAnalysisStatus: 'SUCCESS'}});
    vm.runInContext(source('api/taxonomy-api-client.js'), context);
    installReportCapabilities(window);
    vm.runInContext(source('shared/decision-export-dialog.js'), context);
    const browse = source('core/taxonomy-browse.js');
    const start = browse.indexOf("if (btnId === 'exportDecisionReportDocx') {");
    const end = browse.indexOf("if (btnId === 'exportReportMd'", start);
    assert.ok(start >= 0 && end > start);
    vm.runInContext(`(function () {const btnId='exportDecisionReportDocx';${browse.slice(start, end)}})();`, context);
    await tick();
    return {...f, window, transfer, timers, dialog: f.document.body.querySelector('dialog')};
}

test('live export cancellation after headers aborts the pending body transport', async () => {
    const f = await liveReportBodyFixture();
    const pending = f.dialog.querySelector('form').fire('submit');
    await tick();
    assert.equal(f.transfer.reading(), true, 'headers arrived and body consumption started');
    assert.equal(f.transfer.signal().aborted, false);
    await f.dialog.querySelectorAll('button').find(button => button.textContent === 'Abbrechen').click();
    const aborted = f.transfer.aborted();
    if (!aborted) f.transfer.body.resolve({size: 10});
    await pending;
    assert.equal(aborted, true, 'Cancel must abort the underlying body transfer after headers');
    assert.equal(f.downloads.length, 0);
    assert.equal(f.timers.size, 0, 'body completion/cancellation cleans up transport timeout');
});

async function savedRequirementExportFixture(options = {}) {
    const f = dom(), transfer = pendingReportBody({...options, snapshotId: 'snapshot-1'});
    f.document.readyState = 'loading';
    f.element('section', 'requirementCopilotCard');
    const button = f.element('button');
    button.dataset.decisionReportFormat = 'docx';
    const container = f.element('div');
    button.closest = selector => selector === '[data-decision-report-format]' ? button : container;
    f.document.querySelectorAll = selector => selector === '[data-decision-report-format]' ? [button] : [];
    const fetch = (url, init) => url.includes('/decision-report/options')
        ? Promise.resolve({ok: true, status: 200, json: async () => ({roots: [{code: 'CP', title: 'Capabilities'}]})})
        : transfer.fetch(url, init);
    const window = {location: {pathname: '/projects/1/requirements/2', search: '?lang=de&snapshot=snapshot-1'},
        TaxonomyCopilotApi: {status: async () => ({}), latest: async () => null},
        setTimeout: fn => fn()};
    f.document.currentScript = {src: 'http://localhost/js/api/portfolio-api.js'};
    class FixtureURL extends URL {}
    Object.assign(FixtureURL, f.urls);
    const context = vm.createContext({window, document: f.document, URL: FixtureURL, URLSearchParams,
        fetch, AbortController, DOMException, Headers, setTimeout: fn => fn(),
        CustomEvent: class {constructor(type, init) {this.type = type; this.detail = init.detail;}}});
    vm.runInContext(source('api/portfolio-api.js'), context);
    installReportCapabilities(window);
    vm.runInContext(source('shared/decision-export-dialog.js'), context);
    vm.runInContext(source('portfolio/requirement-copilot.js'), context);
    await f.document.fire('DOMContentLoaded');
    await f.document.fire('click', {target: button, preventDefault() {}, stopImmediatePropagation() {}});
    await tick();
    const dialog = f.document.body.querySelector('dialog');
    assert.ok(dialog, 'real delegated requirement export handler opens the dialog');
    return {...f, transfer, button, dialog};
}

test('saved requirement custom submit cancels the adapter body transfer', async () => {
    const f = await savedRequirementExportFixture();
    const pending = f.dialog.querySelector('form').fire('submit');
    await tick();
    assert.equal(f.transfer.reading(), true);
    assert.equal(f.button.disabled, true);
    await f.dialog.querySelectorAll('button').find(button => button.textContent === 'Abbrechen').click();
    const aborted = f.transfer.aborted();
    if (!aborted) f.transfer.body.resolve({size: 10});
    await pending;
    assert.equal(aborted, true, 'custom submit must forward cancellation to the real portfolio adapter');
    assert.equal(f.downloads.length, 0);
    assert.equal(f.button.disabled, false);
    assert.equal(f.events.at(-1).detail.status, 'CANCELLED');
});

test('saved requirement custom submit suppresses a body arriving after cancellation', async () => {
    const f = await savedRequirementExportFixture({ignoreAbort: true});
    const pending = f.dialog.querySelector('form').fire('submit');
    await tick();
    assert.equal(f.transfer.reading(), true);
    await f.dialog.querySelectorAll('button').find(button => button.textContent === 'Abbrechen').click();
    f.transfer.body.resolve({size: 10});
    await pending;
    assert.equal(f.downloads.length, 0, 'an already cancelled dialog must never trigger a late download');
    assert.equal(f.button.disabled, false);
    assert.equal(f.events.at(-1).detail.status, 'CANCELLED');
});

test('blob transport timeout remains active after headers and cancels the body', async () => {
    const f = await liveReportBodyFixture();
    const pending = f.window.TaxonomyApiClient.requestBlob('/api/decision-report/docx', {method: 'POST'}, {timeoutMillis: 25});
    await tick();
    assert.equal(f.transfer.reading(), true);
    assert.equal(f.timers.size, 1, 'receiving headers must not clear the body timeout');
    const rejection = assert.rejects(pending, error => error.code === 'TIMEOUT' && error.retryable === true);
    [...f.timers][0]();
    await rejection;
    assert.equal(f.transfer.aborted(), true);
    assert.equal(f.timers.size, 0);
    assert.equal(f.downloads.length, 0);
});

test('successful live export downloads the body consumed inside the transport scope', async () => {
    const f = await liveReportBodyFixture();
    const pending = f.dialog.querySelector('form').fire('submit');
    await tick();
    assert.equal(f.transfer.reading(), true);
    f.transfer.body.resolve({size: 10});
    await pending;
    assert.equal(f.downloads.length, 1);
    assert.equal(f.downloads[0].download, 'taxonomy-decision-report.docx');
    assert.equal(f.transfer.aborted(), false, 'transport listener is removed after completed body');
    assert.equal(f.dialog.open, false);
    assert.equal(f.timers.size, 1, 'only the blob URL revocation timer remains');
});

test('successful saved requirement custom submit still downloads and reports success', async () => {
    const f = await savedRequirementExportFixture();
    const pending = f.dialog.querySelector('form').fire('submit');
    await tick();
    f.transfer.body.resolve({size: 10});
    await pending;
    assert.equal(f.downloads.length, 1);
    assert.equal(f.downloads[0].download, 'decision-report.docx');
    assert.equal(f.events.at(-1).detail.status, 'SUCCESS');
    assert.equal(f.events.at(-1).detail.bytes, 10);
    assert.equal(f.button.disabled, false);
    assert.equal(f.dialog.open, false);
});

function impactFixture() {
    const f = dom(), requests = [], ready = [];
    const addListener = f.document.addEventListener;
    f.document.addEventListener = (name, listener) => name === 'DOMContentLoaded'
        ? ready.push(listener) : addListener(name, listener);
    for (const id of ['graphResultContext', 'graphResultsArea', 'graphResultsContent', 'requirementImpactResults',
        'businessText', 'graphNodeInput', 'graphMaxHops', 'graphUpstreamBtn', 'graphDownstreamBtn',
        'graphFailureBtn', 'graphEnrichedFailureBtn', 'requirementImpactBtn']) f.element('div', id);
    f.document.getElementById('graphNodeInput').value = 'CP.1';
    f.document.getElementById('graphMaxHops').value = '2';
    function request(kind, options) {const gate = deferred(); requests.push({kind, options, ...gate}); return gate.promise;}
    const window = {TaxonomyState: {currentScores: {'CP.1': 82}}, _getCurrentScores: () => ({'CP.1': 82})};
    const context = vm.createContext({window, document: f.document,
        TaxonomyI18n: {t: (key, ...args) => [key, ...args].join(' ')},
        TaxonomyUtils: {escapeHtml: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;')},
        AnalysisApi: {graphUpstream: (code, hops) => request('upstream', {code, hops}), graphDownstream: (code, hops) => request('downstream', {code, hops}),
            graphFailureImpact: (code, hops) => request('failure', {code, hops})}, fetch: request});
    for (const name of ['shared/taxonomy-graph.js', 'relations/taxonomy-relations.js', 'core/taxonomy-analysis.js']) {
        vm.runInContext(source(name), context);
    }
    ready.forEach(listener => listener());
    return {...f, window, context, requests, area: f.document.getElementById('requirementImpactResults'),
        graph: f.document.getElementById('graphResultsContent')};
}

function graphEntryFixture() {
    const f = impactFixture();
    for (const id of ['leftPanel', 'rightPanel', 'tab-analyze', 'tab-architecture', 'tab-graph', 'graphExplorerPanel']) f.element('div', id);
    f.document.getElementById('tab-graph').classList.add('d-none');
    f.window.location = {hash: '#analyze'};
    f.window.addEventListener = () => {};
    f.context.history = {replaceState: (state, title, hash) => {f.window.location.hash = hash;}};
    f.context.CustomEvent = class {constructor(type, init) {this.type = type; this.detail = init.detail;}};
    const template = readFileSync(path.join(resources, 'templates/index.html'), 'utf8');
    vm.runInContext(template.match(/<!-- Page Navigation Script -->\s*<script>([\s\S]*?)<\/script>/)[1], f.context);
    return f;
}

test('opening a graph reveals its page, focuses the selected code and starts an upstream query', () => {
    const f = graphEntryFixture();
    f.window.TaxonomyGraph.openGraphExplorer(' BP-1040 ');
    assert.equal(f.window.location.hash, '#graph');
    assert.equal(f.document.getElementById('tab-graph').classList.contains('d-none'), false);
    assert.equal(f.document.getElementById('tab-analyze').classList.contains('d-none'), true);
    assert.equal(f.document.activeElement, f.document.getElementById('graphNodeInput'));
    assert.equal(f.document.getElementById('graphNodeInput').value, 'BP-1040');
    assert.equal(f.requests.length, 1);
    assert.equal(f.requests[0].kind, 'upstream');
    assert.notEqual(f.document.getElementById('graphResultsArea').style.display, 'none');
});

test('graph entry exposes invalid input and backend failures instead of an empty hidden result', async () => {
    const f = graphEntryFixture();
    f.window.TaxonomyGraph.openGraphExplorer('  ');
    assert.equal(f.requests.length, 0);
    assert.match(f.graph.innerHTML, /role="alert".*graph.enter.code/);
    f.window.TaxonomyGraph.openGraphExplorer('CP.1');
    f.requests[0].reject(new Error('Graph service unavailable')); await tick();
    assert.match(f.graph.innerHTML, /role="alert"/);
    assert.match(f.graph.innerHTML, /Graph service unavailable/);
    assert.notEqual(f.document.getElementById('graphResultsArea').style.display, 'none');
});

test('graph entry keeps a later empty result visible when an older query finishes', async () => {
    const f = graphEntryFixture();
    f.window.TaxonomyGraph.openGraphExplorer('CP.1');
    f.window.TaxonomyGraph.openGraphExplorer('BP-1040');
    assert.equal(f.requests.length, 2);
    f.requests[1].resolve({originNodeCode: 'BP-1040', direction: 'UPSTREAM', maxHops: 2,
        neighbors: [], traversedRelationships: [], notes: []}); await tick();
    assert.match(f.graph.innerHTML, /role="status".*graph.empty/);
    assert.match(f.document.getElementById('graphResultContext').innerHTML, /BP-1040/);
    const result = f.graph.innerHTML;
    f.requests[0].resolve({originNodeCode: 'CP.1', direction: 'UPSTREAM', maxHops: 2,
        neighbors: [], traversedRelationships: [], notes: []}); await tick();
    assert.equal(f.graph.innerHTML, result);
});

const impactDto = () => ({businessText: 'Current requirement', maxHops: 2,
    impactedElements: Array.from({length: 16}, (_, index) => ({nodeCode: 'CP.' + index,
        title: 'Capability ' + index, taxonomySheet: 'Capabilities', relevance: 0.82,
        hopDistance: index ? 1 : 0, includedBecause: index ? 'SUPPORTS via <origin>' : 'Selected anchor'})),
    traversedRelationships: [{sourceCode: 'CP.0', targetCode: 'CP.1', relationType: 'SUPPORTS',
        propagatedRelevance: 0.656, hopDistance: 1}], notes: ['Primary catalogue traversal'], totalElements: 16,
    totalRelationships: 1});
const jsonResponse = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), {
    status, headers: {'Content-Type': 'application/json', ...headers}});

test('requirement impact renders the real ImpactElement DTO with codes, titles and traversal evidence', async () => {
    const f = impactFixture();
    f.window.TaxonomyRelations.runRequirementImpact();
    f.requests[0].resolve(jsonResponse(impactDto())); await tick();
    assert.equal((f.area.innerHTML.match(/class="graph-element-row"/g) || []).length, 16);
    for (let index = 0; index < 16; index++) {
        assert.match(f.area.innerHTML, new RegExp('data-code="CP\\.' + index + '"'));
        assert.ok(f.area.innerHTML.includes('Capability ' + index));
    }
    assert.match(f.area.innerHTML, /82%/);
    assert.match(f.area.innerHTML, /graph\.badge\.hop 1/);
    assert.match(f.area.innerHTML, /SUPPORTS via &lt;origin>/);
    assert.match(f.area.innerHTML, /Primary catalogue traversal/);
    assert.doesNotMatch(f.area.innerHTML, /NaN|<td[^>]*><\/td>/);
    assert.match(f.document.getElementById('graphResultContext').innerHTML, /graph\.result\.requirement/);
    assert.match(f.document.getElementById('graphResultContext').innerHTML, /graph\.result\.catalogue\.scope/);
});

test('switching requirement and node or enriched impact clears prior results and ignores late success and failure', async () => {
    for (const buttonId of ['graphUpstreamBtn', 'graphDownstreamBtn', 'graphFailureBtn', 'graphEnrichedFailureBtn']) {
        for (const lateFailure of [false, true]) {
            const f = impactFixture();
            f.window.TaxonomyRelations.runRequirementImpact();
            await f.document.getElementById(buttonId).click();
            assert.equal(f.area.style.display, 'none');
            assert.equal(f.area.innerHTML, '');
            assert.match(f.document.getElementById('graphResultContext').innerHTML, /graph\.result\.node CP\.1/);
            if (lateFailure) f.requests[0].reject(new Error('stale requirement failure'));
            else f.requests[0].resolve(jsonResponse(impactDto()));
            await tick();
            assert.equal(f.area.innerHTML, '');
            f.window.TaxonomyRelations.runRequirementImpact();
            assert.equal(f.document.getElementById('graphResultsArea').style.display, 'none');
            assert.equal(f.graph.innerHTML, '');
            if (lateFailure) f.requests[1].reject(new Error('stale node failure'));
            else f.requests[1].resolve(buttonId === 'graphEnrichedFailureBtn'
                ? jsonResponse({failedNodeCode: 'CP.1', riskScore: 0, totalAffected: 0})
                : {originNodeCode: 'CP.1', failedNodeCode: 'CP.1', neighbors: [], totalAffected: 0});
            f.requests[2].resolve(jsonResponse(impactDto())); await tick();
            assert.equal(f.graph.innerHTML, '');
            assert.match(f.area.innerHTML, /Capability 15/);
        }
    }
});

test('requirement impact reads current browser scores and clears node output even when no scores exist', () => {
    const f = impactFixture();
    f.window.TaxonomyState.currentScores = {};
    f.graph.innerHTML = 'previous node result';
    f.window.TaxonomyRelations.runRequirementImpact();
    assert.equal(f.graph.innerHTML, '');
    assert.equal(f.requests.length, 0);
    assert.match(f.area.innerHTML, /relations\.impact\.no\.scores/);
});

function projectionFixture() {
    const f = dom(), requests = [], timers = new Set();
    const table = f.element('div', 'relationsTableContainer');
    f.element('select', 'relationsTypeFilter');
    f.document.querySelector = selector => selector === 'meta[name="_csrf"]' ? {content: 'csrf-fixture'}
        : selector === 'meta[name="_csrf_header"]' ? {content: 'X-CSRF-TOKEN'} : null;
    const window = {location: {href: 'https://taxonomy.test/', origin: 'https://taxonomy.test', pathname: '/'},
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        __TaxonomyAnalysisSessionContext: {runtime: {workspaceId: 'workspace-A'}},
        fetch(url, options = {}) {const gate = deferred(); requests.push({url, options, ...gate}); return gate.promise;}};
    const context = vm.createContext({window, document: f.document, URL, URLSearchParams, Request, Response,
        Headers, AbortController, DOMException, CustomEvent: class {constructor(type, options) {this.type = type; this.detail = options?.detail;}},
        TaxonomyI18n: {t: (key, ...args) => [key, ...args].join(' ')}, TaxonomyUtils: {escapeHtml: String},
        fetch: (...args) => window.fetch(...args), setTimeout(fn) {timers.add(fn); return fn;}, clearTimeout: timer => timers.delete(timer)});
    for (const name of ['api/taxonomy-api-client.js', 'core/taxonomy-analysis-session-api-routing.js',
        'api/relations-api.js', 'relations/taxonomy-relations.js']) vm.runInContext(source(name), context);
    return {...f, window, requests, table, timers, text: () => {
        function collect(node) {return node.textContent + node.innerHTML + node.children.map(collect).join(' ');}
        return collect(table);
    }, button: () => table.querySelectorAll('button').find(button => button.textContent === 'relations.projection.rebuild')};
}
const projectionStatus = overrides => ({repositoryId: 'repository-A', workspaceId: 'workspace-A', branch: 'draft',
    operationStatus: 'INSPECTED', readinessState: 'NOT_BUILT', currentHeadCommit: 'a'.repeat(40),
    projectedCommit: null, relationCount: 0, pendingRecoveries: [], ...overrides});
const readinessResponse = status => jsonResponse(status, 200, {ETag: '"' + status.currentHeadCommit + '"'});
async function unavailableProjection(f, status = projectionStatus()) {
    f.window.TaxonomyRelations.loadRelations(); await tick();
    f.requests[0].resolve(new Response(null, {status: 409, headers: {'X-Taxonomy-Relation-Projection-State': status.readinessState}}));
    await tick();
    assert.equal(f.requests.length, 2, 'an unavailable projection must inspect readiness');
    f.requests[1].resolve(readinessResponse(status)); await tick();
    assert.ok(f.button(), 'a verified missing/stale projection offers recovery');
}

test('projection recovery uses current head, pinned context and canonical CSRF transport with one POST', async () => {
    const f = projectionFixture(); await unavailableProjection(f);
    assert.match(f.text(), /relations\.projection\.not\.built/);
    assert.match(f.text(), /repository-A.*workspace-A.*draft/);
    const button = f.button(); const first = button.click(), second = button.click(); await tick();
    assert.equal(f.requests.length, 3, 'duplicate clicks share the readiness/rebuild flight');
    const fresh = projectionStatus({readinessState: 'STALE', currentHeadCommit: 'b'.repeat(40)});
    f.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'workspace-B';
    f.requests[2].resolve(readinessResponse(fresh)); await tick();
    const rebuild = f.requests[3];
    assert.match(rebuild.url, /\/projection\/rebuild\?workspaceId=workspace-A$/);
    assert.equal(rebuild.options.headers.get('If-Match'), '"' + fresh.currentHeadCommit + '"');
    assert.equal(rebuild.options.headers.get('X-Taxonomy-Workspace-Id'), 'workspace-A');
    assert.equal(rebuild.options.headers.get('X-CSRF-TOKEN'), 'csrf-fixture');
    assert.equal(rebuild.options.credentials, 'same-origin');
    assert.equal(rebuild.options.method, 'POST');
    assert.equal(rebuild.options.body, undefined);
    rebuild.resolve(readinessResponse({...fresh, operationStatus: 'REBUILT', readinessState: 'READY'})); await tick();
    f.requests[4].resolve(jsonResponse([{sourceCode: 'CP.1', targetCode: 'BP.1', relationType: 'SUPPORTS', id: 1}]));
    await Promise.all([first, second]); await tick();
    assert.equal(f.requests.filter(request => request.options.method === 'POST').length, 1);
    assert.match(f.table.innerHTML, /CP\.1/);
    assert.doesNotMatch(f.table.innerHTML, /relations\.none/);
    assert.equal(f.timers.size, 0);
});

test('changed projection context prevents recovery and a moved Git head remains an error, never an empty table', async () => {
    for (const changedContext of [true, false]) {
        const f = projectionFixture(); await unavailableProjection(f);
        const click = f.button().click(); await tick();
        f.requests[2].resolve(readinessResponse(projectionStatus(changedContext ? {branch: 'other-branch'} : {})));
        await tick();
        if (!changedContext) f.requests[3].resolve(new Response(null, {status: 412}));
        await click; await tick();
        assert.equal(f.requests.filter(request => request.options.method === 'POST').length, changedContext ? 0 : 1);
        assert.match(f.text(), /relations\.projection\.changed/);
        assert.doesNotMatch(f.text(), /relations\.none/);
        assert.equal(f.table.getAttribute('aria-busy'), 'false');
    }
});

test('unavailable readiness, forbidden rebuild and pending verification stay distinct from empty relations', async () => {
    for (const state of ['readiness-forbidden', 'rebuild-forbidden', 'rebuild-pending']) {
        const f = projectionFixture();
        if (state === 'readiness-forbidden') {
            f.window.TaxonomyRelations.loadRelations(); await tick();
            f.requests[0].resolve(new Response(null, {status: 409, headers: {'X-Taxonomy-Relation-Projection-State': 'NOT_BUILT'}})); await tick();
            assert.equal(f.requests.length, 2);
            f.requests[1].resolve(new Response(null, {status: 403})); await tick();
            assert.match(f.text(), /relations\.projection\.check\.failed/);
            assert.equal(f.button(), undefined);
        } else {
            await unavailableProjection(f);
            const click = f.button().click(); await tick();
            f.requests[2].resolve(readinessResponse(projectionStatus())); await tick();
            f.requests[3].resolve(state === 'rebuild-forbidden' ? new Response(null, {status: 403})
                : jsonResponse(projectionStatus({operationStatus: 'RECOVERY_RECONCILIATION_PENDING'}), 202));
            await click; await tick();
            assert.match(f.text(), state === 'rebuild-forbidden' ? /relations\.projection\.rebuild\.failed/ : /relations\.projection\.pending/);
            assert.equal(f.requests.length, 4, 'failed or pending recovery must not render an unchecked relation count');
        }
        assert.doesNotMatch(f.text(), /relations\.none/);
        assert.equal(f.table.getAttribute('aria-busy'), 'false');
    }
});

test('relation refresh keeps the newest valid empty list and rejects malformed success without showing emptiness', async () => {
    for (const oldFails of [false, true]) {
        const f = projectionFixture();
        const older = f.window.TaxonomyRelations.loadRelations();
        const newer = f.window.TaxonomyRelations.loadRelations(); await tick();
        f.requests[1].resolve(jsonResponse([])); await newer;
        f.requests[0].resolve(oldFails
            ? new Response(null, {status: 409, headers: {'X-Taxonomy-Relation-Projection-State': 'NOT_BUILT'}})
            : jsonResponse([{sourceCode: 'STALE', targetCode: 'BP.1', relationType: 'SUPPORTS', id: 1}]));
        await older;
        assert.match(f.text(), /relations\.none/);
        assert.doesNotMatch(f.text(), /STALE|relations\.load\.failed/);
        assert.equal(f.requests.length, 2);
    }
    const f = projectionFixture(), invalid = f.window.TaxonomyRelations.loadRelations(); await tick();
    f.requests[0].resolve(jsonResponse(null)); await invalid;
    assert.match(f.text(), /relations\.load\.failed/);
    assert.doesNotMatch(f.text(), /relations\.none/);
});

test('READY disagreement allows one automatic relation refresh per user load and then requires manual Retry', async () => {
    const f = projectionFixture();
    let loading = f.window.TaxonomyRelations.loadRelations();
    for (let attempt = 0; attempt < 2; attempt++) {
        const offset = attempt * 4;
        await tick();
        for (let step = 0; step < 4; step++) {
            assert.equal(f.requests.length, offset + step + 1);
            f.requests[offset + step].resolve(step % 2
                ? readinessResponse(projectionStatus({readinessState: 'READY'}))
                : new Response(null, {status: 409, headers: {'X-Taxonomy-Relation-Projection-State': 'STALE'}}));
            await tick();
        }
        assert.equal(f.requests.length, offset + 4, 'persistent disagreement must not start a third list request');
        await loading;
        assert.equal(f.table.getAttribute('aria-busy'), 'false');
        assert.match(f.text(), /relations\.projection\.unavailable/);
        assert.doesNotMatch(f.text(), /relations\.none/);
        assert.equal(f.button(), undefined, 'READY does not offer a rebuild');
        const retry = f.table.querySelector('button');
        assert.ok(retry && !retry.disabled, 'manual status refresh remains available');
        if (attempt === 0) loading = retry.click();
    }
});


test('taxonomy selection updates the visible graph without navigating or stealing keyboard focus', async () => {
    const f = graphEntryFixture();
    f.window.TaxonomyGraph.openGraphExplorer('CP.1');
    f.document.getElementById('graphMaxHops').value = '3';
    await f.document.getElementById('graphDownstreamBtn').fire('click');
    const diagram = f.element('svg'); diagram.focus();
    assert.equal(typeof f.window.TaxonomyGraph.selectNode, 'function');
    f.window.TaxonomyGraph.selectNode(' BP-1040 ');
    assert.equal(f.document.getElementById('graphNodeInput').value, 'BP-1040');
    assert.equal(f.document.activeElement, diagram);
    assert.equal(f.requests.at(-1).kind, 'downstream');
    assert.deepEqual({...f.requests.at(-1).options}, {code:'BP-1040',hops:3});
    assert.match(f.document.getElementById('graphResultContext').innerHTML, /BP-1040/);
    const count = f.requests.length;
    f.window.TaxonomyGraph.selectNode('BP-1040');
    assert.equal(f.requests.length, count, 'Repeated selection must not duplicate a query');
    f.requests.at(-1).resolve({originNodeCode:'BP-1040',neighbors:[],traversedRelationships:[],notes:[]}); await tick();
    const rendered = f.graph.innerHTML;
    f.requests[0].resolve({originNodeCode:'CP.1',neighbors:[],traversedRelationships:[],notes:[]}); await tick();
    assert.equal(f.graph.innerHTML, rendered, 'An old result must not replace the selected node');
});

test('selection while graph is hidden defers its query until graph activation', () => {
    const f = graphEntryFixture();
    assert.equal(typeof f.window.TaxonomyGraph.selectNode, 'function');
    f.window.TaxonomyGraph.selectNode('UA-1000');
    assert.equal(f.requests.length, 0);
    assert.equal(f.window.location.hash, '#analyze');
    assert.equal(f.document.getElementById('graphNodeInput').value, 'UA-1000');
    f.document.getElementById('tab-graph').classList.remove('d-none');
    f.document.fire('taxonomy:page-activated', {detail:{page:'graph'}});
    assert.equal(f.requests.length, 1);
    assert.deepEqual({...f.requests[0].options}, {code:'UA-1000',hops:2});
    f.document.fire('taxonomy:page-activated', {detail:{page:'graph'}});
    assert.equal(f.requests.length, 1);
});

test('graph selection follows the active result rather than unsent input and retries after invalidation', ()=>{
    const f=graphEntryFixture();
    f.window.TaxonomyGraph.openGraphExplorer('CP.1');
    f.document.getElementById('graphNodeInput').value='BP-1040';
    f.window.TaxonomyGraph.selectNode('BP-1040');
    assert.equal(f.requests.at(-1).options.code,'BP-1040');
    f.document.fire('taxonomy:analysis-invalidated');
    const count=f.requests.length;
    f.window.TaxonomyGraph.selectNode('BP-1040');
    assert.equal(f.requests.length,count+1);
    assert.notEqual(f.document.getElementById('graphResultsArea').style.display,'none');
});

test('linked graph selection retains enriched failure mode and its hop depth', async()=>{
    const f=graphEntryFixture();
    f.document.getElementById('tab-graph').classList.remove('d-none');
    f.document.getElementById('graphMaxHops').value='3';
    await f.document.getElementById('graphEnrichedFailureBtn').fire('click');
    assert.match(f.requests.at(-1).kind,/CP.1\/enriched-failure-impact\?maxHops=3/);
    f.window.TaxonomyGraph.selectNode('BP-1040');
    assert.match(f.requests.at(-1).kind,/BP-1040\/enriched-failure-impact\?maxHops=3/);
});

function guidanceFixture(search = '') {
    const f=dom(), calls=[];
    for(const id of ['analysisNextSteps','analysisNextStepText','businessText','analysisReformulationHint',
        'analysisReformulationAction','projectReformulationAction','analysisLensAction','architectureLensAction',
        'analysisClarifyAction','analysisReanalyzeAction','analysisGuideError','statusArea','relationSearchReportPanel',
        'architectureNextStepsAction','analysisNextStepsHeading','analyzeBtn']) f.element('div',id);
    f.document.getElementById('businessText').value='Arbeitsschutz';
    const state={lastAnalyzedText:'Arbeitsschutz',lastAnalysisStatus:'PARTIAL',
        analysisRecovery:{state:'COMPLETED_WITH_GAPS',openQuestions:[{key:'CI'}]}};
    const window={TaxonomyState:state, location:{search,assign:url=>calls.push(['navigate',url])},
        TaxonomyI18n:{t:key=>key,resolveUrl:path=>'/taxonomy'+path},
        TaxonomyAnalysisSession:{state:()=>({ready:true}),saveNow:async()=>true,addAsRequirement:options=>calls.push(['save-requirement',options])},
        TaxonomyAnalysisRecovery:{open:()=>calls.push(['clarify'])}};
    f.document.getElementById('analyzeBtn').addEventListener('click',()=>calls.push(['analyze']));
    vm.runInNewContext(source('core/taxonomy-analysis-guidance.js'),{window,document:f.document,URLSearchParams,
        MutationObserver:class{observe(){}}});
    return {...f,window,state,calls};
}

test('partial analysis guides clarification and explicit project promotion with a scoped lens link', async()=>{
    const f=guidanceFixture('?workspaceScopeKey=private-alice&branch=feature');
    assert.equal(f.document.getElementById('analysisNextStepText').textContent,'analysis.guide.partial');
    assert.equal(f.document.getElementById('analysisLensAction').href,'/taxonomy/architecture/editor?workspaceScopeKey=private-alice&branch=feature#editorLensTitle');
    await f.document.getElementById('analysisClarifyAction').fire('click');
    await f.document.getElementById('analysisReformulationAction').fire('click');
    assert.equal(JSON.stringify(f.calls),JSON.stringify([['clarify'],['save-requirement',{preserveDraft:true}]]));
    assert.equal(f.document.getElementById('businessText').value,'Arbeitsschutz');
    assert.equal(f.document.getElementById('analysisReformulationHint').textContent,'analysis.guide.unsaved');
});

test('saved requirement navigation waits for successful draft saving and preserves exact source selection', async()=>{
    const f=guidanceFixture('?projectId=7&requirementId=9&snapshot=snapshot-4&branch=review');
    const button=f.document.getElementById('projectReformulationAction');
    assert.equal(button.href,'/taxonomy/projects/7/requirements/9?branch=review&snapshot=snapshot-4#reformulationOffers');
    f.window.TaxonomyAnalysisSession.saveNow=async()=>false;
    await button.fire('click');await tick();
    assert.deepEqual(f.calls,[]);
    assert.equal(f.document.getElementById('analysisGuideError').hidden,false);
    f.window.TaxonomyAnalysisSession.saveNow=async()=>true;
    await button.fire('click');await tick();
    assert.deepEqual(f.calls,[['navigate','/taxonomy/projects/7/requirements/9?branch=review&snapshot=snapshot-4#reformulationOffers']]);
});

test('lens pins the live analysis workspace even when the URL has no pin or an obsolete pin', ()=>{
    for (const search of ['', '?workspaceScopeKey=obsolete&branch=review']) {
        const f=guidanceFixture(search);
        f.window.TaxonomyAnalysisSession.state=()=>({ready:true,workspaceId:'workspace-a'});
        f.document.fire('taxonomy:view-rendered');
        const target=new URL(f.document.getElementById('analysisLensAction').href,'https://example.test');
        assert.equal(target.searchParams.get('workspaceScopeKey'),'workspace-a');
        if(search) assert.equal(target.searchParams.get('branch'),'review');
        f.window.TaxonomyAnalysisSession.state=()=>({ready:true,workspaceId:null});
        f.document.fire('taxonomy:view-rendered');
        assert.match(f.document.getElementById('analysisLensAction').href,/workspaceScopeKey=__shared__/);
    }
});

test('stale, running and unresolved workspace states disable unsafe reformulation without hiding its entry', async()=>{
    for(const condition of ['stale','running','unavailable']){
        const f=guidanceFixture('?projectId=7&requirementId=9');
        if(condition==='stale') f.document.getElementById('businessText').value='New wording';
        if(condition==='running') f.state.lastAnalysisStatus='IN_PROGRESS';
        if(condition==='unavailable') f.window.TaxonomyAnalysisSession.state=()=>({ready:false});
        f.document.fire('taxonomy:view-rendered');
        const button=f.document.getElementById('analysisReformulationAction');
        assert.equal(button.getAttribute('aria-disabled'),'true');
        assert.equal(button.hidden,false);
        await button.fire('click');
        assert.deepEqual(f.calls,[]);
        assert.equal(f.document.getElementById('analysisNextStepText').textContent,'analysis.guide.'+condition);
        if(condition==='stale'){
            await f.document.getElementById('analysisReanalyzeAction').fire('click');
            assert.deepEqual(f.calls,[['analyze']]);
        }
    }
});

test('partial imported evidence without recovery opens its retained report', async()=>{
    const f=guidanceFixture();f.state.analysisRecovery=null;
    await f.document.getElementById('analysisClarifyAction').fire('click');
    assert.equal(f.document.activeElement,f.document.getElementById('relationSearchReportPanel'));
    assert.deepEqual(f.calls,[]);
    assert.equal(f.document.getElementById('analysisNextStepText').textContent,'analysis.guide.report');
});

test('shared lens navigation requires an empty evidence-free draft and needs no nonexistent draft endpoint',async()=>{
    const f=guidanceFixture();
    f.window.TaxonomyAnalysisSession.state=()=>({ready:true,workspaceId:null});
    let meaningful=true;
    f.window.__TaxonomyAnalysisSessionContext={currentPayload:()=>({}),meaningful:()=>meaningful};
    f.window.TaxonomyAnalysisSession.saveNow=async()=>{throw Error('No shared draft endpoint');};
    f.document.fire('taxonomy:view-rendered');
    const button=f.document.getElementById('analysisLensAction');
    assert.equal(button.getAttribute('aria-disabled'),'true');
    assert.equal(f.document.getElementById('analysisNextStepText').textContent,'analysis.guide.workspace');
    await button.fire('click');assert.deepEqual(f.calls,[]);
    meaningful=false;f.document.getElementById('businessText').value='';f.state.lastAnalyzedText=null;
    f.document.fire('taxonomy:view-rendered');
    await button.fire('click');await tick();
    assert.deepEqual(f.calls,[['navigate','/taxonomy/architecture/editor?workspaceScopeKey=__shared__#editorLensTitle']]);
});

test('editor transport pins shared and private contexts independently of another tab',async()=>{
    const calls=[];
    const window={TaxonomyApiClient:{getJson:async url=>calls.push(url)}};
    vm.runInNewContext(source('api/architecture-editor-api.js'),{window,URLSearchParams});
    for(const scope of ['__shared__','workspace-a']){
        await window.ArchitectureEditorApi.load({workspaceScopeKey:scope});
        const url=new URL(calls.at(-1),'https://example.test');
        assert.equal(url.searchParams.has('workspaceId'),true);
        assert.equal(url.searchParams.get('workspaceId'),scope==='__shared__'?'':scope);
    }
});

test('explicit cancellation releases guidance actions and records a partial result', ()=>{
    const f=guidanceFixture();
    f.state.lastAnalysisStatus='IN_PROGRESS';
    f.element('span','analyzeSpinner');
    const runtime={activeAnalysisControllers:new Set([{}])};
    const context={S:f.state,runtime,language:()=> 'en',text:key=>key,
        showActionAlert(){},queueSave(){},cancelDerivedRequests(){
            runtime.activeAnalysisControllers.clear();
            f.document.getElementById('analyzeSpinner').classList.add('d-none');
        }};
    f.window.__TaxonomyAnalysisSessionContext=context;
    vm.runInNewContext(source('core/taxonomy-analysis-session-ui.js'),{window:f.window,document:f.document,
        CustomEvent:class{constructor(type){this.type=type;}}});
    const originalDispatch=f.document.dispatchEvent;
    f.document.dispatchEvent=event=>{originalDispatch(event);f.document.fire(event.type,event);};
    context.cancelCurrentAnalysis();
    assert.equal(f.state.lastAnalysisStatus,'CANCELLED');
    assert.equal(f.document.getElementById('analysisLensAction').getAttribute('aria-disabled'),'false');
    assert.equal(f.document.getElementById('analysisNextStepText').textContent,'analysis.guide.partial');
});

test('reformulation promotion preserves draft evidence and stops before creation when saving fails', async()=>{
    for(const projects of [[{id:7,title:'Safety'}],[]]) for(const saved of [true,false]){
        const f=dom(),calls=[],reviews=[],mutations=[];
        f.document.readyState='loading';
        const input=f.element('textarea','businessText');input.value='Arbeitsschutz';
        const evidence={scores:{CI:80},businessText:input.value,sourceDocument:{id:14,originalText:'Source evidence'}};
        const context={S:{},runtime:{},text:key=>key,language:()=> 'en',businessTextElement:()=>input,
            currentPayload:()=>evidence,comparable:value=>JSON.stringify(value),meaningful:()=>true,
            saveDraft:async()=>{calls.push('save');return saved;},
            resetDraft:async()=>{calls.push('reset');},invalidate:()=>calls.push('invalidate'),showActionAlert(){},
            jsonRequest:async(path,options)=>{
                if(options.method==='GET')return path==='/api/projects'?projects:[];
                calls.push(path);throw Error('Promotion must use the atomic lifecycle command');
            }};
        const attached=new WeakMap();
        const window={__TaxonomyAnalysisSessionContext:context,location:{assign:url=>calls.push(url)},
            TaxonomyI18n:{resolveUrl:path=>'/taxonomy'+path},
            TaxonomyRequirementIntake:{
                attach(form,options){attached.set(form,options);},
                async prepare(form,payload,projectId){
                    assert.equal(attached.get(form).textElement.value,'Arbeitsschutz');
                    assert.equal(payload.text,'Arbeitsschutz');
                    reviews.push({projectId,payload:JSON.parse(JSON.stringify(payload))});
                    return {choice:'NEW',projectId:projectId===null?null:Number(projectId),requirementId:null,
                        expectedVersionId:null,expectedContentHash:null,
                        inputHash:require('node:crypto').createHash('sha256').update(payload.text).digest('hex'),requirement:payload};
                },
                isCurrent(){return true;}
            },
            TaxonomyArchitectureLifecycle:{async saveRequirement(command){
                calls.push('/api/architecture/editor/requirements');mutations.push(JSON.parse(JSON.stringify(command)));
                return {projectId:7,requirementId:9,requirement:{id:9,projectId:7,currentVersion:{id:1,text:command.requirement.text}},
                    publication:{state:'CURRENT',context:{repositoryId:'fixture-architecture',workspaceScopeKey:'fixture-copy',branch:'draft',revision:2}}};
            }}};
        vm.runInNewContext(source('core/taxonomy-analysis-session-projects.js'),{window,document:f.document,URLSearchParams});
        window.TaxonomyAnalysisSession.addAsRequirement({preserveDraft:true});await tick();
        const form=f.all.find(node=>node.tagName==='form');form.reportValidity=()=>true;
        const select=f.all.find(node=>node.tagName==='select');if(select)select.value='7';
        await form.fire('submit');await tick();await tick();
        assert.equal(reviews.length,1);
        if(saved){
            assert.deepEqual(calls,['save','/api/architecture/editor/requirements','/taxonomy/projects/7/requirements/9']);
            assert.equal(mutations.length,1);
            assert.equal(mutations[0].choice,'NEW');
            assert.equal(mutations[0].requirement.text,'Arbeitsschutz');
            assert.equal(mutations[0].projectId,projects.length?7:null);
            assert.equal(Boolean(mutations[0].newProject),projects.length===0);
            if(!projects.length){assert.equal(mutations[0].newProject.title,'Arbeitsschutz');assert.equal(mutations[0].requirement.requirementKey,'REQ-001');}
        }else{assert.deepEqual(calls,['save']);assert.deepEqual(mutations,[]);}
        assert.equal(calls.includes('reset'),false,'preserveDraft retains the acknowledged source draft');
        assert.equal(calls.includes('invalidate'),false);
        assert.equal(input.value,'Arbeitsschutz');
        assert.deepEqual(evidence,{scores:{CI:80},businessText:'Arbeitsschutz',sourceDocument:{id:14,originalText:'Source evidence'}});
    }
});
