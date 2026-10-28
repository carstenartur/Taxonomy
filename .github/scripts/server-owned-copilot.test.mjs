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
  const elements = new Map(); const listeners = new Map(); const requests = []; const alerts = [];
  function element(id) {
    const classes = new Set(); const attributes = new Map();
    return { id, value: '', style: {}, dataset: {}, innerHTML: '', textContent: '', children: [],
      classList: { add: value => classes.add(value), remove: value => classes.delete(value), contains: value => classes.has(value),
        toggle(value, enabled) { if (enabled) classes.add(value); else classes.delete(value); } },
      setAttribute: (name, value) => attributes.set(name, String(value)), getAttribute: name => attributes.get(name),
      hasAttribute: name => attributes.has(name), removeAttribute: name => attributes.delete(name),
      addEventListener() {}, appendChild(child) { this.children.push(child); }, replaceChildren(...children) { this.children = children; },
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
  C.jsonRequest = async (_path, options) => ({ version: 1, payload: JSON.parse(options.body).payload });
  vm.runInContext(sources['taxonomy-analysis-session-draft.js'], sandbox);
  vm.runInContext(sources['taxonomy-analysis.js'], sandbox);
  const input = elements.get('businessText') || document.getElementById('businessText');
  let snapshot;
  function canonical({ continuation = true, large = false } = {}) {
    const result = { status: 'SUCCESS', provider: 'LOCAL', analysisProviderId: 'LOCAL', analysisOperationId: 'operation-1',
      scores: { BP: 85 }, rawScores: { BP: 85 }, effectiveScores: { BP: 85 }, reasons: { BP: large ? 'e'.repeat(2_100_000) : 'Retained assessment' },
      recovery: continuation ? { id: 'continuation-1', state: 'COMPLETED', openQuestions: [] } : null,
      tree: [{ code: 'BP', name: 'Retained root', children: [] }], copilot: report() };
    snapshot = { request: { businessText: 'Original requirement', provider: 'LOCAL' }, result, recovery: result.recovery };
    return snapshot;
  }
  async function install(options = {}) {
    canonical(options);
    const payload = await C.resolveAnalysisReference({ schemaVersion: 1, draftState: 'ACTIVE', businessText: 'Original requirement', currentView: 'list',
      analysisReference: { workspaceId: 'ws-1', continuationId: 'continuation-1', operationId: 'operation-1' } });
    C.applyDraft({ version: 1, payload: Object.assign({}, payload, { analysisReference: undefined }) });
    C.retainAnalysisReference(snapshot.result, snapshot.request);
    return snapshot;
  }
  return { window, document, C, state, input, elements, requests, alerts, canonical, install,
    edit(value) { input.value = value; document.dispatchEvent({ type: 'input', target: input }); },
    html(id) { return document.getElementById(id).innerHTML; } };
}
function report(state = 'COMPLETED') {
  return { schemaVersion: 1, state,
    gaps: { totalAnchors: 0, totalGaps: 0, missingRelations: [], notes: [] },
    patterns: { matchedPatterns: [], incompletePatterns: [], patternCoverage: 0, notes: [] },
    recommendation: { confirmedElements: [], proposedElements: [], suggestedRelations: [], reasoning: [], confidence: 0 },
    notes: ['Retained evidence is bounded; human review remains necessary.'] };
}
function referencePayload(extra = {}) {
  return { schemaVersion: 1, draftState: 'ACTIVE', businessText: 'Edited requirement', currentView: 'tree',
    analysisReference: { workspaceId: 'ws-1', continuationId: 'continuation-1', operationId: 'operation-1' }, ...extra };
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
  const f = fixture(); f.canonical();
  await f.C.applyDraft({ version: 2, payload: referencePayload() });
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
test('retained legacy successful checkpoints remain visible without being rerun or called canonical completion', () => {
  const f = fixture(); f.state.recoveryContext = { followups: { gap: { state: 'SUCCESS', result: report().gaps } } };
  f.window.TaxonomyAnalysis.renderCopilotResult(null);
  assert.match(f.html('copilotContent'), /historical partial reports/); assert.doesNotMatch(f.html('copilotContent'), /analyze.copilot.complete/);
  assert.match(f.html('gapAnalysisContent'), /open compatibility checks/); assert.equal(f.requests.length, 0);
});
test('clearing editable input does not make a referenced analysis an empty unsaved draft', async () => {
  const f = fixture(); await f.install(); f.edit(''); const payload = f.C.currentPayload();
  assert.equal(payload.draftState, 'ACTIVE'); assert.equal(f.C.meaningful(payload), true);
});
for (const language of ['en', 'de']) test(`${language}: retained zero-anchor checks do not claim architectural completeness`, () => {
  const f = fixture(language); f.window.TaxonomyAnalysis.renderCopilotResult(report());
  assert.match(f.html('gapAnalysisContent'), language === 'de' ? /keine vollständige Architektur/ : /does not establish architectural completeness/);
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
