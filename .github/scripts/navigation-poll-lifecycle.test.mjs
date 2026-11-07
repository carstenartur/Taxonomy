import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const resources = new URL('../../taxonomy-app/src/main/resources/', import.meta.url);
const index = readFileSync(new URL('templates/index.html', resources), 'utf8');
const statusBar = index.indexOf('<div id="systemStatusBar"');
assert.ok(statusBar >= 0);
const statusStart = index.indexOf('<script>', statusBar) + '<script>'.length;
const statusEnd = index.indexOf('</script>', statusStart);
assert.ok(statusStart >= '<script>'.length && statusEnd > statusStart);
const workspaceApi = readFileSync(new URL('static/js/api/workspace-api.js', resources), 'utf8');
const sources = {
    system: index.slice(statusStart, statusEnd),
    sync: readFileSync(new URL('static/js/workspace/taxonomy-workspace-sync.js', resources), 'utf8'),
    browse: readFileSync(new URL('static/js/core/taxonomy-browse.js', resources), 'utf8')
};
const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}

// Execute the complete production scripts, controlling only their external DOM,
// clock and HTTP boundaries. Reads deliberately may ignore abort, as an already
// accepted response/body can arrive after pagehide or a restored page's new read.
function harness(kind, { translationsPending = false } = {}) {
    const dom = new EventTarget(), lifecycle = new EventTarget(), translations = deferred();
    const nodes = new Map(), reads = [], timers = new Map();
    let nextTimer = 0;
    class Element extends EventTarget {
        constructor() {
            super(); this.textContent = ''; this.innerHTML = ''; this.children = [];
            this.dataset = {}; this.style = {}; this.disabled = false;
            const classes = new Set();
            this.classList = {
                contains: name => classes.has(name), add: name => classes.add(name),
                remove: name => classes.delete(name),
                toggle(name, force) {
                    const enabled = force === undefined ? !classes.has(name) : force;
                    if (enabled) classes.add(name); else classes.delete(name);
                    return enabled;
                }
            };
        }
        appendChild(child) { this.children.push(child); child.parentElement = this; if (child.id) nodes.set(child.id, child); }
        remove() {
            if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(child => child !== this);
            if (nodes.get(this.id) === this) nodes.delete(this.id);
        }
    }
    for (const id of ['systemStatusBar', 'gitStatusBar', 'syncStatePanel', 'analyzeBtn',
        'startupBanner', 'startupBannerStatus', 'taxonomyTree', 'expandAll', 'collapseAll']) {
        const node = new Element(); node.id = id; nodes.set(id, node);
    }
    nodes.get('systemStatusBar').textContent = 'retained system status';
    nodes.get('syncStatePanel').innerHTML = 'retained sync status';
    nodes.get('taxonomyTree').innerHTML = 'retained tree';
    nodes.get('startupBanner').classList.add('d-none');
    const document = {
        addEventListener: dom.addEventListener.bind(dom),
        dispatchEvent: dom.dispatchEvent.bind(dom), getElementById: id => nodes.get(id) || null,
        querySelectorAll: () => [], createElement: () => new Element(), body: new Element()
    };
    const schedule = (callback, delay, repeat) => {
        const id = ++nextTimer; timers.set(id, { callback, delay, repeat }); return id;
    };
    const clock = {
        setTimeout: (fn, delay) => schedule(fn, delay, false), clearTimeout: id => timers.delete(id),
        setInterval: (fn, delay) => schedule(fn, delay, true), clearInterval: id => timers.delete(id)
    };
    const state = { taxonomyData: ['retained catalogue'], currentView: 'list' };
    const window = { ...clock, TaxonomyState: state,
        TaxonomyRoleSurface: { ready: Promise.resolve({ administrator: false }), isAdministrator: () => false },
        addEventListener: lifecycle.addEventListener.bind(lifecycle)
    };
    const fetch = (url, init = {}) => {
        const response = deferred();
        const read = { url, init, reject: response.reject,
            answer(body, status = 200) { response.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) }); },
            bodyPending(status = 200) {
                const body = deferred();
                response.resolve({ ok: status >= 200 && status < 300, status, json: () => body.promise });
                return body;
            }
        };
        reads.push(read); return response.promise;
    };
    window.TaxonomyApiClient = {request: (url, init, policy) => fetch(url, {...init, signal: policy.signal})
        .then(response => { if (!response.ok) throw new Error('HTTP ' + response.status); return response; })};
    const scope = { window, document, fetch, ...clock, AbortController, Headers,
        TaxonomyI18n: { t: key => key, ready: () => translations.promise },
        TaxonomyUtils: { escapeHtml: String }, console };
    vm.runInNewContext(workspaceApi, scope, {filename: 'workspace-api.js'});
    vm.runInNewContext(sources[kind], scope, { filename: `production-${kind}-poll.js` });
    if (!translationsPending) translations.resolve();
    return { window, nodes, reads, state, timers, translations,
        start() { dom.dispatchEvent(new Event('DOMContentLoaded')); },
        hide() { lifecycle.dispatchEvent(Object.assign(new Event('pagehide'), { persisted: true })); },
        show(persisted = true) { lifecycle.dispatchEvent(Object.assign(new Event('pageshow'), { persisted })); },
        requests(url) { return reads.filter(read => read.url === url); },
        tick(delay) {
            for (const [id, timer] of [...timers]) if (timer.delay === delay && timers.has(id)) {
                if (!timer.repeat) timers.delete(id);
                timer.callback();
            }
        },
        timerCount(delay) { return [...timers.values()].filter(timer => timer.delay === delay).length; }
    };
}

for (const [kind, endpoint, nodeId, field, result] of [
    ['system', '/api/status/startup', 'systemStatusBar', 'textContent', { initialized: true, memory: { heapUsagePercent: 20 } }],
    ['sync', '/api/workspace/sync-state', 'syncStatePanel', 'innerHTML', { syncStatus: 'AHEAD', unpublishedCommitCount: 3 }]
]) {
    test(`${kind} polling cannot render a pending response after pagehide`, async () => {
        const h = harness(kind); h.start(); await settle();
        const read = h.requests(endpoint).at(-1), retained = h.nodes.get(nodeId)[field];
        const body = read.bodyPending(); await settle();
        h.hide(); body.resolve(result); await settle();
        assert.equal(h.nodes.get(nodeId)[field], retained, 'A departed page must retain its last accepted presentation');
        assert.equal(read.init.signal?.aborted, true, 'The actual pending HTTP read must be cancelled');
        assert.equal(h.timerCount(15000), 0);
        const before = h.requests(endpoint).length;
        if (kind === 'sync') h.window.TaxonomyWorkspaceSync.refresh();
        h.tick(15000); await settle();
        assert.equal(h.requests(endpoint).length, before, 'No background read after departure');
    });

    test(`${kind} polling resumes once from BFCache and rejects the previous page generation`, async () => {
        const h = harness(kind); h.start(); await settle();
        const old = h.requests(endpoint).at(-1), oldBody = old.bodyPending(); await settle();
        const beforeRestore = h.requests(endpoint).length;
        h.hide(); h.show(); await settle();
        const current = h.requests(endpoint).at(-1);
        assert.notEqual(current, old, 'BFCache return must read current server state');
        assert.equal(h.requests(endpoint).length, beforeRestore + 1, 'Restore starts exactly one fresh read');
        current.answer(result); await settle();
        const accepted = h.nodes.get(nodeId)[field];
        oldBody.resolve(kind === 'system' ? { phaseMessage: 'obsolete system status' } : { syncStatus: 'BEHIND' });
        await settle();
        assert.equal(h.nodes.get(nodeId)[field], accepted, 'An old response cannot overwrite the restored page');
        assert.equal(h.timerCount(15000), 1);
        const beforeDuplicateShow = h.requests(endpoint).length;
        h.show(false); h.show(); await settle();
        assert.equal(h.timerCount(15000), 1, 'pageshow cannot duplicate polling');
        assert.equal(h.requests(endpoint).length, beforeDuplicateShow);
        const count = h.requests(endpoint).length; h.tick(15000); await settle();
        assert.equal(h.requests(endpoint).length, count + 1, 'Normal periodic polling remains active');
    });

    test(`${kind} polling still refreshes after an ordinary network error`, async () => {
        const h = harness(kind); h.start(); await settle();
        h.requests(endpoint).at(-1).reject(new TypeError('network unavailable')); await settle();
        h.tick(15000); await settle(); h.requests(endpoint).at(-1).answer(result); await settle();
        assert.notEqual(h.nodes.get(nodeId)[field], kind === 'system' ? 'retained system status' : 'retained sync status');
    });
}

for (const restoreBeforeTranslation of [false, true]) test(`status translations ready after pagehide cannot start an obsolete read (restored=${restoreBeforeTranslation})`, async () => {
    const h = harness('system', { translationsPending: true }); h.start();
    h.hide();
    if (restoreBeforeTranslation) h.show();
    h.translations.resolve(); await settle();
    assert.equal(h.requests('/api/status/startup').length, restoreBeforeTranslation ? 1 : 0);
    if (!restoreBeforeTranslation) { h.show(); await settle(); }
    assert.equal(h.requests('/api/status/startup').length, 1);
});

async function loadingTaxonomy() {
    const h = harness('browse'); h.start();
    h.requests('/api/taxonomy')[0].answer(null, 503); await settle();
    assert.equal(h.timerCount(5000), 1, 'An unavailable catalogue must schedule startup retry');
    return h;
}

test('catalogue startup cancels a scheduled retry when the page is hidden', async () => {
    const h = await loadingTaxonomy(); h.hide(); h.tick(5000); await settle();
    assert.equal(h.requests('/api/status/startup').length, 0);
    assert.equal(h.timerCount(5000), 0);
});

test('catalogue startup ignores a pending loading response and cannot rearm after pagehide', async () => {
    const h = await loadingTaxonomy(); h.tick(5000);
    const read = h.requests('/api/status/startup')[0], retained = h.nodes.get('taxonomyTree').innerHTML;
    h.hide(); read.answer({ initialized: false, phaseMessage: 'obsolete loading phase' }); await settle();
    assert.equal(h.nodes.get('taxonomyTree').innerHTML, retained);
    assert.equal(read.init.signal?.aborted, true);
    assert.equal(h.timerCount(5000), 0);
});

test('catalogue startup cannot launch a ready-tree reload after pagehide', async () => {
    const h = await loadingTaxonomy(); h.tick(5000);
    h.hide(); h.requests('/api/status/startup')[0].answer({ initialized: true }); await settle();
    assert.equal(h.requests('/api/taxonomy').length, 1);
});

test('a rejected pre-navigation startup read cannot rearm a retry after BFCache return', async () => {
    const h = await loadingTaxonomy(); h.tick(5000);
    const obsolete = h.requests('/api/status/startup')[0];
    h.hide(); h.show(); await settle();
    h.requests('/api/taxonomy').at(-1).answer([]); await settle();
    obsolete.reject(new TypeError('departed request failed')); await settle();
    assert.equal(h.timerCount(5000), 0);
    assert.equal(h.nodes.get('startupBanner').classList.contains('d-none'), true);
});

test('a taxonomy body pending at pagehide cannot replace the accepted catalogue', async () => {
    const h = harness('browse'); h.start();
    const read = h.requests('/api/taxonomy')[0], body = read.bodyPending(); await settle();
    const retained = h.state.taxonomyData; h.hide(); body.resolve([]); await settle();
    assert.equal(h.state.taxonomyData, retained);
    assert.equal(read.init.signal?.aborted, true);
});

test('an ordinary taxonomy load failure still displays its error and enables analysis', async () => {
    const h = harness('browse'); h.start();
    h.requests('/api/taxonomy')[0].reject(new TypeError('catalogue unavailable')); await settle();
    assert.match(h.nodes.get('taxonomyTree').innerHTML, /alert-danger/);
    assert.match(h.nodes.get('taxonomyTree').innerHTML, /browse.load.failed/);
    assert.equal(h.nodes.get('startupBanner').classList.contains('d-none'), true);
    assert.equal(h.nodes.get('analyzeBtn').disabled, false);
    assert.equal(h.timerCount(5000), 0);
});

test('catalogue startup resumes after BFCache and retains its network retry until ready', async () => {
    const h = await loadingTaxonomy(); h.tick(5000);
    const old = h.requests('/api/status/startup')[0]; h.hide(); h.show(); await settle();
    const restored = h.requests('/api/taxonomy').at(-1);
    assert.equal(h.requests('/api/taxonomy').length, 2, 'An incomplete catalogue is rechecked on return');
    restored.answer(null, 503); await settle();
    old.answer({ initialized: true }); await settle();
    assert.equal(h.requests('/api/taxonomy').length, 2, 'A pre-navigation status cannot trigger another reload');
    h.tick(5000); h.requests('/api/status/startup').at(-1).reject(new TypeError('network unavailable')); await settle();
    assert.equal(h.timerCount(5000), 1);
    h.tick(5000); h.requests('/api/status/startup').at(-1).answer({ initialized: true }); await settle();
    h.requests('/api/taxonomy').at(-1).answer([]); await settle();
    assert.equal(h.nodes.get('startupBanner').classList.contains('d-none'), true);
    assert.equal(h.nodes.get('analyzeBtn').disabled, false);
    assert.equal(h.timerCount(5000), 0);
    const count = h.requests('/api/taxonomy').length;
    h.hide(); h.show(); await settle();
    assert.equal(h.requests('/api/taxonomy').length, count, 'An already loaded retained catalogue is not discarded or fetched again');
});
