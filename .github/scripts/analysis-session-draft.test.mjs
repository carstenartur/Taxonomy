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

function createHarness({ inputText = '', payload, request }) {
  const scheduled = [];
  const cleared = [];
  const alerts = [];
  const clickListeners = [];
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
    }
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
