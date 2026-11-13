import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import canonicalFixture from './canonical-api-fixture.cjs';

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
    browse: readFileSync(new URL('static/js/core/taxonomy-browse.js', resources), 'utf8'),
    git: readFileSync(new URL('static/js/workspace/taxonomy-git-status.js', resources), 'utf8')
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
function harness(kind, { translationsPending = false, proposals = false, forms = false } = {}) {
    const dom = new EventTarget(), lifecycle = new EventTarget(), translations = deferred();
    const nodes = new Map(), reads = [], timers = new Map(), errors = [];
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
        'startupBanner', 'startupBannerStatus', 'taxonomyTree', 'expandAll', 'collapseAll',
        ...(forms ? ['testLlmConnection', 'proposeRelationsSubmit', 'proposeSpinner', 'proposeRelationType'] : []),
        ...(proposals ? ['proposalsTableContainer', 'proposalPendingBadge'] : [])]) {
        const node = new Element(); node.id = id; nodes.set(id, node);
    }
    nodes.get('systemStatusBar').textContent = 'retained system status';
    nodes.get('syncStatePanel').innerHTML = 'retained sync status';
    nodes.get('taxonomyTree').innerHTML = 'retained tree';
    if (proposals) nodes.get('proposalPendingBadge').textContent = 'retained proposal count';
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
    const state = { taxonomyData: ['retained catalogue'], currentView: 'list', currentProposalFilter: 'PENDING' };
    const window = { ...clock, TaxonomyState: state, location: {href: 'http://taxonomy.test/'},
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
    window.fetch = fetch;
    window.TaxonomyVersioningApi = {state: (branch, {signal}) => fetch('http://taxonomy.test/api/git/state?branch=' + encodeURIComponent(branch), {signal})};
    const scope = { window, document, fetch, ...clock, AbortController, Headers,
        TaxonomyI18n: { t: (key, ...values) => [key, ...values].join(' '), ready: () => translations.promise },
        TaxonomyUtils: { escapeHtml: String }, console: { ...console, error: (...values) => errors.push(values) } };
    canonicalFixture.install(scope, ['api/analysis-api.js', 'api/workspace-api.js', 'api/reference-api.js', 'api/search-api.js', 'api/proposals-api.js']);
    vm.runInNewContext(sources[kind], scope, { filename: `production-${kind}-poll.js` });
    if (!translationsPending) translations.resolve();
    return { window, nodes, reads, state, timers, translations, errors,
        sourceChanged() { dom.dispatchEvent(new Event('taxonomy:entity-context-changed')); },
        start() { dom.dispatchEvent(new Event('DOMContentLoaded')); },
        hide() { lifecycle.dispatchEvent(Object.assign(new Event('pagehide'), { persisted: true })); },
        beforeUnload() { lifecycle.dispatchEvent(new Event('beforeunload')); },
        show(persisted = true) { lifecycle.dispatchEvent(Object.assign(new Event('pageshow'), { persisted })); },
        requests(url) { return reads.filter(read => new URL(read.url, window.location.href).pathname === url); },
        tick(delay) {
            for (const [id, timer] of [...timers]) if (timer.delay === delay && timers.has(id)) {
                if (!timer.repeat) timers.delete(id);
                timer.callback();
            }
        },
        timerCount(delay) { return [...timers.values()].filter(timer => timer.delay === delay).length; }
    };
}

test('Git polling stops at beforeunload, before a departing WebKit document can schedule its next fetch', async () => {
    const h = harness('git'); h.start(); await settle();
    const old = h.requests('/api/git/state')[0];
    assert.ok(old, 'The real Git poll must have started');
    assert.equal(h.timerCount(10000), 1);
    h.beforeUnload();
    assert.equal(old.init.signal.aborted, true, 'The active request is cancelled before pagehide');
    assert.equal(h.timerCount(10000), 0, 'No periodic request may be scheduled while navigating');
    const count = h.requests('/api/git/state').length;
    h.tick(10000); h.window.TaxonomyGitStatus.refresh(); await settle();
    assert.equal(h.requests('/api/git/state').length, count);
    h.hide();
    assert.equal(h.timerCount(10000), 0);
});

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

// A proposal list is now the *only* authoritative request. The badge consumes
// that same guarded response, so there is deliberately no second /pending read.
async function proposalRead() {
    const h = harness('browse', { proposals: true }); h.start(); await settle();
    const reads = h.requests('/api/proposals/pending');
    assert.equal(reads.length, 1, 'Startup must fetch the pending list exactly once');
    return { h, read: reads[0] };
}

function proposalPresentation(h) {
    return {
        table: h.nodes.get('proposalsTableContainer').innerHTML,
        badge: h.nodes.get('proposalPendingBadge').textContent
    };
}

test('one accepted proposal list updates table and pending badge without a second request', async () => {
    const { h, read } = await proposalRead();
    read.answer([{ id: 17, status: 'PENDING', sourceCode: 'A', targetCode: 'B',
        relationType: 'DEPENDS_ON', confidence: 0.9 },
    { id: 18, status: 'PENDING', sourceCode: 'C', targetCode: 'D',
        relationType: 'DEPENDS_ON', confidence: 0.8 }]);
    await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 1,
        'Rendering the badge must not issue a second, navigation-sensitive request');
    assert.match(h.nodes.get('proposalsTableContainer').innerHTML, /proposal-table/);
    assert.equal(h.nodes.get('proposalPendingBadge').textContent, 'browse.proposals.pending 2');
    assert.deepEqual(h.errors, []);
});

test('proposal response body cannot update either presentation or start another request after pagehide', async () => {
    const { h, read } = await proposalRead();
    const body = read.bodyPending(); await settle();
    const retained = proposalPresentation(h), count = h.reads.length;
    h.hide(); body.resolve([{ id: 77, status: 'PENDING' }]); await settle();
    assert.equal(h.reads.length, count, 'A departed response must not initiate a follow-up request');
    assert.deepEqual(proposalPresentation(h), retained, 'Neither table nor badge may update after departure');
    assert.equal(read.init.signal?.aborted, true, 'Page departure must cancel the actual proposal read');
    assert.deepEqual(h.errors, []);
});

test('proposal body rejection after pagehide cannot report an obsolete error', async () => {
    const { h, read } = await proposalRead();
    const body = read.bodyPending(); await settle();
    const retained = proposalPresentation(h);
    h.hide(); body.reject(new TypeError('controlled body rejection after departure')); await settle();
    assert.deepEqual(h.errors, [], 'Only the current page may report a proposal failure');
    assert.deepEqual(proposalPresentation(h), retained);
});

test('switching proposal source clears displayed rows and count before accepting the new inbox', async () => {
    const h = harness('browse', { proposals: true });
    let selected = {repositoryId:'alpha', workspaceId:'A1', branch:'draft'};
    h.window.TaxonomyArchitectureWorkspace = { ready: async () => true };
    h.window.TaxonomyArchitectureWorkflow = { getScope: () => selected };
    h.start();
    await settle();
    const path = '/api/proposals/pending';
    const original = h.requests(path)[0];
    original.answer([{id:37, status:'PENDING', sourceCode:'alpha-node', targetCode:'target', relationType:'DEPENDS_ON'}]);
    await settle();
    assert.match(proposalPresentation(h).table, /alpha-node/);
    h.sourceChanged();
    await settle();
    assert.equal(h.requests(path).length, 1, 'unchanged selection must not fetch the inbox again');
    let undoRemoved = false;
    h.nodes.set('undoToast', {remove() { undoRemoved = true; }});
    selected = {repositoryId:'beta', workspaceId:'', branch:'main'};
    h.sourceChanged();
    assert.equal(undoRemoved, true, 'undo for the prior source is no longer actionable');
    assert.doesNotMatch(proposalPresentation(h).table, /alpha-node/);
    assert.equal(proposalPresentation(h).badge, '');
    await settle();
    const pending = h.requests(path)[1];
    assert.equal(new URL(pending.url, h.window.location.href).searchParams.get('repositoryId'), 'beta');
    selected = {repositoryId:'gamma', workspaceId:'G1', branch:'draft'};
    h.sourceChanged();
    await settle();
    assert.equal(pending.init.signal.aborted, true);
    pending.answer([{id:7,status:'PENDING',sourceCode:'beta-node'}]);
    h.requests(path)[2].answer([]);
    await settle();
    assert.match(proposalPresentation(h).table, /browse.proposals.none/);
    assert.equal(proposalPresentation(h).badge, 'browse.proposals.pending 0');
    assert.deepEqual(h.errors, []);
});

test('a missing selected repository clears the previous proposal count without sending a read', async () => {
    const h = harness('browse', { proposals: true });
    h.window.TaxonomyArchitectureWorkspace = { ready: async () => true };
    h.window.TaxonomyArchitectureWorkflow = { getScope: () => ({}) };
    h.start();
    await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 0);
    assert.equal(h.nodes.get('proposalPendingBadge').textContent, '');
    assert.match(h.nodes.get('proposalsTableContainer').innerHTML, /role="status"/);
    assert.match(h.nodes.get('proposalsTableContainer').innerHTML, /browse.proposals.repository.required/);
    assert.deepEqual(h.errors, []);
});

for (const failure of ['http', 'json', 'network']) {
    test(`an active authoritative proposal ${failure} failure remains visible`, async () => {
        const { h, read } = await proposalRead();
        const count = h.requests('/api/proposals/pending').length;
        if (failure === 'http') read.answer(null, 503);
        else if (failure === 'network') read.reject(new TypeError('network unavailable'));
        else {
            const body = read.bodyPending(); await settle();
            body.reject(new SyntaxError('invalid proposal JSON'));
        }
        await settle();
        assert.equal(h.errors.length, 1, 'Active errors must still fail the clean-console gate');
        assert.equal(h.errors[0][0], '[Taxonomy] Failed to load proposals');
        const reported = h.errors[0][1];
        if (failure === 'http') {
            assert.match(reported.message, /HTTP 503/);
            assert.equal(reported.status, 503);
        } else {
            assert.equal(reported.message, 'Network request failed');
            assert.equal(reported.code, 'NETWORK_ERROR');
            assert.equal(reported.cause.name, failure === 'network' ? 'TypeError' : 'SyntaxError');
            assert.equal(reported.cause.message, failure === 'network' ? 'network unavailable' : 'invalid proposal JSON');
        }
        assert.equal(h.requests('/api/proposals/pending').length, count, 'Failure must not re-request the list');
        assert.match(h.nodes.get('proposalsTableContainer').innerHTML, /text-danger/);
    });
}

test('BFCache restores exactly one proposal list read and ignores the previous response body', async () => {
    const { h, read } = await proposalRead();
    const oldBody = read.bodyPending(); await settle();
    h.hide(); h.show(); await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 2, 'Restore must start one new authoritative list read');
    const current = h.requests('/api/proposals/pending').at(-1);
    current.answer([{ id: 33, status: 'PENDING', sourceCode: 'A',
        targetCode: 'B', relationType: 'DEPENDS_ON' }]);
    await settle();
    assert.equal(h.nodes.get('proposalPendingBadge').textContent, 'browse.proposals.pending 1');
    const accepted = proposalPresentation(h);
    oldBody.resolve([]); await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 2, 'Old generation must not initiate another read');
    assert.deepEqual(proposalPresentation(h), accepted, 'Old body cannot replace the restored table or badge');
    h.show(false); h.show(); await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 2, 'Repeated pageshow must not duplicate reads');
    assert.deepEqual(h.errors, []);
});

test('a pre-navigation rejected proposal body cannot spoil a successful restored page', async () => {
    const { h, read } = await proposalRead();
    const oldBody = read.bodyPending(); await settle();
    h.hide(); h.show(); await settle();
    assert.equal(h.requests('/api/proposals/pending').length, 2);
    h.requests('/api/proposals/pending').at(-1).answer([]); await settle();
    const accepted = proposalPresentation(h);
    assert.equal(accepted.badge, 'browse.proposals.pending 0');
    oldBody.reject(new SyntaxError('obsolete rejected body')); await settle();
    assert.deepEqual(proposalPresentation(h), accepted);
    assert.deepEqual(h.errors, []);
});

test('an invalidated connection test releases its busy button', async () => {
    const h = harness('browse', {forms:true});
    h.start(); await settle();
    const button = h.nodes.get('testLlmConnection');
    button.dispatchEvent(new Event('click'));
    assert.equal(button.disabled, true);
    h.window.__TaxonomyAnalysisSessionContext = {runtime: {workspaceId: 'fresh'}};
    h.reads.at(-1).answer({provider:'obsolete'});
    await settle();
    assert.equal(button.disabled, false);
    assert.equal(button.textContent, 'browse.test.btn');
});

test('proposal cleanup respects the newer request and the currently allowed role', async () => {
    const h = harness('browse', {forms:true});
    h.start(); await settle();
    h.window.TaxonomyRoleSurface.canMutateArchitecture = () => true;
    h.state.pendingProposalNodeCode = 'BP';
    h.nodes.get('proposeRelationType').value = 'DEPENDS_ON';
    const button = h.nodes.get('proposeRelationsSubmit'), spinner = h.nodes.get('proposeSpinner');
    button.dispatchEvent(new Event('click'));
    const older = h.reads.at(-1);
    assert.equal(button.disabled, true);
    h.window.__TaxonomyAnalysisSessionContext = {runtime: {workspaceId: 'fresh'}};
    button.dispatchEvent(new Event('click'));
    const newer = h.reads.at(-1);
    assert.notEqual(older, newer);
    older.answer([]); await settle();
    assert.equal(spinner.classList.contains('d-none'), false);
    assert.equal(button.disabled, true);
    h.window.TaxonomyRoleSurface.canMutateArchitecture = () => false;
    h.window.__TaxonomyAnalysisSessionContext.runtime.workspaceId = 'third';
    newer.answer([]); await settle();
    assert.equal(spinner.classList.contains('d-none'), true);
    assert.equal(button.disabled, true);
});
