import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const base = '../../taxonomy-app/src/main/resources/static/js/';
const sources = ['api/taxonomy-api-client.js', 'api/analysis-session-api.js',
    'core/taxonomy-analysis-progress.js', 'core/taxonomy-scoring.js']
    .map(path => readFileSync(new URL(base + path, import.meta.url), 'utf8'));
const id = 'cb2a3d71-e849-4a50-9855-1f9cb8f81402';
const authority = { workspaceId: 'workspace-a', repositoryId: 'repo-a', branch: 'draft', sourceCommit: 'commit-a' };
const input = { operationId: id, businessText: 'Original durable requirement', provider: 'MOCK',
    analysisScope: { taxonomyRoots: ['CP'], mode: 'TAXONOMIES_ONLY' }, scope: authority };
const result = { status: 'SUCCESS', scores: { CP: 80 }, rawScores: { CP: 80 }, effectiveScores: { CP: 80 },
    reasons: { CP: 'Persisted assessment' }, warnings: [], tree: [{ code: 'CP', children: [] }],
    analysisScope: input.analysisScope, provider: 'MOCK', analysisDurationMillis: 1400 };
function snapshot(status = 'RUNNING', sequence = 2) {
    return { operationId: id, sequence, transport: 'artemis', scope: authority, status,
        phase: status === 'COMPLETED' ? 'FINISHED' : 'SCORING', rawScores: {}, calls: [],
        startedAt: 1000, lastActivityAt: 2000, serverTime: 2400, elapsedMillis: 1400,
        evaluatedNodes: 0, queueWaitMillis: 0, executionMillis: 1400,
        cluster: { completedRoots: status === 'COMPLETED' ? 1 : 0, totalRoots: 1, tasks: [] } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture({ status = 'COMPLETED', requestInput, requestResult, startRequest } = {}) {
    const elements = new Map(), timers = new Map(), listeners = new Map(), calls = [], streams = [];
    let serial = 0;
    class Element {
        constructor(tag) {
            this.tag = tag; this.children = []; this.style = {}; this.dataset = {}; this.value = '';
            this.events = new Map(); this.content = ''; this.isConnected = true;
            this.classList = { add() {}, remove() {}, toggle() {}, contains() { return false; } };
        }
        set id(value) { this.identifier = value; elements.set(value, this); }
        get id() { return this.identifier; }
        set textContent(value) { this.content = String(value); this.children = []; }
        get textContent() { return this.content + this.children.map(value => value.textContent || '').join(' '); }
        set innerHTML(value) { this.content = String(value); this.children = []; }
        get innerHTML() { return this.content; }
        append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
        appendChild(child) { this.append(child); }
        replaceChildren(...children) { this.children = []; this.content = ''; this.append(...children); }
        insertAdjacentElement(_, child) { child.parent = this; }
        setAttribute() {}
        getAttribute() { return null; }
        addEventListener(name, callback) { this.events.set(name, callback); }
        async click() { await this.events.get('click')?.({ target: this }); await flush(); }
        remove() { this.isConnected = false; elements.delete(this.id); }
        querySelector() { return null; }
        querySelectorAll() { return []; }
    }
    for (const name of ['statusArea', 'businessText', 'analyzeBtn', 'providerSelect', 'includeArchitectureView', 'scoreOutput']) {
        const node = new Element('div'); node.id = name;
    }
    elements.get('businessText').value = 'Unsaved current draft';
    elements.get('providerSelect').value = 'MOCK';
    const runtime = { workspaceId: 'workspace-a', analysisGeneration: 1, invalidating: false };
    const state = { taxonomyData: [], currentScores: {}, currentRawScores: {}, currentScoreDetails: {}, currentReasons: {},
        currentView: 'tree', currentDiscrepancies: [], currentProductCoverageGaps: [] };
    const document = {
        documentElement: { lang: 'en' }, readyState: 'complete',
        createElement: tag => new Element(tag), createTextNode: text => ({ textContent: text }),
        getElementById: key => elements.get(key) || null, querySelector: () => null, querySelectorAll: () => [],
        addEventListener(name, callback) { (listeners.get(name) || listeners.set(name, []).get(name)).push(callback); },
        dispatchEvent(event) { for (const listener of listeners.get(event.type) || []) listener(event); }
    };
    const schedule = (fn, delay) => { const key = ++serial; timers.set(key, { fn, delay }); return key; };
    const window = { location: { href: 'https://taxonomy.example/', origin: 'https://taxonomy.example' },
        TaxonomyRoleSurface: {}, TaxonomyUiSemantics: {},
        setTimeout: schedule, clearTimeout: key => timers.delete(key),
        TaxonomyI18n: { t: key => key, getLocale: () => 'en' }, TaxonomyUtils: { escapeHtml: value => String(value) },
        TaxonomyState: state, __TaxonomyAnalysisSessionContext: { runtime, clearDerivedUi() {} },
        TaxonomyAnalysisSession: { state: () => ({ workspaceId: runtime.workspaceId, ready: !runtime.invalidating }) },
        TaxonomyAnalysisSessionReady: Promise.resolve(true),
        TaxonomyBrowse: { clearStatus() {}, showStatus: (_, message) => { elements.get('statusArea').textContent = message; },
            renderView: (_, scores) => { elements.get('scoreOutput').textContent = JSON.stringify(scores); }, ensureNodeRendered() {} },
        EventSource: class {
            constructor(url) { this.url = url; this.events = new Map(); this.readyState = 1; streams.push(this); }
            addEventListener(name, callback) { this.events.set(name, callback); }
            close() { this.closed = true; this.readyState = 2; }
            async send(value) { this.events.get('progress')?.({ data: JSON.stringify(value), lastEventId: String(value.sequence) }); await flush(); }
        },
        async fetch(url, options = {}) {
            calls.push({ url, options });
            if (url === '/api/analyze') return startRequest ? startRequest() : new Promise(() => {});
            const path = new URL(url, 'https://taxonomy.example').pathname;
            let body = path.endsWith('/request') ? requestInput ? await requestInput() : input
                : path.endsWith('/result') ? requestResult ? await requestResult() : result
                    : path === '/api/analysis-runs' ? [snapshot(status)] : snapshot(status);
            return new Response(JSON.stringify(body), { status: 200 });
        }
    };
    const sandbox = vm.createContext({ window, document, AbortController, URL, Request, Response, Headers, CustomEvent,
        TaxonomyI18n: window.TaxonomyI18n, TaxonomyUtils: window.TaxonomyUtils, CSS: { escape: value => value },
        setTimeout: schedule, clearTimeout: window.clearTimeout, crypto: { randomUUID: () => id },
        fetch: window.fetch, console: { log() {}, error() {} } });
    for (const source of sources) vm.runInContext(source, sandbox);
    async function tick() {
        const entry = [...timers].find(([, value]) => value.delay === 0);
        assert.ok(entry, 'Existing-operation observation should be scheduled');
        timers.delete(entry[0]); await entry[1].fn(); await flush();
    }
    function buttons() {
        const all = [];
        const visit = node => { if (node.tag === 'button') all.push(node); node.children?.forEach(visit); };
        if (elements.get('analysisRecentRuns')) visit(elements.get('analysisRecentRuns'));
        return all;
    }
    return { window, document, runtime, state, elements, calls, streams, tick, buttons };
}

test('reload lists existing work and explicit result retrieval restores its original requirement without analysis POST', async () => {
    const f = fixture(); await flush();
    assert.equal(f.elements.get('businessText').value, 'Unsaved current draft');
    const retrieve = f.buttons().find(button => /Retrieve result/.test(button.textContent));
    assert.ok(retrieve, 'Ready sessions expose a visible recovery action');
    await retrieve.click(); await f.tick();
    assert.equal(f.elements.get('businessText').value, 'Original durable requirement');
    assert.equal(f.state.lastAnalyzedText, 'Original durable requirement');
    assert.equal(f.state.currentRawScores.CP, 80);
    assert.match(f.elements.get('scoreOutput').textContent, /"CP":80/);
    assert.equal(f.elements.get('analyzeBtn').disabled, false);
    assert.ok(f.calls.every(call => !call.options.method || call.options.method === 'GET'));
});

test('visible resume follows a running operation through SSE and hydrates its persisted result', async () => {
    const f = fixture({ status: 'RUNNING' }); await flush();
    const resume = f.buttons().find(button => /Resume observation/.test(button.textContent));
    assert.ok(resume); await resume.click(); await f.tick();
    assert.equal(f.streams.length, 1);
    await f.streams[0].send(snapshot('COMPLETED', 3));
    assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    assert.equal(f.state.currentReasons.CP, 'Persisted assessment');
    assert.ok(f.calls.every(call => !call.options.method || call.options.method === 'GET'));
});

test('a failed persisted-result read offers a visible read-only retry', async () => {
    let attempts = 0;
    const f = fixture({ requestResult: () => { if (++attempts === 1) throw new Error('offline'); return result; } });
    await flush(); await f.buttons().find(button => /Retrieve result/.test(button.textContent)).click(); await f.tick();
    const retry = f.elements.get('analysisLiveProgress').children.find(child => /Retry result/.test(child.textContent));
    assert.ok(retry, 'The completed run must offer a result-retrieval retry');
    assert.equal(retry.hidden, false);
    await retry.click();
    assert.equal(f.state.currentRawScores.CP, 80);
    assert.equal(f.elements.get('analyzeBtn').disabled, false);
    assert.equal(attempts, 2);
    assert.ok(f.calls.every(call => !call.options.method || call.options.method === 'GET'));
});

for (const change of ['workspace', 'text', 'authority']) {
    test(`recovery cannot overwrite ${change} that changed while the scoped input was loading`, async () => {
        let deliver;
        const f = fixture({ requestInput: () => new Promise(resolve => { deliver = resolve; }) }); await flush();
        const retrieve = f.buttons().find(button => /Retrieve result/.test(button.textContent));
        assert.ok(retrieve); const pending = retrieve.click(); await flush();
        if (change === 'workspace') f.runtime.workspaceId = 'workspace-b';
        if (change === 'text') f.elements.get('businessText').value = 'New edit';
        deliver(change === 'authority' ? { ...input, scope: { ...authority, sourceCommit: 'foreign' } } : input);
        await pending;
        assert.notEqual(f.elements.get('businessText').value, 'Original durable requirement');
        assert.equal(f.streams.length, 0);
        assert.equal(f.state.currentRawScores.CP, undefined);
    });
}

test('active analysis can finish from persisted SSE result while its original POST is disconnected', async () => {
    let rejectPost;
    const f = fixture({ status: 'RUNNING', startRequest: () => new Promise((_, reject) => { rejectPost = reject; }) });
    await flush();
    const operation = f.window.TaxonomyScoring.runAnalysis(); await f.tick();
    assert.equal(f.streams.length, 1);
    rejectPost(new Error('Connection lost')); await flush();
    assert.equal(f.streams[0].closed, undefined, 'Accepted durable work continues after the POST connection fails');
    await f.streams[0].send(snapshot('COMPLETED', 3));
    assert.equal(f.state.currentRawScores.CP, 80);
    assert.equal((await operation).status, 'SUCCESS');
    assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
});

test('starting a new analysis supersedes a still-loading recovery choice', async () => {
    let deliver;
    const f = fixture({ requestInput: () => new Promise(resolve => { deliver = resolve; }) }); await flush();
    const retrieving = f.window.TaxonomyScoring.resumeAnalysis(id, authority); await flush();
    f.window.TaxonomyScoring.runAnalysis();
    deliver(input); await retrieving;
    assert.equal(f.elements.get('businessText').value, 'Unsaved current draft');
    assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1);
});
