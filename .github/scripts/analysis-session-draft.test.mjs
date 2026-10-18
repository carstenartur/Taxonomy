import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(
  new URL(
    '../../taxonomy-app/src/main/resources/static/js/core/' +
      'taxonomy-analysis-session-draft.js',
    import.meta.url
  ),
  'utf8'
);
const uiSource = await readFile(new URL(
  '../../taxonomy-app/src/main/resources/static/js/core/taxonomy-analysis-session-ui.js',
  import.meta.url), 'utf8');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function fakeControl(id) {
  const attributes = new Map();
  return {
    id,
    dataset: {},
    disabled: false,
    setAttribute(name, value) {
      attributes.set(name, String(value));
    },
    removeAttribute(name) {
      attributes.delete(name);
    },
    getAttribute(name) {
      return attributes.get(name) || null;
    }
  };
}

function createHarness({ inputText = '', payload, request, withUi = false }) {
  const scheduled = [];
  const cleared = [];
  const alerts = [];
  const clickListeners = [];
  const editListeners = new Map();
  let decisionFocusCount = 0;
  const input = {
    value: inputText,
    classList: {
      add() {},
      remove() {}
    }
  };
  const controls = {
    analyzeBtn: fakeControl('analyzeBtn'),
    copilotBtn: fakeControl('copilotBtn'),
    taskNextAction: fakeControl('taskNextAction'),
    exportReport: fakeControl('exportReport')
  };
  controls.copilotBtn.setAttribute('aria-disabled', 'true');
  const runtime = {
    workspaceId: 'ws-1',
    workspaceResolved: true,
    version: null,
    restoring: false,
    invalidating: false,
    conflict: false,
    saveTimer: null,
    saveInFlight: null,
    saveQueued: false,
    lastSavedComparable: null,
    lastObservedComparable: null,
    restoredPayload: null
  };
  const state = {
    currentScores: null,
    lastAnalysisProvider: null,
    lastAnalysisStatus: null,
    currentReasons: {},
    currentDiscrepancies: [],
    currentArchView: null,
    storedBusinessText: null,
    lastAnalyzedText: null,
    evaluatedNodes: new Set(),
    taxonomyData: []
  };
  const context = {
    S: state,
    runtime,
    AUTOSAVE_DELAY_MS: 900,
    MAX_RESTORE_ATTEMPTS: 1,
    text: key => key,
    language: () => 'en',
    statusArea: () => null,
    guardStaleActions() {},
    resetPanel() {},
    businessTextElement: () => input,
    currentPayload: () => payload.current,
    comparable: value => JSON.stringify(value),
    meaningful: value => Boolean(String(value?.businessText || '').trim()),
    draftEndpoint: () => '/api/analysis-drafts/ws-1',
    jsonRequest: request,
    hasDerivedAnalysis: () => false,
    isStale: () => false,
    showActionAlert(kind, title, body, actions, marker) {
      alerts.push({ kind, title, body, actions, marker });
    },
    showStaleActions() {},
    openRequirementDialog() {}
  };
  const window = {
    __TaxonomyAnalysisSessionContext: context,
    TaxonomyBrowse: null,
    TaxonomyScoring: null,
    _taxonomyCurrentScores: null,
    _currentProvisionalRelations: [],
    console,
    clearTimeout(id) {
      cleared.push(id);
    },
    setTimeout(callback, delay) {
      scheduled.push({ callback, delay });
      return scheduled.length;
    }
  };
  const document = {
    dispatchEvent() {},
    getElementById(id) {
      return controls[id] || null;
    },
    querySelectorAll(selector) {
      assert.match(selector, /#exportGroup button/);
      return Object.values(controls);
    },
    querySelector(selector) {
      if (selector !== '#statusArea [data-analysis-session-action]') return null;
      return {
        focus() {
          decisionFocusCount += 1;
        }
      };
    },
    addEventListener(type, listener) {
      if (type === 'click') clickListeners.push(listener);
      if (type === 'input' || type === 'change') {
        const listeners = editListeners.get(type) || [];
        listeners.push(listener);
        editListeners.set(type, listeners);
      }
    }
  };

  const sandbox = {
    window,
    document,
    console,
    Array,
    Boolean,
    CustomEvent: class CustomEvent {
      constructor(type, options = {}) {
        this.type = type;
        this.detail = options.detail;
      }
    },
    JSON,
    Object,
    Promise,
    Set,
    String,
    encodeURIComponent
  };
  if (withUi) vm.runInNewContext(uiSource, sandbox,
    { filename: 'taxonomy-analysis-session-ui.js' });
  vm.runInNewContext(source, sandbox, { filename: 'taxonomy-analysis-session-draft.js' });

  return {
    context,
    runtime,
    state,
    input,
    controls,
    scheduled,
    cleared,
    alerts,
    clickListeners,
    window,
    editInput(value, type = 'input') {
      input.value = value;
      payload.current = { ...payload.current, businessText: value };
      for (const listener of editListeners.get(type) || []) {
        listener({ type, target: input });
      }
    },
    decisionFocusCount: () => decisionFocusCount
  };
}

test('keeps restoration active while GET is pending and saves input typed during a 204', async () => {
  const response = deferred();
  const payload = { current: { businessText: '' } };
  const harness = createHarness({
    payload,
    request: () => response.promise
  });

  const loading = harness.context.loadDraft();
  assert.equal(harness.runtime.restoring, true);
  assert.equal(harness.runtime.draftDecisionPending, true);
  assert.equal(harness.controls.analyzeBtn.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.controls.exportReport.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.scheduled.length, 0);

  harness.input.value = 'Typed while the remote draft was loading';
  payload.current = { businessText: harness.input.value };
  response.resolve(null);
  await loading;

  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.draftDecisionPending, false);
  assert.equal(harness.controls.analyzeBtn.getAttribute('aria-disabled'), null);
  assert.equal(harness.controls.copilotBtn.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.controls.exportReport.getAttribute('aria-disabled'), null);
  assert.equal(harness.runtime.version, null);
  assert.equal(harness.runtime.lastSavedComparable, null);
  assert.equal(harness.runtime.lastObservedComparable, null);
  assert.equal(harness.scheduled.length, 1);
  assert.equal(harness.scheduled[0].delay, 0);
});

test('blocks task actions and autosave until resume choice is resolved', async () => {
  const payload = { current: { businessText: 'Local unsaved text' } };
  const saved = {
    version: 7,
    payload: { businessText: 'Newer saved text' }
  };
  const harness = createHarness({
    inputText: payload.current.businessText,
    payload,
    request: async () => saved
  });

  await harness.context.loadDraft();

  assert.equal(harness.runtime.restoring, true);
  assert.equal(harness.runtime.draftDecisionPending, true);
  assert.equal(harness.controls.analyzeBtn.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.controls.exportReport.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.scheduled.length, 0);
  assert.equal(harness.alerts.length, 1);
  assert.equal(harness.alerts[0].marker, 'resume-choice');

  let prevented = false;
  let stopped = false;
  harness.clickListeners[0]({
    target: {
      closest(selector) {
        assert.match(selector, /#exportGroup button/);
        return harness.controls.exportReport;
      }
    },
    preventDefault() {
      prevented = true;
    },
    stopImmediatePropagation() {
      stopped = true;
    }
  });
  assert.equal(prevented, true);
  assert.equal(stopped, true);
  assert.equal(harness.decisionFocusCount(), 1);

  const keepLocal = harness.alerts[0].actions.find(action => action.id === 'keep-local');
  assert.ok(keepLocal, 'Resume choice must offer the local state');
  keepLocal.handler();

  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.draftDecisionPending, false);
  assert.equal(harness.controls.analyzeBtn.getAttribute('aria-disabled'), null);
  assert.equal(harness.controls.copilotBtn.getAttribute('aria-disabled'), 'true');
  assert.equal(harness.controls.exportReport.getAttribute('aria-disabled'), null);
  assert.equal(harness.runtime.version, 7);
  assert.equal(
    harness.runtime.lastSavedComparable,
    JSON.stringify(saved.payload)
  );
  assert.equal(harness.scheduled.length, 1);
  assert.equal(harness.scheduled[0].delay, 0);
});

for (const edit of [
  { name: 'new requirement text', values: ['Typed after choosing to reload'] },
  { name: 'an intentionally cleared requirement', values: [''] },
  { name: 'an edit away and back', values: ['Changed temporarily', 'Original local text'] }
]) {
  test(`forced reload preserves ${edit.name} entered while its GET is pending`, async () => {
    const response = deferred();
    const payload = { current: { businessText: 'Original local text' } };
    const harness = createHarness({
      inputText: payload.current.businessText,
      payload,
      request: () => response.promise
    });
    harness.runtime.version = 2;
    harness.state.currentScores = { BP: 77 };
    const loading = harness.context.loadDraft({ force: true });

    for (const value of edit.values) harness.editInput(value);
    response.resolve({ version: 3, payload: { businessText: 'Saved remote text', scores: { BP: 12 } } });
    await loading;

    assert.equal(harness.input.value, edit.values.at(-1));
    assert.equal(harness.state.currentScores.BP, 77);
    assert.equal(harness.runtime.version, 2);
    assert.equal(harness.runtime.restoring, true);
    assert.equal(harness.runtime.draftDecisionPending, true);
    assert.equal(harness.alerts.at(-1).marker, 'resume-choice');
    assert.equal(harness.scheduled.length, 0);

    harness.alerts.at(-1).actions.find(action => action.id === 'keep-local').handler();
    assert.equal(harness.runtime.version, 3);
    assert.equal(harness.runtime.restoring, false);
    assert.equal(harness.scheduled.length, 1);
    assert.equal(harness.scheduled[0].delay, 0);
    assert.equal(harness.input.value, edit.values.at(-1));
  });
}

test('initial restoration preserves an input edit that returns to the empty initial value', async () => {
  const response = deferred();
  const payload = { current: { businessText: '' } };
  const harness = createHarness({ payload, request: () => response.promise });
  const loading = harness.context.loadDraft();
  harness.editInput('A new requirement');
  harness.editInput('', 'change');
  response.resolve({ version: 4, payload: { businessText: 'Existing saved draft' } });
  await loading;

  assert.equal(harness.input.value, '');
  assert.equal(harness.runtime.version, null);
  assert.equal(harness.alerts.at(-1).marker, 'resume-choice');
});

test('forced reload preserves newer programmatic analysis state without an input event', async () => {
  const response = deferred();
  const payload = { current: { businessText: 'Original local text', scores: { BP: 77 } } };
  const harness = createHarness({
    inputText: payload.current.businessText,
    payload,
    request: () => response.promise
  });
  const loading = harness.context.loadDraft({ force: true });
  payload.current = { businessText: 'Original local text', scores: { BP: 91 } };
  harness.state.currentScores = { BP: 91 };
  response.resolve({ version: 3, payload: { businessText: 'Saved remote text', scores: { BP: 12 } } });
  await loading;

  assert.equal(harness.input.value, 'Original local text');
  assert.equal(harness.state.currentScores.BP, 91);
  assert.equal(harness.alerts.at(-1).marker, 'resume-choice');
});

test('forced reload still replaces unchanged local state after explicit user consent', async () => {
  const payload = { current: { businessText: 'Original local text' } };
  const harness = createHarness({
    inputText: payload.current.businessText,
    payload,
    request: async () => ({ version: 3, payload: { businessText: 'Saved remote text', scores: { BP: 12 } } })
  });
  await harness.context.loadDraft({ force: true });

  assert.equal(harness.input.value, 'Saved remote text');
  assert.equal(harness.state.currentScores.BP, 12);
  assert.equal(harness.runtime.version, 3);
  assert.equal(harness.alerts.length, 0);
});

test('serializes overlapping autosaves and uses the latest optimistic version', async () => {
  const first = deferred();
  const second = deferred();
  const requests = [];
  const payload = { current: { businessText: 'First draft' } };
  const harness = createHarness({
    inputText: payload.current.businessText,
    payload,
    request: async (url, options) => {
      requests.push({ url, options, body: JSON.parse(options.body) });
      return requests.length === 1 ? first.promise : second.promise;
    }
  });

  const saving = harness.context.saveDraft();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].body.expectedVersion, null);
  assert.equal(requests[0].body.payload.businessText, 'First draft');

  payload.current = { businessText: 'Second draft' };
  harness.input.value = payload.current.businessText;
  const queued = harness.context.saveDraft();
  payload.current = { businessText: 'Latest draft' };
  harness.input.value = payload.current.businessText;
  harness.context.saveDraft();

  assert.equal(requests.length, 1, 'No concurrent PUT may use the same version');
  assert.equal(harness.runtime.saveQueued, true);

  first.resolve({ version: 1, payload: requests[0].body.payload });
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(requests.length, 2);
  assert.equal(requests[1].body.expectedVersion, 1);
  assert.equal(requests[1].body.payload.businessText, 'Latest draft');

  second.resolve({ version: 2, payload: requests[1].body.payload });
  await Promise.all([saving, queued]);

  assert.equal(harness.runtime.version, 2);
  assert.equal(harness.runtime.conflict, false);
  assert.equal(harness.runtime.saveInFlight, null);
  assert.equal(harness.runtime.saveQueued, false);
  assert.equal(
    harness.runtime.lastSavedComparable,
    JSON.stringify({ businessText: 'Latest draft' })
  );
});

test('persists an empty tombstone instead of deleting an existing draft row', async () => {
  const requests = [];
  const payload = {
    current: { draftState: 'EMPTY', businessText: '' }
  };
  const harness = createHarness({
    payload,
    request: async (url, options) => {
      requests.push({ url, method: options.method, body: JSON.parse(options.body) });
      return { version: 3, payload: payload.current };
    }
  });
  harness.runtime.version = 2;
  harness.runtime.lastSavedComparable = JSON.stringify({
    draftState: 'ACTIVE', businessText: 'Old requirement'
  });

  await harness.context.saveDraft();

  assert.equal(requests.length, 1);
  assert.equal(requests[0].method, 'PUT');
  assert.equal(requests[0].body.expectedVersion, 2);
  assert.equal(requests[0].body.payload.draftState, 'EMPTY');
  assert.equal(harness.runtime.version, 3);
});

test('explicit reset remains repeatable and uses the server reset command', async () => {
  const requests = [];
  const payload = { current: { draftState: 'EMPTY', businessText: '' } };
  const harness = createHarness({
    payload,
    request: async (url, options) => {
      requests.push({ url, method: options.method, body: JSON.parse(options.body) });
      const version = requests.length;
      return { version, payload: payload.current };
    }
  });
  harness.context.analysisOptions = () => ({ provider: 'OPENAI' });

  await harness.context.resetDraft();
  await harness.context.resetDraft();

  assert.deepEqual(requests.map(request => request.method), ['POST', 'POST']);
  assert.deepEqual(requests.map(request => request.url), [
    '/api/analysis-drafts/ws-1/reset',
    '/api/analysis-drafts/ws-1/reset'
  ]);
  assert.equal(requests[1].body.analysisOptions.provider, 'OPENAI');
  assert.equal(harness.runtime.version, 2);
  assert.equal(harness.runtime.resetting, false);
});

test('ACTIVE restore retains analysis provider and terminal authority', () => {
  const payload = {
    current: {
      draftState: 'ACTIVE',
      businessText: 'Need resilient communications',
      scores: { BP: 77 }
    }
  };
  const harness = createHarness({
    inputText: '',
    payload,
    request: async () => null
  });
  harness.state.taxonomyData = [{}];
  harness.window.TaxonomyBrowse = {
    renderView() {},
    updateExportGroupVisibility() {}
  };
  harness.window.TaxonomyScoring = {
    renderArchitectureView() {},
    renderSuggestedRelations() {}
  };

  harness.context.applyDraft({
    version: 4,
    payload: {
      draftState: 'ACTIVE',
      businessText: 'Need resilient communications',
      scores: { BP: 77 },
      rawScores: { BP: 77 },
      effectiveScores: { BP: 77 },
      lastAnalysisProvider: 'MOCK',
      lastAnalysisStatus: 'SUCCESS',
      reasons: { BP: 'relevant' },
      discrepancies: [],
      productCoverageGaps: [],
      architectureView: { includedElements: [{ nodeCode: 'BP' }] },
      provisionalRelations: [],
      evaluatedNodes: ['BP'],
      currentView: 'summary'
    }
  });

  assert.equal(harness.state.lastAnalysisProvider, 'MOCK');
  assert.equal(harness.state.lastAnalysisStatus, 'SUCCESS');
  assert.equal(harness.state.currentScores.BP, 77);
  assert.equal(harness.input.value, 'Need resilient communications');
});

test('authoritative EMPTY restore discards every stale analysis envelope', () => {
  const payload = { current: { businessText: '' } };
  const harness = createHarness({
    inputText: 'stale local requirement',
    payload,
    request: async () => null
  });
  harness.state.taxonomyData = [{}];
  harness.state.currentScores = { OLD: 99 };
  harness.state.lastAnalysisProvider = 'OPENAI';
  harness.state.lastAnalysisStatus = 'SUCCESS';
  harness.state.currentArchView = { includedElements: ['OLD'] };
  harness.window._taxonomyCurrentScores = { OLD: 99 };
  harness.window._currentProvisionalRelations = [{ source: 'OLD' }];
  harness.window.TaxonomyBrowse = {};
  harness.window.TaxonomyScoring = {};

  harness.context.applyDraft({
    version: 9,
    payload: {
      draftState: 'EMPTY',
      businessText: '',
      scores: { STALE: 90 },
      rawScores: { STALE: 90 },
      effectiveScores: { STALE: 90 },
      scoreDetails: { STALE: { kind: 'PRODUCT_SUITABILITY' } },
      productSuitabilityScores: { STALE: 90 },
      scoreSemanticsVersion: 1,
      scoreSemanticsWarnings: ['stale'],
      reasons: { STALE: 'stale' },
      discrepancies: [{ parentCode: 'STALE' }],
      productCoverageGaps: [{ productFamilyCode: 'STALE' }],
      architectureView: { includedElements: ['STALE'] },
      storedBusinessText: 'stale',
      lastAnalyzedText: 'stale',
      lastAnalysisProvider: 'GEMINI',
      lastAnalysisStatus: 'PARTIAL',
      evaluatedNodes: ['STALE'],
      provisionalRelations: [{ source: 'STALE' }]
    }
  });

  assert.equal(harness.runtime.version, 9);
  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.draftDecisionPending, false);
  assert.equal(harness.input.value, '');
  assert.equal(harness.state.currentScores, null);
  assert.equal(harness.state.lastAnalysisProvider, null);
  assert.equal(harness.state.lastAnalysisStatus, null);
  assert.equal(Object.keys(harness.state.currentRawScores).length, 0);
  assert.equal(Object.keys(harness.state.currentEffectiveScores).length, 0);
  assert.equal(Object.keys(harness.state.currentScoreDetails).length, 0);
  assert.equal(Object.keys(harness.state.currentProductSuitabilityScores).length, 0);
  assert.equal(harness.state.scoreSemanticsVersion, 0);
  assert.equal(harness.state.currentScoreSemanticsWarnings.length, 0);
  assert.equal(Object.keys(harness.state.currentReasons).length, 0);
  assert.equal(harness.state.currentDiscrepancies.length, 0);
  assert.equal(harness.state.currentProductCoverageGaps.length, 0);
  assert.equal(harness.state.currentArchView, null);
  assert.equal(harness.state.storedBusinessText, null);
  assert.equal(harness.state.lastAnalyzedText, null);
  assert.equal(harness.state.evaluatedNodes.size, 0);
  assert.equal(harness.state.currentView, 'list');
  assert.equal(harness.window._taxonomyCurrentScores, null);
  assert.equal(harness.window._currentProvisionalRelations.length, 0);
});


test('discarding an analysis clears coverage and every execution binding while preserving the new requirement', () => {
  const h = createHarness({ inputText: 'new requirement', payload: { current: {} },
    request: async () => null, withUi: true });
  Object.assign(h.state, { currentScores: { OLD: 100 }, analysisCoverage: { failedOrBlockedNodes: 4 },
    analysisRecovery: { state: 'PAUSED' }, recoveryContext: { id: 'old-operation' },
    lastAnalysisScope: { mode: 'COMPLETE' }, lastAnalysisProvider: 'GEMMA',
    lastAnalysisStatus: 'PARTIAL', lastAnalysisDurationMillis: 1234 });
  h.context.invalidate({ keepText: true, skipSave: true });
  assert.equal(h.input.value, 'new requirement');
  for (const field of ['currentScores', 'analysisCoverage', 'analysisRecovery', 'recoveryContext',
    'lastAnalysisScope', 'lastAnalysisProvider', 'lastAnalysisStatus', 'lastAnalysisDurationMillis']) {
    assert.equal(h.state[field], null, `${field} still belongs to the discarded analysis`);
  }
});

test('a missing previously saved draft never schedules old browser results for recreation', async () => {
  const h = createHarness({ inputText: 'old requirement',
    payload: { current: { businessText: 'old requirement', scores: { OLD: 100 } } },
    request: async () => null, withUi: true });
  h.runtime.version = 7;
  h.runtime.confirmedBusinessText = 'old requirement';
  h.state.currentScores = { OLD: 100 };
  await h.context.loadDraft({ force: true });
  assert.equal(h.state.currentScores, null);
  assert.equal(h.input.value, '');
  assert.equal(h.scheduled.some(task => task.delay === 0), false,
    'missing server data must never authorize recreating old analysis results');
});


async function savedHarness(request, language = 'en') {
  const original = { businessText: 'saved requirement', scores: { OLD: 100 } };
  const h = createHarness({ inputText: original.businessText, payload: { current: original },
    request, withUi: true });
  h.context.language = () => language;
  h.context.currentPayload = () => ({ businessText: h.input.value, scores: h.state.currentScores });
  h.window.TaxonomyBrowse = { renderView() {}, updateExportGroupVisibility() {} };
  h.window.TaxonomyScoring = { renderArchitectureView() {}, renderSuggestedRelations() {} };
  h.state.taxonomyData = [{}];
  h.context.applyDraft({ workspaceId: 'ws-1', branch: 'draft', version: 7,
    updatedAt: '2026-10-18T10:00:00Z', payload: original });
  return h;
}

function requireReconciliation(h) {
  assert.equal(typeof h.context.reconcileDraft, 'function', 'the open page must reconcile server state');
}

for (const language of ['de', 'en']) {
  test(`an unchanged server revision leaves local input and rendered results alone (${language})`, async () => {
    const calls = [];
    const h = await savedHarness(async (url, options) => {
      calls.push({ url, options });
      return { workspaceId: 'ws-1', branch: 'draft', version: 7, updatedAt: '2026-10-18T10:00:00Z' };
    }, language);
    requireReconciliation(h);
    h.input.value = 'unsaved local edit';
    const scores = h.state.currentScores;
    await h.context.reconcileDraft();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, '/api/analysis-drafts/ws-1/status');
    assert.equal(calls[0].options.method, 'GET');
    assert.equal(h.state.currentScores, scores);
    assert.equal(h.input.value, 'unsaved local edit');
  });

  test(`server deletion clears old results without PUT or model work (${language})`, async () => {
    const calls = [];
    const h = await savedHarness(async (url, options) => { calls.push({ url, options }); return null; }, language);
    requireReconciliation(h);
    await h.context.reconcileDraft();
    assert.equal(h.state.currentScores, null);
    assert.equal(h.input.value, '');
    assert.ok(calls.every(call => call.options.method === 'GET'));
    assert.equal(h.scheduled.some(task => task.delay === 0), false);
    assert.ok(h.alerts.some(alert => alert.marker === 'server-draft-missing'));
  });

  test(`unsaved input survives server deletion and needs an explicit save choice (${language})`, async () => {
    const methods = [];
    const h = await savedHarness(async (url, options) => { methods.push(options.method); return null; }, language);
    requireReconciliation(h);
    h.editInput('new local requirement');
    await h.context.reconcileDraft();
    assert.equal(h.input.value, 'new local requirement');
    assert.equal(h.state.currentScores, null);
    assert.equal(h.runtime.draftDecisionPending, true);
    await h.context.saveDraft();
    assert.deepEqual(methods, ['GET']);
    assert.ok(h.alerts.at(-1).actions.some(action => action.id === 'keep-local'));
  });
}

test('a network failure blocks autosave until the server revision has been confirmed again', async () => {
  let connected = false;
  const methods = [];
  const h = await savedHarness(async (url, options) => {
    methods.push(options.method);
    if (!connected) throw Object.assign(new Error('Network request failed'), { code: 'NETWORK_ERROR' });
    return { workspaceId: 'ws-1', branch: 'draft', version: 7, updatedAt: '2026-10-18T10:00:00Z' };
  });
  requireReconciliation(h);
  await h.context.reconcileDraft();
  assert.equal(h.runtime.serverUnconfirmed, true);
  assert.equal(h.runtime.draftDecisionPending, true);
  h.editInput('local text while offline');
  await h.context.saveDraft();
  assert.deepEqual(methods, ['GET']);
  connected = true;
  await h.context.reconcileDraft();
  assert.equal(h.runtime.serverUnconfirmed, false);
  assert.equal(h.runtime.draftDecisionPending, false);
  assert.equal(h.input.value, 'local text while offline');
});

test('overlapping server checks share one read and an old workspace response cannot replace a new scope', async () => {
  const response = deferred();
  let reads = 0;
  const h = await savedHarness(async () => { reads++; return response.promise; });
  requireReconciliation(h);
  const first = h.context.reconcileDraft();
  const second = h.context.reconcileDraft();
  assert.equal(reads, 1);
  h.runtime.workspaceId = 'ws-2';
  response.resolve(null);
  await Promise.all([first, second]);
  assert.equal(h.input.value, 'saved requirement');
  assert.deepEqual(h.state.currentScores, { OLD: 100 });
});

test('reconnection detects a newer saved draft and replaces a clean browser state', async () => {
  const calls = [];
  const h = await savedHarness(async (url, options) => {
    calls.push({ url, options });
    const view = { workspaceId: 'ws-1', branch: 'draft', version: 8,
      updatedAt: '2026-10-18T10:10:00Z', payload: { businessText: 'server requirement', scores: { NEW: 80 } } };
    return url.endsWith('/status') ? { ...view, payload: undefined } : view;
  });
  requireReconciliation(h);
  await h.context.reconcileDraft();
  assert.equal(h.input.value, 'server requirement');
  assert.equal(h.state.currentScores.NEW, 80);
  assert.equal(h.runtime.version, 8);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.options.method === 'GET'));
});

test('a reset on the server offers a choice for edits made while the version check was pending', async () => {
  const status = deferred();
  const h = await savedHarness(async url => url.endsWith('/status') ? status.promise
    : { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new',
      payload: { draftState: 'EMPTY', businessText: '' } });
  requireReconciliation(h);
  const checking = h.context.reconcileDraft();
  h.editInput('typed during check');
  status.resolve({ workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new' });
  await checking;
  assert.equal(h.input.value, 'typed during check');
  assert.equal(h.state.currentScores, null);
  assert.equal(h.runtime.draftDecisionPending, true);
});

test('a version check started before a successful local save cannot roll that save back', async () => {
  const status = deferred();
  const h = await savedHarness(async (url, options) => options.method === 'PUT'
    ? { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new', payload: { businessText: 'saved new text' } }
    : status.promise);
  requireReconciliation(h);
  const checking = h.context.reconcileDraft();
  h.editInput('saved new text');
  await h.context.saveDraft();
  status.resolve(null);
  await checking;
  assert.equal(h.input.value, 'saved new text');
  assert.equal(h.runtime.version, 8);
});


test('a full draft read that fails after a successful revision check remains recoverable', async () => {
  let fullReadAvailable = false;
  const h = await savedHarness(async url => {
    if (url.endsWith('/status')) return { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new' };
    if (!fullReadAvailable) throw new Error('Network request failed');
    return { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new',
      payload: { businessText: 'new server text', scores: {} } };
  });
  await h.context.reconcileDraft();
  assert.equal(h.runtime.serverUnconfirmed, true);
  assert.equal(h.runtime.restoring, false, 'a failed observation must not strand the restoration barrier');
  fullReadAvailable = true;
  await h.context.reconcileDraft();
  assert.equal(h.runtime.serverUnconfirmed, false);
  assert.equal(h.input.value, 'new server text');
});

test('an unavailable old workspace discards its results before resolving the fresh server workspace', async () => {
  const h = await savedHarness(async () => { throw Object.assign(new Error('Workspace unavailable'), { status: 403 }); });
  h.editInput('unsaved text before restart');
  let resolved = false;
  h.context.resolveActiveWorkspaceAndLoad = async options => {
    resolved = true;
    assert.equal(h.runtime.workspaceId, null);
    assert.equal(h.state.currentScores, null);
    assert.equal(options.reconcile, true);
    h.runtime.workspaceId = 'fresh-workspace';
    return null;
  };
  await h.context.reconcileDraft();
  assert.equal(resolved, true);
  assert.equal(h.input.value, 'unsaved text before restart');
});


for (const change of ['workspace', 'analysis']) {
  test(`a late full draft read cannot replace a newer ${change}`, async () => {
    const full = deferred();
    const h = await savedHarness(async url => url.endsWith('/status')
      ? { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new' } : full.promise);
    const checking = h.context.reconcileDraft();
    await new Promise(resolve => setImmediate(resolve));
    if (change === 'workspace') h.runtime.workspaceId = 'ws-2';
    else h.runtime.analysisGeneration = 2;
    h.input.value = 'newer local state';
    const newerScores = { CURRENT: 42 };
    h.state.currentScores = newerScores;
    full.resolve({ workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new',
      payload: { businessText: 'obsolete server response', scores: { STALE: 100 } } });
    await checking;
    assert.equal(h.input.value, 'newer local state');
    assert.equal(h.state.currentScores, newerScores);
  });
}

test('a connection notice cannot erase a pending local-versus-server choice', async () => {
  const h = await savedHarness(async () => null);
  h.editInput('unsaved local text');
  await h.context.reconcileDraft();
  assert.equal(h.runtime.restoring, true);
  h.context.serverUnavailable(new Error('connection lost while choosing'));
  assert.equal(h.alerts.at(-1).marker, 'server-draft-missing');
  assert.ok(h.alerts.at(-1).actions.some(action => action.id === 'keep-local'));
});


test('a renderer retry from a discarded draft cannot restore old relations or unlock a newer restore', async () => {
  const h = createHarness({ inputText: '', payload: { current: {} }, request: async () => null, withUi: true });
  h.context.applyDraft({ version: 7, payload: { businessText: 'old', provisionalRelations: [{ source: 'OLD' }] } });
  const retry = h.scheduled.find(task => task.delay === 100);
  assert.ok(retry);
  h.context.invalidate({ skipSave: true });
  h.runtime.restoredPayload = { businessText: 'new' };
  h.runtime.restoring = true;
  const rendered = [];
  h.state.taxonomyData = [{}];
  h.window.TaxonomyBrowse = { renderView() {}, updateExportGroupVisibility() {} };
  h.window.TaxonomyScoring = { renderArchitectureView() {}, renderSuggestedRelations(value) { rendered.push(value); } };
  retry.callback();
  assert.equal(rendered.length, 0);
  assert.equal(h.runtime.restoring, true);
});


test('failure rendering an observed revision is reported even after its own invalidation advances the generation', async () => {
  const h = await savedHarness(async url => url.endsWith('/status')
    ? { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new' }
    : { workspaceId: 'ws-1', branch: 'draft', version: 8, updatedAt: 'new',
      payload: { businessText: 'new', scores: {}, architectureView: { id: 'new-architecture' } } });
  const invalidate = h.context.invalidate;
  h.context.invalidate = options => {
    invalidate(options);
    h.runtime.analysisGeneration = (h.runtime.analysisGeneration || 0) + 1;
  };
  h.window.TaxonomyScoring.renderArchitectureView = view => {
    if (view) throw new Error('rendering unavailable');
  };
  await h.context.reconcileDraft();
  assert.equal(h.runtime.serverUnconfirmed, true);
  assert.equal(h.runtime.draftDecisionPending, true);
  assert.equal(h.runtime.restoring, false);
  assert.equal(h.alerts.at(-1).marker, 'server-unconfirmed');
});

test('a missing status endpoint leaves the last confirmed results visible and never switches workspaces', async () => {
  const h = await savedHarness(async () => { throw Object.assign(new Error('status route unavailable'), { status: 404 }); });
  await h.context.reconcileDraft();
  assert.equal(h.state.currentScores.OLD, 100);
  assert.equal(h.runtime.workspaceId, 'ws-1');
  assert.equal(h.runtime.serverUnconfirmed, true);
});
