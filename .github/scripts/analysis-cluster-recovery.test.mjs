import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const base = '../../taxonomy-app/src/main/resources/static/js/';
const sources = ['api/taxonomy-api-client.js', 'api/analysis-api.js', 'api/analysis-session-api.js',
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
function fixture({ status = 'COMPLETED', requestInput, requestResult, startRequest, localProgress = false, localScores = {CP:80}, sourceReady = true } = {}) {
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
    const runtime = { workspaceId: 'workspace-a', analysisGeneration: 1, invalidating: false, sourceReady };
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
        TaxonomyAnalysisSession: { state: () => ({ workspaceId: runtime.workspaceId, ready: !runtime.invalidating && runtime.sourceReady }) },
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
            if (new URL(url, 'https://taxonomy.example').pathname === '/api/analyze') return startRequest ? startRequest() : new Promise(() => {});
            const path = new URL(url, 'https://taxonomy.example').pathname;
            let body = path.endsWith('/request') ? requestInput ? await requestInput() : input
                : path.endsWith('/result') ? requestResult ? await requestResult() : result
                    : path === '/api/analysis-runs' ? [snapshot(status)] : {...snapshot(status), ...(localProgress ? {transport: 'local', rawScores: localScores} : {})};
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

test('changing repository selection detaches a durable stream and ignores its late completion', async () => {
    const f = fixture({status: 'RUNNING'}); await flush();
    const resume = f.buttons().find(button => /Resume observation/.test(button.textContent));
    await resume.click();
    await f.tick();
    const [stream] = f.streams;
    assert.ok(stream);
    f.document.dispatchEvent({type: 'taxonomy:repository-context-changed', detail: {selectionChanged: false}});
    assert.equal(stream.closed, undefined, 'An unchanged context poll keeps the current stream');

    f.document.dispatchEvent({type: 'taxonomy:repository-context-changed', detail: {selectionChanged: true}});
    assert.equal(stream.closed, true);
    assert.equal(f.elements.has('analysisLiveProgress'), false);
    assert.equal(f.elements.get('analyzeBtn').disabled, false);
    await stream.send(snapshot('COMPLETED', 3));
    assert.equal(f.calls.some(call => call.url.includes('/result')), false);
    assert.equal(f.calls.some(call => call.options.method === 'POST'), false,
        'Changing the local view must not cancel durable work for another tab');
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


test('ordinary complete HTTP result owns status and measured duration after a terminal local progress preview', async () => {
    let deliver;
    const f = fixture({ status: 'COMPLETED', localProgress: true, startRequest: () => new Promise(resolve => {deliver = resolve;}) });
    await flush();
    const pending = f.window.TaxonomyScoring.runAnalysis();
    await f.tick();
    assert.equal(f.state.lastAnalysisStatus, 'IN_PROGRESS');
    assert.equal(f.state.lastAnalysisDurationMillis, null);
    deliver(Response.json(result));
    await pending;
    assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    assert.equal(f.state.lastAnalysisDurationMillis, 1400);
    assert.equal(f.elements.get('analyzeBtn').disabled, false);
});


test('recent durable runs load after source readiness that follows the draft-only startup promise', async () => {
    const f = fixture({sourceReady: false});
    await flush();
    assert.equal(f.calls.length, 0);
    f.document.dispatchEvent({type: 'taxonomy:analysis-source-ready', detail: {workspaceId: 'workspace-a'}});
    await flush(); assert.equal(f.calls.length, 0, 'The event cannot bypass current admission readiness');
    f.runtime.sourceReady = true;
    f.document.dispatchEvent({type: 'taxonomy:analysis-source-ready', detail: {workspaceId: 'workspace-a'}});
    await flush();
    assert.equal(f.calls.length, 1);
    assert.equal(new URL(f.calls[0].url, 'https://taxonomy.example').pathname, '/api/analysis-runs');
    assert.ok(f.buttons().some(button => /Retrieve result/.test(button.textContent)));
    assert.ok(f.calls.every(call => !call.options.method || call.options.method === 'GET'));
});

test('a revoked progressive operation cannot publish queued scores or a late result after edit and revert', async () => {
    let deliver;
    const f = fixture({status:'RUNNING', startRequest:() => new Promise(resolve => { deliver = resolve; })});
    await flush();
    let current = true, bound;
    const presentations = [];
    const owner = {current:() => current, bind(operation, cancel) { bound = {operation,cancel}; return true; },
        present(operation, update) {presentations.push({operation, update});}};
    const pending = f.window.TaxonomyScoring.runAnalysis({refinement:owner});
    await f.tick();
    assert.equal(bound?.operation, id);
    await f.streams[0].send({...snapshot('RUNNING',3),rawScores:{CP:50}});
    assert.equal(f.state.currentEffectiveScores.CP,50);
    assert.equal(presentations.at(-1).update.scores.CP,50);
    current = false;
    f.state.currentRawScores = {CP:17}; f.state.currentEffectiveScores = {CP:17};
    await f.streams[0].send({...snapshot('RUNNING',4),rawScores:{CP:70}});
    deliver(Response.json(result)); await pending;
    assert.equal(f.state.currentRawScores.CP,17,'a late response cannot replace a newer manual score');
    assert.equal(presentations.at(-1).update.scores.CP,50);
});

test('a lost managed progressive POST keeps the monitor observable and never promotes live numeric placeholders',async()=>{
    let refuse;
    const f=fixture({status:'RUNNING',localProgress:true,localScores:{product:60},startRequest:()=>new Promise((_,reject)=>{refuse=reject;})});
    f.state.taxonomyData=[{code:'family',children:[{code:'product',analysisRole:'PRODUCT',parentCode:'family'}]}];
    await flush();
    const updates=[]; let stop;
    const refinement={current:()=>true,bind(_,cancel){stop=cancel;return true;},present(id,update){updates.push(update);}};
    const pending=f.window.TaxonomyScoring.runAnalysis({refinement,recoveryManaged:true});
    await f.tick();
    assert.equal(updates.at(-1).details.product.effectiveRelevance,null);
    assert.equal(updates.at(-1).scores.product,0,'the live numeric envelope still has its placeholder');
    refuse(new Error('response lost')); await assert.rejects(pending); await flush();
    assert.equal(updates.at(-1)?.status,'CHECKING');
    assert.equal(updates.at(-1).scores,undefined);
    assert.notEqual(f.state.lastAnalysisStatus,'ERROR');
    stop();
    assert.equal(f.elements.get('analysisLiveProgress').textContent.includes('finished'),false);
});

test('further extension progress cannot reclassify changed local evidence as an owned projection', async () => {
    const f = fixture({status:'RUNNING'});
    await flush();
    let projectedReason = '', remembered = 0;
    f.window.TaxonomyScoring.runAnalysis({
        continuation:{analysisScope:input.analysisScope}, recoveryManaged:true,
        canProjectProgress:() => (f.state.currentReasons.CP || '') === projectedReason,
        rememberProgressEvidence() { projectedReason = f.state.currentReasons.CP || ''; remembered++; }
    });
    await f.tick();
    const [stream] = f.streams;
    await stream.send({...snapshot('RUNNING', 3), rawScores:{CP:66}, reasons:{CP:'Interim'}});
    assert.equal(f.state.currentScores.CP, 66);
    const beforeEdit = remembered;
    f.state.currentReasons.CP = 'Manual assessment';
    await stream.send({...snapshot('RUNNING', 4), rawScores:{CP:71}, reasons:{CP:'Later progress'}});
    assert.equal(f.state.currentScores.CP, 66);
    assert.equal(f.state.currentReasons.CP, 'Manual assessment');
    assert.equal(remembered, beforeEdit);
});

for (const transport of ['POST','Artemis']) {
 test(`a successful ${transport} terminal delivery retains a competing assessment for managed review`, async () => {
    let deliver;
    const f = fixture({status:'RUNNING', startRequest:()=>new Promise(resolve=>{deliver=resolve;})});
    await flush();
    const shown = [], conflicts = [];
    const owner = {current:()=>true, bind:()=>true, present:(id,update)=>shown.push(update)};
    const pending = f.window.TaxonomyScoring.runAnalysis({continuation:{analysisScope:input.analysisScope},
        recoveryManaged:true, refinement:owner, canProjectProgress:()=>f.state.currentReasons.CP !== 'Reviewed manually',
        onEvidenceConflict:saved=>conflicts.push(saved)});
    await f.tick();
    await f.streams[0].send({...snapshot('RUNNING',3), rawScores:{CP:66}});
    f.state.currentScores.CP = 92;
    f.state.currentReasons.CP = 'Reviewed manually';
    if (transport === 'POST') deliver(Response.json(result));
    else await f.streams[0].send(snapshot('COMPLETED',4));
    await pending;
    assert.equal(f.state.currentScores.CP,92);
    assert.equal(f.state.currentReasons.CP,'Reviewed manually');
    assert.equal(conflicts.length,1);
    assert.equal(conflicts[0].scores.CP,80);
    assert.ok(shown.every(update=>update.status === 'IN_PROGRESS'));
    assert.equal(f.elements.get('analyzeBtn').disabled,false);
    if (transport === 'Artemis') {deliver(Response.json(result));await flush();assert.equal(conflicts.length,1);}
 });
}
