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

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createHarness({ inputText = '', payload, request }) {
  const scheduled = [];
  const cleared = [];
  const alerts = [];
  const input = {
    value: inputText,
    classList: {
      add() {},
      remove() {}
    }
  };
  const runtime = {
    workspaceId: 'ws-1',
    version: null,
    restoring: false,
    invalidating: false,
    conflict: false,
    saveTimer: null,
    lastSavedComparable: null,
    lastObservedComparable: null,
    restoredPayload: null
  };
  const state = {
    currentScores: null,
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
    getElementById() { return null; }
  };

  vm.runInNewContext(source, {
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
  }, { filename: 'taxonomy-analysis-session-draft.js' });

  return { context, runtime, state, input, scheduled, cleared, alerts };
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
  assert.equal(harness.scheduled.length, 0);

  harness.input.value = 'Typed while the remote draft was loading';
  payload.current = { businessText: harness.input.value };
  response.resolve(null);
  await loading;

  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.version, null);
  assert.equal(harness.runtime.lastSavedComparable, null);
  assert.equal(harness.runtime.lastObservedComparable, null);
  assert.equal(harness.scheduled.length, 1);
  assert.equal(harness.scheduled[0].delay, 0);
});

test('blocks autosave until local-versus-saved resume choice is resolved', async () => {
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
  assert.equal(harness.scheduled.length, 0);
  assert.equal(harness.alerts.length, 1);
  assert.equal(harness.alerts[0].marker, 'resume-choice');

  const keepLocal = harness.alerts[0].actions.find(action => action.id === 'keep-local');
  assert.ok(keepLocal, 'Resume choice must offer the local state');
  keepLocal.handler();

  assert.equal(harness.runtime.restoring, false);
  assert.equal(harness.runtime.version, 7);
  assert.equal(
    harness.runtime.lastSavedComparable,
    JSON.stringify(saved.payload)
  );
  assert.equal(harness.scheduled.length, 1);
  assert.equal(harness.scheduled[0].delay, 0);
});
