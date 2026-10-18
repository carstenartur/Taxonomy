import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-progress.js', import.meta.url), 'utf8');
const clientSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/taxonomy-api-client.js', import.meta.url), 'utf8');
const apiSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/analysis-session-api.js', import.meta.url), 'utf8');
const routingSource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-api-routing.js', import.meta.url), 'utf8');
const id = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
function fixture(fetcher, workspaceId = 'workspace-a', withView = false) {
    const scope = { workspaceId, generation: 1, analysisGeneration: 1, invalidating: false };
    const timers = new Map(), calls = [], snapshots = [], unavailable = [], streams = [], results = [];
    let serial = 0;
    const authFailures = [];
    const schedule = (fn, delay) => { const key = ++serial; timers.set(key, { fn, delay }); return key; };
    const unschedule = key => timers.delete(key);
    const elements = new Map(), listeners = new Map();
    class Element {
        constructor(tag) { this.tag = tag; this.children = []; this.ownText = ''; this.attributes = {}; this.isConnected = true; }
        set id(value) { this.identifier = value; elements.set(value, this); }
        get id() { return this.identifier; }
        set textContent(value) { this.ownText = String(value); this.children = []; }
        get textContent() { return this.ownText + this.children.map(child => child.textContent).join(' '); }
        append(...children) { this.children.push(...children); }
        setAttribute(key, value) { this.attributes[key] = String(value); }
        getAttribute(key) { return Object.hasOwn(this.attributes, key) ? this.attributes[key] : null; }
        querySelector(selector) { return descendants(this).find(child => child.tag === selector) || null; }
        focus() { document.activeElement = this; }
        addEventListener(name, listener) { (this.listeners ||= new Map()).set(name, listener); }
        insertAdjacentElement() {}
        replaceChildren(...children) { this.children = children; }
        remove() { this.isConnected = false; elements.delete(this.id); }
    }
    if (withView) for (const name of ['statusArea', 'llmCommLogContent']) {
        const node = new Element('div'); node.id = name;
    }
    const document = {
        documentElement: { lang: 'en' },
        createElement: tag => new Element(tag),
        addEventListener(name, listener) {
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(listener);
        }, getElementById(id) { return elements.get(id) || null; },
        querySelector(selector) {
            return selector === 'meta[name="_csrf"]' ? { content: 'test-token' }
                : selector === 'meta[name="_csrf_header"]' ? { content: 'X-CSRF-TOKEN' } : null;
        },
        dispatchEvent(event) {
            authFailures.push(event.detail);
            for (const listener of listeners.get(event.type) || []) listener(event);
        }
    };
    const window = {
        EventSource: class {
            constructor(url) { this.url = url; this.listeners = new Map(); this.readyState = 0; streams.push(this); }
            addEventListener(name, listener) { this.listeners.set(name, listener); }
            close() { this.closed = true; this.readyState = 2; }
            async emit(name, value, eventId = value?.sequence) {
                this.listeners.get(name)?.({ data: JSON.stringify(value), lastEventId: String(eventId ?? '') });
                await new Promise(resolve => setImmediate(resolve));
            }
        },
        setTimeout: schedule, clearTimeout: unschedule,
        location: { href: 'https://taxonomy.example/', origin: 'https://taxonomy.example' },
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        __TaxonomyAnalysisSessionContext: { runtime: scope },
        fetch: async (url, options) => {
            calls.push({ url, options: { ...options, headers: Object.fromEntries(options.headers) } });
            return fetcher(url, options);
        }
    };
    const sandbox = vm.createContext({ window, document, AbortController, console,
        URL, Request, Headers, Response, CustomEvent, setTimeout: schedule, clearTimeout: unschedule });
    vm.runInContext(clientSource, sandbox);
    vm.runInContext(apiSource, sandbox);
    vm.runInContext(routingSource, sandbox);
    vm.runInContext(source, sandbox);
    const monitor = withView ? window.TaxonomyAnalysisProgress.start(id, value => snapshots.push({ value }))
        : window.TaxonomyAnalysisProgress.createMonitor({
        id, context: () => ({ ...scope }), api: window.TaxonomyAnalysisSessionApi,
        setTimeout: schedule, clearTimeout: unschedule,
        onSnapshot: (value, changed) => snapshots.push({ value, changed }),
        onUnavailable: message => unavailable.push(message),
        onResult: value => results.push(value)
    });
    async function step(delay) {
        const entry = [...timers].find(([, item]) => item.delay === delay);
        assert.ok(entry, `Expected timer ${delay}; got ${[...timers.values()].map(v => v.delay)}`);
        timers.delete(entry[0]);
        await entry[1].fn();
        await new Promise(resolve => setImmediate(resolve));
    }
    return { window, document, scope, timers, calls, snapshots, unavailable, authFailures, elements, streams, results, api: window.TaxonomyAnalysisSessionApi, monitor, step };
}
function descendants(element) { return element.children.flatMap(child => [child, ...descendants(child)]); }
function workBar(fixture, kind, root = '') {
    return descendants(fixture.elements.get('analysisWorkProgress')).find(element => element.tag === 'progress'
        && element.getAttribute('data-progress-kind') === kind
        && (element.getAttribute('data-taxonomy-root') || '') === root);
}
function taxonomyRow(fixture, root) {
    return descendants(fixture.elements.get('analysisWorkProgress')).find(element => element.children.some(child =>
        child.tag === 'progress' && child.getAttribute('data-progress-kind') === 'nodes'
        && child.getAttribute('data-taxonomy-root') === root));
}
function response(data, status = 200) {
    return new Response(status === 202 ? null : JSON.stringify(data), { status });
}
function snapshot(sequence = 1, status = 'RUNNING') {
    return { operationId: id, sequence, status, phase: 'LLM_REQUEST', calls: [{ id: 1, status: 'STARTED' }],
        rawScores: { CP: 80 }, memory: { percent: 81, warning: true } };
}

function clusterSnapshot(sequence = 1, status = 'RUNNING') {
    return { ...snapshot(sequence, status), transport: 'artemis', evaluatedNodes: 0, calls: [],
        memory: null, databaseStorage: 'EXTERNAL_OR_UNKNOWN', indexStorage: 'EXTERNAL_OR_UNKNOWN',
        startedAt: 1000, lastActivityAt: 2000, serverTime: 2000, elapsedMillis: 1000,
        executionStartedAt: 1000, queueWaitMillis: 0, executionMillis: 1000,
        scope: { workspaceId: 'workspace-a', repositoryId: 'repo-a', branch: 'draft', sourceCommit: 'commit-a' },
        cluster: { completedRoots: 1, totalRoots: 2, tasks: [
            { taskType: 'SUBTAXONOMY_ANALYSIS', root: { code: 'CP' }, queued: 0, running: 1, completed: 0, failed: 0 },
            { taskType: 'RELATION_ANALYSIS', root: { code: 'IP' }, queued: 3, running: 0, completed: 0, failed: 0 }
        ] } };
}

test('cluster registration switches to durable SSE without recurring status reads', async () => {
    const f = fixture(async () => response(clusterSnapshot(5)));
    await f.step(0);
    assert.equal(f.streams.length, 1);
    const target = new URL(f.streams[0].url, f.window.location.href);
    assert.equal(target.pathname, `/api/analysis-runs/${id}/events`);
    assert.equal(target.searchParams.get('workspaceId'), 'workspace-a');
    assert.equal(target.searchParams.get('afterSequence'), '5');
    assert.equal(f.timers.size, 0, 'live SSE must replace the status polling timer');
    await f.streams[0].emit('progress', clusterSnapshot(6));
    assert.deepEqual(f.snapshots.map(item => item.value.sequence), [5, 6]);
    assert.equal(f.calls.length, 1);
    f.monitor.stop();
    assert.equal(f.streams[0].closed, true);
});

test('event and recovered-result URLs keep the original workspace and external application base path', async () => {
    const f = fixture(async () => response({ status: 'SUCCESS' }));
    f.window.TaxonomyI18n = { getBasePath: () => '/taxonomy',
        resolveUrl: url => url.startsWith('/taxonomy/') ? url : '/taxonomy' + url };
    f.window.__TaxonomyAnalysisSessionContext.installWorkspaceEventSourceRouting();
    f.scope.workspaceId = 'workspace-b';
    const stream = f.api.openRunEvents(id, { workspaceId: 'workspace-a', afterSequence: 7 });
    const target = new URL(stream.url, f.window.location.href);
    assert.equal(target.pathname, `/taxonomy/api/analysis-runs/${id}/events`);
    assert.equal(target.searchParams.get('workspaceId'), 'workspace-a');
    assert.equal(target.searchParams.get('afterSequence'), '7');
    await f.api.getRunResult(id, { workspaceId: 'workspace-a' });
    assert.equal(new URL(f.calls[0].url, f.window.location.href).searchParams.get('workspaceId'), 'workspace-a');
    assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    const central = f.api.openRunEvents(id, { workspaceId: null, afterSequence: 0 });
    assert.equal(new URL(central.url, f.window.location.href).searchParams.get('workspaceId'), '');
    f.monitor.stop();
});

test('cluster replay rejects duplicate reverse foreign and changed-authority events before rendering', async () => {
    const f = fixture(async () => response(clusterSnapshot(5)));
    await f.step(0);
    const stream = f.streams[0];
    assert.ok(stream, 'cluster observation must open a stream');
    for (const value of [clusterSnapshot(5), clusterSnapshot(4),
        { ...clusterSnapshot(6), operationId: 'foreign-operation' },
        ...['workspaceId', 'repositoryId', 'branch', 'sourceCommit'].map(field => ({
            ...clusterSnapshot(6), scope: { ...clusterSnapshot(6).scope, [field]: 'foreign' }
        }))]) await stream.emit('progress', value);
    await stream.emit('progress', clusterSnapshot(6), 8);
    assert.deepEqual(f.snapshots.map(item => item.value.sequence), [5]);
    await stream.emit('snapshot', clusterSnapshot(7));
    assert.deepEqual(f.snapshots.map(item => item.value.sequence), [5, 7]);
    f.monitor.stop();
});

test('EventSource reconnect uses durable replay and never falls back to periodic polling', async () => {
    const f = fixture(async () => response(clusterSnapshot(5)));
    await f.step(0);
    const stream = f.streams[0];
    assert.ok(stream, 'cluster observation must open a stream');
    await stream.emit('error');
    assert.deepEqual(f.unavailable, ['CONNECTION_LOST']);
    assert.equal(f.timers.size, 0);
    assert.equal(stream.closed, undefined, 'native EventSource must retain its Last-Event-ID reconnect');
    await stream.emit('open');
    await stream.emit('snapshot', clusterSnapshot(8));
    assert.deepEqual(f.snapshots.map(item => item.value.sequence), [5, 8]);
    assert.equal(f.calls.length, 1);
    assert.equal(f.streams.length, 1);
    f.monitor.stop();
});

for (const field of ['workspaceId', 'generation', 'invalidating']) {
    test(`cluster ${field} invalidation closes SSE without applying late progress`, async () => {
        const f = fixture(async () => response(clusterSnapshot(5)));
        await f.step(0);
        const stream = f.streams[0];
        assert.ok(stream, 'cluster observation must open a stream');
        f.scope[field] = field === 'invalidating' ? true : field === 'generation' ? 2 : 'workspace-b';
        await stream.emit('progress', clusterSnapshot(6));
        assert.equal(stream.closed, true);
        assert.deepEqual(f.snapshots.map(item => item.value.sequence), [5]);
        f.monitor.stop();
    });
}

test('cluster cancellation stays scoped and ends on its durable terminal event', async () => {
    const f = fixture(async (url, options) => response(clusterSnapshot(options.method === 'POST' ? 6 : 5,
        options.method === 'POST' ? 'CANCELLED' : 'RUNNING')));
    await f.step(0);
    await f.monitor.cancel();
    await f.monitor.cancel();
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.equal(f.calls[1].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    assert.ok(f.streams[0], 'cluster observation must open a stream');
    await f.streams[0].emit('progress', clusterSnapshot(6, 'CANCELLED'));
    assert.equal(f.streams[0].closed, true);
    assert.equal(f.timers.size, 0);
    assert.equal(f.snapshots.at(-1).value.status, 'CANCELLED');
});

test('departure from an observed cluster run sends one scoped cancellation without polling', async () => {
    const f = fixture(async () => response(clusterSnapshot(5)));
    await f.step(0);
    f.scope.workspaceId = 'workspace-b';
    f.monitor.cancelAndStop();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.calls.length, 2);
    assert.equal(f.calls[1].options.method, 'POST');
    assert.match(f.calls[1].url, /\/cancel\?workspaceId=workspace-a$/);
    assert.equal(f.timers.size, 0, 'cluster cleanup must not restart periodic status reads');
    assert.equal(f.streams[0].closed, true);
});

test('reopened completed cluster run retrieves one persisted result without starting analysis', async () => {
    const f = fixture(async url => response(url.includes('/result')
        ? { status: 'SUCCESS', rawScores: { CP: 80, IP: 65 } } : clusterSnapshot(10, 'COMPLETED')));
    await f.step(0);
    assert.equal(f.results.length, 1);
    assert.equal(f.results[0].rawScores.IP, 65);
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    assert.match(f.calls[1].url, /\/result\?workspaceId=workspace-a$/);
    assert.equal(f.streams.length, 0);
    assert.equal(f.timers.size, 0);
});

test('late persisted result cannot overwrite a changed workspace', async () => {
    let finishResult;
    const f = fixture(async url => url.includes('/result')
        ? new Promise(resolve => { finishResult = resolve; }) : response(clusterSnapshot(10, 'COMPLETED')));
    const pending = f.step(0);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(typeof finishResult, 'function', 'terminal observation must retrieve the persisted result');
    f.scope.workspaceId = 'workspace-b';
    finishResult(response({ status: 'SUCCESS', rawScores: { IP: 65 } }));
    await pending;
    assert.equal(f.results.length, 0);
});

test('persisted result retrieval can be explicitly retried after a lost response without starting new work', async () => {
    let resultReads = 0;
    const f = fixture(async url => {
        if (!url.includes('/result')) return response(clusterSnapshot(10, 'COMPLETED'));
        if (++resultReads === 1) throw new Error('connection lost');
        return response({ status: 'SUCCESS', rawScores: { CP: 80 } });
    });
    await f.step(0);
    assert.deepEqual(f.unavailable, ['RESULT_UNAVAILABLE']);
    assert.equal(f.timers.size, 0);
    assert.equal((await f.monitor.recoverResult()).status, 'SUCCESS');
    assert.equal(f.results.length, 1);
    assert.equal(f.calls.length, 3);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
});

test('cluster progress view shows known root totals and queued/running work by task family and root', async () => {
    const f = fixture(async () => response(clusterSnapshot(5)), 'workspace-a', true);
    await f.step(0);
    const text = f.elements.get('analysisWorkProgress').textContent;
    assert.match(text, /1 of 2/);
    assert.match(text, /CP.*1.*running/);
    assert.match(text, /IP.*3.*queued/);
    assert.match(text, /Relationship/);
    assert.doesNotMatch(f.elements.get('analysisLiveProgress').textContent, /0 nodes evaluated/);
    f.monitor.stop();
});

test('shared relation preparation remains visible before target-shard work is known', async () => {
    const value = clusterSnapshot(5);
    value.cluster.tasks = [{ taskType: 'RELATION_ANALYSIS', root: null, queued: 0, running: 1, completed: 0, failed: 0 }];
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /Relationship search.*Preparation.*1.*running/);
    f.monitor.stop();
});

test('cluster finalization stays observable and cancellable until the enriched result is ready', async () => {
    const value = { ...clusterSnapshot(7), phase: 'FINALIZING' };
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    const panel = f.elements.get('analysisLiveProgress');
    assert.match(panel.textContent, /Finalizing analysis/);
    assert.equal(panel.children.find(child => child.tag === 'button').disabled, false);
    assert.equal(f.streams.length, 1);
    f.monitor.stop();
});

test('shows fixed node totals and distinguishes direct assessments from pruned descendants', async () => {
    const f = fixture(async () => response({ ...snapshot(), nodeProgress: {
        total: 120, assessed: 28, excluded: 45, open: 47,
        taxonomies: [{ root: 'BP', nameEn: 'Business processes', nameDe: 'Geschäftsprozesse',
            total: 120, assessed: 28, excluded: 45, open: 47 }]
    } }), 'workspace-a', true);
    await f.step(0);
    const text = f.elements.get('analysisLiveProgress').textContent;
    assert.match(text, /73 of 120/);
    assert.match(text, /28.*assessed.*45.*excluded.*47.*open/);
    assert.match(text, /Business processes/);
    f.monitor.stop();
});

test('preserves provider wait reasons while showing relation work and its separate budget', async () => {
    const f = fixture(async () => response({ ...snapshot(), relationProgress: {
        totalSources: 40, assessedSources: 32, totalSearches: 80, completedSearches: 28,
        unresolvedSearches: 4, pendingSearches: 48, calls: 24, maxCalls: 24, verifiedRelations: 12,
        step: 'NAVIGATE', current: { sourceId: 'BP-1', targetRoot: 'IP', type: 'CONSUMES',
            direction: 'OUTGOING', depth: 3, candidates: ['IP-2'] }, taxonomies: []
    } }), 'workspace-a', true);
    await f.step(0);
    const text = f.elements.get('analysisLiveProgress').textContent;
    assert.match(text, /28 of 80/);
    assert.match(text, /Waiting for LLM response/);
    assert.doesNotMatch(text, /Assessing relationships/);
    assert.match(text, /BP-1.*IP/);
    assert.match(text, /CONSUMES/);
    assert.match(text, /24.*24/);
    assert.match(text, /4.*unresolved/);
    assert.doesNotMatch(text, /80 of 80/);
    f.monitor.stop();
});

function coverage() {
    return { total: 12, assessed: 4, excluded: 3, open: 5, taxonomies: [
        { root: 'BP', nameEn: 'Business processes', nameDe: 'Geschäftsprozesse', total: 8, assessed: 3, excluded: 2, open: 3 },
        { root: 'IP', nameEn: 'Information products', nameDe: 'Informationsprodukte', total: 4, assessed: 1, excluded: 1, open: 2 }
    ] };
}
function relationshipWork() {
    return { totalSources: 3, assessedSources: 2, totalSearches: 10, completedSearches: 4,
        unresolvedSearches: 2, pendingSearches: 4, calls: 8, maxCalls: 12, verifiedRelations: 99,
        step: 'NAVIGATE', current: null, taxonomies: [
            { root: 'BP', total: 6, completed: 2, unresolved: 1, pending: 3 },
            { root: 'IP', total: 4, completed: 2, unresolved: 1, pending: 1 }
        ] };
}

test('taxonomy rows distinguish waiting active zero and failed assessments in catalogue order', async () => {
    const parts = [
        { root: 'BP', total: 3, assessed: 1, excluded: 2, open: 0, positive: 0, allocatedPercent: 0, rootPercent: 0 },
        { root: 'BR', total: 2, assessed: 0, excluded: 0, open: 2, positive: 0, allocatedPercent: 0, rootPercent: null },
        { root: 'CI', total: 4, assessed: 1, excluded: 0, open: 3, positive: 1, allocatedPercent: 0, rootPercent: 70 },
        { root: 'CP', total: 3, assessed: 0, excluded: 0, open: 3, positive: 0, allocatedPercent: 0, rootPercent: null }
    ];
    let value = { ...snapshot(), node: 'CI-child', calls: [{ id: 1, node: 'BR', status: 'FAILED' },
        { id: 2, node: 'CI-child', status: 'STARTED' }],
        nodeProgress: { total: 12, assessed: 2, excluded: 2, open: 8, taxonomies: parts.slice().reverse() } };
    const f = fixture(async () => response(value), 'workspace-a', true);
    f.window.TaxonomyState = { taxonomyData: parts.map(part => ({ code: part.root })) };
    await f.step(0);
    assert.equal(taxonomyRow(f, 'BP').getAttribute('data-assessment-state'), 'COMPLETED_ZERO');
    assert.match(taxonomyRow(f, 'BP').textContent, /Completed.*0%/);
    assert.equal(taxonomyRow(f, 'BR').getAttribute('data-assessment-state'), 'FAILED');
    assert.match(taxonomyRow(f, 'BR').textContent, /Assessment failed/);
    assert.doesNotMatch(taxonomyRow(f, 'BR').textContent, /0%/);
    assert.equal(taxonomyRow(f, 'CI').getAttribute('data-assessment-state'), 'RUNNING');
    assert.equal(taxonomyRow(f, 'CI').getAttribute('data-active'), 'true');
    assert.match(taxonomyRow(f, 'CI').textContent, /Assessing/);
    assert.equal(taxonomyRow(f, 'CP').getAttribute('data-assessment-state'), 'NOT_STARTED');
    assert.match(taxonomyRow(f, 'CP').textContent, /Not started/);
    assert.doesNotMatch(taxonomyRow(f, 'CP').textContent, /0%/);
    assert.deepEqual(descendants(f.elements.get('analysisWorkProgress')).filter(element => element.tag === 'progress'
        && element.getAttribute('data-taxonomy-root')).map(element => element.getAttribute('data-taxonomy-root')),
        ['BP', 'BR', 'CI', 'CP']);

    value = { ...value, sequence: 2, status: 'PARTIAL', phase: 'FINISHED', calls: [] };
    await f.step(1000);
    assert.equal(taxonomyRow(f, 'CI').getAttribute('data-assessment-state'), 'PARTIAL');
    assert.match(taxonomyRow(f, 'CI').textContent, /Incomplete/);
    assert.notEqual(taxonomyRow(f, 'CI').getAttribute('data-active'), 'true');
    assert.equal(taxonomyRow(f, 'CP').getAttribute('data-assessment-state'), 'NOT_STARTED');
    f.monitor.stop();
});

test('current node changes refresh localized taxonomy assessment state without new scores', async () => {
    let value = { ...snapshot(), node: 'custom-node', calls: [], nodeProgress: {
        total: 2, assessed: 0, excluded: 0, open: 2, taxonomies: [
            { root: 'BP', total: 1, assessed: 0, excluded: 0, open: 1, rootPercent: null },
            { root: 'IP', total: 1, assessed: 0, excluded: 0, open: 1, rootPercent: null }
        ] } };
    const f = fixture(async () => response(value), 'workspace-a', true);
    f.document.documentElement.lang = 'de';
    f.window.TaxonomyState = { taxonomyData: [{ code: 'BP', children: [{ code: 'custom-node', taxonomyRoot: 'BP' }] }, { code: 'IP' }] };
    await f.step(0);
    assert.match(taxonomyRow(f, 'BP').textContent, /Wird bewertet/);
    assert.match(taxonomyRow(f, 'IP').textContent, /Noch nicht begonnen/);
    value = { ...value, sequence: 2, node: 'IP' };
    await f.step(1000);
    assert.equal(taxonomyRow(f, 'IP').getAttribute('data-assessment-state'), 'RUNNING');
    assert.notEqual(taxonomyRow(f, 'BP').getAttribute('data-active'), 'true');
    f.monitor.stop();
});

test('durable failed taxonomy tasks stay incomplete even when their root has a positive score', async () => {
    const f = fixture(async () => response({ ...clusterSnapshot(), node: 'CP',
        calls: [{ id: 1, node: 'CP', status: 'STARTED' }], nodeProgress: {
        total: 4, assessed: 1, excluded: 0, open: 3, taxonomies: [
            { root: 'CP', total: 2, assessed: 1, excluded: 0, open: 1, rootPercent: 80 },
            { root: 'IP', total: 2, assessed: 0, excluded: 0, open: 2, rootPercent: null }
        ] }, cluster: { completedRoots: 1, totalRoots: 2, tasks: [
            { taskType: 'SUBTAXONOMY_ANALYSIS', root: { code: 'CP' }, queued: 0, running: 0, completed: 0, failed: 1 },
            { taskType: 'SUBTAXONOMY_ANALYSIS', root: { code: 'IP' }, queued: 0, running: 1, completed: 0, failed: 0 }
        ] } }), 'workspace-a', true);
    await f.step(0);
    assert.equal(taxonomyRow(f, 'CP').getAttribute('data-assessment-state'), 'PARTIAL');
    assert.match(taxonomyRow(f, 'CP').textContent, /Incomplete/);
    assert.equal(taxonomyRow(f, 'IP').getAttribute('data-assessment-state'), 'RUNNING');
    f.monitor.stop();
});

test('a durable root failure overrides uncommitted zero coverage from a crashed worker', async () => {
    const f = fixture(async () => response({ ...clusterSnapshot(), node: 'CP', calls: [], nodeProgress: {
        total: 1, assessed: 1, excluded: 0, open: 0, taxonomies: [
            { root: 'CP', total: 1, assessed: 1, excluded: 0, open: 0, rootPercent: 0 }
        ] }, cluster: { completedRoots: 1, totalRoots: 1, tasks: [
            { taskType: 'SUBTAXONOMY_ANALYSIS', root: { code: 'CP' }, queued: 0, running: 0, completed: 0, failed: 1 }
        ] } }), 'workspace-a', true);
    await f.step(0);
    assert.equal(taxonomyRow(f, 'CP').getAttribute('data-assessment-state'), 'PARTIAL');
    assert.match(taxonomyRow(f, 'CP').textContent, /Incomplete/);
    assert.doesNotMatch(taxonomyRow(f, 'CP').textContent, /Completed.*relevance/);
    f.monitor.stop();
});

test('one catalogue-ordered node bar per taxonomy survives relation work and recolours only completed positive sources', async () => {
    let value = { ...snapshot(), nodeProgress: coverage() };
    value.nodeProgress.taxonomies[0] = { ...value.nodeProgress.taxonomies[0], positive: 2, allocatedPercent: 0.125, rootPercent: 80 };
    value.nodeProgress.taxonomies[1] = { ...value.nodeProgress.taxonomies[1], positive: 1, allocatedPercent: 20, rootPercent: 20 };
    const f = fixture(async () => response(value), 'workspace-a', true);
    f.window.TaxonomyState = { taxonomyData: [{ code: 'BP' }, { code: 'IP' }] };
    await f.step(0);
    const before = workBar(f, 'nodes', 'BP');
    assert.equal(before.getAttribute('data-positive'), '2');
    assert.equal(before.getAttribute('data-relations-completed'), '0');
    assert.match(before.getAttribute('aria-valuetext'), /0\.125%.*80%/);
    value = { ...value, sequence: 2, relationProgress: { ...relationshipWork(),
        taxonomies: relationshipWork().taxonomies.slice().reverse(),
        sourceTaxonomies: [{ root: 'IP', total: 1, completed: 0, unresolved: 0 },
            { root: 'BP', total: 2, completed: 1, unresolved: 1 }] } };
    value.nodeProgress = { ...value.nodeProgress, taxonomies: value.nodeProgress.taxonomies.slice().reverse() };
    await f.step(1000);
    const parts = descendants(f.elements.get('analysisWorkProgress')).filter(element => element.tag === 'progress'
        && element.getAttribute('data-taxonomy-root'));
    assert.deepEqual(parts.map(element => element.getAttribute('data-taxonomy-root')), ['BP', 'IP']);
    assert.ok(parts.every(element => element.getAttribute('data-progress-kind') === 'nodes'));
    const after = workBar(f, 'nodes', 'BP');
    assert.equal(after.getAttribute('max'), '8');
    assert.equal(after.getAttribute('value'), '5');
    assert.equal(after.getAttribute('data-positive'), '2');
    assert.equal(after.getAttribute('data-relations-completed'), '1');
    assert.equal(after.getAttribute('data-relations-unresolved'), '1');
    assert.match(after.getAttribute('aria-valuetext'), /1 of 2.*source/);
    assert.equal(workBar(f, 'relations', 'BP'), undefined, 'Target work must not create a second taxonomy list');
    f.monitor.stop();
});

test('legacy previews never invent positive totals or completed source counts from search task percentages', async () => {
    const f = fixture(async () => response({ ...snapshot(), nodeProgress: coverage(), relationProgress: relationshipWork(),
        scoresTruncated: true, rawScores: { BP: 80 } }), 'workspace-a', true);
    await f.step(0);
    const bar = workBar(f, 'nodes', 'BP');
    assert.equal(bar.getAttribute('data-positive'), null);
    assert.equal(bar.getAttribute('data-relations-completed'), null);
    assert.match(bar.getAttribute('aria-valuetext'), /not recorded/);
    assert.match(bar.getAttribute('style'), /analysis-segment-unknown/);
    assert.doesNotMatch(bar.getAttribute('style'), /analysis-segment-positive/);
    f.monitor.stop();
});

test('the current source is never double-counted as active after it has become unresolved', async () => {
    const nodes = coverage(); nodes.taxonomies[0] = { ...nodes.taxonomies[0], positive: 3 };
    const f = fixture(async () => response({ ...snapshot(), nodeProgress: nodes,
        relationProgress: { ...relationshipWork(), sourceTaxonomies: [{ root: 'BP', total: 2, completed: 0, unresolved: 1 }],
            current: { sourceId: 'BP-1', targetRoot: 'IP', type: 'CONSUMES', direction: 'OUTGOING', depth: 1, candidates: ['IP-1'] } }
    }), 'workspace-a', true);
    await f.step(0);
    const bar = workBar(f, 'nodes', 'BP');
    assert.match(bar.getAttribute('style'), /analysis-segment-relation-open/);
    assert.doesNotMatch(bar.getAttribute('style'), /analysis-segment-active/,
        'The current pointer cannot prove which aggregate source class owns the node');
    assert.equal(bar.getAttribute('data-relations-unresolved'), '1');
    f.monitor.stop();
});

test('the first relation snapshot marks source membership from catalogue metadata without an ID prefix', async () => {
    const value = { ...snapshot(), nodeProgress: coverage(), relationProgress: { ...relationshipWork(),
        current: { sourceId: 'catalogue-item', targetRoot: 'IP', type: 'CONSUMES', direction: 'OUTGOING', depth: 1, candidates: ['IP-1'] } } };
    const f = fixture(async () => response(value), 'workspace-a', true);
    f.window.TaxonomyState = { taxonomyData: [{ code: 'BP', children: [{ code: 'catalogue-item', taxonomyRoot: 'BP', nameEn: 'Time tracking' }] },
        { code: 'IP', children: [] }] };
    await f.step(0);
    const rows = descendants(f.elements.get('analysisWorkProgress')).filter(element => element.children.some(child => child.tag === 'progress'
        && child.getAttribute('data-progress-kind') === 'nodes' && child.getAttribute('data-taxonomy-root')));
    assert.equal(rows.length, 2);
    const active = rows.filter(row => row.getAttribute('data-active') === 'true');
    assert.equal(active.length, 1, 'The first render must identify the active source taxonomy');
    assert.ok(active[0].children.some(child => child.tag === 'progress' && child.getAttribute('data-taxonomy-root') === 'BP'));
    await f.step(1000);
    assert.equal(active[0].getAttribute('data-active'), 'true', 'An unchanged work snapshot must retain the source marker');
    f.monitor.stop();
});

test('resource and raw status details are opt-in while the live line stays concise', async () => {
    const f = fixture(async () => response({ ...snapshot(), databaseStorage: 'IN_MEMORY', indexStorage: 'IN_MEMORY',
        startedAt: 1000, serverTime: 2000, lastActivityAt: 2000, nodeProgress: coverage() }), 'workspace-a', true);
    await f.step(0);
    const details = f.elements.get('analysisProgressDiagnostics');
    assert.ok(details, 'Technical details need their own disclosure');
    assert.equal(details.open, false);
    assert.match(details.textContent, /Heap.*81%/);
    const state = f.elements.get('analysisLiveProgress').children[1];
    assert.doesNotMatch(state.textContent, /RUNNING|heartbeat|IN_MEMORY|Heap/);
    assert.ok(state.textContent.length < 180);
    const metrics = f.elements.get('analysis-taxonomy-counts-BP');
    assert.ok(metrics);
    assert.match(metrics.textContent, /Total.*8.*Processed.*5.*Non-zero.*Allocated/);
    f.monitor.stop();
});

test('a budget stop explains retained work without exposing a raw stop code as the user message', async () => {
    const f = fixture(async () => response({ ...snapshot(1, 'PARTIAL'), stopReason: 'CALL_BUDGET' }), 'workspace-a', true);
    await f.step(0);
    const warning = f.elements.get('analysisLiveProgress').children.find(child => child.className === 'fw-bold');
    assert.match(warning.textContent, /budget.*retained/i);
    assert.doesNotMatch(warning.textContent, /CALL_BUDGET/);
    assert.match(f.elements.get('analysisProgressDiagnostics').textContent, /CALL_BUDGET/);
    f.monitor.stop();
});

test('every taxonomy has a visible labelled bar while direct and excluded node coverage stay distinct', async () => {
    const value = { ...clusterSnapshot(), nodeProgress: coverage(), relationProgress: relationshipWork() };
    value.cluster = { ...value.cluster, completedRoots: 8, totalRoots: 8 };
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    assert.equal(f.elements.get('analysisTaxonomyProgressDetails').open, true, 'Every taxonomy starts visible');
    for (const [root, completed, total] of [['', 7, 12], ['BP', 5, 8], ['IP', 2, 4]]) {
        const bar = workBar(f, 'nodes', root);
        assert.ok(bar, `Missing native node bar for ${root || 'overall coverage'}`);
        assert.equal(bar.getAttribute('value'), String(completed));
        assert.equal(bar.getAttribute('max'), String(total));
        assert.ok(f.elements.get(bar.getAttribute('aria-labelledby'))?.textContent, 'Native bar has a visible label');
        assert.match(bar.getAttribute('aria-valuetext'), /assessed.*excluded.*open/);
    }
    assert.match(f.elements.get('analysisWorkProgress').textContent, /4 directly assessed.*3 excluded by parent decision.*5 open/);
    assert.equal(descendants(f.elements.get('analysisWorkProgress')).filter(element => element.tag === 'progress'
        && element.getAttribute('data-progress-kind') === 'nodes').length, 3, 'Distributed DTOs do not duplicate node coverage');
    const bars = descendants(f.elements.get('analysisWorkProgress')).filter(element => element.tag === 'progress');
    assert.equal(bars.length, 4, 'Node rows are shared with relation source progress, alongside the two overall meters');
    assert.equal(workBar(f, 'cluster-nodes'), undefined);
    assert.equal(workBar(f, 'cluster-relations'), undefined);
    assert.ok(bars.every(bar => Number(bar.getAttribute('value')) < Number(bar.getAttribute('max'))));
    assert.doesNotMatch(f.elements.get('analysisWorkProgress').textContent, /8 of 8 complete/);
    f.monitor.stop();
});

test('taxonomy count rows stay compact while accessible values retain the assessment and exclusion meaning', async () => {
    const f = fixture(async () => response({ ...snapshot(), nodeProgress: coverage() }), 'workspace-a', true);
    await f.step(0);
    const bar = workBar(f, 'nodes', 'BP');
    const row = descendants(f.elements.get('analysisWorkProgress')).find(element => element.children.includes(bar));
    assert.equal(row.children[0].children[1].textContent, '5/8');
    assert.match(row.textContent, /Total.*8.*Processed.*5.*Non-zero/);
    assert.match(bar.getAttribute('aria-valuetext'), /3 directly assessed · 2 excluded by parent decision · 3 open/);
    f.monitor.stop();
});

test('relations have overall and target bars based on completed searches independently of verified relations', async () => {
    const f = fixture(async () => response({ ...snapshot(), relationProgress: relationshipWork() }), 'workspace-a', true);
    f.window.TaxonomyState = { taxonomyData: [{ code: 'BP', nameEn: 'Business processes' }, { code: 'IP', nameEn: 'Information products' }] };
    await f.step(0);
    for (const [root, completed, total] of [['', 4, 10], ['BP', 2, 6], ['IP', 2, 4]]) {
        const bar = workBar(f, 'relations', root);
        assert.ok(bar, `Missing relationship-search bar for ${root || 'overall search'}`);
        assert.equal(bar.getAttribute('value'), String(completed), 'Unresolved tasks and found relations are not completed work');
        assert.equal(bar.getAttribute('max'), String(total));
        assert.match(bar.getAttribute('aria-valuetext'), /unresolved.*pending/);
        const label = f.elements.get(bar.getAttribute('aria-labelledby'))?.textContent;
        assert.match(label, root ? new RegExp(root) : /Relationship search/);
    }
    assert.match(f.elements.get('analysisWorkProgress').textContent, /99 relationship proposals checked/);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /search tasks.*relationships found/i);
    f.monitor.stop();
});

test('the concise live status announces local relationship work independently of the non-live taxonomy list', async () => {
    const f = fixture(async () => response({ ...snapshot(), evaluatedNodes: 0, relationProgress: relationshipWork() }), 'workspace-a', true);
    await f.step(0);
    assert.equal(f.elements.get('analysisWorkProgress').getAttribute('aria-live'), 'off');
    assert.match(f.elements.get('analysisLiveProgress').children[1].textContent, /Relationship search.*4 of 10/);
    assert.doesNotMatch(f.elements.get('analysisLiveProgress').children[1].textContent, /0 nodes evaluated/);
    f.monitor.stop();
});

test('zero planned searches stay unknown until finished and empty work never becomes a fabricated complete bar', async () => {
    const r = { ...relationshipWork(), totalSources: 0, assessedSources: 0, totalSearches: 0, completedSearches: 0,
        unresolvedSearches: 0, pendingSearches: 0, step: 'SOURCES', taxonomies: [{ root: 'BP', total: 0, completed: 0, unresolved: 0, pending: 0 }] };
    let value = { ...snapshot(), relationProgress: r };
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    for (const root of ['', 'BP']) {
        const bar = workBar(f, 'relations', root);
        assert.ok(bar);
        assert.equal(bar.getAttribute('value'), null);
        assert.match(bar.getAttribute('aria-valuetext'), /not yet known/i);
    }
    value = { ...value, sequence: 2, status: 'COMPLETED', relationProgress: { ...r, step: 'FINISHED' } };
    await f.step(1000);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /No search tasks required/);
    assert.equal(workBar(f, 'relations').getAttribute('value'), null);
    assert.doesNotMatch(f.elements.get('analysisWorkProgress').textContent, /100%|0 of 0 complete/);
});

test('cluster-only dynamic search work has indeterminate total and target bars with honest failure counts', async () => {
    const value = clusterSnapshot(); value.phase = 'RELATIONS';
    value.cluster.tasks[1] = { taskType: 'RELATION_ANALYSIS', root: { code: 'IP' }, queued: 1, running: 0, completed: 3, failed: 2 };
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    for (const [kind, root] of [['cluster-nodes', 'CP'], ['cluster-relations', ''], ['cluster-relations', 'IP']]) {
        const bar = workBar(f, kind, root);
        assert.ok(bar, `Missing cluster bar ${kind} ${root}`);
        assert.equal(bar.getAttribute('value'), null, 'Task states cannot claim a fixed catalogue/search denominator');
        assert.match(bar.getAttribute('aria-valuetext'), /running|active|queued|failed/);
    }
    assert.match(f.elements.get('analysisWorkProgress').textContent, /More tasks may be added/);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /2 failed/);
    f.monitor.stop();
});

test('terminal cluster-only search states retain bars without suggesting that the stopped run will add work', async () => {
    const value = clusterSnapshot(2, 'PARTIAL'); value.phase = 'FINISHED';
    value.cluster.tasks = [{ taskType: 'RELATION_ANALYSIS', root: { code: 'IP' }, queued: 0, running: 0, completed: 3, failed: 2 }];
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    assert.ok(workBar(f, 'cluster-relations'), 'Terminal task history keeps the overall search bar');
    assert.equal(workBar(f, 'cluster-relations', 'IP').getAttribute('value'), null);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /Search totals unavailable/);
    assert.doesNotMatch(f.elements.get('analysisWorkProgress').textContent, /More tasks may be added/);
});

test('live updates retain keyboard disclosure state and focus and refresh localized labels', async () => {
    let value = { ...snapshot(), nodeProgress: coverage() };
    const f = fixture(async () => response(value), 'workspace-a', true);
    await f.step(0);
    const details = f.elements.get('analysisTaxonomyProgressDetails');
    details.open = false;
    f.elements.get('analysisTaxonomyProgressToggle').focus();
    value = { ...value, sequence: 2, nodeProgress: { ...coverage(), assessed: 5, open: 4 } };
    await f.step(1000);
    assert.equal(f.elements.get('analysisTaxonomyProgressDetails').open, false);
    assert.equal(f.document.activeElement, f.elements.get('analysisTaxonomyProgressToggle'));
    f.document.documentElement.lang = 'de';
    value = { ...value, sequence: 3 };
    await f.step(1000);
    assert.match(f.elements.get('analysisWorkProgress').textContent, /Geschäftsprozesse/);
    assert.match(workBar(f, 'nodes', 'BP').getAttribute('aria-valuetext'), /bewertet.*ausgeschlossen.*offen/);
    assert.equal(f.document.activeElement, f.elements.get('analysisTaxonomyProgressToggle'));
    f.monitor.stop();
});

for (const status of ['ERROR', 'CANCELLED', 'PARTIAL']) {
    test(`${status} retains incomplete relationship work without showing successful completion`, async () => {
        const f = fixture(async () => response({ ...snapshot(1, status), relationProgress: { ...relationshipWork(), step: 'PAUSED' } }), 'workspace-a', true);
        await f.step(0);
        assert.equal(f.elements.get('analysisWorkProgress').getAttribute('data-run-status'), status);
        assert.equal(workBar(f, 'relations').getAttribute('value'), '4');
        assert.match(f.elements.get('analysisWorkProgress').textContent, /incomplete/);
        assert.doesNotMatch(f.elements.get('analysisLiveProgress').textContent, /Complete result received/);
        assert.match(f.elements.get('analysisLiveProgress').textContent, new RegExp(status));
    });
}

test('observes a started call and partial scores before completion; never starts work', async () => {
    const f = fixture(async () => response(snapshot()));
    await f.step(0);
    assert.equal(f.snapshots[0].value.calls[0].status, 'STARTED');
    assert.equal(f.snapshots[0].value.rawScores.CP, 80);
    assert.equal(f.snapshots[0].value.memory.warning, true);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'GET');
    assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    assert.match(f.calls[0].url, /workspaceId=workspace-a/);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('unchanged business sequence still updates heartbeat and heap data', async () => {
    let count = 0;
    const f = fixture(async () => response({ ...snapshot(), serverTime: ++count, memory: { percent: count } }));
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.snapshots.map(s => s.changed), [true, false]);
    assert.equal(f.snapshots[1].value.serverTime, 2);
    assert.equal(f.snapshots[1].value.memory.percent, 2);
    f.monitor.stop();
});

test('rejects another operation and out-of-order responses', async () => {
    const values = [snapshot(3), snapshot(2), { ...snapshot(4), operationId: 'another-run' }];
    const f = fixture(async () => response(values.shift()));
    await f.step(0); await f.step(1000); await f.step(1000);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.snapshots[0].value.sequence, 3);
    f.monitor.stop();
});

test('late response after a generation change never reaches the browser', async () => {
    let resolve;
    const f = fixture(() => new Promise(r => { resolve = r; }));
    const pending = f.step(0);
    f.scope.generation++;
    resolve(response(snapshot()));
    await pending;
    assert.equal(f.snapshots.length, 0);
    assert.equal(f.timers.size, 0);
    f.monitor.stop();
});

test('cancellation is explicit, deduplicated, CSRF-protected and pinned to original workspace', async () => {
    const f = fixture(async () => response(snapshot(2, 'CANCELLING')));
    f.scope.workspaceId = 'workspace-b';
    await Promise.all([f.monitor.cancel(), f.monitor.cancel()]);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'POST');
    assert.equal(f.calls[0].options.headers['x-csrf-token'], 'test-token');
    assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    assert.match(f.calls[0].url, /\/cancel\?workspaceId=workspace-a$/);
    assert.equal(f.snapshots.length, 0);
    f.monitor.stop();
});

test('temporary connection failure retries observation without repeating analysis', async () => {
    let fail = true;
    const f = fixture(async () => { if (fail) { fail = false; throw new Error('offline'); } return response(snapshot()); });
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['Network request failed']);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    f.monitor.stop();
});

test('an unregistered run reports waiting, not fake progress', async () => {
    const f = fixture(async () => response({}, 404));
    await f.step(0);
    assert.deepEqual(f.unavailable, ['WAITING_FOR_RUN']);
    assert.equal(f.snapshots.length, 0);
    f.monitor.stop();
});

test('terminal status releases timers and allows lazy details only in the original scope', async () => {
    const f = fixture(async url => response(url.includes('/calls/')
        ? { prompt: 'bounded prompt', response: 'bounded response', truncated: true }
        : snapshot(2, 'COMPLETED')));
    await f.step(0);
    assert.equal(f.timers.size, 0);
    assert.equal((await f.monitor.detail(1)).truncated, true);
    f.scope.workspaceId = 'workspace-b';
    await assert.rejects(f.monitor.detail(1), /STALE_ANALYSIS/);
    assert.equal(f.calls.length, 2);
});


test('terminal observation never sends a later cancellation write', async () => {
    const f = fixture(async () => response(snapshot(2, 'COMPLETED')));
    await f.step(0);
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 1);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    assert.equal(f.timers.size, 0);
});

test('explicitly stopped monitoring cannot cancel a superseded run', async () => {
    const f = fixture(async () => response(snapshot()));
    f.monitor.stop();
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 0);
    assert.equal(f.timers.size, 0);
});

test('early admission polls accept an empty pending response without starting a run', async () => {
    let count = 0;
    const f = fixture(async () => ++count === 1
        ? { ok: true, status: 202, json: async () => { throw new Error('pending response has no JSON body'); } }
        : response(snapshot(count)));
    await f.step(0);
    assert.deepEqual(f.unavailable, ['WAITING_FOR_RUN']);
    assert.equal(f.snapshots.length, 0);
    await f.step(1000); await f.step(1000);
    assert.equal(f.snapshots.length, 2);
    assert.ok(f.calls.slice(0, 2).every(call => call.url.endsWith('&waitForRegistration=true')));
    assert.ok(!f.calls[2].url.includes('waitForRegistration'));
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    f.monitor.stop();
});

test('a known run disappearing is not reported as pending admission', async () => {
    let count = 0;
    const f = fixture(async () => response(++count === 1 ? snapshot() : {}, count === 1 ? 200 : 404));
    await f.step(0); await f.step(1000);
    assert.deepEqual(f.unavailable, ['HTTP 404']);
    assert.equal(f.snapshots.length, 1);
    assert.ok(!f.calls[1].url.includes('waitForRegistration'));
    f.monitor.stop();
});


test('all three telemetry operations use canonical CSRF, request IDs and no-store transport', async () => {
    const f = fixture(async url => response(url.includes('/calls/') ? { prompt: 'bounded' } : snapshot()));
    await f.step(0);
    await f.monitor.detail('1/a?b');
    await f.monitor.cancel();
    assert.equal(f.calls.length, 3);
    assert.match(f.calls[1].url, /calls\/1%2Fa%3Fb\?workspaceId=workspace-a$/);
    for (const { options } of f.calls) {
        assert.equal(options.headers['x-taxonomy-workspace-id'], 'workspace-a');
        assert.ok(options.headers['x-request-id']);
        assert.equal(options.cache, 'no-store');
        assert.equal(options.credentials, 'same-origin');
    }
    assert.equal(f.calls[2].options.headers['x-csrf-token'], 'test-token');
    assert.equal(f.calls[2].options.keepalive, true);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('the polling deadline aborts the canonical request and reports a timeout', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const pending = f.step(0);
    await f.step(5000);
    await pending;
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.deepEqual(f.unavailable, ['CONNECTION_TIMEOUT']);
    f.monitor.stop();
    assert.equal(f.timers.size, 0);
});

test('stopping the monitor aborts the actual transport without a late UI error', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }));
    const pending = f.step(0);
    f.monitor.stop();
    await pending;
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.equal(f.snapshots.length, 0);
    assert.equal(f.unavailable.length, 0);
    assert.equal(f.timers.size, 0);
});

test('authorization failure is reported centrally and never retried as a cancellation write', async () => {
    const f = fixture(async () => response({ detail: 'Denied' }, 403));
    await f.monitor.cancel();
    assert.equal(f.calls.length, 1);
    assert.equal(f.authFailures.length, 1);
    assert.equal(f.authFailures[0].status, 403);
    assert.deepEqual(f.unavailable, ['HTTP 403: Denied']);
    f.monitor.stop();
});


test('unscoped session requests still follow the active tab while operation cancellation stays pinned', async () => {
    const f = fixture(async () => response(snapshot()));
    f.scope.workspaceId = 'workspace-b';
    await f.api.request('/api/analysis-drafts/current', {});
    await f.monitor.cancel();
    assert.equal(f.calls.length, 2);
    assert.match(f.calls[0].url, /analysis-drafts\/current\?workspaceId=workspace-b$/);
    assert.match(f.calls[1].url, /cancel\?workspaceId=workspace-a$/);
    assert.equal(f.calls[1].options.headers['x-taxonomy-workspace-id'], 'workspace-a');
    f.monitor.stop();
});

test('cancellation before registration is retained and delivered once admission is observed', async () => {
    let admitted = false;
    const f = fixture(async (url, options) => response(
        options.method === 'POST' ? snapshot(2, 'CANCELLING') : snapshot(),
        !admitted ? options.method === 'POST' ? 404 : 202 : 200));
    await f.monitor.cancel();
    await f.monitor.cancel();
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    await f.step(0);
    assert.equal(f.snapshots.length, 0);
    admitted = true;
    await f.step(1000);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 2);
    await f.step(1000);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 2);
    assert.ok(!f.unavailable.some(message => message.includes('404')));
    f.monitor.stop();
});

test('a deferred cancellation does not write to an already completed run', async () => {
    const f = fixture(async (url, options) => options.method === 'POST'
        ? response({}, 404) : response(snapshot(2, 'COMPLETED')));
    await f.monitor.cancel();
    await f.step(0);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.equal(f.timers.size, 0);
});


test('a known run disappearing terminates polling and cancellation without discarding the last snapshot', async () => {
    let count = 0;
    const f = fixture(async () => ++count === 1 ? response(snapshot()) : response({}, 404));
    await f.step(0); await f.step(1000);
    assert.equal(f.snapshots.length, 1);
    assert.equal(f.timers.size, 0, 'expired runs must not be polled forever');
    assert.equal(f.monitor.isCurrent(), false);
    assert.equal(await f.monitor.cancel(), false);
    assert.equal(f.calls.length, 2);
});

for (const status of [400, 401, 403]) {
    test(`permanent observation rejection ${status} releases the polling timer`, async () => {
        const f = fixture(async () => response({}, status));
        await f.step(0);
        assert.equal(f.unavailable.length, 1);
        assert.equal(f.timers.size, 0);
        assert.equal(f.monitor.isCurrent(), false);
    });
}


for (const workspaceId of [null, '']) {
    test(`central scope ${String(workspaceId)} remains explicit for cancellation after a workspace change`, async () => {
        const f = fixture(async () => response(snapshot()), workspaceId);
        f.scope.workspaceId = 'workspace-b';
        await f.monitor.cancel();
        const url = new URL(f.calls[0].url, 'https://taxonomy.example/');
        assert.equal(url.searchParams.has('workspaceId'), true);
        assert.equal(url.searchParams.get('workspaceId'), '');
        assert.equal(f.calls[0].options.headers['x-taxonomy-workspace-id'], '');
        f.monitor.stop();
    });
}

test('all central observation endpoints retain the explicit pin through named routing', async () => {
    const f = fixture(async () => response(snapshot()));
    await f.api.getRunStatus(id, { workspaceId: null });
    await f.api.getRunCallDetail(id, 1, { workspaceId: null });
    await f.api.cancelRun(id, { workspaceId: null });
    for (const call of f.calls) {
        const url = new URL(call.url, 'https://taxonomy.example/');
        assert.equal(url.searchParams.has('workspaceId'), true);
        assert.equal(url.searchParams.get('workspaceId'), '');
        assert.equal(call.options.headers['x-taxonomy-workspace-id'], '');
    }
    f.monitor.stop();
});


for (const change of ['none', 'workspace', 'generation']) {
    test(`abandoned POST cleanup survives late admission and ${change} change without stale UI updates`, async () => {
        let reads = 0;
        const f = fixture(async (url, options) => options.method === 'POST'
            ? response(snapshot(3, 'CANCELLING'))
            : ++reads === 1 ? response(null, 202) : response(snapshot(2)));
        f.monitor.cancelAndStop();
        f.monitor.cancelAndStop();
        f.monitor.stop();
        if (change === 'workspace') f.scope.workspaceId = 'workspace-b';
        if (change === 'generation') f.scope.generation++;
        await f.step(0); await f.step(1000);
        assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
        assert.ok(f.calls.every(call => call.url.includes('workspaceId=workspace-a')));
        const cancel = f.calls.find(call => call.options.method === 'POST');
        assert.equal(cancel.options.headers['x-csrf-token'], 'test-token');
        assert.equal(cancel.options.headers['x-taxonomy-workspace-id'], 'workspace-a');
        assert.equal(f.snapshots.length, 0);
        assert.equal(f.timers.size, 0);
    });
}

test('abandoned POST cleanup never writes to an operation already completed', async () => {
    const f = fixture(async () => response(snapshot(2, 'COMPLETED')));
    f.monitor.cancelAndStop(); await f.step(0);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'GET');
    assert.equal(f.timers.size, 0);
});

test('abandoned unregistered POST cleanup stops at its independent bounded deadline', async () => {
    const f = fixture(async () => response(null, 202));
    f.monitor.cancelAndStop(); await f.step(0); await f.step(1000);
    await f.step(30000);
    assert.equal(f.timers.size, 0);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    assert.equal(f.snapshots.length, 0);
});

test('cleanup does not duplicate an already in-flight user cancellation', async () => {
    let release;
    const f = fixture(() => new Promise(resolve => { release = resolve; }));
    const cancel = f.monitor.cancel();
    f.monitor.cancelAndStop(); await f.step(0);
    assert.equal(f.calls.length, 1);
    release(response(snapshot(2, 'CANCELLING'))); await cancel;
    await f.step(1000);
    assert.equal(f.calls.length, 1);
    assert.equal(f.timers.size, 0);
});

test('cleanup aborts a stalled status read at the bounded deadline', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
    }));
    f.monitor.cancelAndStop(); const read = f.step(0);
    await f.step(30000); await read;
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.equal(f.timers.size, 0);
});

test('cleanup stops at authorization failure without attempting a cancellation write', async () => {
    const f = fixture(async () => response({ detail: 'denied' }, 403));
    f.monitor.cancelAndStop(); await f.step(0);
    assert.equal(f.calls.length, 1);
    assert.equal(f.timers.size, 0);
});

test('cleanup never retries an ambiguous cancellation write', async () => {
    const f = fixture(async (url, options) => {
        if (options.method === 'POST') throw new Error('connection lost after submission');
        return response(snapshot());
    });
    f.monitor.cancelAndStop(); await f.step(0);
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.equal(f.timers.size, 0);
});


test('cleanup cannot repeat an earlier user cancel whose response was lost', async () => {
    const f = fixture(async () => { throw new Error('response lost after cancel submission'); });
    await f.monitor.cancel();
    f.monitor.cancelAndStop(); await f.step(0);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.method, 'POST');
    assert.equal(f.timers.size, 0);
});


function finalSnapshot() {
    return { ...snapshot(2, 'COMPLETED'), calls: [{ id: 1, status: 'COMPLETED' }] };
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('final HTTP result closes the panel immediately and refreshes the last LLM log without applying preview scores', async () => {
    let final = false;
    const f = fixture(async () => response(final ? finalSnapshot() : snapshot()), 'workspace-a', true);
    await f.step(0);
    assert.match(f.elements.get('llmCommLogContent').textContent, /STARTED/);
    final = true;
    f.monitor.finish('SUCCESS');
    assert.match(f.elements.get('analysisLiveProgress').textContent, /Complete result received/);
    assert.equal(f.elements.get('analysisLiveProgress').children.find(child =>
        child.tag === 'button' && child.textContent === 'Analysis finished').disabled, true);
    await flush();
    assert.match(f.elements.get('llmCommLogContent').textContent, /COMPLETED/);
    assert.equal(f.snapshots.length, 1, 'final preview must never replace the complete HTTP score envelope');
    assert.equal(f.calls.length, 2);
    assert.ok(f.calls.every(call => call.options.method === 'GET'));
    assert.equal(f.timers.size, 0);
});

test('HTTP completion before the first poll still retrieves diagnostic metadata exactly once', async () => {
    const f = fixture(async () => response(finalSnapshot()), 'workspace-a', true);
    f.monitor.finish('SUCCESS'); f.monitor.finish('SUCCESS');
    await flush();
    assert.equal(f.calls.length, 1);
    assert.match(f.elements.get('llmCommLogContent').textContent, /COMPLETED/);
    assert.equal(f.snapshots.length, 0);
    assert.equal(f.timers.size, 0);
});

test('an already observed terminal snapshot needs no redundant final request', async () => {
    const f = fixture(async () => response(finalSnapshot()), 'workspace-a', true);
    await f.step(0); f.monitor.finish('SUCCESS'); await flush();
    assert.equal(f.calls.length, 1);
    assert.equal(f.timers.size, 0);
});

for (const change of ['workspace', 'generation', 'invalidation']) {
test(`final diagnostic response cannot touch changed ${change} context`, async () => {
    let release;
    const f = fixture(() => new Promise(resolve => { release = resolve; }), 'workspace-a', true);
    f.monitor.finish('SUCCESS');
    assert.equal(f.calls.length, 1);
    if (change === 'workspace') f.scope.workspaceId = 'workspace-b';
    if (change === 'generation') f.scope.analysisGeneration++;
    if (change === 'invalidation') f.scope.invalidating = true;
    release(response(finalSnapshot())); await flush();
    assert.doesNotMatch(f.elements.get('llmCommLogContent').textContent, /COMPLETED/);
    assert.equal(f.timers.size, 0);
});

}

test('a stalled final diagnostic read is bounded without changing the successful analysis outcome', async () => {
    const f = fixture((url, options) => new Promise((resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
    }), 'workspace-a', true);
    f.monitor.finish('SUCCESS');
    await f.step(5000); await flush();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].options.signal.aborted, true);
    assert.match(f.elements.get('analysisLiveProgress').textContent, /Complete result received/);
    assert.match(f.elements.get('llmCommLogContent').textContent, /Final diagnostic status unavailable/);
    assert.equal(f.timers.size, 0);
});

test('rejected HTTP admission finalizes without looking up an unaccepted operation', async () => {
    const f = fixture(async () => response({}, 404), 'workspace-a', true);
    f.monitor.finish('ERROR', false); await flush();
    assert.equal(f.calls.length, 0);
    assert.equal(f.timers.size, 0);
});


test('an observed terminal monitor still owns its forthcoming full HTTP result', async () => {
    const f = fixture(async () => response(finalSnapshot()), 'workspace-a', true);
    await f.step(0);
    assert.equal(f.monitor.isCurrent(), false, 'Polling stopped on the terminal observation');
    assert.equal(f.monitor.acceptsResult(), true, 'The full result must not be discarded with polling');
});

test('only the latest in-scope monitor may apply a full HTTP result', () => {
    const f = fixture(async () => response(snapshot()), 'workspace-a', true);
    assert.equal(f.monitor.acceptsResult(), true);
    const next = f.window.TaxonomyAnalysisProgress.start('11111111-1111-4111-8111-111111111111');
    assert.equal(f.monitor.acceptsResult(), false);
    assert.equal(next.acceptsResult(), true);
    f.scope.analysisGeneration++;
    assert.equal(next.acceptsResult(), false);
    next.stop();
});

test('explicit cluster detail opens unresolved attempts and refreshes the completed answer with unknown usage preserved', async () => {
    let completed = false;
    const call = { id: 1, provider: 'Fixture', node: 'CP', status: 'STARTED', startedAt: 1000, durationMillis: 0 };
    const f = fixture(async url => {
        if (String(url).includes('/calls/1')) return response({ prompt: 'PRIVATE-REQUEST', response: completed ? 'PRIVATE-ANSWER' : '',
            promptLength: 15, responseLength: completed ? 14 : 0, truncated: false, taskId: 'operation:subtaxonomy:CP', deliveryAttempt: 2,
            attempts: [{ attemptId: 'attempt', source: 'HTTP', retryIndex: 0, state: completed ? 'COMPLETED' : 'STARTED',
                httpStatus: completed ? 200 : null, outcome: completed ? 'RESPONSE' : null,
                usage: { inputTokens: null, outputTokens: completed ? 0 : null, totalTokens: null, invalid: false } }] });
        return response({ ...clusterSnapshot(1), calls: [call] });
    }, 'workspace-a', true);
    f.window.TaxonomyRoleSurface.canAnalyze = () => true;
    await f.step(0);
    const entry = f.elements.get('llmCommLogContent').children.find(child => child.tag === 'details');
    assert.ok(entry);
    assert.equal(f.calls.filter(request => String(request.url).includes('/calls/')).length, 0, 'Ordinary progress never fetches request/answer detail');
    entry.open = true;
    entry.listeners.get('toggle')();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.calls.filter(request => String(request.url).includes('/calls/')).length, 1, 'An unresolved START is inspectable after explicit opening');
    assert.ok(entry.textContent.includes('UNKNOWN'));
    assert.ok(entry.textContent.includes('STARTED'));
    completed = true;
    await f.streams[0].emit('progress', { ...clusterSnapshot(2), calls: [{ ...call, status: 'COMPLETED', durationMillis: 10 }] });
    await new Promise(resolve => setImmediate(resolve));
    assert.ok(entry.textContent.includes('PRIVATE-ANSWER'));
    assert.ok(entry.textContent.includes('output=0'));
    assert.ok(entry.textContent.includes('total=UNKNOWN'));
    f.monitor.stop();
    entry.open = false;
    entry.listeners.get('toggle')();
    entry.open = true;
    entry.listeners.get('toggle')();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.calls.filter(request => String(request.url).includes('/calls/')).length, 3,
        'Explicit reopening reads retained evidence again after live observation ends');
});


test('discarding the analysis removes its progress panel even after observation has finished', async () => {
    const f = fixture(async () => response(snapshot(1, 'COMPLETED')), 'workspace-a', true);
    await f.step(0);
    assert.ok(f.document.getElementById('analysisLiveProgress'));
    f.document.dispatchEvent(new CustomEvent('taxonomy:analysis-invalidated'));
    assert.equal(f.document.getElementById('analysisLiveProgress'), null);
});

test('cancelling retains the observed partial progress until the analysis is explicitly discarded', async () => {
    const f = fixture(async () => response(snapshot()), 'workspace-a', true);
    await f.step(0);
    f.document.dispatchEvent(new CustomEvent('taxonomy:analysis-cancelled'));
    assert.ok(f.document.getElementById('analysisLiveProgress'));
});
