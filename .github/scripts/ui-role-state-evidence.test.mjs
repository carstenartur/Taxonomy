import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { webcrypto } from 'node:crypto';
import vm from 'node:vm';
import { createEvidencePolicy, screenshotStrategy } from './ui-evidence-policy.mjs';
import { createRejectedAnalysisFixture } from './ui-rejected-analysis-fixture.mjs';

const moduleNames = ['core', 'transport', 'ui', 'draft', 'projects'];
const sources = await Promise.all([...moduleNames.map(name =>
  `../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-${name}.js`),
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-recovery.js'
].map(file => readFile(new URL(file, import.meta.url), 'utf8')));
const evidenceSource = await readFile(new URL('./ui-role-state-evidence.mjs', import.meta.url), 'utf8');
const evidenceStart = evidenceSource.indexOf('export function createRoleStateEvidence');
assert.ok(evidenceStart >= 0);

function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

// Only DOM, timer, scoring and HTTP delivery boundaries are doubled. The real
// serializers, recovery decision, observation rebasing, CAS queue and public
// saveNow produce both the live payload and its acknowledged server receipt.
function browserHarness() {
  const listeners = new Map(), timers = new Map(), storage = new Map();
  const requests = [], writes = [], starts = [], audits = [];
  const observation = deferred(), observationStarted = deferred();
  const decisionObservation = deferred(), decisionObservationStarted = deferred();
  const draftRead = deferred(), draftReadStarted = deferred();
  const scoring = deferred();
  let nextTimer = 0, persisted, draftSnapshot;
  let holdDraftRead = false;
  const input = { value: 'The same requirement needs its remaining taxonomy analysis.',
    classList: { add() {}, remove() {}, contains: () => false }, addEventListener() {} };
  const state = { taxonomyData: [{ code: 'BP' }], currentView: 'list' };
  const document = {
    readyState: 'loading', documentElement: { lang: 'en' }, body: { classList: { toggle() {} } },
    getElementById: id => id === 'businessText' ? input : null,
    querySelector: () => null, querySelectorAll: () => [],
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
    dispatchEvent(event) {
      for (const listener of listeners.get(event.type) || []) listener(event);
      return true;
    }
  };
  const window = {
    TaxonomyState: state, location: new URL('https://taxonomy.example/'), console,
    setTimeout(callback, delay) { timers.set(++nextTimer, { callback, delay }); return nextTimer; },
    clearTimeout(id) { timers.delete(id); }, setInterval: () => 0, clearInterval() {},
    sessionStorage: { getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) },
    TaxonomyBrowse: { renderView() {}, updateExportGroupVisibility() {}, showStatus() {} },
    TaxonomyScoring: { renderArchitectureView() {}, renderSuggestedRelations() {},
      runAnalysis(options) { starts.push(options.continuation); return scoring.promise; } },
    TaxonomyRecoveryViewport: { mount: () => ({ update() {}, close() {}, isOpen: () => false }) },
    TaxonomyAnalysisSessionApi: { request }
  };
  window.fetch = request;
  const realm = vm.createContext({ window, document, console, Set, URL, Headers, Request,
    AbortController, crypto: webcrypto, sessionStorage: window.sessionStorage,
    clearTimeout: window.clearTimeout, setTimeout: window.setTimeout,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options?.detail; } }
  });
  for (const source of sources) vm.runInContext(source, realm);
  const context = window.__TaxonomyAnalysisSessionContext;
  const runtime = context.runtime;
  Object.assign(runtime, { initialized: true, workspaceResolved: true,
    workspaceId: 'workspace-42', version: 1 });
  const session = window.TaxonomyAnalysisSession;
  session.invalidate({ keepText: true, skipSave: true, silent: true, reason: 'test-baseline' });
  state.currentScores = {};
  persisted = { workspaceId: runtime.workspaceId, version: 1,
    payload: JSON.parse(JSON.stringify(context.currentPayload())) };
  runtime.lastSavedComparable = context.comparable(persisted.payload);

  async function request(path, options = {}) {
    path = new URL(path, window.location.href).pathname;
    requests.push({ path, method: options.method || 'GET', cache: options.cache });
    if (path.startsWith('/api/analysis-continuations/')) {
      const first = requests.filter(request => request.path.startsWith('/api/analysis-continuations/')).length === 1;
      return { ok: false, status: 404, json: async () => {
        (first ? observationStarted : decisionObservationStarted).resolve();
        await (first ? observation : decisionObservation).promise;
        return { detail: 'Unknown continuation' };
      } };
    }
    assert.equal(path, '/api/analysis-drafts/' + runtime.workspaceId);
    if (options.method === 'PUT') {
      const command = JSON.parse(options.body);
      assert.equal(command.expectedVersion, persisted.version);
      persisted = { workspaceId: runtime.workspaceId, version: persisted.version + 1,
        payload: command.payload };
      writes.push(structuredClone(persisted));
      return { ok: true, status: 200, json: async () => structuredClone(persisted) };
    }
    assert.equal(options.method, 'GET');
    if (options.cache !== undefined) assert.equal(options.cache, 'no-store');
    draftSnapshot = structuredClone(persisted);
    draftReadStarted.resolve();
    if (holdDraftRead) await draftRead.promise;
    return { ok: true, status: 200, json: async () => structuredClone(draftSnapshot) };
  }

  const page = {
    evaluate(callback, argument) {
      realm.__argument = argument;
      realm.fetch = (...args) => window.fetch(...args);
      return vm.runInContext('(' + callback.toString() + ')(__argument)', realm);
    },
    async waitForFunction(callback, argument) {
      for (let attempts = 0; attempts < 100; attempts++) {
        if (await this.evaluate(callback, argument)) return;
        await new Promise(resolve => setImmediate(resolve));
      }
      throw new Error('Controlled browser condition was never reached');
    }
  };
  const evidenceModule = { createEvidencePolicy, screenshotStrategy,
    AxeBuilder: class {
      withTags() { return this; }
      analyze() { audits.push('axe'); return Promise.resolve({ violations: [] }); }
    } };
  vm.runInNewContext(evidenceSource.slice(evidenceStart).replace('export function', 'function')
    + '\nthis.createRoleStateEvidence = createRoleStateEvidence;', evidenceModule);
  const evidence = evidenceModule.createRoleStateEvidence({ page, outputDir: '/unused',
    checks: [], findings: [] });

  return { window, context, runtime, state, input, session, page, requests, writes, starts, audits,
    observation, observationStarted, decisionObservation, decisionObservationStarted, draftRead, draftReadStarted,
    current: () => context.comparable(context.currentPayload()),
    persisted: () => persisted,
    snapshot: () => draftSnapshot,
    holdDraft() { holdDraftRead = true; },
    run: () => evidence.runAxe('analysis-success'),
    async rejectStart({ explicitRead = false } = {}) {
      const finished = window.TaxonomyAnalysisRecovery.startManagedAnalysis();
      assert.equal(starts.length, 1);
      if (explicitRead) {
        window.TaxonomyAnalysisRecovery.refresh();
        await observationStarted.promise;
      }
      scoring.reject(Object.assign(new Error('Injected HTTP 503'), { httpStatus: 503 }));
      await observationStarted.promise;
      await page.waitForFunction(() => window.TaxonomyState.recoveryContext?.syncState === 'CHECKING');
      assert.equal(state.recoveryContext.id, starts[0].continuationId);
      assert.equal(state.recoveryContext.syncState, 'CHECKING');
      return { finished };
    },
    async finishObservation(finished) {
      observation.resolve();
      await finished;
      assert.equal(state.recoveryContext.syncState, 'UNAVAILABLE');
      assert.equal(state.recoveryContext.observationStatus, 404);
      assert.equal(state.recoveryContext.rejectionStatus, 503);
    }
  };
}

test('a real recovery observation during the draft GET retains the exact acknowledged persistence proof', async () => {
  const h = browserHarness();
  const { finished } = await h.rejectStart();
  h.holdDraft();
  const work = h.run();
  await h.draftReadStarted.promise;
  assert.equal(h.snapshot().payload.recoveryContext.syncState, 'CHECKING');
  await h.finishObservation(finished);
  assert.equal(h.current(), h.runtime.lastSavedComparable);
  h.draftRead.resolve();
  await work;
  assert.equal(h.writes.length, 1);
  assert.equal(h.runtime.version, 2);
  assert.equal(h.runtime.acknowledgedDraftWrites[0].serialized, h.context.comparable(h.snapshot().payload));
  assert.equal(h.audits.length, 1);
});

test('a settled observation of a cleanly saved CHECKING draft does not manufacture another PUT', async () => {
  const h = browserHarness();
  const { finished } = await h.rejectStart();
  assert.equal(await h.session.saveNow(), true);
  assert.equal(h.persisted().payload.recoveryContext.syncState, 'CHECKING');
  await h.finishObservation(finished);
  assert.equal(h.current(), h.runtime.lastSavedComparable);
  await h.run();
  assert.equal(h.writes.length, 1);
  assert.equal(h.audits.length, 1);
});

test('a restored clean draft without its own PUT retains the complete direct payload comparison', async () => {
  const h = browserHarness();
  await h.session.reload();
  assert.equal(h.runtime.acknowledgedDraftWrites.length, 0);
  await h.run();
  assert.equal(h.writes.length, 0);
  assert.equal(h.audits.length, 1);
  assert.equal(h.requests.at(-1).cache, 'no-store', 'Evidence still requires an uncached server read');
});

async function savedCheckingDraft() {
  const h = browserHarness();
  const { finished } = await h.rejectStart();
  assert.equal(await h.session.saveNow(), true);
  await h.finishObservation(finished);
  h.holdDraft();
  return h;
}

test('a matching acknowledgement takes priority when same-revision remote data is changed to the live observation', async () => {
  const h = await savedCheckingDraft();
  const work = h.run();
  await h.draftReadStarted.promise;
  h.snapshot().payload = JSON.parse(h.current());
  h.draftRead.resolve();
  await assert.rejects(work, /persistence barrier diverged/);
  assert.equal(h.audits.length, 0);
});

for (const [label, change] of [
  ['another workspace', h => {
    h.runtime.workspaceId = 'workspace-other';
    h.state.recoveryContext.workspaceId = 'workspace-other';
    h.persisted().workspaceId = 'workspace-other';
  }],
  ['another recovery identity', h => { h.state.recoveryContext.id = webcrypto.randomUUID(); }],
  ['an older acknowledgement revision', h => {
    h.runtime.version = 3;
    h.persisted().version = 3;
  }],
  ['no acknowledged write', h => { h.runtime.acknowledgedDraftWrites = []; }]
]) {
  test(`an acknowledgement from ${label} cannot prove a different current payload`, async () => {
    const h = await savedCheckingDraft();
    change(h);
    h.runtime.lastSavedComparable = h.current();
    const work = h.run();
    await h.draftReadStarted.promise;
    h.draftRead.resolve();
    await assert.rejects(work, /persistence barrier diverged/);
    assert.equal(h.writes.length, 1, 'Only the original real CAS write supplied an acknowledgement');
    assert.equal(h.audits.length, 0);
  });
}

for (const [label, change] of [
  ['workspace', h => { h.runtime.workspaceId = 'workspace-other'; }],
  ['revision', h => { h.runtime.version++; }],
  ['analysis generation', h => { h.runtime.analysisGeneration++; }],
  ['draft generation', h => { h.runtime.draftGeneration++; }],
  ['acknowledgement generation', h => { h.runtime.draftAcknowledgementGeneration++; }],
  ['conflict', h => { h.runtime.conflict = true; }],
  ['restoration', h => { h.runtime.restoring = true; }],
  ['unconfirmed server', h => { h.runtime.serverUnconfirmed = true; }],
  ['pending draft decision', h => { h.runtime.draftDecisionPending = true; }],
  ['active save', h => { h.runtime.saveInFlight = Promise.resolve(true); }],
  ['queued save', h => { h.runtime.saveQueued = true; }],
  ['local edit', h => { h.input.value = 'A new local requirement'; }]
]) {
  test(`a ${label} change during verification blocks Axe even with an exact acknowledged server payload`, async () => {
    const h = await savedCheckingDraft();
    const work = h.run();
    await h.draftReadStarted.promise;
    change(h);
    h.draftRead.resolve();
    await assert.rejects(work, /draft|persistence/i);
    assert.equal(h.audits.length, 0);
  });
}

test('a same-revision server mutation cannot hide behind an otherwise matching acknowledgement', async () => {
  const h = await savedCheckingDraft();
  const work = h.run();
  await h.draftReadStarted.promise;
  h.snapshot().payload.businessText = 'Unexpected server-side replacement';
  h.draftRead.resolve();
  await assert.rejects(work, /persistence barrier diverged/);
  assert.equal(h.audits.length, 0);
});

for (const explicitRead of [false, true]) {
  test(`rejected-START observation wait consumes the final real GET body with ${explicitRead ? 'two reads' : 'one read'}`, async () => {
    const h = browserHarness(), fixture = createRejectedAnalysisFixture();
    const { finished } = await h.rejectStart({ explicitRead });
    await fixture.rejectStart({ request: () => ({ method: () => 'POST',
      url: () => 'https://taxonomy.example/api/analyze', postDataJSON: () => h.starts[0] }),
    fulfill: async () => {} });
    let settled = false;
    const waiting = fixture.waitForObservation(h.page).then(() => { settled = true; });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(settled, false, 'An unfinished receipt body must precede blocking Axe');
    if (explicitRead) {
      h.observation.resolve();
      await h.decisionObservationStarted.promise;
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(settled, false, 'The earlier read cannot replace the afterDecision observation');
      h.decisionObservation.resolve();
    } else h.observation.resolve();
    await finished;
    await waiting;
    assert.equal(settled, true);
    const reads = h.requests.filter(request => request.path.startsWith('/api/analysis-continuations/'));
    assert.equal(reads.length, explicitRead ? 2 : 1, 'The wait itself does not refresh recovery');
    assert.ok(reads.every(read => read.path === '/api/analysis-continuations/' + h.starts[0].continuationId));
    assert.equal(h.starts.length, 1);
  });
}
