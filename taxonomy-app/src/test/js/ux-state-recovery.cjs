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
            replaceChildren(...children) {this.children = []; this.append(...children);},
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
