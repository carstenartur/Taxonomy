import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const root = new URL('../../taxonomy-app/src/main/resources/static/js/core/', import.meta.url);
const sources = Object.fromEntries(await Promise.all([
  'taxonomy-analysis-session-core.js', 'taxonomy-analysis-session-draft.js', 'taxonomy-analysis.js'
].map(async name => [name, await readFile(new URL(name, root), 'utf8')])));
const clone = value => JSON.parse(JSON.stringify(value));
const flush = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve; let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fixture(language = 'en') {
  const elements = new Map(); const listeners = new Map(); const requests = []; const alerts = []; const draftWrites = [];
  const draftControl = {};
  function element(id) {
    const classes = new Set(); const attributes = new Map();
    return { id, value: '', style: {}, dataset: {}, innerHTML: '', textContent: '', children: [],
      classList: { add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value),
        toggle(value, enabled) { if (enabled) classes.add(value); else classes.delete(value); } },
      setAttribute: (name, value) => attributes.set(name, String(value)), getAttribute: name => attributes.get(name),
      hasAttribute: name => attributes.has(name), removeAttribute: name => attributes.delete(name),
      addEventListener() {}, appendChild(child) { this.children.push(child); }, append(...children) { this.children.push(...children); },
      replaceChildren(...children) { this.children = children; }, remove() {}, querySelectorAll() { return []; },
      querySelector() { return null; }, focus() {} };
  }
  const document = { documentElement: { lang: language }, body: element('body'),
    getElementById(id) { if (!elements.has(id)) elements.set(id, element(id)); return elements.get(id); },
    querySelector() { return null; }, querySelectorAll() { return []; }, createElement: element,
    addEventListener(type, listener) { if (!listeners.has(type)) listeners.set(type, []); listeners.get(type).push(listener); },
    dispatchEvent(event) { for (const listener of listeners.get(event.type) || []) listener(event); }
  };
  const state = { taxonomyData: [{ code: 'BP', children: [] }], currentScores: null, currentReasons: {},
    currentRawScores: {}, currentEffectiveScores: {}, currentArchView: null, lastAnalyzedText: null,
    lastAnalysisProvider: null, lastAnalysisStatus: null, lastAnalysisDurationMillis: null, evaluatedNodes: new Set(), currentView: 'list' };
  const i18n = { t: (key, ...args) => key + (args.length ? ': ' + args.join(', ') : '') };
  const utils = { escapeHtml: value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;') };
  const window = { TaxonomyState: state, TaxonomyI18n: i18n, TaxonomyUtils: utils,
    location: { origin: 'https://taxonomy.example', href: 'https://taxonomy.example/' },
    setTimeout() { return 1; }, clearTimeout() {}, requestAnimationFrame: callback => callback(),
    TaxonomyBrowse: { renderView() {}, updateExportGroupVisibility() {} },
    TaxonomyScoring: { renderArchitectureView() {}, renderSuggestedRelations() {} },
    TaxonomyAnalysisSessionApi: { async request(path, options, workspaceId) {
      requests.push({ path, options, workspaceId });
      return { ok: true, json: async () => clone(snapshot) };
    } }
  };
  const sandbox = vm.createContext({ window, document, console, Set, Date, JSON, URL, Headers, Request,
    TaxonomyI18n: i18n, TaxonomyUtils: utils, CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } },
    fetch: async (path, options) => { requests.push({ path, options }); throw new Error('Unexpected browser calculation request'); }
  });
  vm.runInContext(sources['taxonomy-analysis-session-core.js'], sandbox);
  const C = window.__TaxonomyAnalysisSessionContext;
  Object.assign(C.runtime, { workspaceId: 'ws-1', workspaceResolved: true, analysisGeneration: 1 });
  C.showActionAlert = (kind, title, body, actions, marker) => alerts.push({ kind, title, body, actions, marker });
  C.showStaleActions = () => {};
  C.jsonRequest = async (_path, options) => {
    const body=JSON.parse(options.body);draftWrites.push(body);
    if(draftControl.write)return draftControl.write(body);
    return {version:(body.expectedVersion??0)+1,payload:body.payload};
  };
  vm.runInContext(sources['taxonomy-analysis-session-draft.js'], sandbox);
  vm.runInContext(sources['taxonomy-analysis.js'], sandbox);
  const input = elements.get('businessText') || document.getElementById('businessText');
  let snapshot;
  function canonical({ continuation = true, large = false } = {}) {
    const result = { status: 'SUCCESS', provider: 'LOCAL', analysisProviderId: 'LOCAL', analysisOperationId: 'operation-1',
      scores: { BP: 85 }, rawScores: { BP: 85 }, effectiveScores: { BP: 85 }, reasons: { BP: large ? 'e'.repeat(2_100_000) : 'Retained assessment' },
      recovery: continuation ? { id: 'continuation-1', version: 9, state: 'COMPLETED', openQuestions: [] } : null,
      tree: [{ code: 'BP', name: 'Retained root', children: [] }], copilot: report() };
    snapshot = { request: { businessText: 'Original requirement', provider: 'LOCAL' }, result, recovery: result.recovery };
    return snapshot;
  }
  async function install(options = {}) {
    canonical(options);
    const payload = await C.resolveAnalysisReference({ schemaVersion: 1, draftState: 'ACTIVE', businessText: 'Original requirement', currentView: 'list',
      analysisReference: { workspaceId: 'ws-1', continuationId: 'continuation-1', recoveryVersion: 9, operationId: 'operation-1' } });
    C.applyDraft({ version: 1, payload: Object.assign({}, payload, { analysisReference: undefined }) });
    C.retainAnalysisReference(snapshot.result, snapshot.request);
    return snapshot;
  }
  return { window, document, sandbox, C, state, input, elements, requests, alerts, draftWrites, draftControl, canonical, install,
    edit(value) { input.value = value; document.dispatchEvent({ type: 'input', target: input }); },
    html(id) { return document.getElementById(id).innerHTML; } };
}
function report(state = 'COMPLETED') {
  return { schemaVersion: 2, state,
    gaps: { profileId:'compatibility', profileVersion:'1.0.0', totalAnchors: 0, totalGaps: 0, judgments: [], notes: [] },
    patterns: { matchedPatterns: [], incompletePatterns: [], patternCoverage: 0, notes: [] },
    recommendation: { matchedElements: [], acceptedElements: [], proposedElements: [], suggestedRelations: [], reasoning: [], meanRelevance: null },
    notes: ['Retained evidence is bounded; human review remains necessary.'] };
}
function referencePayload(extra = {}) {
  return { schemaVersion: 1, draftState: 'ACTIVE', businessText: 'Edited requirement', currentView: 'tree',
    analysisReference: { workspaceId: 'ws-1', continuationId: 'continuation-1', recoveryVersion: 9, operationId: 'operation-1' }, ...extra };
}

test('managed historical Copilot never invokes browser report calculations', async () => {
  const f = fixture(); f.state.analysisOperationId = 'old-operation'; f.state.currentScores = { BP: 80 };
  await f.window.TaxonomyAnalysis.runCopilotFlow();
  assert.equal(f.requests.length, 0); assert.match(f.html('copilotContent'), /historical result/i);
});
test('multi-megabyte owned canonical result is saved as a bounded reference while edited input survives', async () => {
  const f = fixture(); await f.install({ large: true }); f.edit('Changed input');
  const payload = f.C.currentPayload();
  assert.ok(JSON.stringify(f.C.currentPayload(true)).length > 2_000_000);
  assert.ok(JSON.stringify(payload).length < 5000);
  assert.equal(payload.analysisReference.continuationId, 'continuation-1');
  assert.equal(payload.businessText, 'Changed input'); assert.equal(payload.reasons, undefined);
});
for (const [name, edit] of [
  ['score', f => { f.state.currentScores.BP = 30; }],
  ['reason', f => { f.state.currentReasons.BP = 'Manual rationale'; }],
  ['legacy checkpoint', f => { f.state.recoveryContext.followups = { gap: { state: 'SUCCESS', result: {} } }; }],
  ['retained architecture', f => { f.state.currentArchView = { nodes: [{ code: 'manual' }] }; }],
  ['independent relation report', f => { f.state.currentRelationSearchReport = { retained: true }; }]
]) test(`manual ${name} remains a full draft instead of being discarded into a reference`, async () => {
  const f = fixture(); await f.install(); edit(f);
  assert.equal(f.C.currentPayload().analysisReference, undefined);
  assert.ok(f.C.currentPayload().reasons);
});
test('reference restoration hydrates authorized server result and retains stale edited text and selected view', async () => {
  const f = fixture(); const saved = f.canonical();
  saved.result.modelBinding = { state: 'CATALOGUE_UNRESOLVED', catalogueRevision: null, executionCatalogueRevision: 'executed-2' };
  await f.C.applyDraft({ version: 2, payload: referencePayload() });
  assert.deepEqual(clone(f.C.currentPayload(true).modelBinding), saved.result.modelBinding);
  assert.equal(f.requests[0].workspaceId, 'ws-1'); assert.match(f.requests[0].path, /continuation-1$/);
  assert.equal(f.input.value, 'Edited requirement'); assert.equal(f.state.lastAnalyzedText, 'Original requirement');
  assert.equal(f.state.currentView, 'tree'); assert.equal(f.C.isStale(), true);
  assert.equal(f.state.taxonomyData[0].name, 'Retained root'); assert.equal(f.state.currentCopilot.state, 'COMPLETED');
});
for (const [name, change] of [
  ['typing', f => f.edit('Keep typing')],
  ['workspace', f => { f.C.runtime.workspaceId = 'ws-2'; }],
  ['reset', f => { f.C.runtime.analysisGeneration++; }],
  ['newer draft', f => f.C.applyDraft({ version: 3, payload: { businessText: 'Newer draft', scores: { BP: 20 }, draftState: 'ACTIVE' } })]
]) test(`late reference read cannot overwrite ${name}`, async () => {
  const f = fixture(); f.canonical(); const pending = deferred();
  const delegate = f.window.TaxonomyAnalysisSessionApi.request;
  f.window.TaxonomyAnalysisSessionApi.request = async (...args) => { await pending.promise; return delegate(...args); };
  const restoring = f.C.applyDraft({ version: 2, payload: referencePayload() }); await flush();
  change(f); const before = f.input.value; pending.resolve(); await restoring;
  assert.equal(f.input.value, before); assert.notEqual(f.state.currentScores?.BP, 85);
});
for (const [name, change] of [
  ['score', f => { f.state.currentScores.BP = 33; }],
  ['reason', f => { f.state.currentReasons.BP = 'Local edit'; }],
  ['view', f => { f.state.currentView = 'tabs'; }],
  ['language option', f => { f.state.activeAnalysisLanguage = 'de'; }]
]) test(`manual ${name} changed during reference retrieval is retained for an explicit resume choice`, async () => {
  const f = fixture(); await f.install(); const pending = deferred();
  const delegate = f.window.TaxonomyAnalysisSessionApi.request;
  f.window.TaxonomyAnalysisSessionApi.request = async (...args) => { await pending.promise; return delegate(...args); };
  const restoring = f.C.applyDraft({ version: 2, payload: referencePayload() }); await flush();
  change(f); const before = f.C.comparable(f.C.currentPayload(true)); pending.resolve(); await restoring;
  assert.equal(f.C.comparable(f.C.currentPayload(true)), before);
  assert.equal(f.alerts.at(-1).marker, 'resume-choice');
});
for (const kind of ['foreign', 'missing']) test(`${kind} reference is an explicit load error and preserves existing work`, async () => {
  const f = fixture(); await f.install(); f.edit('Keep local work');
  const payload = referencePayload();
  if (kind === 'foreign') payload.analysisReference.workspaceId = 'other-workspace';
  else f.window.TaxonomyAnalysisSessionApi.request = async () => ({ ok: false, status: 404 });
  await f.C.applyDraft({ version: 2, payload });
  assert.equal(f.input.value, 'Keep local work'); assert.equal(f.state.currentScores.BP, 85);
  assert.equal(f.C.runtime.serverUnconfirmed, true); assert.match(f.alerts.at(-1).title, /unconfirmed/);
});
test('an ephemeral local operation id cannot replace evidence with a durable reference', async () => {
  const f = fixture(); await f.install();
  f.C.runtime.analysisReference = null; f.C.runtime.analysisEvidence = null;
  const snapshot = f.canonical({ continuation: false }); f.state.analysisRecovery = null;
  f.C.retainAnalysisReference(snapshot.result, snapshot.request);
  assert.equal(f.C.currentPayload().analysisReference, undefined);
});
for (const state of ['COMPLETED', 'PARTIAL', 'SKIPPED_PARTIAL', 'SKIPPED_SCOPE']) {
  test(`canonical ${state} reports render without calculation requests or false success`, async () => {
    const f = fixture(); f.state.analysisOperationId = 'run'; f.state.currentCopilot = report(state);
    await f.window.TaxonomyAnalysis.runCopilotFlow();
    assert.equal(f.requests.length, 0); assert.match(f.html('copilotContent'), /human review/);
    if (state !== 'COMPLETED') assert.doesNotMatch(f.html('copilotContent'), /analyze.copilot.complete/);
  });
}
test('an EMPTY tombstone does not dereference an attached stale reference', async () => {
  const f = fixture(); f.canonical();
  await f.C.applyDraft({ version: 2, payload: referencePayload({ draftState: 'EMPTY' }) });
  assert.equal(f.requests.length, 0); assert.equal(f.input.value, ''); assert.equal(f.state.currentScores, null);
});
test('obsolete browser checkpoints do not become a canonical assessment or trigger a recalculation', () => {
  const f = fixture(); f.state.recoveryContext = { followups: { gap: { state: 'SUCCESS', result: report().gaps } } };
  f.window.TaxonomyAnalysis.renderCopilotResult(null);
  assert.match(f.html('copilotContent'), /historical result/); assert.doesNotMatch(f.html('copilotContent'), /analyze.copilot.complete/);
  assert.doesNotMatch(f.html('gapAnalysisContent'), /bg-success/); assert.equal(f.requests.length, 0);
});
test('clearing editable input does not make a referenced analysis an empty unsaved draft', async () => {
  const f = fixture(); await f.install(); f.edit(''); const payload = f.C.currentPayload();
  assert.equal(payload.draftState, 'ACTIVE'); assert.equal(f.C.meaningful(payload), true);
});
for (const language of ['en', 'de']) test(`${language}: retained zero-anchor checks do not claim architectural completeness`, () => {
  const f = fixture(language); f.window.TaxonomyAnalysis.renderCopilotResult(report());
  assert.match(f.html('gapAnalysisContent'), /analysis.evidence.noObligations/);
  assert.doesNotMatch(f.html('gapAnalysisContent'), /analyze.gap.no.gaps|bg-success/);
  assert.match(f.html('copilotContent'), /human review/);
});
test('retained connected pattern evidence displays concrete steps and applicability warnings', () => {
  const f = fixture(); const value = report();
  value.patterns.incompletePatterns = [{ patternName: 'Connected fixture', completeness: 75,
    expectedSteps: ['BP REALIZES CP'], presentSteps: ['BP-fixture REALIZES CP-fixture [when approved]'],
    missingSteps: ['Applicability still requires review.'] }];
  f.window.TaxonomyAnalysis.renderCopilotResult(value);
  const html = f.html('patternDetectionContent');
  assert.match(html, /BP-fixture REALIZES CP-fixture \[when approved\]/); assert.match(html, /Applicability still requires review/);
  assert.doesNotMatch(html, /❌ BP REALIZES CP/);
});

test('durable cluster result restores through its scoped input and result APIs and saves as a reference', async () => {
  const f = fixture(); const saved = f.canonical({ continuation: false }); const calls = [];
  f.window.TaxonomyAnalysisSessionApi.getRunInput = async (id, scope) => {
    calls.push({ id, scope, kind: 'input' });
    return { ok: true, json: async () => ({ ...saved.request, operationId: id, scope: { workspaceId: 'ws-1' } }) };
  };
  f.window.TaxonomyAnalysisSessionApi.getRunResult = async (id, scope) => {
    calls.push({ id, scope, kind: 'result' }); return { ok: true, json: async () => clone(saved.result) };
  };
  await f.C.applyDraft({ version: 2, payload: referencePayload({
    analysisReference: { workspaceId: 'ws-1', operationId: 'operation-1', continuationId: null } }) });
  assert.deepEqual(calls.map(call => [call.kind, call.id, call.scope.workspaceId]),
    [['input', 'operation-1', 'ws-1'], ['result', 'operation-1', 'ws-1']]);
  assert.equal(f.state.currentScores.BP, 85); assert.equal(f.input.value, 'Edited requirement');
  assert.equal(f.C.currentPayload().analysisReference.operationId, 'operation-1');
  assert.equal(f.C.currentPayload().reasons, undefined);
});
test('a durable run input from a different workspace cannot hydrate or request the result', async () => {
  const f = fixture(); f.input.value = 'Local work'; let resultCalls = 0;
  f.window.TaxonomyAnalysisSessionApi.getRunInput = async () => ({ ok: true,
    json: async () => ({ operationId: 'operation-1', scope: { workspaceId: 'other-workspace' }, businessText: 'Other work' }) });
  f.window.TaxonomyAnalysisSessionApi.getRunResult = async () => { resultCalls++; throw new Error('Unexpected result read'); };
  await f.C.applyDraft({ version: 2, payload: referencePayload({
    analysisReference: { workspaceId: 'ws-1', operationId: 'operation-1', continuationId: null } }) });
  assert.equal(resultCalls, 0); assert.equal(f.input.value, 'Local work'); assert.equal(f.C.runtime.serverUnconfirmed, true);
});

test('compacted draft references preserve the exact canonical continuation result version', async()=>{
  const f=fixture();await f.install();
  const payload=f.C.currentPayload();assert.equal(payload.analysisReference.recoveryVersion,9);
  assert.equal(payload.analysisRecovery,undefined,'The compact reference alone must carry the retained version');
  f.state.analysisRecovery={...f.state.analysisRecovery,version:10};
  assert.equal(f.C.currentPayload().analysisReference.recoveryVersion,9,'Status metadata cannot repin evidence before a newer result is actually hydrated');
});

test('a continuation without an exact canonical version remains full evidence instead of an unpinned reference',async()=>{
  const f=fixture();await f.install();f.C.runtime.analysisReference=null;f.C.runtime.analysisEvidence=null;
  const saved=f.canonical();delete saved.result.recovery.version;f.state.analysisRecovery=saved.result.recovery;
  f.C.retainAnalysisReference(saved.result,saved.request);
  assert.equal(f.C.currentPayload().analysisReference,undefined);assert.ok(f.C.currentPayload().reasons);
});

for(const version of [-1,'9',Number.MAX_SAFE_INTEGER+1,10]) {
  test(`reference restoration rejects ${String(version)} instead of silently binding latest continuation evidence`,async()=>{
    const f=fixture();await f.install();f.edit('Keep local work');
    const saved=referencePayload();
    if(version===undefined)delete saved.analysisReference.recoveryVersion;else saved.analysisReference.recoveryVersion=version;
    await f.C.applyDraft({version:2,payload:saved});
    assert.equal(f.input.value,'Keep local work');assert.equal(f.state.currentScores.BP,85);
    assert.equal(f.C.runtime.serverUnconfirmed,true);assert.match(f.alerts.at(-1).title,/unconfirmed/);
  });
}

for(const legacy of [false,true]) {
  test(`${legacy?'legacy unpinned':'newer canonical'} live restoration saves the actually hydrated reference before focus`,async()=>{
    const f=fixture();const current=f.canonical();current.recovery.version=10;
    const payload=referencePayload();if(legacy)delete payload.analysisReference.recoveryVersion;
    await f.C.applyDraft({version:4,payload});
    assert.equal(f.C.runtime.analysisReference.recoveryVersion,10,'Only the hydrated canonical response establishes the new evidence pin');
    const writes=f.draftWrites;
    assert.equal(await f.C.saveCurrentDraft(),true);
    assert.equal(writes.length,1,'A hydrated reference is not already persisted in the old draft row');
    assert.equal(writes[0].expectedVersion,4);assert.equal(writes[0].payload.analysisReference.recoveryVersion,10);
    assert.equal(f.C.runtime.version,5,'The read-only focus call must use this acknowledged new draft revision');
    assert.equal(writes[0].payload.reasons,undefined,'Large retained evidence stays bounded during resume');
  });
}

test('background recovery cannot acknowledge a new canonical reference without an actual draft write',async()=>{
  const f=fixture();await f.install();await f.C.saveCurrentDraft();
  const priorVersion=f.C.runtime.version;f.draftWrites.length=0;
  const saved=f.canonical();saved.recovery.version=10;saved.result.reasons={BP:'New saved canonical rationale'};
  f.window.TaxonomyRecoveryViewport={mount:()=>({update(){},isOpen:()=>false,isDismissed:()=>true,open(){},close(){},error(){}})};
  f.window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'ws-1',version:f.C.runtime.version}),saveNow:f.C.saveCurrentDraft};
  f.sandbox.clearTimeout=()=>{};f.sandbox.setTimeout=()=>1;
  vm.runInContext(await readFile(new URL('taxonomy-analysis-recovery.js',root),'utf8'),f.sandbox);
  assert.equal(await f.window.TaxonomyAnalysisRecovery.refresh({background:true}),true);
  assert.equal(f.draftWrites.length,0,'A pure status observer does not become a competing draft writer');
  assert.equal(f.C.currentPayload().analysisReference.recoveryVersion,10);
  assert.equal(f.state.currentReasons.BP,'New saved canonical rationale');await f.C.saveCurrentDraft();
  assert.equal(f.draftWrites.length,1);assert.equal(f.C.runtime.version,priorVersion+1);
  assert.equal(f.draftWrites[0].payload.analysisReference.recoveryVersion,10);
});

test('repeated canonical observations neither schedule render autosaves nor become periodic polling edits',async()=>{
  const f=fixture();await f.install();await f.C.saveCurrentDraft();const priorVersion=f.C.runtime.version;f.draftWrites.length=0;
  const timers=[];f.window.setTimeout=fn=>{timers.push(fn);return timers.length;};
  f.window.TaxonomyBrowse.renderView=()=>f.C.queueSave();
  f.window.TaxonomyRecoveryViewport={mount:()=>({update(){},isOpen:()=>false,isDismissed:()=>true,open(){},close(){},error(){}})};
  f.window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'ws-1',version:f.C.runtime.version}),saveNow:f.C.saveCurrentDraft};
  f.sandbox.clearTimeout=()=>{};f.sandbox.setTimeout=()=>1;
  vm.runInContext(await readFile(new URL('taxonomy-analysis-recovery.js',root),'utf8'),f.sandbox);
  for(const version of [10,11]) {
    const saved=f.canonical();saved.recovery.version=version;saved.result.reasons={BP:'Canonical reason '+version};
    assert.equal(await f.window.TaxonomyAnalysisRecovery.refresh({background:true}),true);
    assert.equal(f.C.currentPayload().analysisReference.recoveryVersion,version);
    assert.equal(f.draftWrites.length,0);assert.equal(timers.length,0,'Rendering an observation cannot queue an automatic save');
    assert.equal(f.C.runtime.lastObservedComparable,f.C.comparable(f.C.currentPayload()),'The ordinary change poll must see an observation, not a local edit');
  }
  await f.C.saveCurrentDraft();assert.equal(f.draftWrites.length,1);assert.equal(f.C.runtime.version,priorVersion+1);
  assert.equal(f.draftWrites[0].payload.analysisReference.recoveryVersion,11);
});

async function observedRecoveryFixture() {
  const f=fixture();await f.install();await f.C.saveCurrentDraft();f.draftWrites.length=0;
  f.window.TaxonomyRecoveryViewport={mount:()=>({update(){},isOpen:()=>false,isDismissed:()=>true,open(){},close(){},error(){}})};
  f.window.TaxonomyAnalysisSession={state:()=>({ready:true,workspaceId:'ws-1',version:f.C.runtime.version}),saveNow:f.C.saveCurrentDraft};
  f.sandbox.clearTimeout=()=>{};f.sandbox.setTimeout=()=>1;
  vm.runInContext(await readFile(new URL('taxonomy-analysis-recovery.js',root),'utf8'),f.sandbox);
  const saved=f.canonical();saved.recovery.version=10;saved.result.reasons={BP:'Canonical reason 10'};
  assert.equal(await f.window.TaxonomyAnalysisRecovery.refresh({background:true}),true);
  return f;
}
async function lifecycleObservers(f) {
  const listeners=new Map();f.document.readyState='loading';f.document.visibilityState='visible';
  f.window.setInterval=()=>1;f.window.addEventListener=(name,fn)=>listeners.set(name,fn);
  f.sandbox.MutationObserver=class{observe(){}};f.C.queueStaleActions=()=>{};
  vm.runInContext(await readFile(new URL('taxonomy-analysis-session-projects.js',root),'utf8'),f.sandbox);
  f.C.installObservers();return listeners;
}
for(const lifecycle of ['pagehide','hidden']) {
  test(`a pure canonical observer stays read-only through actual ${lifecycle} lifecycle handling`,async()=>{
    const f=await observedRecoveryFixture();const listeners=await lifecycleObservers(f);
    if(lifecycle==='pagehide')listeners.get('pagehide')();
    else{f.document.visibilityState='hidden';f.document.dispatchEvent({type:'visibilitychange'});}
    await flush();await flush();assert.equal(f.draftWrites.length,0);
    await f.C.saveCurrentDraft();assert.equal(f.draftWrites.length,1,'Explicit Save now still persists the canonical reference');
    f.edit('Local input before leaving');
    if(lifecycle==='pagehide')listeners.get('pagehide')();else f.document.dispatchEvent({type:'visibilitychange'});
    await flush();await flush();assert.equal(f.draftWrites.length,2,'The same lifecycle still flushes real local edits');
    assert.equal(f.draftWrites.at(-1).payload.businessText,'Local input before leaving');
  });
}
for(const edit of ['text','reason']) {
  test(`a confirmed save retires the observer marker so reverting a later ${edit} edit queues persistence`,async()=>{
    const f=await observedRecoveryFixture();await f.C.saveCurrentDraft();
    if(edit==='text')f.edit('New local input');else f.state.currentReasons.BP='Local edited rationale';
    await f.C.saveCurrentDraft();
    const timers=[];f.window.setTimeout=fn=>{timers.push(fn);return timers.length;};
    if(edit==='text')f.edit('Original requirement');else f.state.currentReasons.BP='Canonical reason 10';
    f.C.queueSave();assert.equal(timers.length,1,'Returning to old observed content is a new edit against the acknowledged row');
    const writes=f.draftWrites.length;await timers[0]();assert.equal(f.draftWrites.length,writes+1);
  });
}
test('a newer observation arriving during a pending write is not acknowledged or retired by the older response',async()=>{
  const f=await observedRecoveryFixture();const gate=deferred();let submitted;
  f.draftControl.write=body=>{submitted=body;return gate.promise;};
  const saving=f.C.saveDraft();await flush();
  const newer=f.canonical();newer.recovery.version=11;newer.result.reasons={BP:'Canonical reason 11'};
  assert.equal(await f.window.TaxonomyAnalysisRecovery.refresh({background:true}),true);
  gate.resolve({version:f.C.runtime.version+1,payload:submitted.payload});await saving;
  const listeners=await lifecycleObservers(f);const count=f.draftWrites.length;
  listeners.get('pagehide')();await flush();assert.equal(f.draftWrites.length,count,'The old acknowledgement cannot convert the newer observation into a local edit');
  delete f.draftControl.write;await f.C.saveCurrentDraft();
  assert.equal(f.draftWrites.length,count+1);assert.equal(f.draftWrites.at(-1).payload.analysisReference.recoveryVersion,11);
});
