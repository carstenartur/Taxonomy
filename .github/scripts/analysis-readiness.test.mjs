import canonicalFixture from './canonical-api-fixture.cjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js', import.meta.url), 'utf8');
const recoverySource = readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js', import.meta.url), 'utf8');
function fixture(options = {}) {
    const statuses = [], calls = [], observations = [], renders = [], order = [], progressActions = [];
    const recoveryUi = { opened: false, openCount: 0, message: '',
        update(message) { this.message = message; }, close() { this.opened = false; },
        open() { this.opened = true; this.openCount++; }, isOpen() { return this.opened; },
        error(message) { statuses.push(['error', message]); }, isDismissed: () => false };
    let rejectRequest, activeMonitor;
    const requests = [];
    const sessionState = { ready: true, workspaceId: 'workspace-a' };
    const state = { currentDiscrepancies: ['old discrepancy'], currentProductCoverageGaps: ['old gap'],
        currentArchView: { title: 'old architecture' }, evaluatedNodes: new Set(['CP']),
        storedBusinessText: 'old requirement', lastAnalyzedText: 'old requirement',
        pendingProposalNodeCode: 'CP', lastAnalysisProvider: 'old provider', currentReasons: { CP: 'retained reason' }, currentRawScores: { CP: 80 },
        lastAnalysisStatus: 'SUCCESS', taxonomyData: [], currentScoreDetails: {}, currentScores: { CP: 80 } };
    const window = { _currentProvisionalRelations: ['old relation'], TaxonomyState: state, TaxonomyBrowse: {
        showStatus: (...args) => statuses.push(args), clearStatus() {}, ensureNodeRendered() {},
        renderView: (tree, scores) => { renders.push({ tree, scores: { ...scores } }); order.push('render'); }
    } };
    const textField = { value: 'communications', classList: { remove() {} } };
    const document = { documentElement: { lang: 'en' }, querySelector: () => null, querySelectorAll: () => [], getElementById: id => id === 'businessText' ? textField
        : id === 'includeArchitectureView' ? { checked: options.includeArchitecture !== false } : null };
    const sandbox = { window, document, CSS: { escape: value => value }, console: { log() {}, error() {} },
        TaxonomyI18n: { t: (key, ...values) => [key, ...values].join(': ') }, TaxonomyUtils: { escapeHtml: text => text },
        crypto: { randomUUID: () => 'cb2a3d71-e849-4a50-9855-1f9cb8f81402' },
        fetch: (...args) => { calls.push(args); order.push('post'); return new Promise((resolve, reject) => { rejectRequest = reject; requests.push({ resolve, reject }); }); }
    };
    if (Object.hasOwn(options, 'crypto')) sandbox.crypto = options.crypto;
    if (options.analysisScope) window.TaxonomyAnalysisScope = {read: () => options.analysisScope};
    if (options.invalidScope) window.TaxonomyAnalysisScope = {read: () => { throw new Error('Select a taxonomy'); }};
    sandbox.setTimeout = () => 1; sandbox.clearTimeout = () => {};
    canonicalFixture.install(sandbox, ['api/analysis-api.js']);
    vm.runInNewContext(source, sandbox);
    function progress() { window.TaxonomyAnalysisProgress = { start: (...args) => {
        observations.push(args); order.push('monitor');
        const workspace = sessionState.workspaceId;
        const monitor = { acceptsResult: () => activeMonitor === monitor && sessionState.workspaceId === workspace,
            finish() { progressActions.push('finish'); }, stop() { progressActions.push('stop'); },
            cancel() { progressActions.push('cancel'); }, transportFailed() { progressActions.push('transportFailed'); } };
        activeMonitor = monitor;
        return monitor;
    } }; }
    function loadRecovery() {
        window.__TaxonomyAnalysisSessionContext = { S: state,
            runtime: { workspaceId: sessionState.workspaceId, analysisGeneration: 0 }, language: () => 'en' };
        window.TaxonomyRecoveryViewport = { mount: actions => { recoveryUi.actions = actions; return recoveryUi; } };
        document.addEventListener = () => {};
        textField.addEventListener = () => {};
        sandbox.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
        sandbox.setTimeout = () => 1;
        sandbox.clearTimeout = () => {};
        vm.runInNewContext(recoverySource, sandbox);
        return window.TaxonomyAnalysisRecovery;
    }
    return { window, state, sessionState, statuses, calls, observations, progress, renders, order, loadRecovery,
        requests, progressActions, sandbox, recoveryUi, rejectRequest: error => error.httpStatus
            ? requests.at(-1).resolve(Response.json({detail: error.message}, {status: error.httpStatus}))
            : rejectRequest(error) };
}

for (const [action, failure, observation, opens] of [
    ['analyze', 503, 404, false],
    ['copilot', 503, 404, true],
    ['analyze', 'network', 404, true],
    ['analyze', 503, 503, true],
    ['analyze', 503, 403, true],
    ['analyze', 503, 'RUNNING', true],
    ['analyze', 503, 'PAUSED', true],
    ['analyze', 503, 'COMPLETED', true]
]) {
    test(`recovery presentation after ${action} ${failure} and observed ${observation}`, async () => {
        const f = fixture({ crypto: webcrypto }); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        const recovery = f.loadRecovery();
        vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/analysis-session-api.js', import.meta.url), 'utf8'), f.sandbox);
        const start = () => action === 'copilot' ? recovery.startCopilot() : f.window.TaxonomyScoring.runAnalysis();
        const work = start(), sent = JSON.parse(f.calls[0][1].body), target = f.state.recoveryContext;
        if (failure === 'network') f.requests[0].reject(new TypeError('Failed to fetch'));
        else f.requests[0].resolve(Response.json({ error: 'QA_PROVIDER_UNAVAILABLE', message: 'Deterministic QA failure' }, { status: failure }));
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(f.calls.length, 2, 'the rejected start must read its original continuation, without another POST');
        assert.equal(f.calls[1][1].method, 'GET');
        assert.equal(f.calls[1][0], '/api/analysis-continuations/' + sent.continuationId + '?workspaceId=workspace-a');
        assert.equal(new Headers(f.calls[1][1].headers).get('X-Taxonomy-Workspace-Id'), 'workspace-a');
        const openedBeforeRead = f.recoveryUi.openCount;
        if (typeof observation === 'number') f.requests[1].resolve(Response.json({ detail: 'Analysis continuation unavailable' }, { status: observation }));
        else {
            const status = { id: sent.continuationId, version: 3, state: observation, completedCalls: 1,
                openQuestions: observation === 'PAUSED' ? [{ key: 'open', nodes: ['BP'], skipped: false }] : [] };
            f.requests[1].resolve(Response.json({ request: sent, recovery: status,
                result: observation === 'RUNNING' ? null : { status: observation === 'COMPLETED' ? 'SUCCESS' : 'PARTIAL',
                    recovery: status, rawScores: { BP: 80 }, scores: { BP: 80 }, reasons: { BP: 'Accepted evidence' }, analysisScope: sent.analysisScope } }));
        }
        await work;
        assert.equal(f.recoveryUi.opened, opens);
        assert.equal(f.state.recoveryContext, target); assert.equal(target.id, sent.continuationId);
        assert.equal(f.calls.length, 2, 'observation never repeats the provider request');
        if (!opens) {
            assert.equal(openedBeforeRead, 0, 'ordinary rejection waits for its status read without trapping navigation');
            assert.equal(f.recoveryUi.openCount, 0, 'an unregistered ordinary start is reported inline');
            assert.ok(f.statuses.some(([kind, message]) => kind === 'danger' && message.includes('Deterministic QA failure')));
            assert.equal(target.confirmed, undefined); assert.equal(target.observationStatus, 404);
            assert.equal(target.syncState, 'UNAVAILABLE'); assert.ok(f.recoveryUi.message);
            f.recoveryUi.actions.open(); assert.equal(f.recoveryUi.opened, true, 'the explicit status action remains available');
            start(); assert.equal(f.calls.length, 2, 'keeping navigation usable must not authorize a fresh start');
            assert.equal(f.state.recoveryContext, target);
        }
    });
}

test('recovery presentation keeps ordinary successful analysis non-modal', async () => {
    const f = fixture({ crypto: webcrypto }); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState }; f.loadRecovery();
    const work = f.window.TaxonomyScoring.runAnalysis(), sent = JSON.parse(f.calls[0][1].body);
    f.requests[0].resolve(Response.json({ status: 'SUCCESS', rawScores: { BP: 80 }, scores: { BP: 80 }, reasons: {},
        recovery: { id: sent.continuationId, version: 3, state: 'COMPLETED', openQuestions: [] } }));
    await work;
    assert.equal(f.recoveryUi.openCount, 0); assert.equal(f.recoveryUi.opened, false);
    assert.equal(f.state.currentRawScores.BP, 80); assert.equal(f.state.recoveryContext.confirmed, true);
    f.recoveryUi.actions.open(); assert.equal(f.recoveryUi.opened, true);
});

for (const language of ['en', 'de']) for (const action of ['analyze', 'copilot']) for (const extension of [false, true]) {
    test(`completed reuse presentation ${language} ${action} from restored ${extension ? 'extension' : 'ordinary'} source`, async () => {
        const f = fixture({ crypto: webcrypto, includeArchitecture: true }); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        let ready; f.window.TaxonomyAnalysisSessionReady = new Promise(resolve => { ready = resolve; });
        const scope = { taxonomyRoots: ['BP'], mode: 'FULL' };
        f.window.TaxonomyAnalysisScope = { read: () => scope, acceptResult: value => { f.state.lastAnalysisScope = value; } };
        const recovery = f.loadRecovery();
        f.window.__TaxonomyAnalysisSessionContext.language = () => language;
        vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/api/analysis-session-api.js', import.meta.url), 'utf8'), f.sandbox);
        const id = webcrypto.randomUUID(), request = { businessText: 'communications', provider: null,
            resumable: true, continuationId: id, continuationAction: 'START', analysisScope: scope, includeArchitectureView: true };
        if (extension) Object.assign(request, { taxonomySourceContinuationId: webcrypto.randomUUID(), taxonomySourceVersion: 3, taxonomyRequestedScope: scope });
        const status = { id, version: 7, state: 'COMPLETED', completedCalls: 39, openQuestions: [] };
        const saved = { request, recovery: status, result: { status: 'SUCCESS', recovery: status,
            rawScores: { BP: 71.125 }, scores: { BP: 71.125 }, reasons: { BP: 'Saved explanation.\nKeep verbatim.' },
            analysisScope: scope, analysisProviderId: 'CUSTOM_OPENAI', analysisCoverage: { failedOrBlockedNodes: 0, nodes: {} },
            tree: [{ code: 'BP', children: [] }], architectureView: { title: 'Retained architecture' } } };
        const storage = new Map([['taxonomy.recovery.active.v1', JSON.stringify({ id, workspaceId: 'workspace-a' })]]);
        f.sandbox.sessionStorage = { getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) };
        ready(); await new Promise(resolve => setImmediate(resolve));
        assert.equal(f.calls.length, 1); assert.equal(f.calls[0][1].method, 'GET');
        f.requests[0].resolve(Response.json(saved)); await new Promise(resolve => setImmediate(resolve));
        const source = f.state.recoveryContext;
        assert.equal(source.noFollowups, extension, 'restored source history must not decide the current button presentation');
        assert.equal(recovery.startPlan(action === 'analyze').kind, 'REUSE');
        const retained = JSON.stringify([f.state.currentRawScores, f.state.currentReasons, f.state.currentArchView]);
        if (action === 'copilot') recovery.startCopilot(); else f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.calls.length, 1, 'completed reuse must not start a provider request or another observation');
        assert.equal(f.state.recoveryContext, source); assert.equal(source.id, id);
        assert.equal(JSON.stringify([f.state.currentRawScores, f.state.currentReasons, f.state.currentArchView]), retained);
        assert.equal(f.recoveryUi.opened, action === 'copilot');
        assert.equal(f.statuses.at(-1)[0], 'info');
        assert.match(f.statuses.at(-1)[1], language === 'de' ? /Analyse abgeschlossen.*bereits vollständig bewertet/ : /Analysis complete.*already fully assessed/);
        f.recoveryUi.actions.open(); assert.equal(f.recoveryUi.opened, true, 'explicit Analysis status remains available');
        assert.equal(f.calls.length, 1); assert.equal(source.noFollowups, extension);
    });
}

for (const phase of ['no lifecycle', 'progress loaded before lifecycle', 'loader still running']) {
    test(`full analysis does not start or clear results with ${phase}`, () => {
        const f = fixture();
        if (phase !== 'no lifecycle') f.progress();
        if (phase === 'loader still running') {
            f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
            f.window.__taxonomyAnalysisSessionLoading = true;
        }
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
        assert.deepEqual(f.statuses, [['warning', 'scoring.lifecycle.not.ready']]);
    });
}

test('interactive analysis clears the previous frozen scope without reading next-run controls', () => {
    const f = fixture({invalidScope: true});
    f.state.lastAnalysisScope = {taxonomyRoots: ['BP'], mode: 'TAXONOMIES_ONLY'};
    const accepted = [];
    f.window.TaxonomyAnalysisScope.acceptResult = scope => accepted.push(scope);
    f.window.TaxonomyScoring.runInteractiveAnalysis();
    assert.equal(f.state.lastAnalysisScope, null);
    assert.deepEqual(accepted, [null]);
    assert.equal(f.state.lastAnalysisStatus, 'IN_PROGRESS');
});

test('once lifecycle is ready full analysis always starts observation before its POST', () => {
    const f = fixture();
    f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.__taxonomyAnalysisSessionLoading = false;
    f.window.TaxonomyScoring.runAnalysis();
    assert.equal(f.observations.length, 1);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0][0], '/api/analyze?workspaceId=workspace-a');
    assert.equal(new Headers(f.calls[0][1].headers).get('X-Taxonomy-Workspace-Id'), 'workspace-a');
    assert.equal(new Headers(f.calls[0][1].headers).get('X-Analysis-Operation-Id'), f.observations[0][0]);
    assert.equal(JSON.parse(f.calls[0][1].body).includeArchitectureView, true);
});

test('ordinary architecture analysis requests a real retained server continuation while keeping the normal request', () => {
    const f=fixture();f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
    f.window.TaxonomyScoring.runAnalysis();
    const request=JSON.parse(f.calls[0][1].body);
    assert.equal(request.resumable,true);assert.equal(request.continuationAction,'START');
    assert.equal(request.continuationId,new Headers(f.calls[0][1].headers).get('X-Analysis-Operation-Id'));
    assert.equal(request.businessText,'communications');assert.equal(request.includeArchitectureView,true);
    assert.equal(f.calls.length,1);
});
test('ordinary analysis with architecture disabled keeps its opt-out and a reusable continuation', () => {
    const f=fixture({includeArchitecture:false});f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
    f.window.TaxonomyScoring.runAnalysis();
    const request=JSON.parse(f.calls[0][1].body);
    assert.equal(request.includeArchitectureView,false);assert.equal(request.resumable,true);
    assert.equal(request.continuationId,new Headers(f.calls[0][1].headers).get('X-Analysis-Operation-Id'));
});

for (const action of ['copilot','analyze']) for (const roots of [['CP'],[]]) {
 test(`${action} sends the saved taxonomy source and selected work through the real analysis transport`,async()=>{
    const f=fixture({crypto:webcrypto,includeArchitecture:false});f.progress();
    f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
    f.state.taxonomyData=[{code:'BP'},{code:'CP'}];
    let scope={taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'};
    f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
    const recovery=f.loadRecovery();
    const start=()=>action==='copilot'?recovery.startCopilot():f.window.TaxonomyScoring.runAnalysis();
    const first=start();const initial=JSON.parse(f.calls[0][1].body);
    const sourceResult={status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{BP:'Accepted BP explanation'},
      analysisScope:scope,analysisCoverage:{failedOrBlockedNodes:0,nodes:{}},
      recovery:{id:initial.continuationId,version:7,state:'COMPLETED',openQuestions:[]}};
    f.requests[0].resolve(Response.json(sourceResult));await first;
    const source=f.state.recoveryContext,oldValues=JSON.stringify([f.state.currentRawScores,f.state.currentReasons]);
    scope={taxonomyRoots:roots,mode:'TAXONOMIES_ONLY'};
    const second=start();
    assert.equal(f.calls.length,2);
    const request=JSON.parse(f.calls[1][1].body);
    assert.equal(request.taxonomySourceContinuationId,source.id);assert.equal(request.taxonomySourceVersion,7);
    assert.notEqual(request.continuationId,source.id);assert.equal(request.resumable,true);
    assert.deepEqual(request.analysisScope,scope);assert.equal(request.includeArchitectureView,false);
    assert.equal(request.scores,undefined);assert.equal(request.rawScores,undefined);
    assert.equal(JSON.stringify([f.state.currentRawScores,f.state.currentReasons]),oldValues);
    assert.equal(new Headers(f.calls[1][1].headers).get('X-Taxonomy-Workspace-Id'),'workspace-a');
    f.requests[1].resolve(Response.json({...sourceResult,rawScores:{BP:80,CP:60},scores:{BP:80,CP:60},
      reasons:{BP:'Accepted BP explanation',CP:'New CP explanation'},
      analysisScope:{taxonomyRoots:['BP','CP'],mode:'TAXONOMIES_ONLY'},
      recovery:{id:request.continuationId,version:1,state:'COMPLETED',openQuestions:[]}}));await second;
    assert.deepEqual({...f.state.currentRawScores},{BP:80,CP:60});
    assert.equal(f.state.currentReasons.BP,'Accepted BP explanation');
    assert.deepEqual(Array.from(f.state.lastAnalysisScope.taxonomyRoots),['BP','CP']);
 });
}

test('ordinary full analysis without an architecture view still retains a durable source',()=>{
 const f=fixture({crypto:webcrypto,includeArchitecture:false,analysisScope:{taxonomyRoots:['BP'],mode:'FULL'}});f.progress();
 f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};f.loadRecovery();
 f.window.TaxonomyScoring.runAnalysis();const request=JSON.parse(f.calls[0][1].body);
 assert.equal(request.includeArchitectureView,false);assert.equal(request.resumable,true);
 assert.equal(request.continuationAction,'START');assert.equal(request.continuationId,f.state.recoveryContext.id);
});

test('managed automatic starts retain the secure random-values fallback',()=>{
 const f=fixture({crypto:{getRandomValues:webcrypto.getRandomValues.bind(webcrypto)},includeArchitecture:false});f.progress();
 f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};f.loadRecovery();f.window.TaxonomyScoring.runAnalysis();
 assert.equal(f.calls.length,1);
 const request=JSON.parse(f.calls[0][1].body);
 assert.match(request.continuationId,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});

for(const action of ['copilot','analyze']) test(`${action} rejects a missing secure ID source without changing retained evidence`,()=>{
 const f=fixture({crypto:{},includeArchitecture:false});f.progress();
 f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};const recovery=f.loadRecovery();
 const before=JSON.stringify([f.state.currentRawScores,f.state.currentReasons,f.state.currentArchView,f.state.lastAnalysisStatus]);
 if(action==='copilot')recovery.startCopilot();else f.window.TaxonomyScoring.runAnalysis();
 assert.equal(f.calls.length,0);assert.equal(f.observations.length,0);
 assert.equal(JSON.stringify([f.state.currentRawScores,f.state.currentReasons,f.state.currentArchView,f.state.lastAnalysisStatus]),before);
 assert.equal(f.state.recoveryContext,undefined);assert.match(f.statuses.at(-1)[1],/secure analysis identity/i);
});

test('accepted wider node-only result clears earlier relation views only after admission',async()=>{
 const f=fixture({crypto:webcrypto});f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
 let scope={taxonomyRoots:['BP'],mode:'FULL'};
 f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
 f.loadRecovery();const first=f.window.TaxonomyScoring.runAnalysis();const initial=JSON.parse(f.calls[0][1].body);
 f.requests[0].resolve(Response.json({status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{BP:'Retained'},analysisScope:scope,
  architectureView:{title:'BP view'},relationSearchReport:{title:'BP relations'},
  recovery:{id:initial.continuationId,version:3,state:'COMPLETED',openQuestions:[]}}));await first;
 const suggestedPanel={style:{display:''}},originalGet=f.sandbox.document.getElementById;
 f.sandbox.document.getElementById=id=>id==='suggestedRelationsPanel'?suggestedPanel:originalGet(id);
 scope={taxonomyRoots:['CP'],mode:'TAXONOMIES_ONLY'};const next=f.window.TaxonomyScoring.runAnalysis();
 assert.equal(f.state.currentArchView.title,'BP view');assert.equal(f.state.currentRelationSearchReport.title,'BP relations');
 assert.equal(suggestedPanel.style.display,'');
 const target=JSON.parse(f.calls[1][1].body);
 f.requests[1].resolve(Response.json({status:'SUCCESS',rawScores:{BP:80,CP:70},scores:{BP:80,CP:70},reasons:{BP:'Retained'},
  analysisScope:{taxonomyRoots:['BP','CP'],mode:'TAXONOMIES_ONLY'},
  recovery:{id:target.continuationId,version:1,state:'COMPLETED',openQuestions:[]}}));await next;
 assert.equal(f.state.currentArchView,null);assert.equal(f.state.currentRelationSearchReport,null);
});

for(const mode of ['TAXONOMIES_ONLY','FULL']) for(const mounted of [false,true])
 test(`review ${mode} acceptance clears canonical suggestions and summary ${mounted?'with':'without'} relation panels`,async()=>{
  const f=fixture({crypto:webcrypto});f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
  f.state.taxonomyData=[{code:'BP'},{code:'CP'}];let scope={taxonomyRoots:['BP'],mode:'FULL'};
  f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
  const fields={viewSummary:{style:{display:'none'}}};
  if(mounted)Object.assign(fields,{suggestedRelationsPanel:{style:{display:'none'}},suggestedRelationsContent:{innerHTML:''},suggestedRelationsBadge:{textContent:''}});
  const originalGet=f.sandbox.document.getElementById;f.sandbox.document.getElementById=id=>fields[id]||originalGet(id);
  f.loadRecovery();const first=f.window.TaxonomyScoring.runAnalysis();const initial=JSON.parse(f.calls[0][1].body);
  const suggestions=[{sourceCode:'BP',targetCode:'CP',relationType:'SUPPORTS',confidence:0.8,reasoning:'Saved suggestion'}];
  const sourceResult={status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{BP:'Retained'},analysisScope:scope,
   architectureView:{title:'BP view'},provisionalRelations:suggestions,
   recovery:{id:initial.continuationId,version:3,state:'COMPLETED',openQuestions:[]}};
  f.requests[0].resolve(Response.json(sourceResult));await first;
  assert.deepEqual(JSON.parse(JSON.stringify(f.window._currentProvisionalRelations)),suggestions);
  f.state.currentView='summary';scope={taxonomyRoots:['CP'],mode};const second=f.window.TaxonomyScoring.runAnalysis();
  assert.equal(f.state.currentView,'summary');assert.equal(fields.viewSummary.style.display,'');
  assert.deepEqual(JSON.parse(JSON.stringify(f.window._currentProvisionalRelations)),suggestions);
  const target=JSON.parse(f.calls[1][1].body);
  const accepted={status:'SUCCESS',rawScores:{BP:80,CP:70},scores:{BP:80,CP:70},reasons:{BP:'Retained'},
   analysisScope:{taxonomyRoots:['BP','CP'],mode},
   recovery:{id:target.continuationId,version:1,state:'COMPLETED',openQuestions:[]}};
  if(mode==='TAXONOMIES_ONLY')accepted.provisionalRelations=[];
  f.requests[1].resolve(Response.json(accepted));await second;
  assert.deepEqual(Array.from(f.window._currentProvisionalRelations),[]);
  assert.equal(f.state.currentArchView,null);assert.equal(f.state.currentView,'list');assert.equal(fields.viewSummary.style.display,'none');
  // Serialize and restore through the real working-draft modules, with the canonical
  // response already accepted by real Scoring and AnalysisApi above.
  delete f.window.__TaxonomyAnalysisSessionContext;delete f.window.TaxonomyAnalysisSession;
  f.window.setTimeout=f.sandbox.setTimeout;f.window.clearTimeout=f.sandbox.clearTimeout;
  f.window.TaxonomyBrowse.updateExportGroupVisibility=()=>{};
  f.sandbox.document.dispatchEvent=()=>{};
  f.sandbox.CustomEvent=class {constructor(type,options={}){this.type=type;this.detail=options.detail;}};
  for(const name of ['taxonomy-analysis-session-core.js','taxonomy-analysis-session-draft.js'])
   vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/'+name,import.meta.url),'utf8'),f.sandbox);
  const C=f.window.__TaxonomyAnalysisSessionContext;C.runtime.workspaceId='workspace-a';
  const saved=JSON.parse(JSON.stringify(C.currentPayload(true)));
  assert.deepEqual(saved.provisionalRelations,[]);assert.equal(saved.currentView,'list');
  f.window._currentProvisionalRelations=suggestions;f.state.currentView='summary';f.state.currentArchView={title:'Stale view'};
  C.applyDraft({version:1,payload:saved});
  assert.deepEqual(Array.from(f.window._currentProvisionalRelations),[]);assert.equal(f.state.currentView,'list');
  assert.equal(fields.viewSummary.style.display,'none');
 });

for(const action of ['copilot','analyze'])test(`review mode-only FULL Server Default uses taxonomy admission through ${action}`,async()=>{
 const f=fixture({crypto:webcrypto,includeArchitecture:true});f.progress();
 f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};f.state.taxonomyData=[{code:'BP'}];
 const provider={value:''},originalGet=f.sandbox.document.getElementById;
 f.sandbox.document.getElementById=id=>id==='providerSelect'?provider:originalGet(id);
 let scope={taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'};
 f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
 const recovery=f.loadRecovery(),start=()=>action==='copilot'?recovery.startCopilot():f.window.TaxonomyScoring.runAnalysis();
 const first=start(),initial=JSON.parse(f.calls[0][1].body);
 f.requests[0].resolve(Response.json({status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{BP:'Saved explanation'},
  analysisScope:scope,analysisProviderId:'CUSTOM_OPENAI',
  recovery:{id:initial.continuationId,version:3,state:'COMPLETED',openQuestions:[]}}));await first;
 scope={taxonomyRoots:['BP'],mode:'FULL'};const second=start(),request=JSON.parse(f.calls[1][1].body);
 assert.equal(request.provider,null);assert.equal(request.includeArchitectureView,true);
 assert.deepEqual(request.analysisScope,scope);assert.equal(request.taxonomySourceContinuationId,initial.continuationId);
 assert.equal(request.relationSourceContinuationId,undefined);assert.equal(f.state.currentRawScores.BP,80);
 f.requests[1].resolve(Response.json({detail:'The server default provider changed; start a new analysis'},{status:409}));await second;
 assert.equal(f.state.recoveryContext.id,initial.continuationId);
 assert.equal(f.state.currentRawScores.BP,80);assert.equal(f.state.currentReasons.BP,'Saved explanation');
 start();assert.equal(f.calls.length,2,'the rejected mode transition is not automatically repeated');
});

test('review ordinary node-to-FULL opt-out uses taxonomy admission and accepts a report without a projection',async()=>{
 const f=fixture({crypto:webcrypto,includeArchitecture:false});f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
 f.state.taxonomyData=[{code:'BP'}];let scope={taxonomyRoots:['BP'],mode:'TAXONOMIES_ONLY'};
 f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
 f.loadRecovery();const first=f.window.TaxonomyScoring.runAnalysis();const initial=JSON.parse(f.calls[0][1].body);
 const original={status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{BP:'Saved explanation'},analysisScope:scope,
  recovery:{id:initial.continuationId,version:3,state:'COMPLETED',openQuestions:[]}};
 f.requests[0].resolve(Response.json(original));await first;
 scope={taxonomyRoots:['BP'],mode:'FULL'};const second=f.window.TaxonomyScoring.runAnalysis();
 const request=JSON.parse(f.calls[1][1].body);
 assert.equal(request.taxonomySourceContinuationId,initial.continuationId);assert.equal(request.relationSourceContinuationId,undefined);
 assert.equal(request.includeArchitectureView,false);assert.equal(f.state.currentRawScores.BP,80);
 const report={sources:[],searchExhausted:true};
 f.requests[1].resolve(Response.json({...original,analysisScope:scope,relationSearchReport:report,architectureView:null,copilot:null,
  recovery:{id:request.continuationId,version:1,state:'COMPLETED',openQuestions:[]}}));await second;
 assert.equal(f.state.currentArchView,null);assert.equal(f.state.currentCopilot,null);
 assert.deepEqual(JSON.parse(JSON.stringify(f.state.currentRelationSearchReport)),report);
 assert.equal(f.state.currentReasons.BP,'Saved explanation');
});

for(const nextProjection of [false,true])test(`review same-mode projection change to ${nextProjection} is not swallowed by reuse`,async()=>{
 const f=fixture({crypto:webcrypto,includeArchitecture:!nextProjection});f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
 const scope={taxonomyRoots:['BP'],mode:'FULL'};
 f.window.TaxonomyAnalysisScope={read:()=>scope,acceptResult:value=>{f.state.lastAnalysisScope=value;},restrictsGlobalAnalysis:()=>true};
 f.loadRecovery();const first=f.window.TaxonomyScoring.runAnalysis();const initial=JSON.parse(f.calls[0][1].body);
 f.requests[0].resolve(Response.json({status:'SUCCESS',rawScores:{BP:80},scores:{BP:80},reasons:{},analysisScope:scope,
  recovery:{id:initial.continuationId,version:3,state:'COMPLETED',openQuestions:[]}}));await first;
 const originalGet=f.sandbox.document.getElementById;
 f.sandbox.document.getElementById=id=>id==='includeArchitectureView'?{checked:nextProjection}:originalGet(id);
 f.window.TaxonomyScoring.runAnalysis();assert.equal(f.calls.length,2);
 const request=JSON.parse(f.calls[1][1].body);
 assert.equal(request.includeArchitectureView,nextProjection);assert.equal(request.taxonomySourceContinuationId,initial.continuationId);
});
for(const status of ['SUCCESS','PARTIAL'])for(const generation of [0,1])test('ordinary '+status+' analysis binds its retained request in generation '+generation+' without automatic follow-ups',async()=>{
    const f=fixture();f.progress();f.window.TaxonomyAnalysisSession={state:()=>f.sessionState};
    const recovery=f.loadRecovery();f.window.__TaxonomyAnalysisSessionContext.runtime.analysisGeneration=generation;
    f.window.TaxonomyScoring.runAnalysis();
    const request=JSON.parse(f.calls[0][1].body);
    f.requests[0].resolve({ok:true,json:async()=>({status,scores:{},reasons:{},warnings:[],analysisOperationId:f.observations[0][0],
        recovery:{id:request.continuationId,version:1,state:status==='PARTIAL'?'PAUSED':'COMPLETED',openQuestions:[]}})});
    await new Promise(resolve=>setImmediate(resolve));
    assert.equal(recovery.isManaged(),true);
    assert.equal(f.state.recoveryContext.id,request.continuationId);
    assert.equal(f.state.recoveryContext.workspaceId,f.sessionState.workspaceId);
    assert.equal(f.state.recoveryContext.request.businessText,request.businessText);
    assert.equal(f.state.recoveryContext.noFollowups,true);assert.equal(f.state.recoveryContext.confirmed,true);
    assert.equal(f.calls.length,1,'A retained result never adds automatic follow-up work');
});


test('automatic request carries taxonomy selection and skips the relation architecture option', () => {
    const selected = {taxonomyRoots: ['BP'], mode: 'TAXONOMIES_ONLY'};
    const f = fixture({analysisScope: selected});
    f.progress();
    f.window.TaxonomyAnalysisSession = {state: () => f.sessionState};
    f.window.TaxonomyScoring.runAnalysis();
    const body = JSON.parse(f.calls[0][1].body);
    assert.deepEqual(body.analysisScope, selected);
    assert.equal(body.includeArchitectureView, false);
});

test('empty taxonomy selection preserves prior evidence and makes no request', () => {
    const f = fixture({invalidScope: true});
    f.progress();
    f.window.TaxonomyAnalysisSession = {state: () => f.sessionState};
    f.window.TaxonomyScoring.runAnalysis();
    assert.equal(f.calls.length, 0);
    assert.equal(f.observations.length, 0);
    assert.equal(f.state.currentReasons.CP, 'retained reason');
    assert.deepEqual(f.statuses, [['warning', 'Select a taxonomy']]);
});

test('continuation keeps its frozen scope when next-run controls change', () => {
    const f = fixture({analysisScope: {taxonomyRoots: ['CP'], mode: 'FULL'}});
    f.progress();
    f.window.TaxonomyAnalysisSession = {state: () => f.sessionState};
    const continuation = {businessText: 'communications', analysisScope: {taxonomyRoots: ['BP'], mode: 'TAXONOMIES_ONLY'},
        continuationId: 'saved', continuationAction: 'RETRY'};
    f.window.TaxonomyScoring.runAnalysis({continuation});
    assert.deepEqual(JSON.parse(f.calls[0][1].body), continuation);
});

for (const previousView of ['list', 'summary']) {
    test(`new Copilot operation clears previous ${previousView} evidence before sending START`, () => {
        const f = fixture(); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.state.currentView = previousView;
        f.state.currentRelationSearchReport = { progress: { verifiedRelations: 4 } };
        f.state.recoveryContext = { id: 'old-operation', workspaceId: f.sessionState.workspaceId,
            request: { businessText: 'old requirement' } };
        f.state.analysisRecovery = { id: 'old-operation', state: 'COMPLETED' };

        f.loadRecovery().startCopilot();

        assert.equal(f.calls.length, 1);
        const posted = JSON.parse(f.calls[0][1].body);
        assert.equal(posted.continuationAction, 'START');
        assert.notEqual(posted.continuationId, 'old-operation');
        assert.equal(posted.continuationId, f.state.recoveryContext.id);
        assert.equal(f.state.currentArchView, null);
        assert.equal(f.state.currentRelationSearchReport, null);
        assert.equal(f.state.currentView, 'list');
        assert.deepEqual({ ...f.state.currentRawScores }, {});
        assert.deepEqual({ ...f.state.currentReasons }, {});
        assert.equal(f.state.currentDiscrepancies.length, 0);
        assert.equal(f.state.currentProductCoverageGaps.length, 0);
        assert.equal(f.window._currentProvisionalRelations.length, 0);
        assert.equal(f.state.lastAnalyzedText, null);
        assert.deepEqual(f.order, ['render', 'monitor', 'post']);
        assert.deepEqual(f.renders[0].scores, {});

        f.observations[0][1]({ rawScores: { BP: 55 } });
        assert.deepEqual({ ...f.state.currentRawScores }, { BP: 55 },
            'the first live result must not merge scores from the old operation');
    });
}

test('repeated START for the same Copilot operation retains accepted evidence', () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    const architecture = f.state.currentArchView;
    const report = { progress: { verifiedRelations: 4 } };
    f.state.currentView = 'summary';
    f.state.currentRelationSearchReport = report;
    f.state.lastAnalyzedText = 'communications';
    f.state.recoveryContext = { id: 'saved-operation', workspaceId: f.sessionState.workspaceId,
        request: { businessText: 'communications' } };
    const continuation = { ...f.state.recoveryContext.request,
        continuationId: 'saved-operation', continuationAction: 'START' };

    f.window.TaxonomyScoring.runAnalysis({ continuation, recoveryManaged: true });

    assert.equal(JSON.parse(f.calls[0][1].body).continuationId, 'saved-operation');
    assert.equal(f.state.currentArchView, architecture);
    assert.equal(f.state.currentRelationSearchReport, report);
    assert.equal(f.state.currentView, 'summary');
    assert.equal(f.state.currentReasons.CP, 'retained reason');
    assert.equal(f.state.lastAnalyzedText, 'communications');
    f.observations[0][1]({ rawScores: { BP: 55 } });
    assert.deepEqual({ ...f.state.currentRawScores }, { CP: 80, BP: 55 },
        'live updates of the same operation must retain its accepted sibling scores');
});


for (const view of ['list', 'tabs', 'tree', 'sunburst', 'graph']) {
    test(`a new ${view} analysis visibly clears stale scores before its monitor and POST`, async () => {
        const f = fixture();
        f.progress();
        f.state.currentView = view;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.renders.length, 1);
        assert.deepEqual(f.renders[0].scores, {});
        assert.deepEqual(f.order, ['render', 'monitor', 'post']);
        const failure = new Error('HTTP 503');
        failure.httpStatus = 503;
        f.rejectRequest(failure);
        await new Promise(resolve => setImmediate(resolve));
        assert.deepEqual(f.renders.at(-1).scores, {});
        assert.equal(f.statuses.at(-1)[0], 'danger');
    });
}


for (const reason of ['restoring', 'conflict', 'invalidating', 'unresolved']) {
    test(`full analysis cannot enter while workspace state is ${reason}`, () => {
        const f = fixture(); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.sessionState.ready = false;
        f.sessionState[reason] = true;
        f.window.TaxonomyScoring.runAnalysis();
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
    });
}

test('new requirement cannot inherit old architecture, gaps, relations or export evidence after rejection', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    function assertClean() {
        assert.equal(f.state.currentDiscrepancies.length, 0);
        assert.equal(f.state.currentProductCoverageGaps.length, 0);
        assert.equal(f.state.currentArchView, null);
        assert.equal(f.state.evaluatedNodes.size, 0);
        assert.equal(f.window._currentProvisionalRelations.length, 0);
        assert.equal(f.state.lastAnalyzedText, null);
        assert.equal(f.state.storedBusinessText, null);
        assert.equal(f.state.lastAnalysisProvider, null);
    }
    assertClean();
    const error = Object.assign(new Error('HTTP 503'), { httpStatus: 503 });
    f.rejectRequest(error);
    await new Promise(resolve => setImmediate(resolve));
    assertClean();
});

for (const workspaceId of [null, '', ' \t', undefined]) {
    test(`read-only or missing workspace (${JSON.stringify(workspaceId)}) cannot admit a workspace-scoped analysis`, () => {
        const f = fixture(); f.progress();
        f.sessionState.workspaceId = workspaceId;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
        assert.deepEqual(f.statuses, [['warning', 'guard.blocked.readonly']]);
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}


for (const [name, crypto] of [
    ['absent', undefined],
    ['null', null],
    ['missing both methods', {}],
    ['non-callable methods', { randomUUID: true, getRandomValues: true }],
    ['disabled random UUID', { randomUUID() { throw new Error('disabled'); } }],
    ['disabled random values', { getRandomValues() { throw new Error('disabled'); } }]
]) {
    test(`unavailable secure operation IDs (${name}) report a failure without starting or clearing anything`, () => {
        const f = fixture({ crypto }); f.progress();
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
        assert.deepEqual(f.statuses, [['danger', 'scoring.secure.id.unavailable']]);
        assert.equal(f.calls.length, 0);
        assert.equal(f.observations.length, 0);
        assert.equal(f.renders.length, 0);
        assert.equal(f.state.currentArchView.title, 'old architecture');
        assert.equal(f.state.currentReasons.CP, 'retained reason');
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}

test('secure random-values fallback supplies the same canonical version-four ID to monitor and POST', () => {
    const f = fixture({ crypto: { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) } }); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    assert.doesNotThrow(() => f.window.TaxonomyScoring.runAnalysis());
    assert.equal(f.calls.length, 1);
    const id = new Headers(f.calls[0][1].headers).get('X-Analysis-Operation-Id');
    assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    assert.equal(id, f.observations[0][0]);
});


// Use actual catalogue identities and roles, never a fabricated taxonomy.
const liveCatalogue = JSON.parse(readFileSync(new URL(
    '../../taxonomy-knowledge/src/main/resources/data/nato-taxonomy.json', import.meta.url), 'utf8'));
const liveProduct = liveCatalogue.nodePatches.find(node => node.analysisRole === 'PRODUCT'
    && liveCatalogue.nodePatches.some(parent => parent.code === node.parentCode));
assert.ok(liveProduct, 'Expected a real product with its real family in the catalogue');
const liveFamily = liveCatalogue.nodePatches.find(node => node.code === liveProduct.parentCode);
const unresolvedProviderKey = '__unresolved_provider_key__';

for (const view of ['list', 'tabs', 'tree', 'sunburst', 'graph']) {
    test(`live ${view} scores preserve unresolved provider evidence without interrupting known scores`, () => {
        const f = fixture(); f.progress();
        f.state.taxonomyData = [liveFamily, liveProduct];
        f.state.currentView = view;
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        f.window.TaxonomyScoring.runAnalysis();
        const receive = f.observations[0][1];
        assert.doesNotThrow(() => receive({ rawScores: {
            [unresolvedProviderKey]: 90, [liveFamily.code]: 40, [liveProduct.code]: 80
        } }));
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
        assert.equal(f.state.currentScores[liveProduct.code], 32);
        const detail = f.state.currentScoreDetails[unresolvedProviderKey];
        assert.equal(detail.kind, 'HIERARCHICAL_RELEVANCE');
        assert.equal(detail.rawScore, 90);
        assert.equal(detail.effectiveRelevance, 90);
        assert.equal(detail.parentCode, null);
        assert.equal(detail.parentScore, null);
        assert.ok(f.state.currentScoreSemanticsWarnings.some(w => w.includes(unresolvedProviderKey)));
        assert.doesNotThrow(() => receive({ rawScores: {
            [liveFamily.code]: 50, [liveProduct.code]: 70, [unresolvedProviderKey]: 5
        } }));
        assert.equal(f.state.currentScores[liveProduct.code], 35);
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 5);
        assert.equal(f.calls.length, 1, 'Status updates must not restart analysis');
    });
}

for (const transport of ['POST', 'resume']) {
    test(`${transport} progress marks locally derived product relevance as pending until its family is evaluated`, async () => {
        const f = fixture(); f.progress();
        f.state.taxonomyData = [liveFamily, liveProduct];
        f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
        if (transport === 'POST') {
            f.window.TaxonomyScoring.runAnalysis();
            assert.equal(f.calls[0][0], '/api/analyze?workspaceId=workspace-a');
        } else {
            f.window.TaxonomyAnalysisSessionApi = { getRunInput: async () => ({ json: async () => ({
                operationId: 'saved-operation', businessText: 'communications', scope: { workspaceId: 'workspace-a' }
            }) }) };
            await f.window.TaxonomyScoring.resumeAnalysis('saved-operation');
            assert.equal(f.calls.length, 0, 'Resuming observes the saved run without a new analysis request');
        }
        const receive = f.observations[0][1];
        receive({ rawScores: { [liveProduct.code]: 80 } });
        assert.equal(f.state.currentRawScores[liveProduct.code], 80);
        assert.equal(f.state.currentScores[liveProduct.code], 0);
        assert.equal(f.state.currentScoreDetails[liveProduct.code].effectiveRelevance, null);
        assert.match(f.window.TaxonomyScoring.describeScore(liveProduct.code, 0).label, /pending/);
        receive({ rawScores: { [liveProduct.code]: 80, [liveFamily.code]: 40 } });
        assert.equal(f.state.currentScores[liveProduct.code], 32);
        assert.equal(f.state.currentScoreDetails[liveProduct.code].effectiveRelevance, 32);
        receive({ rawScores: { [liveProduct.code]: 80, [liveFamily.code]: 0 } });
        assert.equal(f.state.currentScoreDetails[liveProduct.code].effectiveRelevance, 0);
        assert.doesNotMatch(f.window.TaxonomyScoring.describeScore(liveProduct.code, 0).label, /pending/);
    });
}

test('unresolved-provider diagnostics are bounded without dropping typed score evidence', () => {
    const f = fixture(); f.progress();
    f.state.taxonomyData = [liveFamily, liveProduct];
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    const scores = Object.fromEntries(Array.from({ length: 200 }, (_, i) => ['unresolved-provider-' + i, 50]));
    assert.doesNotThrow(() => f.observations[0][1]({ rawScores: scores }));
    assert.equal(Object.keys(f.state.currentRawScores).length, 200);
    assert.equal(Object.keys(f.state.currentScoreDetails).length, 200);
    assert.equal(f.state.currentScoreSemanticsWarnings.length, 101);
    assert.equal(f.state.currentScoreSemanticsWarnings.at(-1), 'Additional score-semantics warnings were suppressed.');
});

test('expert-entered raw scores still reject unknown catalogue identities', () => {
    const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
    assert.throws(() => f.window.TaxonomyScoring.applyLocalRawScores(
        { [unresolvedProviderKey]: 90 }, true), /Cannot resolve taxonomy score/);
});


test('lost POST uses bounded original-operation cleanup instead of premature terminal finish', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    f.rejectRequest(new TypeError('network connection lost'));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(f.progressActions, ['transportFailed']);
    assert.equal(f.calls.length, 1);
});

test('rejected HTTP admission finishes normally without a cleanup cancellation', async () => {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    f.window.TaxonomyScoring.runAnalysis();
    f.rejectRequest(Object.assign(new Error('HTTP 503'), { httpStatus: 503 }));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(f.progressActions, ['finish']);
});


function readyFixture() {
    const f = fixture(); f.progress();
    f.window.TaxonomyAnalysisSession = { state: () => f.sessionState };
    return f;
}
const flushResults = () => new Promise(resolve => setImmediate(resolve));
const completedResult = (scores = {}) => ({ status: 'SUCCESS', scores, reasons: {}, warnings: [] });

test('live analysis exposes whole reasons and refreshes a reason-only update before completion', () => {
    const f = readyFixture();
    f.state.currentView = 'decision';
    f.window.TaxonomyScoring.runAnalysis();
    const receive = f.observations[0][1];
    receive({ rawScores: { CP: 80 }, reasons: { CP: 'Saved root reason\nComplete final line.' } });
    assert.equal(f.state.currentReasons.CP, 'Saved root reason\nComplete final line.');
    const count = f.renders.length;
    receive({ rawScores: { CP: 80 }, reasons: { CP: 'Updated saved root reason' } });
    assert.equal(f.state.currentReasons.CP, 'Updated saved root reason');
    assert.equal(f.renders.length, count + 1, 'A reason-only update must refresh its diagram evidence reference');
    for (const reasons of [undefined, {}, { CP: null }, { CP: '   ' }]) {
        receive({ rawScores: { CP: 80 }, reasons });
        assert.equal(f.state.currentReasons.CP, 'Updated saved root reason');
    }
    assert.equal(f.state.lastAnalysisStatus, 'IN_PROGRESS');
    assert.equal(f.calls.length, 1, 'Showing existing reasons does not request another assessment');
});

test('authoritative terminal reasons replace the live preview and reject late progress', async () => {
    const f = readyFixture();
    f.state.currentView = 'decision';
    f.window.TaxonomyScoring.runAnalysis();
    const receive = f.observations[0][1];
    receive({ rawScores: { CP: 80, BP: 10 }, reasons: { CP: 'Preview reason', BP: 'Preview only' } });
    assert.equal(f.state.currentReasons.BP, 'Preview only');
    f.requests[0].resolve({ ok: true, json: async () => ({ ...completedResult({ CP: 80 }),
        reasons: { CP: 'Authoritative saved reason' } }) });
    await flushResults();
    assert.equal(f.state.currentReasons.CP, 'Authoritative saved reason');
    assert.equal(f.state.currentReasons.BP, undefined);
    receive({ rawScores: { CP: 80 }, reasons: { CP: 'Late preview reason' } });
    assert.equal(f.state.currentReasons.CP, 'Authoritative saved reason');
    assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
});

test('a superseded analysis cannot publish its reasons into the next analysis', () => {
    const f = readyFixture();
    f.window.TaxonomyScoring.runAnalysis();
    const oldReceive = f.observations[0][1];
    f.window.TaxonomyScoring.runAnalysis();
    f.observations[1][1]({ rawScores: { CP: 50 }, reasons: { CP: 'Current run reason' } });
    oldReceive({ rawScores: { CP: 80 }, reasons: { CP: 'Superseded run reason' } });
    assert.equal(f.state.currentReasons.CP, 'Current run reason');
    assert.equal(f.state.currentScores.CP, 50);
});

for (const view of ['list', 'tabs']) {
    test(`live ${view} updates recompute an active relevance filter`, () => {
        const f = readyFixture();
        f.state.currentView = view;
        f.window.TaxonomyBrowse.isRelevanceFilterActive = () => true;
        f.window.TaxonomyScoring.runAnalysis();
        const before = f.renders.length;
        f.observations[0][1]({ rawScores: { CP: 80 }, reasons: { CP: 'Recorded reason' } });
        assert.equal(f.renders.length, before + 1, 'Newly relevant nodes must enter the filtered result');
    });
}

for (const ending of ['success', 'network failure', 'HTTP rejection']) {
    test(`superseded full-analysis ${ending} cannot overwrite a newer run`, async () => {
        const f = readyFixture();
        f.window.TaxonomyScoring.runAnalysis();
        f.window.TaxonomyScoring.runAnalysis();
        const count = f.renders.length;
        if (ending === 'success') f.requests[0].resolve({ ok: true, json: async () => completedResult({ CP: 90 }) });
        else f.requests[0].reject(Object.assign(new Error('obsolete failure'),
            ending === 'HTTP rejection' ? { httpStatus: 503 } : {}));
        await flushResults();
        assert.equal(f.state.lastAnalysisStatus, 'IN_PROGRESS');
        assert.equal(f.state.currentRawScores.CP, undefined);
        assert.equal(f.renders.length, count);
        assert.equal(f.statuses.length, 0);
        assert.equal(f.progressActions.length, 0);
        f.requests[1].resolve({ ok: true, json: async () => completedResult({ CP: 40 }) });
        await flushResults();
        assert.equal(f.state.currentRawScores.CP, 40);
        assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    });
}

for (const ending of ['success', 'failure']) {
    test(`full-analysis ${ending} after workspace invalidation does not change the new context`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.sessionState.workspaceId = 'workspace-b';
        const count = f.renders.length;
        if (ending === 'success') f.requests[0].resolve({ ok: true, json: async () => completedResult({ CP: 90 }) });
        else f.requests[0].reject(new Error('obsolete error'));
        await flushResults();
        assert.equal(f.renders.length, count);
        assert.equal(f.state.currentRawScores.CP, undefined);
        assert.equal(f.statuses.length, 0);
        assert.equal(f.progressActions.length, 0);
    });
}

for (const [status, body, expected] of [
    [400, { error: 'Unknown provider: unavailable' }, 'Unknown provider: unavailable'],
    [403, { detail: 'Full analysis requires an isolated workspace' }, 'Full analysis requires an isolated workspace'],
    [503, { message: 'Taxonomy data is still loading' }, 'Taxonomy data is still loading'],
    [503, { detail: '', error: 'Analysis capacity exhausted' }, 'Analysis capacity exhausted'],
    [503, { detail: 'x'.repeat(5000) }, 'x'.repeat(1024)]
]) {
    test(`full-analysis HTTP ${status} retains bounded server diagnostic ${JSON.stringify(body).slice(0, 70)}`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.requests[0].resolve({ ok: false, status, json: async () => body });
        await flushResults();
        assert.equal(f.state.lastAnalysisStatus, 'ERROR');
        assert.equal(f.statuses.at(-1)[1], 'scoring.analysis.error: ' + expected);
        assert.deepEqual(f.progressActions, ['finish']);
    });
}

for (const body of [null, {}, { detail: { internal: 'not a user-facing string' } }, 'invalid-json']) {
    test(`HTTP failure keeps numeric fallback for absent or malformed diagnostics ${JSON.stringify(body)}`, async () => {
        const f = readyFixture(); f.window.TaxonomyScoring.runAnalysis();
        f.requests[0].resolve({ ok: false, status: 502, json: async () => {
            if (body === 'invalid-json') throw new SyntaxError('invalid JSON');
            return body;
        } });
        await flushResults();
        assert.equal(f.statuses.at(-1)[1], 'scoring.analysis.error: HTTP 502');
        assert.deepEqual(f.progressActions, ['finish']);
    });
}

for (const replace of [false, true]) {
    test(`valid manual score edits remain possible after unresolved provider evidence (replace=${replace})`, () => {
        const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
        const api = f.window.TaxonomyScoring;
        api.applyLocalRawScores({ [liveFamily.code]: 40, [liveProduct.code]: 80, [unresolvedProviderKey]: 90 }, true, true);
        assert.doesNotThrow(() => api.applyLocalRawScores({ [liveFamily.code]: 50 }, replace));
        assert.equal(f.state.currentRawScores[liveFamily.code], 50);
        if (!replace) {
            assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
            assert.equal(f.state.currentProductSuitabilityScores[liveProduct.code], 80);
            assert.equal(f.state.currentScores[liveProduct.code], 40);
            assert.ok(f.state.currentScoreSemanticsWarnings.some(w => w.includes(unresolvedProviderKey)));
        } else assert.equal(f.state.currentRawScores[unresolvedProviderKey], undefined);
    });
}

test('retained provider evidence does not authorize newly entered unknown keys', () => {
    const f = fixture(); f.state.taxonomyData = [liveFamily, liveProduct];
    const api = f.window.TaxonomyScoring;
    api.applyLocalRawScores({ [liveFamily.code]: 40, [unresolvedProviderKey]: 90 }, true, true);
    for (const key of [unresolvedProviderKey, '__new_unknown_key__']) {
        assert.throws(() => api.applyLocalRawScores({ [key]: 10 }, false), /Cannot resolve taxonomy score/);
        assert.equal(f.state.currentRawScores[unresolvedProviderKey], 90);
        assert.equal(f.state.currentRawScores.__new_unknown_key__, undefined);
    }
});


test('an unconfirmed analysis session blocks leaf-justification model requests with retained provider bindings', () => {
    const f = fixture();
    f.window.TaxonomyAnalysisSession = { state: () => ({ ready: false }) };
    f.state.analysisProviderId = 'GEMMA';
    const button = { textContent: 'Explain', disabled: false };
    f.window.TaxonomyScoring.requestLeafJustification('CP', button);
    assert.equal(f.calls.length, 0);
    assert.equal(button.disabled, false);
});


test('main HTTP completion survives the production generation and operation transport adapters', async () => {
    const f = readyFixture();
    const C = f.window.__TaxonomyAnalysisSessionContext = { S: f.state,
        runtime: {workspaceId: 'workspace-a', analysisGeneration: 1}, language: () => 'en',
        requestUrl: input => new URL(typeof input === 'string' ? input : input.url, 'https://example.test/'),
        installWorkspaceFetchRouting() {}, installWorkspaceEventSourceRouting() {} };
    f.window.setInterval = () => 1; f.window.clearInterval = () => {};
    f.window.setTimeout = () => 1; f.window.clearTimeout = () => {};
    for (const file of ['taxonomy-analysis-session-transport.js', 'taxonomy-operation-coordinator.js']) {
        vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/' + file, import.meta.url), 'utf8'), f.sandbox);
    }
    const pending = f.window.TaxonomyScoring.runAnalysis();
    f.requests[0].resolve(Response.json({...completedResult({CP: 80}), analysisDurationMillis: 1200}));
    await pending;
    assert.equal(C.runtime.activeAnalysisControllers.size, 0);
    assert.equal(f.state.lastAnalysisStatus, 'SUCCESS');
    assert.equal(f.state.lastAnalysisDurationMillis, 1200);
    assert.equal(f.state.currentScores.CP, 80);
});



for (const change of ['branch', 'commit']) {
    test(`an accepted ${change} navigation still rejects the old main analysis response after startup`, async () => {
        const f = readyFixture();
        f.window.__TaxonomyAnalysisSessionContext = {S: f.state,
            runtime: {workspaceId: 'workspace-a', analysisGeneration: 1}};
        for (const file of ['api/workspace-api.js', 'versioning/taxonomy-context-bar.js']) {
            vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/' + file, import.meta.url), 'utf8'), f.sandbox);
        }
        const bar = f.window.TaxonomyContextBar;
        const initial = bar.fetchAndRender('absent-context-container');
        f.requests[0].resolve(Response.json({contextId: 'initial', branch: 'draft', commitId: 'a'.repeat(40)}));
        await initial;
        const pending = f.window.TaxonomyScoring.runAnalysis();
        const branch = change === 'branch' ? 'other' : 'draft';
        const commit = change === 'commit' ? 'b'.repeat(40) : 'a'.repeat(40);
        const navigation = bar.open(branch, commit, true);
        await flushResults();
        f.requests[2].resolve(Response.json({contextId: 'new-selection', branch, commitId: commit}));
        await navigation;
        f.state.lastAnalysisStatus = 'IMPORTED'; f.state.lastAnalysisDurationMillis = 700;
        f.state.currentScores = {BP: 25};
        const rendered = f.renders.length;
        f.requests[1].resolve(Response.json({...completedResult({CP: 80}), analysisDurationMillis: 1200}));
        await pending;
        assert.equal(f.state.lastAnalysisStatus, 'IMPORTED');
        assert.equal(f.state.lastAnalysisDurationMillis, 700);
        assert.deepEqual(f.state.currentScores, {BP: 25});
        assert.equal(f.renders.length, rendered);
    });
}


for (const restored of ['PARTIAL', 'stale']) {
    test(`source confirmation updates the real Guidance controls for a restored ${restored} draft`, async () => {
        const f = fixture(), fields = new Map(), listeners = new Map();
        let focused, starts = 0;
        const field = id => {
            if (!fields.has(id)) fields.set(id, {value: '', textContent: '', hidden: false, disabled: false,
                setAttribute() {}, addEventListener(type, action) {this[type] = action;},
                scrollIntoView() {}, focus() {focused = id;}});
            return fields.get(id);
        };
        field('businessText').value = restored === 'stale' ? 'New requirement' : 'Original requirement';
        field('relationSearchReportPanel').hidden = true;
        field('analyzeBtn').click = () => starts++;
        Object.assign(f.state, {lastAnalysisStatus: restored === 'PARTIAL' ? 'PARTIAL' : 'SUCCESS',
            lastAnalyzedText: 'Original requirement'});
        f.sessionState.ready = false;
        f.window.TaxonomyAnalysisSession = {state: () => f.sessionState};
        f.window.TaxonomyAnalysisSessionReady = Promise.resolve(true);
        f.window.TaxonomyI18n.ready = () => Promise.resolve();
        Object.assign(f.sandbox.document, {readyState: 'complete', getElementById: field,
            addEventListener(type, action) {if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(action);}});
        f.sandbox.MutationObserver = class {observe() {}};
        vm.runInContext(readFileSync(new URL('../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-guidance.js', import.meta.url), 'utf8'), f.sandbox);
        await flushResults();
        const control = field(restored === 'PARTIAL' ? 'analysisClarifyAction' : 'analysisReanalyzeAction');
        assert.equal(control.hidden, false); assert.equal(control.disabled, true);
        f.sessionState.ready = true;
        for (const action of listeners.get('taxonomy:analysis-source-ready') || []) action({detail: {workspaceId: 'workspace-a'}});
        assert.equal(control.disabled, false);
        control.click();
        if (restored === 'PARTIAL') assert.equal(focused, 'statusArea');
        else assert.equal(starts, 1);
        assert.equal(f.calls.length, 0, 'Readiness notification does not start analysis automatically');
    });
}
